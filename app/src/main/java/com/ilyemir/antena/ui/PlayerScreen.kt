package com.ilyemir.antena.ui

import android.app.Activity
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Text
import com.ilyemir.antena.data.db.ChannelEntity
import com.ilyemir.antena.player.PlaybackController
import com.ilyemir.antena.ui.theme.AntenaColors
import com.ilyemir.antena.ui.theme.AntenaType
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tam ekran yayın ekranı — geleneksel televizyon davranışı:
 *
 *  - Açılışta menü yok: doğrudan en son izlenen kanal çalar.
 *  - Yukarı/Aşağı: sadece "Ana Liste" (ilk 15 kanal) içinde döner.
 *  - Sol / OK: tüm kanalların listelendiği büyük sol çekmece.
 *  - Sağ: yatay 0-9 rakam şeridi (NumPad).
 *  - Geri: uygulama kapanmaz; "Çıkmak için tekrar basın" uyarısı gösterir.
 */
@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    channels: List<ChannelEntity>,
    initialChannelNumber: Int,
    onChannelChanged: (Int) -> Unit,
) {
    val context = LocalContext.current
    val channelCount = channels.size
    var position by remember(channelCount) {
        mutableIntStateOf(
            channels.indexOfFirst { it.number == initialChannelNumber }.takeIf { it >= 0 } ?: 0,
        )
    }
    val channel = channels[position.coerceIn(0, channelCount - 1)]
    val digitsLength = remember(channelCount) { channels.maxOf { it.number }.toString().length }

    val controller = remember { PlaybackController(context) }
    DisposableEffect(controller) {
        onDispose { controller.release() }
    }
    val playbackState by controller.state.collectAsState()

    var showDrawer by remember { mutableStateOf(false) }
    var showNumPad by remember { mutableStateOf(false) }
    var numBuffer by remember { mutableStateOf("") }
    var osdVisible by remember { mutableStateOf(true) }
    var exitHintVisible by remember { mutableStateOf(false) }
    var osdNonce by remember { mutableIntStateOf(0) }

    fun tuneToIndex(index: Int) {
        if (index in channels.indices) position = index
    }

    fun tuneToNumber(digits: String) {
        val number = digits.toIntOrNull() ?: return
        val exact = channels.indexOfFirst { it.number == number }
        if (exact >= 0) {
            tuneToIndex(exact)
            return
        }
        val prefix = channels.indexOfFirst { it.number.toString().startsWith(digits) }
        if (prefix >= 0) tuneToIndex(prefix)
    }

    fun appendDigit(digit: Char) {
        if (numBuffer.length >= digitsLength) return
        numBuffer += digit
        osdNonce++
        if (numBuffer.length == digitsLength) {
            val buffer = numBuffer
            numBuffer = ""
            tuneToNumber(buffer)
        }
    }

    /** Yukarı/Aşağı: ana listenin (ilk 15 kanal) içinde sonsuz döngü. */
    fun zap(delta: Int) {
        if (channelCount == 0) return
        val mainSize = minOf(MAIN_LIST_SIZE, channelCount)
        val mainList = channels.subList(0, mainSize)
        val currentIndex = mainList.indexOfFirst { it.number == channel.number }
        position = when {
            currentIndex < 0 && delta > 0 -> 0
            currentIndex < 0 -> mainSize - 1
            else -> (currentIndex + delta).mod(mainSize)
        }
    }

    // Debounce'lu yayın başlatma: hızlı zap sırasında her akış yüklenmez;
    // yeni yayın gelene kadar önceki kanalın son karesi ekranda donuk kalır.
    LaunchedEffect(channel.id) {
        delay(ZAP_DEBOUNCE_MS)
        controller.play(channel)
        onChannelChanged(channel.number)
    }

    // OSD: kanal değişince göster, 3,5 sn sonra kendini gizle
    LaunchedEffect(channel.id, osdNonce) {
        osdVisible = true
        delay(OSD_DURATION_MS)
        osdVisible = false
    }

    // Çıkış uyarısı zaman aşımı
    LaunchedEffect(exitHintVisible) {
        if (exitHintVisible) {
            delay(EXIT_HINT_MS)
            exitHintVisible = false
        }
    }

    // Rakam girişi: 2 sn tuşlara basılmazsa girilen numaraya geç
    LaunchedEffect(numBuffer) {
        if (numBuffer.isNotEmpty()) {
            delay(NUM_ENTRY_TIMEOUT_MS)
            val buffer = numBuffer
            numBuffer = ""
            tuneToNumber(buffer)
        }
    }

    // Uygulama arka plana geçince duraklat, dönünce sürdür
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, controller) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> controller.player.pause()
                Lifecycle.Event.ON_START -> controller.player.play()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Çift Geri koruması: çekmece/NumPad açıksa kapat; değilse önce uyarı, tekrarında çık.
    BackHandler {
        when {
            showDrawer -> showDrawer = false
            showNumPad -> {
                showNumPad = false
                numBuffer = ""
            }
            exitHintVisible -> (context as? Activity)?.finish()
            else -> exitHintVisible = true
        }
    }

    val rootFocus = remember { FocusRequester() }
    LaunchedEffect(showDrawer, showNumPad) {
        if (!showDrawer && !showNumPad) runCatching { rootFocus.requestFocus() }
    }

    // Çekmece/NumPad kapanınca OSD yeniden görünür: girilen kanal numarası onaylanır.
    var overlayWasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(showDrawer, showNumPad) {
        val open = showDrawer || showNumPad
        if (open) {
            overlayWasOpen = true
        } else if (overlayWasOpen) {
            overlayWasOpen = false
            osdNonce++
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocus)
            .focusable()
            .onKeyEvent { event ->
                if (showDrawer || showNumPad) return@onKeyEvent false
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionUp, Key.ChannelDown -> {
                        zap(-1)
                        true
                    }
                    Key.DirectionDown, Key.ChannelUp -> {
                        zap(+1)
                        true
                    }
                    Key.DirectionLeft, Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        showDrawer = true
                        true
                    }
                    Key.DirectionRight -> {
                        showNumPad = true
                        numBuffer = ""
                        true
                    }
                    else -> {
                        val digit = KEY_DIGITS[event.key]
                        if (digit != null) {
                            appendDigit(digit)
                            true
                        } else {
                            false
                        }
                    }
                }
            },
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = controller.player
                    useController = false
                    isFocusable = false
                    descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    // Freeze-frame zap: kaynak değişse bile yüzey temizlenmez,
                    // yeni yayın ilk karesini verene kadar son kare ekranda kalır.
                    setKeepContentOnPlayerReset(true)
                    // Geleneksel TV hissi: dönen yükleme göstergesi yok.
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        // Kanal OSD'si (sol üst): mono numara + kanal adı + durum
        AnimatedVisibility(
            visible = osdVisible && !showDrawer && !showNumPad,
            enter = fadeIn(tween(120)),
            exit = fadeOut(tween(250)),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(28.dp),
        ) {
            Row(
                Modifier
                    .background(AntenaColors.OverlayScrim)
                    .border(1.dp, AntenaColors.Border)
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    channel.number.toString().padStart(3, '0'),
                    style = AntenaType.ChannelNumber,
                    color = AntenaColors.Accent,
                )
                Column {
                    Text(
                        channel.name,
                        style = AntenaType.ChannelName,
                        color = AntenaColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        when (playbackState) {
                            PlaybackController.State.Connecting -> "Bağlanıyor"
                            PlaybackController.State.Reconnecting -> "Yeniden bağlanıyor"
                            else -> channel.group ?: ""
                        },
                        style = AntenaType.Label,
                        color = AntenaColors.TextSecondary,
                        maxLines = 1,
                    )
                }
            }
        }

        // Tuş ipuçları (sol alt)
        AnimatedVisibility(
            visible = osdVisible && !showDrawer && !showNumPad,
            enter = fadeIn(tween(120)),
            exit = fadeOut(tween(250)),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(20.dp),
        ) {
            Text(
                "▲▼ Kanal    OK Liste    ▶ Rakam    Geri Çıkış",
                style = AntenaType.LabelMono,
                color = AntenaColors.TextSecondary,
            )
        }

        // Sağ üst: rakam girişi / saat / çıkış uyarısı
        Column(
            Modifier
                .align(Alignment.TopEnd)
                .padding(28.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (numBuffer.isNotEmpty()) {
                Text(
                    numBuffer.padEnd(digitsLength, '_'),
                    style = AntenaType.NumberEntry,
                    color = AntenaColors.Accent,
                )
            } else {
                Clock()
            }
            AnimatedVisibility(
                visible = exitHintVisible,
                enter = fadeIn(tween(80)),
                exit = fadeOut(tween(200)),
            ) {
                Text(
                    "Çıkmak için tekrar basın",
                    style = AntenaType.ExitHint,
                    color = AntenaColors.Accent,
                )
            }
        }

        // Sol çekmece: tüm kanallar
        AnimatedVisibility(
            visible = showDrawer,
            enter = fadeIn(tween(120)) + slideInHorizontally(tween(160)) { -it / 3 },
            exit = fadeOut(tween(120)) + slideOutHorizontally(tween(160)) { -it / 3 },
            modifier = Modifier.align(Alignment.CenterStart),
        ) {
            ChannelDrawer(
                channels = channels,
                currentNumber = channel.number,
                onSelect = { index ->
                    showDrawer = false
                    tuneToIndex(index)
                },
                onDismiss = { showDrawer = false },
            )
        }

        // Yatay NumPad şeridi (alt)
        AnimatedVisibility(
            visible = showNumPad,
            enter = fadeIn(tween(120)),
            exit = fadeOut(tween(120)),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            NumPadBar(
                onDigit = { appendDigit(it) },
                onCommit = {
                    val buffer = numBuffer
                    numBuffer = ""
                    if (buffer.isNotEmpty()) tuneToNumber(buffer)
                },
                onClear = { numBuffer = "" },
                onDismiss = {
                    showNumPad = false
                    numBuffer = ""
                },
            )
        }
    }
}

