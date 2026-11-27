package com.ilyemir.antena.player

import androidx.media3.common.util.UnstableApi
import com.ilyemir.antena.data.db.ChannelEntity
import java.net.HttpURLConnection
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Kanal çalınmadan HEMEN önce çalışan hafif dinamik akış çözücü.
 *
 * Havuzda iki özel şema kullanılır; Media3 bunları doğrudan çalamaz,
 * bu çözücü oynatma anında gerçek .m3u8 adresine çevirir:
 *
 *  - `web://<sayfa>` — yayıncının canlı izleme sayfasını `Mozilla/5.0` User-Agent
 *    ile indirir, HTML içindeki canlı `.m3u8` adresini regex ile ayıklar
 *    (DMAX, TLC, Teve2, TV8.5 — token'lar sayfa başına taze üretildiği için
 *    havuza sabit adres yazmak yerine her zap'ta çözülür).
 *  - `yt://<youtube-canlı-adresi>` —/@kanal/live veya watch?v= adresinden
 *    videoId bulur, watch sayfasındaki `ytInitialPlayerResponse` içinden
 *    `hlsManifestUrl` değerini ayıklar (HT Spor, TGRT Haber, Beyaz TV ...).
 *
 * Çözüm başarısız olursa orijinal adres (önek soyulmuş) döner; oynatıcı
 * en kötü ihtimalle her zamanki gibi sessizce yeniden dener.
 */
@UnstableApi
object StreamResolver {

    const val WEB_SCHEME = "web://"
    const val YT_SCHEME = "yt://"

    /** Kanal adresi çözülmeli mi? (web:// veya yt:// öneki) */
    fun needsResolution(url: String): Boolean =
        url.startsWith(WEB_SCHEME, ignoreCase = true) || url.startsWith(YT_SCHEME, ignoreCase = true)

    /**
     * Kanalı Media3'ün çalabileceği gerçek bir http(s) akış adresine çevirir.
     * Çözücü hangi User-Agent ile yayın aldıysa oynatıcının da aynı UA'yı kullanması
     * gerekir (googlevideo parça istekleri UA'ya bağlıdır; aksi halde 403).
     * Ağ hatası/boş sonuç halinde öneki soyulmuş adres aynen döner.
     */
    suspend fun resolve(channel: ChannelEntity): Resolved {
        val url = channel.url
        return when {
            url.startsWith(WEB_SCHEME, ignoreCase = true) -> {
                val page = url.removePrefix(WEB_SCHEME)
                Resolved(runCatching { resolveWeb(page) }.getOrNull() ?: page, null)
            }

            url.startsWith(YT_SCHEME, ignoreCase = true) -> {
                val page = url.removePrefix(YT_SCHEME)
                Resolved(
                    runCatching { resolveYouTube(page) }.getOrNull() ?: page,
                    // Manifest mobil UA ile çözüldü: parçalar da aynı UA ile istenmeli.
                    YOUTUBE_USER_AGENT,
                )
            }

            else -> Resolved(url, null)
        }
    }

    /** @param url Media3'ün çalacağı adres; @param userAgent oynatıcının kullanacağı UA (null → kanal/varsayılan). */
    data class Resolved(val url: String, val userAgent: String?)

    private suspend fun resolveWeb(pageUrl: String): String? = withContext(Dispatchers.IO) {
        val html = fetchText(pageUrl)
        // HTML içindeki .m3u8 adresleri bazen \/ veya &amp; kaçışlarıyla gelir.
        val candidates = Regex("""https?:\\?/\\?/[^"'\s\\]+?\.m3u8[^"'\s\\]*""")
            .findAll(html)
            .map {
                it.value
                    .replace("\\/", "/")
                    .replace("&amp;", "&")
                    .trimEnd('\\')
            }
            .distinct()
            .toList()
        if (candidates.isEmpty()) return@withContext null
        // Sayfadaki adreslerin bir kısmı ölü/eski CDN'e işaret eder (Teve2 gibi):
        // ilk çalışanı seç, hiçbiri dönmezse en iyi ihtimale düş.
        val referer = URI(pageUrl).let { "${it.scheme}://${it.host}/" }
        candidates.asSequence().take(MAX_CANDIDATES).firstOrNull { isLivePlaylist(it, referer) }
            ?: candidates.first()
    }

    /** Adresin gerçekten oynatılabilir bir HLS manifesti olup olmadığını hızlıca doğrular. */
    private fun isLivePlaylist(url: String, referer: String?): Boolean = runCatching {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 4_000
            connection.readTimeout = 6_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", BROWSER_USER_AGENT)
            referer?.let { connection.setRequestProperty("Referer", it) }
            if (connection.responseCode !in 200..299) return@runCatching false
            val buffer = CharArray(96)
            val length = connection.inputStream.bufferedReader(Charsets.UTF_8)
                .use { reader -> reader.read(buffer, 0, buffer.size) }
            val head = if (length <= 0) "" else String(buffer, 0, length)
            head.startsWith("#EXTM3U") || head.contains("#EXTINF") || head.contains("#EXT-X-STREAM-INF")
        } finally {
            connection.disconnect()
        }
    }.getOrDefault(false)

    private suspend fun resolveYouTube(liveUrl: String): String? = withContext(Dispatchers.IO) {
        // 1) /@kanal/live sayfasının kendisi çoğu zaman ytInitialPlayerResponse taşır.
        val liveHtml = runCatching { fetchText(liveUrl, YOUTUBE_USER_AGENT) }.getOrNull()
        liveHtml?.let { extractHls(it) }?.let { return@withContext it }

        // 2) Sayfa player response içermiyorsa videoId bulup watch sayfasını çöz.
        val videoId = liveHtml?.let(::findVideoId)
            ?: Regex("""[?&]v=([\w-]{11})""").find(liveUrl)?.groupValues?.get(1)
            ?: return@withContext null
        val watchHtml = runCatching {
            fetchText("https://www.youtube.com/watch?v=$videoId", YOUTUBE_USER_AGENT)
        }.getOrNull()
        watchHtml?.let(::extractHls)
            // 3) Son çare: innertube player API.
            ?: runCatching { fetchPlayerResponseHls(videoId) }.getOrNull()
    }

    /** ytInitialPlayerResponse içindeki (veya JSON cevabındaki) hlsManifestUrl. */
    private fun extractHls(html: String): String? =
        Regex(""""hlsManifestUrl"\s*:\s*"(https:[^"]+)"""")
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.replace("\\/", "/")

    private fun findVideoId(html: String): String? =
        Regex(""""videoId"\s*:\s*"([\w-]{11})"""").find(html)?.groupValues?.get(1)

    private fun fetchText(url: String, userAgent: String = BROWSER_USER_AGENT): String {
        var current = url
        repeat(5) {
            val connection = URI(current).toURL().openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 6_000
                connection.readTimeout = 10_000
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("User-Agent", userAgent)
                connection.setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*")
                val code = connection.responseCode
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location")
                        ?: error("Yönlendirme adresi yok (HTTP $code)")
                    current = if (location.startsWith("http")) location
                    else URI(current).resolve(location).toString()
                    return@repeat
                }
                check(code in 200..299) { "HTTP $code" }
                return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } finally {
                connection.disconnect()
            }
        }
        error("Çok fazla yönlendirme")
    }

    private suspend fun fetchPlayerResponseHls(videoId: String): String? {
        val body = "{\"context\":{\"client\":{\"clientName\":\"WEB\",\"clientVersion\":\"2.20240726.00.00\"," +
            "\"hl\":\"tr\",\"gl\":\"TR\"}},\"videoId\":\"$videoId\"}"
        val connection = URI(INNERTUBE_PLAYER_URL).toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 6_000
            connection.readTimeout = 10_000
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("User-Agent", BROWSER_USER_AGENT)
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.toByteArray()) }
            check(connection.responseCode in 200..299)
            val json = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            return Regex(""""hlsManifestUrl"\s*:\s*"(https:[^"]+)"""")
                .find(json)
                ?.groupValues
                ?.getOrNull(1)
                ?.replace("\\/", "/")
        } finally {
            connection.disconnect()
        }
    }

    private const val MAX_CANDIDATES = 4
    /** YouTube, masaüstü Chrome UA'sında player response'u bazen gizliyor; mobil UA güvenilir. */
    private const val YOUTUBE_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 11; SM-T295) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    private const val BROWSER_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"
    private const val INNERTUBE_PLAYER_URL =
        "https://www.youtube.com/youtubei/v1/player?key=AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
}
