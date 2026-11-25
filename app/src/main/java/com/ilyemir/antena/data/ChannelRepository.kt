package com.ilyemir.antena.data

import android.content.Context
import com.ilyemir.antena.data.db.AntenaDatabase
import com.ilyemir.antena.data.db.ChannelEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI

/**
 * Kanal listesinin tek doğrusu: Room'daki tablo.
 * M3U indirme + ayrıştırma + numaralandırma burada yapılır.
 */
class ChannelRepository(context: Context) {

    private val appContext = context.applicationContext
    private val dao = AntenaDatabase.get(context).channelDao()
    private val settings = SettingsStore(context)

    /** Görünür (gizlenmemiş) kanallar, numara sırasına göre. */
    val channels: Flow<List<ChannelEntity>> = dao.observeVisible()

    val lastChannelNumber: Flow<Int> = settings.lastChannelNumber

    val m3uUrl: Flow<String> = settings.m3uUrl

    suspend fun setLastChannelNumber(number: Int) = settings.setLastChannelNumber(number)

    /** Panel yönetimi için gizliler dahil tüm kanallar. */
    suspend fun allChannels(): List<ChannelEntity> = dao.getAll()

    suspend fun channelCount(): Int = dao.count()

    /**
     * M3U adresini indirip veritabanını yeniler. Dönen değer kanal sayısıdır.
     * Üst üste tekrarlanan kanal adları: ikinci adres birincinin yedek (backup) adresi sayılır.
     */
    suspend fun importFromUrl(url: String): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val entities = buildEntities(downloadText(url))
            dao.replaceAll(entities)
            settings.setM3uUrl(url)
            entities.size
        }
    }

    /**
     * APK'ya gömülü, çok kaynaklı doğrulanmış Türk kanal havuzunu içe aktarır
     * (assets/default_tr.m3u + default_favorites.txt). İlk açılışta otomatik çağrılır;
     * böylece kullanıcı M3U adresi beklemeden doğrudan 001 kanalından yayına başlar.
     */
    suspend fun importEmbeddedDefaults(): Int = withContext(Dispatchers.IO) {
        val text = appContext.assets.open("default_tr.m3u")
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        val favorites = appContext.assets.open("default_favorites.txt")
            .bufferedReader(Charsets.UTF_8).use { it.readLines() }
            .map { it.trim() }.filter { it.isNotEmpty() }

        val favRank = favorites.withIndex()
            .associate { (index, name) -> name.lowercase() to index + 1 }
        val entities = buildEntities(text).map { channel ->
            favRank[channel.name.lowercase()]
                ?.let { channel.copy(isFavorite = true, favoriteOrder = it) }
                ?: channel
        }
        dao.replaceAll(entities)
        entities.size
    }

    /** M3U metninden numaralandırılmış kanal listesi üretir (yedek adres sezgisi dahil). */
    private fun buildEntities(text: String): List<ChannelEntity> {
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
        return entities
    }

    /** Panel sıralama yaması: number = genel sıra, favoriteOrder = Özel Liste sırası. */
    data class OrderPatch(val id: Long, val number: Int? = null, val favoriteOrder: Int? = null)

    suspend fun applyOrder(patches: List<OrderPatch>) = withContext(Dispatchers.IO) {
        val byId = dao.getAll().associateBy { it.id }
        val updated = patches.mapNotNull { patch ->
            byId[patch.id]?.copy(
                number = patch.number ?: byId.getValue(patch.id).number,
                favoriteOrder = patch.favoriteOrder ?: byId.getValue(patch.id).favoriteOrder,
            )
        }
        if (updated.isNotEmpty()) dao.updateAll(updated)
        updated.size
    }

    /** Tek kanalda isim / yayın adresi / yedek adresi / görünürlük / favori düzenlemesi. */
    suspend fun patch(
        id: Long,
        name: String? = null,
        url: String? = null,
        backupUrl: String? = null,
        hidden: Boolean? = null,
        isFavorite: Boolean? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        val current = dao.getById(id) ?: return@withContext false
        var favoriteOrder = current.favoriteOrder
        if (isFavorite == true && favoriteOrder <= 0) {
            favoriteOrder = (dao.getAll().maxOfOrNull { it.favoriteOrder } ?: 0) + 1
        }
        dao.update(
            current.copy(
                name = name?.trim()?.ifEmpty { null } ?: current.name,
                url = url?.trim()?.ifEmpty { null } ?: current.url,
                backupUrl = backupUrl?.trim()?.ifEmpty { null } ?: current.backupUrl,
                hidden = hidden ?: current.hidden,
                isFavorite = isFavorite ?: current.isFavorite,
                favoriteOrder = favoriteOrder,
            ),
        )
        true
    }

    data class MergeResult(val updated: Int, val added: Int, val kept: Int)

    /**
     * Smart Merge: mevcut sıralamayı, özel isimleri ve görünürlüğü BOZMAZ.
     * Yalnızca adı eşleşen kanalların yayın (ve yoksa yedek) adreslerini yeniler;
     * listede yeni olan kanalları sona ekler, eskileri silmez.
     */
    suspend fun smartMerge(url: String): MergeResult = withContext(Dispatchers.IO) {
        val entries = M3uParser.parse(downloadText(url))
        require(entries.isNotEmpty()) { "Listede kanal bulunamadı" }

        // Yeni listedeki aynı adlı tekrarlar: ikinci adres yedek kabul edilir.
        class Fresh(val entry: M3uEntry, var backup: String?)
        val incoming = LinkedHashMap<String, Fresh>()
        for (entry in entries) {
            val key = entry.name.trim().lowercase()
            val existing = incoming[key]
            when {
                existing == null -> incoming[key] = Fresh(entry, null)
                existing.backup == null -> existing.backup = entry.url
            }
        }

        val existing = dao.getAll().toMutableList()
        var updated = 0
        var added = 0
        val seen = HashSet<String>()

        for (index in existing.indices) {
            val channel = existing[index]
            val key = channel.name.trim().lowercase()
            val fresh = incoming[key]?.entry ?: continue
            seen += key
            val merged = channel.copy(
                url = fresh.url,
                backupUrl = incoming[key]?.backup ?: channel.backupUrl,
                userAgent = fresh.userAgent ?: channel.userAgent,
                referrer = fresh.referrer ?: channel.referrer,
            )
            if (merged != channel) {
                existing[index] = merged
                updated++
            }
        }

        var nextNumber = (existing.maxOfOrNull { it.number } ?: 0) + 1
        for ((key, fresh) in incoming) {
            if (key in seen) continue
            val entry = fresh.entry
            existing += ChannelEntity(
                number = nextNumber++,
                name = entry.name,
                url = entry.url,
                backupUrl = fresh.backup,
                group = entry.group,
                userAgent = entry.userAgent,
                referrer = entry.referrer,
            )
            added++
        }

        dao.replaceAll(existing)
        settings.setM3uUrl(url)
        MergeResult(updated = updated, added = added, kept = existing.size - updated - added)
    }

    /** antena_backup.json içeriği. */
    suspend fun exportJson(): String = withContext(Dispatchers.IO) {
        val array = org.json.JSONArray()
        for (channel in dao.getAll()) {
            array.put(
                org.json.JSONObject().apply {
                    put("number", channel.number)
                    put("name", channel.name)
                    put("url", channel.url)
                    put("backupUrl", channel.backupUrl ?: org.json.JSONObject.NULL)
                    put("group", channel.group ?: org.json.JSONObject.NULL)
                    put("userAgent", channel.userAgent ?: org.json.JSONObject.NULL)
                    put("referrer", channel.referrer ?: org.json.JSONObject.NULL)
                    put("hidden", channel.hidden)
                    put("isFavorite", channel.isFavorite)
                    put("favoriteOrder", channel.favoriteOrder)
                },
            )
        }
        org.json.JSONObject()
            .put("app", "antena")
            .put("version", 2)
            .put("source", m3uUrlFirst())
            .put("channels", array)
            .toString(2)
    }

    private suspend fun m3uUrlFirst(): String = settings.m3uUrl.first()

    /** Yedeği geri yükler; mevcut tablonun yerini alır. */
    suspend fun importJson(json: String): Int = withContext(Dispatchers.IO) {
        val root = runCatching { org.json.JSONObject(json) }.getOrElse {
            error("Geçersiz yedek dosyası")
        }
        val array = root.optJSONArray("channels") ?: error("Yedekte kanal listesi yok")
        val entities = ArrayList<ChannelEntity>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val url = item.optString("url")
            if (url.isEmpty()) continue
            entities += ChannelEntity(
                number = item.optInt("number", entities.size + 1),
                name = item.optString("name").ifEmpty { "Kanal ${entities.size + 1}" },
                url = url,
                backupUrl = item.optString("backupUrl").takeIf { it.isNotEmpty() && it != "null" },
                group = item.optString("group").takeIf { it.isNotEmpty() && it != "null" },
                userAgent = item.optString("userAgent").takeIf { it.isNotEmpty() && it != "null" },
                referrer = item.optString("referrer").takeIf { it.isNotEmpty() && it != "null" },
                hidden = item.optBoolean("hidden", false),
                isFavorite = item.optBoolean("isFavorite", false),
                favoriteOrder = item.optInt("favoriteOrder", 0),
            )
        }
        require(entities.isNotEmpty()) { "Yedekte geçerli kanal yok" }
        dao.replaceAll(entities)
        entities.size
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
