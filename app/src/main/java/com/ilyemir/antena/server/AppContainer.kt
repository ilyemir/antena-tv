package com.ilyemir.antena.server

import android.content.Context
import android.content.Intent
import com.ilyemir.antena.AntenaApplication
import com.ilyemir.antena.data.ChannelRepository
import com.ilyemir.antena.data.SettingsStore
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Web paneli ile TV arayüzü arasındaki tek yönlü komut köprüsü.
 */
sealed interface RemoteCmd {
    data object ChannelUp : RemoteCmd
    data object ChannelDown : RemoteCmd
    data class Digit(val value: Int) : RemoteCmd
    data object ClearDigits : RemoteCmd
    data object Previous : RemoteCmd
    data object ToggleAspect : RemoteCmd
    data object Exit : RemoteCmd
}

/**
 * Uygulama ömrü boyunca yaşayan çalışır durum. Sunucu okur, TV arayüzü yazar/günceller.
 */
object AntenaRuntime {
    /** Şu an izlenen kanalın numarası; yayın yokken null. */
    val currentChannelNumber = MutableStateFlow<Int?>(null)

    /** Web panelinden gelen kumanda komutları. */
    val commands = MutableSharedFlow<RemoteCmd>(extraBufferCapacity = 32)

    val guestMode = MutableStateFlow(false)
    val sleepMinutes = MutableStateFlow(0)
    val bootStart = MutableStateFlow(true)
    val aspectMode = MutableStateFlow("fit")
    val m3uUrl = MutableStateFlow("")

    fun send(command: RemoteCmd) {
        commands.tryEmit(command)
    }
}

/**
 * Uygulama kapsayıcısı: repository, ayarlar ve Ktor CIO sunucusu.
 */
class AppContainer(private val application: AntenaApplication) {

    val repository = ChannelRepository(application)
    val settings = SettingsStore(application)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    var serverPort: Int = 0
        private set

    internal fun updateServerPort(port: Int) {
        serverPort = port
    }

    fun start() {
        scope.launch { AntenaServer(application, this@AppContainer).start() }
        // Ayarlar değiştiğinde sunucunun okuduğu anlık durumu güncelle.
        scope.launch { settings.guestMode.collect { AntenaRuntime.guestMode.value = it } }
        scope.launch { settings.sleepMinutes.collect { AntenaRuntime.sleepMinutes.value = it } }
        scope.launch { settings.bootStart.collect { AntenaRuntime.bootStart.value = it } }
        scope.launch { settings.aspectMode.collect { AntenaRuntime.aspectMode.value = it } }
        scope.launch { settings.m3uUrl.collect { AntenaRuntime.m3uUrl.value = it } }
    }

    fun shutdown() {
        AntenaServer.stop()
    }
}

/**
 * Yerel Wi-Fi/Ethernet IPv4 adresini bulur (127.0.0.1 hariç).
 * Kutu Ethernet veya Wi-Fi ile bağlı olabileceğinden tüm arayüzler taranır.
 */
fun Context.localIpAddress(): String {
    runCatching {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull()?.let { return it }
    return "127.0.0.1"
}

/**
 * Ayar düğmesi veriliyse Mi Box açılışında uygulamayı kendiliğinden açar.
 * Android 10+ kısıtları nedeniyle activity başlatma ROM izin verirse çalışır;
 * bu yüzden deneme sessizce yutulur.
 */
class BootReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext as? AntenaApplication ?: return
        val enabled = kotlinx.coroutines.runBlocking { app.container.settings.bootStart.first() }
        if (!enabled) return
        runCatching {
            val launch = Intent(context, com.ilyemir.antena.MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launch)
        }
    }
}
