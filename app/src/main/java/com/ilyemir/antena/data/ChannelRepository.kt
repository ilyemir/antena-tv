package com.ilyemir.antena.data

import android.content.Context
import com.ilyemir.antena.data.db.AntenaDatabase
import com.ilyemir.antena.data.db.ChannelEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI

/**
 * Kanal listesinin tek doğrusu: Room'daki tablo.
 * M3U indirme + ayrıştırma + numaralandırma burada yapılır.
 */
class ChannelRepository(context: Context) {

    private val dao = AntenaDatabase.get(context).channelDao()
    private val settings = SettingsStore(context)

    /** Görünür (gizlenmemiş) kanallar, numara sırasına göre. */
    val channels: Flow<List<ChannelEntity>> = dao.observeVisible()

    val lastChannelNumber: Flow<Int> = settings.lastChannelNumber

    val m3uUrl: Flow<String> = settings.m3uUrl

    suspend fun setLastChannelNumber(number: Int) = settings.setLastChannelNumber(number)

    /**
     * M3U adresini indirip veritabanını yeniler. Dönen değer kanal sayısıdır.
     * Üst üste tekrarlanan kanal adları: ikinci adres birincinin yedek (backup) adresi sayılır.
     */
    suspend fun importFromUrl(url: String): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val text = downloadText(url)
            val entries = M3uParser.parse(text)
            require(entries.isNotEmpty()) { "Listede kanal bulunamadı" }

            val entities = ArrayList<ChannelEntity>(entries.size)
            for (entry in entries) {
                val previous = entities.lastOrNull()
                if (previous != null &&
                    previous.backupUrl == null &&
                    previous.name.equals(entry.name, ignoreCase = true)
                ) {
                    entities[entities.size - 1] = previous.copy(backupUrl = entry.url)
                } else {
                    entities += ChannelEntity(
                        number = entities.size + 1,
                        name = entry.name,
                        url = entry.url,
                        group = entry.group,
                        userAgent = entry.userAgent,
                        referrer = entry.referrer,
                    )
                }
            }
            dao.replaceAll(entities)
            settings.setM3uUrl(url)
            entities.size
        }
    }

    private fun downloadText(url: String): String {
        var current = url
        repeat(5) {
            val connection = URI(current).toURL().openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("User-Agent", USER_AGENT)
                val code = connection.responseCode
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location")
                        ?: error("Yönlendirme adresi alınamadı")
                    current = if (location.startsWith("http")) {
                        location
                    } else {
                        URI(current).resolve(location).toString()
                    }
                    return@repeat
                }
                require(code in 200..299) { "Sunucu HTTP $code döndü" }
                return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } finally {
                connection.disconnect()
            }
        }
        error("Çok fazla yönlendirme")
    }

    private companion object {
        const val USER_AGENT = "Antena/1.0 (Android TV)"
    }
}
