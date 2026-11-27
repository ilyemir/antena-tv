package com.ilyemir.antena.server

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * `antena.local` adını kendi kendine çözen minik mDNS cevaplayıcısı.
 *
 * Android'in NsdManager'ı yalnızca SERVİS (_http._tcp) kaydı yayınlar, A kaydı
 * (yani `antena.local` -> IP) üretmez; bu yüzden 224.0.0.251:5353'e gelen
 * A sorgularını cihazın yerel IPv4 adresiyle cevaplamayı deniyoruz.
 *
 * Sınırlama: Android'in kendi mdns demonu 5353'ü tek başına tuttuğundan bazı
 * ROM'larda (Mi Box dahil) sorgular bu sokete ulaşmaz ve isim çözümlemez;
 * panel o zaman `http://<ip>:8080` adresinden açılır. Kayıt başarısız olursa
 * uygulama çalışmaya devam eder.
 */
class LocalNameResponder(private val context: Context) {

    private var job: Job? = null
    private var socket: MulticastSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    fun start(scope: CoroutineScope) {
        job = scope.launch(Dispatchers.IO) {
            runCatching {
                val sock = MulticastSocket(MDNS_PORT).apply {
                    reuseAddress = true
                    setTimeToLive(2) // yalnızca yerel ağ
                }
                val group = InetAddress.getByName(MDNS_GROUP)
                NetworkInterface.getNetworkInterfaces().asSequence()
                    .filter { it.isUp && it.supportsMulticast() && !it.isLoopback }
                    .forEach { runCatching { sock.joinGroup(InetSocketAddress(group, MDNS_PORT), it) } }
                // Wi-Fi'de multicast alımı için kilit şart (Ethernet'te etkisiz).
                multicastLock = (context.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
                    ?.createMulticastLock("antena-mdns")
                    ?.apply { setReferenceCounted(false); acquire() }
                socket = sock
                Log.i(TAG, "mDNS cevaplayıcı çalışıyor: $HOST_NAME.local")
                val buffer = ByteArray(1200)
                while (isActive) {
                    val request = DatagramPacket(buffer, buffer.size)
                    runCatching { sock.receive(request) }
                    val answer = buildAnswer(buffer, request.offset, request.length) ?: continue
                    sock.send(DatagramPacket(answer, answer.size, request.address, request.port))
                }
            }.onFailure { Log.w(TAG, "mDNS cevaplayıcı kapandı: ${it.message}") }
        }
    }

    fun stop() {
        job?.cancel(); job = null
        runCatching { socket?.close() }; socket = null
        runCatching { multicastLock?.release() }; multicastLock = null
    }

    /**
     * Sorgu "antena.local" A kaydı içinse aynı pakete tek A cevabı ekler;
     * değilse null döner (gereksiz trafik üretmeyiz).
     */
    private fun buildAnswer(buffer: ByteArray, offset: Int, length: Int): ByteArray? {
        if (length < 16 || (buffer[offset + 2].toInt() and 0x80) != 0) return null // zaten cevap
        // QNAME: 12. bayttan itibaren uzunluk-önekli etiketler, 0 ile biter.
        var p = offset + 12
        var qnameLength = -1
        while (p < offset + length) {
            val label = buffer[p].toInt() and 0xFF
            if (label == 0) {
                qnameLength = p - (offset + 12) + 1
                break
            }
            if (label >= 0xC0) return null // sıkıştırılmış isim: kendi kaydımıza benzemez
            p += label + 1
        }
        if (qnameLength < 0) return null
        val questionEnd = offset + 12 + qnameLength + 4 // + QTYPE + QCLASS
        if (length < questionEnd) return null

        val qname = buffer.copyOfRange(offset + 12, offset + 12 + qnameLength)
        if (!nameOf(qname).startsWith("$HOST_NAME.$MDNS_TLD")) return null
        val type = ((buffer[offset + 12 + qnameLength].toInt() and 0xFF) shl 8) or
            (buffer[offset + 13 + qnameLength].toInt() and 0xFF)
        if (type != TYPE_A) return null

        val ip = context.localIpAddressBytes() ?: return null
        val questionSize = 12 + qnameLength + 4
        val out = ByteArray(questionSize + 16)
        // 1) Sorgunun tamamı aynen kopyalanır (isim pointer'ı 0x0C buna işaret eder).
        buffer.copyInto(out, 0, offset, offset + questionSize)
        // 2) Header: QR + yetkili cevap, ANCOUNT = 1.
        out[2] = 0x81.toByte(); out[3] = 0x80.toByte()
        out.writeShort(1, ANCOUNT_AT)
        // 3) Tek A kaydı.
        val r = questionSize
        out.writeShort(0xC00C, r) // QNAME pointer
        out.writeShort(TYPE_A, r + 2)
        out.writeShort(CLASS_IN, r + 4)
        out.writeInt(TTL_SECONDS, r + 6)
        out.writeShort(4, r + 10)
        ip.copyInto(out, r + 12)
        return out
    }

    private fun nameOf(labels: ByteArray): String {
        val sb = StringBuilder()
        var i = 0
        while (i < labels.size) {
            val len = labels[i].toInt() and 0xFF
            if (len == 0) break
            sb.append(String(labels, i + 1, len, Charsets.US_ASCII)).append('.')
            i += len + 1
        }
        return sb.toString().lowercase()
    }

    private fun ByteArray.writeShort(value: Int, at: Int) {
        this[at] = (value ushr 8).toByte(); this[at + 1] = value.toByte()
    }

    private fun ByteArray.writeInt(value: Int, at: Int) {
        this[at] = (value ushr 24).toByte(); this[at + 1] = (value ushr 16).toByte()
        this[at + 2] = (value ushr 8).toByte(); this[at + 3] = value.toByte()
    }

    private companion object {
        const val TAG = "AntenaMdns"
        const val MDNS_GROUP = "224.0.0.251"
        const val MDNS_PORT = 5353
        const val MDNS_TLD = "local"
        const val HOST_NAME = "antena"
        const val TYPE_A = 1
        const val CLASS_IN = 1
        const val TTL_SECONDS = 60
        const val ANCOUNT_AT = 6
    }
}

/** localIpAddress()'in ham byte karşılığı (A kaydı için). */
private fun Context.localIpAddressBytes(): ByteArray? =
    NetworkInterface.getNetworkInterfaces().asSequence()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.asSequence() }
        .filterIsInstance<Inet4Address>()
        .firstOrNull { it.isSiteLocalAddress }
        ?.address
