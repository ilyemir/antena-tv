package com.ilyemir.antena.player

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.ilyemir.antena.data.db.ChannelEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Geleneksel TV davranışlı oynatıcı motoru.
 *
 *  - Donanım çözümleme öncelikli (MediaCodec + decoder fallback).
 *  - Freeze-frame zap: kaynak değişse bile yüzey temizlenmez; yeni kare gelene kadar
 *    önceki kanalın son karesi ekranda donuk kalır (PlayerView.keepContentOnPlayerReset).
 *  - Primary + Backup URL: birincil adres [BACKUP_FALLBACK_MS] içinde READY olmazsa
 *    otomatik yedek adrese geçilir.
 *  - Sessiz auto-reconnect: hata durumunda kullanıcıya bir şey gösterilmeden
 *    [FAST_RECONNECT_MS] içinde (ilk denemelerde 500 ms) tekrar denenir.
 *  - Uzantısız URL'ler bir kez HLS olarak denenir.
 */
@OptIn(UnstableApi::class)
class PlaybackController(context: Context) : Player.Listener {

    enum class State { Idle, Connecting, Playing, Reconnecting }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(State.Idle)
    val state: StateFlow<State> = _state

    val player: ExoPlayer

    private var channel: ChannelEntity? = null
    private var usingBackup = false
    private var triedHls = false
    private var reconnectAttempts = 0

    private var fallbackJob: Job? = null
    private var reconnectJob: Job? = null

    init {
        val renderersFactory = DefaultRenderersFactory(context)
            // Donanım (MediaCodec) çözümleyicileri öncelikle kullan; tek bir codec başarısız
            // olursa alternatif çözümleyiciye düş.
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)

        // Canlı TV için küçük arabellek: hızlı zap, düşük gecikme.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs= */ 10_000,
                /* maxBufferMs= */ 20_000,
                /* bufferForPlaybackMs= */ 1_000,
                /* bufferForPlaybackAfterRebufferMs= */ 2_000,
            )
            .build()

        player = ExoPlayer.Builder(context, renderersFactory)
            .setLoadControl(loadControl)
            // 50 Hz yayınları destekleyen ekranlarda yenileme hızını yayına uydurur
            // (API 31+ Surface.setFrameRate; eski ROM'larda sessizce yok sayılır).
            .setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS)
            .build()
            .apply { addListener(this@PlaybackController) }
    }

    /** Kanalı çalmaya başlar; önceki kare yeni yayın gelene kadar ekranda kalır. */
    fun play(channel: ChannelEntity) {
        fallbackJob?.cancel()
        reconnectJob?.cancel()
        this.channel = channel
        usingBackup = false
        triedHls = false
        reconnectAttempts = 0
        startSource(channel.url)
        scheduleBackupFallback(channel)
    }

    private fun startSource(url: String, forceHls: Boolean = false) {
        val channel = this.channel ?: return
        _state.value = State.Connecting
        player.setMediaSource(buildSource(channel, url, forceHls))
        player.prepare()
        player.playWhenReady = true
    }

    private fun scheduleBackupFallback(channel: ChannelEntity) {
        val backup = channel.backupUrl ?: return
        fallbackJob = scope.launch {
            delay(BACKUP_FALLBACK_MS)
            if (_state.value != State.Playing && !usingBackup) {
                usingBackup = true
                startSource(backup)
            }
        }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_READY) {
            reconnectJob?.cancel()
            reconnectAttempts = 0
            _state.value = State.Playing
        }
    }

    override fun onPlayerError(error: PlaybackException) {
        val channel = this.channel ?: return

        // Uzantısız adres bazı sunucularda düz TS/HLS olarak yayınlanır; bir kez HLS zorla.
        if (!triedHls && !isHlsUrl(channel.url)) {
            triedHls = true
            startSource(channel.url, forceHls = true)
            return
        }

        // Sessiz auto-reconnect: kullanıcıya hata gösterilmez.
        _state.value = State.Reconnecting
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(if (reconnectAttempts < FAST_RECONNECT_TRIES) FAST_RECONNECT_MS else SLOW_RECONNECT_MS)
            reconnectAttempts++
            val backup = channel.backupUrl
            when {
                usingBackup && backup != null -> {
                    usingBackup = false
                    startSource(channel.url)
                }
                !usingBackup && backup != null -> {
                    usingBackup = true
                    startSource(backup)
                }
                else -> startSource(channel.url)
            }
        }
    }

    fun release() {
        scope.cancel()
        player.release()
    }

    private fun buildSource(
        channel: ChannelEntity,
        url: String,
        forceHls: Boolean,
    ): androidx.media3.exoplayer.source.MediaSource {
        val headers = buildMap {
            channel.referrer?.let { put("Referer", it) }
        }
        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(channel.userAgent ?: DEFAULT_USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            // 3 sn bağlantı zaman aşımı: yedek adrese geçiş gecikmesine hizalı.
            .setConnectTimeoutMs(BACKUP_FALLBACK_MS.toInt())
            .setReadTimeoutMs(15_000)
            .setDefaultRequestProperties(headers)

        val item = MediaItem.Builder()
            .setUri(url)
            .apply {
                if (forceHls || isHlsUrl(url)) setMimeType(MimeTypes.APPLICATION_M3U8)
            }
            .build()

        return DefaultMediaSourceFactory(dataSourceFactory).createMediaSource(item)
    }

    private fun isHlsUrl(url: String): Boolean = url.substringBefore('?').contains(".m3u8", ignoreCase = true)

    private companion object {
        const val BACKUP_FALLBACK_MS = 3_000L
        const val FAST_RECONNECT_MS = 500L
        const val FAST_RECONNECT_TRIES = 5
        const val SLOW_RECONNECT_MS = 2_000L
        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 8.0; MiBox) Antena/1.0"
    }
}
