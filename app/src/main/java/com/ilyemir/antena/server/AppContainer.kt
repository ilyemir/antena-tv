package com.ilyemir.antena.server

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.ilyemir.antena.AntenaApplication
import com.ilyemir.antena.data.ChannelRepository
import com.ilyemir.antena.data.SettingsStore
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
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
    /** Sağ üst köşedeki dijital saat (web panelinden açılıp kapanır). */
    val showClock = MutableStateFlow(true)

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
        scope.launch {
            AntenaServer(application, this@AppContainer).start()
            // Panel portu bağlanınca mDNS yayınla: iPhone Safari "http://antena.local:8080"
            // ile kutunun IP'si değişse bile panele ulaşır.
            serverPort.takeIf { it > 0 }?.let { registerWebService(it) }
        }
        // antena.local A kaydına bizzat cevap veren minik mDNS sunucusu.
        localNameResponder.start(scope)
        // Ayarlar değiştiğinde sunucunun okuduğu anlık durumu güncelle.
        scope.launch { settings.guestMode.collect { AntenaRuntime.guestMode.value = it } }
        scope.launch { settings.sleepMinutes.collect { AntenaRuntime.sleepMinutes.value = it } }
        scope.launch { settings.bootStart.collect { AntenaRuntime.bootStart.value = it } }
        scope.launch { settings.aspectMode.collect { AntenaRuntime.aspectMode.value = it } }
        scope.launch { settings.m3uUrl.collect { AntenaRuntime.m3uUrl.value = it } }
        scope.launch { settings.showClock.collect { AntenaRuntime.showClock.value = it } }
    }

    fun shutdown() {
        localNameResponder.stop()
        unregisterWebService()
        AntenaServer.stop()
    }

    // ---- mDNS: _http._tcp "antena" (antena.local) -------------------------------------

    private val localNameResponder = LocalNameResponder(application)

    private var nsdRegistration: NsdManager.RegistrationListener? = null

    /**
     * NsdManager kaydı ana (Looper'lı) iş parçacığında yapılmalıdır; başarısızlık
     * paneli IP ile erişilebilir kılmayı engellemez, bu yüzden sessizce yutulur.
     */
    private fun registerWebService(port: Int) {
        Handler(Looper.getMainLooper()).post {
            runCatching {
                if (nsdRegistration != null) return@runCatching
                val nsd = application.getSystemService(Context.NSD_SERVICE) as NsdManager
                val info = NsdServiceInfo().apply {
                    serviceName = MDNS_SERVICE_NAME
                    serviceType = MDNS_SERVICE_TYPE
                    setPort(port)
                }
                val listener = object : NsdManager.RegistrationListener {
                    override fun onServiceRegistered(registration: NsdServiceInfo) {
                        Log.i(TAG, "mDNS kaydedildi: ${registration.serviceName}${registration.serviceType} :${registration.port}")
                    }

                    override fun onRegistrationFailed(registration: NsdServiceInfo, errorCode: Int) {
                        Log.w(TAG, "mDNS kaydı başarısız (kod $errorCode), yeniden denenecek")
                        nsdRegistration = null
                        retryRegistration(port)
                    }

                    override fun onUnregistrationFailed(registration: NsdServiceInfo, errorCode: Int) = Unit
                    override fun onServiceUnregistered(registration: NsdServiceInfo) = Unit
                }
                nsdRegistration = listener
                nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
            }
        }
    }

    /**
     * Bazı ROM'lar açılışta mDNS kaydını reddeder (Wi-Fi/Ethernet henüz hazır değil);
     * birkaç saniye sonra tek bir yeniden deneme yapılır.
     */
    private fun retryRegistration(port: Int) {
        scope.launch {
            delay(4_000)
            if (nsdRegistration == null) registerWebService(port)
        }
    }

    private fun unregisterWebService() {
        val listener = nsdRegistration ?: return
        nsdRegistration = null
        Handler(Looper.getMainLooper()).post {
            runCatching {
                (application.getSystemService(Context.NSD_SERVICE) as? NsdManager)
                    ?.unregisterService(listener)
            }
        }
    }

    private companion object {
        const val TAG = "AntenaMdns"
        const val MDNS_SERVICE_NAME = "antena"
        const val MDNS_SERVICE_TYPE = "_http._tcp."
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