@Composable
private fun Clock() {
    val now by produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(30_000)
        }
    }
    val format = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    Text(
        format.format(Date(now)),
        style = AntenaType.Clock,
        color = AntenaColors.TextSecondary,
    )
}

/** Fiziksel/hardware klavye rakamları (Mi Box kumandası yoksa da çalışsın). */
private val KEY_DIGITS = mapOf(
    Key.Zero to '0', Key.One to '1', Key.Two to '2', Key.Three to '3', Key.Four to '4',
    Key.Five to '5', Key.Six to '6', Key.Seven to '7', Key.Eight to '8', Key.Nine to '9',
    Key.NumPad0 to '0', Key.NumPad1 to '1', Key.NumPad2 to '2', Key.NumPad3 to '3',
    Key.NumPad4 to '4', Key.NumPad5 to '5', Key.NumPad6 to '6', Key.NumPad7 to '7',
    Key.NumPad8 to '8', Key.NumPad9 to '9',
)

/** Ana Zap Döngüsü: Yukarı/Aşağı yalnızca bu kadar kanal içinde döner. */
private const val MAIN_LIST_SIZE = 15
private const val ZAP_DEBOUNCE_MS = 250L
private const val OSD_DURATION_MS = 3_500L
private const val EXIT_HINT_MS = 2_500L
private const val NUM_ENTRY_TIMEOUT_MS = 2_000L
