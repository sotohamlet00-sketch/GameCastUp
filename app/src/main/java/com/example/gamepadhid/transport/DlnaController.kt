package com.example.gamepadhid.transport

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.example.gamepadhid.shizuku.DiagLog
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL

/**
 * DLNA/UPnP NATIVO (sin bibliotecas):
 *  - Descubrimiento: SSDP M-SEARCH por multicast (con MulticastLock) + descripción XML del dispositivo.
 *  - Control: SOAP del servicio AVTransport (SetAVTransportURI, Play, Stop) con la URL del MPEG-TS continuo.
 * Hace falta que la TV sea un "MediaRenderer" (muchas Samsung, LG, Sony y Philips lo son).
 */
class DlnaController(context: Context) {

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var searching = false
    private var searchThread: Thread? = null
    private var lastDevice: TvDevice? = null

    // ------------------------------------------------------------------ Descubrimiento

    fun startDiscovery(onDevices: (List<TvDevice>) -> Unit) {
        stopDiscovery()
        searching = true
        searchThread = Thread({
            val wifi = appContext.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val lock = wifi.createMulticastLock("gamecast-ssdp").apply { setReferenceCounted(false); acquire() }
            var socket: DatagramSocket? = null
            try {
                val sock = DatagramSocket().apply { soTimeout = 800 }
                socket = sock
                wifiNetwork()?.let { try { it.bindSocket(sock) } catch (e: Exception) { /* sigue por la ruta por defecto */ } }
                val msg = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\n" +
                    "MX: 2\r\nST: $SERVICE\r\n\r\n").toByteArray()
                val group = InetAddress.getByName("239.255.255.250")
                val found = LinkedHashMap<String, TvDevice>()
                val seen = HashSet<String>()
                val buf = ByteArray(2048)
                var sent = 0
                var lastSend = 0L
                while (searching) {
                    val now = SystemClock.uptimeMillis()
                    if (sent < 4 && now - lastSend > 2500) { // varias veces: UDP puede perder paquetes
                        sock.send(DatagramPacket(msg, msg.size, group, 1900))
                        sent++
                        lastSend = now
                    }
                    try {
                        val packet = DatagramPacket(buf, buf.size)
                        sock.receive(packet)
                        val text = String(packet.data, 0, packet.length, Charsets.ISO_8859_1)
                        val location = header(text, "LOCATION") ?: continue
                        if (!seen.add(location)) continue
                        val device = describe(location) ?: continue
                        found[location] = device
                        val snapshot = found.values.toList()
                        main.post { if (searching) onDevices(snapshot) }
                    } catch (e: SocketTimeoutException) {
                        // sigue esperando
                    }
                }
            } catch (e: Exception) {
                DiagLog.add("DLNA: búsqueda interrumpida: $e")
            } finally {
                try { socket?.close() } catch (e: Exception) { /* cerrado */ }
                if (lock.isHeld) lock.release()
            }
        }, "gamecast-ssdp").also { it.isDaemon = true; it.start() }
    }

    fun stopDiscovery() {
        searching = false
        searchThread = null
    }

    private fun header(response: String, name: String): String? =
        response.lineSequence().firstOrNull { it.startsWith("$name:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()

    /** Descarga la descripción y devuelve la TV si ofrece AVTransport. */
    private fun describe(location: String): TvDevice? {
        val xml = httpGet(location) ?: return null
        if (!xml.contains("AVTransport")) return null
        val name = Regex("<friendlyName>(.*?)</friendlyName>", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.groupValues?.get(1)?.trim()?.let { unescape(it) } ?: return null
        val model = Regex("<modelName>(.*?)</modelName>", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.groupValues?.get(1)?.trim()?.let { unescape(it) }.orEmpty()
        return TvDevice(TransportKind.DLNA, location, name, if (model.isEmpty()) "DLNA / UPnP" else "DLNA · $model")
    }

    private fun controlUrl(location: String): URL? {
        val xml = httpGet(location) ?: return null
        val service = xml.split("<service>").drop(1).firstOrNull { it.contains("AVTransport") } ?: return null
        val path = Regex("<controlURL>(.*?)</controlURL>", RegexOption.DOT_MATCHES_ALL)
            .find(service)?.groupValues?.get(1)?.trim() ?: return null
        return try { URL(URL(location), path) } catch (e: Exception) { null }
    }

    // ------------------------------------------------------------------ Reproducción

    /** Manda [tsUrl] a la TV. [done] se llama en el hilo principal: null si fue bien, o el motivo del fallo. */
    fun play(device: TvDevice, tsUrl: String, done: (String?) -> Unit) {
        lastDevice = device
        Thread({
            val result: String? = try {
                val control = controlUrl(device.id)
                if (control == null) "La TV no ofrece el servicio AVTransport."
                else {
                    runCatching { soap(control, "Stop", "<InstanceID>0</InstanceID>") } // limpia un estado anterior; si falla no importa
                    soap(
                        control, "SetAVTransportURI",
                        "<InstanceID>0</InstanceID><CurrentURI>${escape(tsUrl)}</CurrentURI>" +
                            "<CurrentURIMetaData>${escape(didl(tsUrl))}</CurrentURIMetaData>"
                    ) ?: soap(control, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>")
                }
            } catch (e: Exception) {
                "${e.javaClass.simpleName}: ${e.message}"
            }
            main.post { done(result) }
        }, "gamecast-dlna-play").start()
    }

    fun stop() {
        val device = lastDevice ?: return
        lastDevice = null
        Thread({
            try {
                controlUrl(device.id)?.let { soap(it, "Stop", "<InstanceID>0</InstanceID>") }
            } catch (e: Exception) {
                // la TV ya no está
            }
        }, "gamecast-dlna-stop").start()
    }

    private fun didl(url: String): String =
        "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
            "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\"><item id=\"0\" parentID=\"-1\" restricted=\"1\">" +
            "<dc:title>GameCast</dc:title><upnp:class>object.item.videoItem</upnp:class>" +
            "<res protocolInfo=\"http-get:*:video/mp2t:*\">${escape(url)}</res></item></DIDL-Lite>"

    /** Devuelve null si fue bien, o el motivo. */
    private fun soap(control: URL, action: String, args: String): String? {
        val body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>" +
            "<u:$action xmlns:u=\"$SERVICE\">$args</u:$action></s:Body></s:Envelope>"
        val conn = open(control)
        return try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            conn.setRequestProperty("SOAPACTION", "\"$SERVICE#$action\"")
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            if (code in 200..299) null else "La TV respondió HTTP $code a $action."
        } finally {
            conn.disconnect()
        }
    }

    // ------------------------------------------------------------------ Red

    private fun wifiNetwork(): Network? {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.allNetworks.firstOrNull {
            cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
    }

    private fun open(url: URL): HttpURLConnection {
        val conn = (wifiNetwork()?.openConnection(url) ?: url.openConnection()) as HttpURLConnection
        conn.connectTimeout = 4000
        conn.readTimeout = 6000
        return conn
    }

    private fun httpGet(location: String): String? = try {
        val conn = open(URL(location))
        try {
            if (conn.responseCode in 200..299) conn.inputStream.bufferedReader().readText() else null
        } finally {
            conn.disconnect()
        }
    } catch (e: Exception) {
        null
    }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    private fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&")

    private companion object {
        const val SERVICE = "urn:schemas-upnp-org:service:AVTransport:1"
    }
}
