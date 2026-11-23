package com.example.gamepadhid.stream

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Surface
import com.example.gamepadhid.shizuku.DiagLog
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.CopyOnWriteArraySet

/**
 * FLUJO DE VÍDEO DEL TELÉFONO PARA LA TV (Google Cast y DLNA).
 *   Surface de entrada → ScreenEncoder (H.264) → TsMuxer → HlsServer (HTTP en el teléfono, red local).
 * Quien alimenta la Surface es una pantalla virtual (Modo Consola) o MediaProjection (espejo simple).
 * Latencia aproximada: 6–10 s con HLS (Cast), algo menos con TS continuo (DLNA). Pensado para ver, no para jugar.
 */
object StreamSession {

    enum class State { IDLE, STARTING, RUNNING, ERROR }

    @Volatile var state = State.IDLE
        private set
    @Volatile var message: String? = null
        private set
    @Volatile var keyFrames = 0
        private set

    private var encoder: ScreenEncoder? = null
    private var server: HlsServer? = null
    private var host: String? = null
    private var port = 0

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    private fun publish(s: State, msg: String?) {
        state = s
        message = msg
        main.post { for (l in listeners) l() }
    }

    val segmentCount: Int get() = server?.segmentCount ?: 0
    val hlsUrl: String? get() = host?.let { "http://$it:$port/live.m3u8" }
    val tsUrl: String? get() = host?.let { "http://$it:$port/live.ts" }

    /**
     * Arranca todo y devuelve la Surface donde hay que dibujar, o null si falló (el motivo queda en [message]).
     * @param errorNoNetwork texto si el teléfono no está en una red Wi-Fi local
     */
    @Synchronized
    fun start(context: Context, width: Int, height: Int, errorNoNetwork: String): Surface? {
        stop()
        publish(State.STARTING, null)
        keyFrames = 0
        val ip = localAddress()
        if (ip == null) {
            publish(State.ERROR, errorNoNetwork)
            return null
        }
        return try {
            val srv = HlsServer()
            port = srv.start()
            host = ip
            server = srv
            val muxer = TsMuxer()
            val bitrate = (width.toLong() * height * FPS * 0.15).toInt().coerceIn(2_000_000, 8_000_000)
            val enc = ScreenEncoder(width, height, bitrate, FPS, object : ScreenEncoder.Listener {
                override fun onFrame(data: ByteArray, ptsUs: Long, keyFrame: Boolean) {
                    if (keyFrame) keyFrames++
                    srv.onFrame(muxer.mux(data, ptsUs, keyFrame), keyFrame, ptsUs)
                }

                override fun onError(message: String) {
                    DiagLog.add("StreamSession: $message")
                    publish(State.ERROR, message)
                }
            })
            val surface = enc.start()
            encoder = enc
            DiagLog.add("StreamSession: ${width}x$height ${bitrate / 1000} kbps en http://$ip:$port")
            publish(State.RUNNING, null)
            surface
        } catch (e: Exception) {
            DiagLog.add("StreamSession falló: $e")
            stop()
            publish(State.ERROR, "${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    @Synchronized
    fun stop() {
        encoder?.stop()
        encoder = null
        server?.stop()
        server = null
        host = null
        keyFrames = 0
        if (state != State.ERROR) publish(State.IDLE, null)
    }

    /** IPv4 del teléfono en la red local (prefiere wlan*; vale también un punto de acceso propio). */
    fun localAddress(): String? {
        return try {
            val candidates = NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
            for (nif in candidates) {
                for (addr in nif.inetAddresses) {
                    if (addr is Inet4Address && addr.isSiteLocalAddress) return addr.hostAddress
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    private const val FPS = 30
}
