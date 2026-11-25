package com.ilyemir.antena.server

import android.content.Context
import android.media.AudioManager
import androidx.core.content.getSystemService
import com.ilyemir.antena.R
import com.ilyemir.antena.data.db.ChannelEntity
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * iPhone Safari Kontrol Paneli ("Antena Remote") için gömülü HTTP sunucusu.
 *
 *  - Motor: Ktor Server CIO (Netty KULLANILMAZ), 0.0.0.0:8080.
 *  - Panel tek HTML/CSS/JS paketi olarak cihazdan sunulur; dış CDN yok, yerel ağda çevrimdışı açılır.
 *  - Fontlar da APK içinden servis edilir (aynı Space Grotesk / IBM Plex ailesi).
 */
class AntenaServer(
    private val context: Context,
    private val container: AppContainer,
) {

    suspend fun start() {
        if (started.getAndSet(true)) return
        for (port in intArrayOf(PREFERRED_PORT, PREFERRED_PORT + 1, 8888)) {
            val engine = runCatching {
                embeddedServer(CIO, host = "0.0.0.0", port = port) {
                    routing { routes() }
                }.also { it.start(wait = false) }
            }.getOrNull()
            if (engine != null) {
                running = engine
                container.updateServerPort(port)
                return
            }
        }
        started.set(false)
    }

    private fun ok(message: String = "ok") = JSONObject().put("ok", true).put("message", message)

    private fun ChannelEntity.toJson() = JSONObject().apply {
        put("id", id)
        put("number", number)
        put("name", name)
        put("url", url)
        put("backupUrl", backupUrl ?: JSONObject.NULL)
        put("group", group ?: JSONObject.NULL)
        put("hidden", hidden)
    }

    private fun Route.routes() {

        get("/") {
            call.respondText(html(), ContentType.Text.Html)
        }

        get("/fonts/{name}") {
            val name = call.parameters["name"] ?: ""
            val resId = FONT_RESOURCES[name]
            if (resId == null) {
                call.respondText("yok", status = io.ktor.http.HttpStatusCode.NotFound)
            } else {
                val bytes = withContext(Dispatchers.IO) {
                    context.resources.openRawResource(resId).use { it.readBytes() }
                }
                call.response.header(HttpHeaders.CacheControl, "public, max-age=604800")
                call.respondBytes(bytes, FONT_TYPES[name] ?: ContentType.Application.OctetStream)
            }
        }

        // ---------------- Durum ----------------

        get("/api/state") {
            val count = withContext(Dispatchers.IO) { container.repository.channelCount() }
            call.respondText(
                JSONObject()
                    .put("ip", context.localIpAddress())
                    .put("port", container.serverPort)
                    .put("channels", count)
                    .put("currentNumber", AntenaRuntime.currentChannelNumber.value ?: JSONObject.NULL)
                    .put("guestMode", AntenaRuntime.guestMode.value)
                    .put("sleepMinutes", AntenaRuntime.sleepMinutes.value)
                    .put("bootStart", AntenaRuntime.bootStart.value)
                    .put("aspect", AntenaRuntime.aspectMode.value)
                    .put("source", AntenaRuntime.m3uUrl.value)
                    .toString(),
                ContentType.Application.Json,
            )
        }

        // ---------------- Kanallar ----------------

        get("/api/channels") {
            val channels = withContext(Dispatchers.IO) { container.repository.allChannels() }
            val array = JSONArray()
            channels.forEach { array.put(it.toJson()) }
            call.respondText(array.toString(), ContentType.Application.Json)
        }

        post("/api/channels/order") {
            val body = JSONArray(call.receiveText())
            val order = ArrayList<Pair<Long, Int>>(body.length())
            for (i in 0 until body.length()) {
                val item = body.optJSONObject(i) ?: continue
                val id = item.optLong("id")
                val number = item.optInt("number", i + 1)
                if (id > 0) order += id to number
            }
            val changed = withContext(Dispatchers.IO) { container.repository.reorder(order) }
            call.respondText(ok("($changed) kanal yeniden sıralandı").toString(), ContentType.Application.Json)
        }

        post("/api/channels/patch") {
            val body = JSONObject(call.receiveText())
            val id = body.optLong("id")
            val hidden = if (body.has("hidden")) body.getBoolean("hidden") else null
            val patched = withContext(Dispatchers.IO) {
                container.repository.patch(
                    id = id,
                    name = body.optString("name").takeIf { it.isNotEmpty() },
                    url = body.optString("url").takeIf { it.isNotEmpty() },
                    backupUrl = if (body.has("backupUrl")) body.optString("backupUrl") else null,
                    hidden = hidden,
                )
            }
            call.respondText(
                if (patched) ok().toString()
                else JSONObject().put("ok", false).put("error", "kanal bulunamadı").toString(),
                ContentType.Application.Json,
            )
        }

        // ---------------- Playlist & Smart Merge ----------------

        post("/api/playlist") {
            val body = JSONObject(call.receiveText())
            val url = body.optString("url").trim()
            val merge = body.optBoolean("merge", false)
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    if (merge) {
                        val merged = container.repository.smartMerge(url)
                        JSONObject()
                            .put("ok", true)
                            .put("mode", "merge")
                            .put("updated", merged.updated)
                            .put("added", merged.added)
                            .put("kept", merged.kept)
                    } else {
                        val total = container.repository.importFromUrl(url).getOrThrow()
                        JSONObject().put("ok", true).put("mode", "replace").put("total", total)
                    }
                }
            }
            call.respondText(
                result.getOrElse {
                    JSONObject().put("ok", false).put("error", it.message ?: "içe aktarma başarısız")
                }.toString(),
                ContentType.Application.Json,
            )
        }

        // ---------------- Kumanda ----------------

        post("/api/remote") {
            val body = JSONObject(call.receiveText())
            when (body.optString("action")) {
                "chUp" -> AntenaRuntime.send(RemoteCmd.ChannelUp)
                "chDown" -> AntenaRuntime.send(RemoteCmd.ChannelDown)
                "digit" -> AntenaRuntime.send(RemoteCmd.Digit(body.optInt("digit")))
                "clear" -> AntenaRuntime.send(RemoteCmd.ClearDigits)
                "prev" -> AntenaRuntime.send(RemoteCmd.Previous)
                "aspect" -> AntenaRuntime.send(RemoteCmd.ToggleAspect)
                "volUp" -> volume(raise = true)
                "volDown" -> volume(raise = false)
                "mute" -> toggleMute()
            }
            call.respondText(ok().toString(), ContentType.Application.Json)
        }

        // ---------------- Sistem & Kilit ----------------

        post("/api/settings") {
            val body = JSONObject(call.receiveText())
            withContext(Dispatchers.IO) {
                if (body.has("guestMode")) {
                    val value = body.getBoolean("guestMode")
                    container.settings.setGuestMode(value)
                    AntenaRuntime.guestMode.value = value
                }
                if (body.has("sleepMinutes")) {
                    val value = body.optInt("sleepMinutes").coerceIn(0, 240)
                    container.settings.setSleepMinutes(value)
                    AntenaRuntime.sleepMinutes.value = value
                }
                if (body.has("bootStart")) {
                    val value = body.getBoolean("bootStart")
                    container.settings.setBootStart(value)
                    AntenaRuntime.bootStart.value = value
                }
                if (body.has("aspect")) {
                    val value = body.optString("aspect").ifEmpty { "fit" }
                    container.settings.setAspectMode(value)
                    AntenaRuntime.aspectMode.value = value
                }
            }
            call.respondText(ok().toString(), ContentType.Application.Json)
        }

        get("/api/backup") {
            val json = withContext(Dispatchers.IO) { container.repository.exportJson() }
            call.response.header(
                HttpHeaders.ContentDisposition,
                "attachment; filename=\"antena_backup.json\"",
            )
            call.respondText(json, ContentType.Application.Json)
        }

        post("/api/restore") {
            val body = call.receiveText()
            val result = runCatching {
                withContext(Dispatchers.IO) { container.repository.importJson(body) }
            }
            call.respondText(
                result.fold(
                    onSuccess = { ok("${it} kanal geri yüklendi").toString() },
                    onFailure = { JSONObject().put("ok", false).put("error", it.message ?: "geçersiz yedek") },
                ).toString(),
                ContentType.Application.Json,
            )
        }
    }

    private fun volume(raise: Boolean) {
        val audio = context.getSystemService<AudioManager>() ?: return
        runCatching {
            audio.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                if (raise) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                0,
            )
        }
    }

    private fun toggleMute() {
        val audio = context.getSystemService<AudioManager>() ?: return
        runCatching {
            audio.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                AudioManager.ADJUST_TOGGLE_MUTE,
                0,
            )
        }
    }

    private suspend fun html(): String {
        htmlCache?.let { return it }
        val text = withContext(Dispatchers.IO) {
            context.assets.open("remote.html").bufferedReader().use { it.readText() }
        }
        htmlCache = text
        return text
    }

    companion object {
        private const val PREFERRED_PORT = 8080
        private val started = AtomicBoolean(false)

        @Volatile
        private var running: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

        @Volatile
        private var htmlCache: String? = null

        private val FONT_RESOURCES = mapOf(
            "space-grotesk-bold.ttf" to R.font.space_grotesk_bold,
            "space-grotesk-medium.ttf" to R.font.space_grotesk_medium,
            "ibm-plex-sans-regular.ttf" to R.font.ibm_plex_sans_regular,
            "ibm-plex-sans-medium.ttf" to R.font.ibm_plex_sans_medium,
            "ibm-plex-sans-semibold.ttf" to R.font.ibm_plex_sans_semibold,
            "ibm-plex-mono-regular.ttf" to R.font.ibm_plex_mono_regular,
            "ibm-plex-mono-medium.ttf" to R.font.ibm_plex_mono_medium,
        )

        private val FONT_TYPES = mapOf(
            "space-grotesk-bold.ttf" to ContentType("font", "truetype"),
            "space-grotesk-medium.ttf" to ContentType("font", "truetype"),
            "ibm-plex-sans-regular.ttf" to ContentType("font", "truetype"),
            "ibm-plex-sans-medium.ttf" to ContentType("font", "truetype"),
            "ibm-plex-sans-semibold.ttf" to ContentType("font", "truetype"),
            "ibm-plex-mono-regular.ttf" to ContentType("font", "truetype"),
            "ibm-plex-mono-medium.ttf" to ContentType("font", "truetype"),
        )

        fun stop() {
            runCatching { running?.stop(100, 500) }
            running = null
            started.set(false)
        }
    }
}
