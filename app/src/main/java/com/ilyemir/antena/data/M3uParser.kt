package com.ilyemir.antena.data

/**
 * Tek bir çalınabilir kanal — M3U listesinden ayrıştırılmış hali.
 */
data class M3uEntry(
    val name: String,
    val url: String,
    val logo: String? = null,
    val tvgId: String? = null,
    val group: String? = null,
    val userAgent: String? = null,
    val referrer: String? = null,
)

/**
 * Genişletilmiş M3U / M3U8 listelerini ayrıştırır (iptv-org ve çoğu IPTV sağlayıcısının formatı).
 *
 * Destekler:
 *  - `#EXTINF:-1 tvg-id="..." tvg-logo="..." group-title="A;B",Kanal Adı`
 *  - `#EXTGRP:Grup` (alternatif grup bildirimi)
 *  - `#EXTVLCOPT:http-user-agent=...` / `#EXTVLCOPT:http-referrer=...`
 *  - Kodi tarzı URL sonuna eklenen başlıklar: `http://sunucu/akis.m3u8|User-Agent=x&Referer=y`
 *
 * Sentez notu: yapı, açık kaynak LiteTV (MIT) ve onair-tv projelerindeki ayrıştırıcıların
 * ayıklanmış ve sadeleştirilmiş halidir.
 */
object M3uParser {

    private val attrRegex = Regex("""([A-Za-z0-9_-]+)="([^"]*)"""")

    fun parse(text: String): List<M3uEntry> {
        val channels = ArrayList<M3uEntry>()
        var info: String? = null
        var vlcUserAgent: String? = null
        var vlcReferrer: String? = null
        var extGroup: String? = null

        for (raw in text.lineSequence()) {
            val line = raw.trim().removePrefix("\uFEFF")
            when {
                line.isEmpty() -> Unit

                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    info = line
                    vlcUserAgent = null
                    vlcReferrer = null
                    extGroup = null
                }

                line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                    val option = line.substringAfter(':')
                    val key = option.substringBefore('=').trim().lowercase()
                    val value = option.substringAfter('=', "").trim()
                    when (key) {
                        "http-user-agent" -> vlcUserAgent = value.ifEmpty { null }
                        "http-referrer", "http-referer" -> vlcReferrer = value.ifEmpty { null }
                    }
                }

                line.startsWith("#EXTGRP:", ignoreCase = true) ->
                    extGroup = line.substringAfter(':').trim().ifEmpty { null }

                line.startsWith("#") -> Unit // #EXTM3U ve diğer yönergeler

                else -> {
                    val extinf = info
                    if (extinf != null) {
                        channels += buildEntry(
                            extinf = extinf,
                            rawUrl = line,
                            vlcUserAgent = vlcUserAgent,
                            vlcReferrer = vlcReferrer,
                            extGroup = extGroup,
                            index = channels.size,
                        )
                    }
                    info = null
                    vlcUserAgent = null
                    vlcReferrer = null
                    extGroup = null
                }
            }
        }
        return channels
    }

    private fun buildEntry(
        extinf: String,
        rawUrl: String,
        vlcUserAgent: String?,
        vlcReferrer: String?,
        extGroup: String?,
        index: Int,
    ): M3uEntry {
        val attrs = attrRegex.findAll(extinf)
            .associate { it.groupValues[1].lowercase() to it.groupValues[2].trim() }

        // Kodi tarzı "url|Header=deger&Header2=deger2"
        val url = rawUrl.substringBefore('|').trim()
        val pipeHeaders = rawUrl.substringAfter('|', "")
            .split('&')
            .mapNotNull { pair ->
                val k = pair.substringBefore('=', "").trim()
                val v = pair.substringAfter('=', "").trim()
                if (k.isEmpty() || v.isEmpty()) null else k.lowercase() to v
            }
            .toMap()

        val name = titleOf(extinf)
            ?: attrs["tvg-name"]?.ifEmpty { null }
            ?: "Kanal ${index + 1}"

        val group = (attrs["group-title"]?.ifEmpty { null } ?: extGroup)
            ?.let(::unescape)
            ?.ifEmpty { null }

        return M3uEntry(
            name = unescape(name),
            url = url,
            logo = attrs["tvg-logo"]?.ifEmpty { null },
            tvgId = attrs["tvg-id"]?.ifEmpty { null },
            group = group,
            userAgent = vlcUserAgent
                ?: attrs["user-agent"]?.ifEmpty { null }
                ?: pipeHeaders["user-agent"],
            referrer = vlcReferrer
                ?: attrs["referrer"]?.ifEmpty { null }
                ?: pipeHeaders["referer"]
                ?: pipeHeaders["referrer"],
        )
    }

    /** Kanal adı: tırnak içinde olmayan ilk virgülden sonraki metin. */
    private fun titleOf(extinf: String): String? {
        var inQuotes = false
        for (i in extinf.indices) {
            when (extinf[i]) {
                '"' -> inQuotes = !inQuotes
                ',' -> if (!inQuotes) return extinf.substring(i + 1).trim().ifEmpty { null }
            }
        }
        return null
    }

    /** Bazı listeler "&" yerine "&amp;" yazar (HTML tarzı); gerçek karakterleri göster. */
    private fun unescape(s: String): String = if ('&' !in s) s else s
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")
}
