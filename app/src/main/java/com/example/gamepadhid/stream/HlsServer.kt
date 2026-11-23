package com.example.gamepadhid.stream

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.ceil

/**
 * Servidor HTTP del teléfono para la TV (puerto efímero, sin dependencias):
 *   /live.m3u8   lista HLS en vivo (los últimos 6 segmentos)       → Google Cast
 *   /segN.ts     segmentos de ~2 s que empiezan en fotograma clave
 *   /live.ts     MPEG-TS continuo (empieza en el siguiente clave)  → DLNA
 * CORS abierto, para el receptor web de Chromecast.
 */
class HlsServer {

    private class Segment(val seq: Long, val data: ByteArray, val durationSec: Double)

    private val lock = Any()
    private val segments = ArrayDeque<Segment>()
    private var nextSeq = 0L
    private val current = ByteArrayOutputStream()
    private var segStartUs = 0L

    private val liveClients = CopyOnWriteArrayList<LiveClient>()
    private val pool = Executors.newCachedThreadPool()
    private var server: ServerSocket? = null
    @Volatile private var running = false

    val segmentCount: Int get() = synchronized(lock) { segments.size }

    /** Arranca y devuelve el puerto. */
    fun start(): Int {
        val s = ServerSocket(0)
        s.reuseAddress = true
        server = s
        running = true
        pool.execute {
            while (running) {
                try {
                    val client = s.accept()
                    pool.execute { handle(client) }
                } catch (e: Exception) {
                    break
                }
            }
        }
        return s.localPort
    }

    fun stop() {
        running = false
        try { server?.close() } catch (e: Exception) { /* cerrado */ }
        server = null
        for (c in liveClients) c.close()
        liveClients.clear()
        pool.shutdownNow()
        synchronized(lock) { segments.clear(); current.reset() }
    }

    /** [ts] son los paquetes TS de un fotograma (ver TsMuxer.mux). */
    fun onFrame(ts: ByteArray, key: Boolean, ptsUs: Long) {
        synchronized(lock) {
            if (key && current.size() > 0 && ptsUs - segStartUs >= SEGMENT_US) {
                segments.addLast(Segment(nextSeq++, current.toByteArray(), (ptsUs - segStartUs) / 1_000_000.0))
                while (segments.size > KEEP_SEGMENTS) segments.removeFirst()
                current.reset()
            }
            if (current.size() == 0) {
                if (!key) return@synchronized // los segmentos siempre empiezan en un clave
                segStartUs = ptsUs
            }
            current.write(ts)
        }
        for (c in liveClients) c.offer(ts, key)
    }

    // ------------------------------------------------------------------ HTTP

    private fun handle(socket: Socket) {
        var keepOpen = false
        try {
            socket.soTimeout = 10_000
            val input = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val requestLine = input.readLine() ?: return
            while (true) { // las cabeceras no se necesitan
                val line = input.readLine() ?: break
                if (line.isEmpty()) break
            }
            val parts = requestLine.split(" ")
            val method = parts.getOrNull(0) ?: return
            val path = parts.getOrNull(1)?.substringBefore('?') ?: "/"
            val out = socket.getOutputStream()
            val head = method == "HEAD"

            when {
                method == "OPTIONS" -> respond(out, "204 No Content", "text/plain", null, false)
                path == "/live.m3u8" -> {
                    val body = playlist()
                    if (body == null) respond(out, "404 Not Found", "text/plain", "sin segmentos".toByteArray(), head)
                    else respond(out, "200 OK", "application/vnd.apple.mpegurl", body.toByteArray(), head)
                }
                path.startsWith("/seg") && path.endsWith(".ts") -> {
                    val seq = path.removePrefix("/seg").removeSuffix(".ts").toLongOrNull()
                    val data = synchronized(lock) { segments.firstOrNull { it.seq == seq }?.data }
                    if (data == null) respond(out, "404 Not Found", "text/plain", "no existe".toByteArray(), head)
                    else respond(out, "200 OK", "video/mp2t", data, head)
                }
                path == "/live.ts" -> {
                    if (head) {
                        respond(out, "200 OK", "video/mp2t", null, true)
                    } else {
                        socket.soTimeout = 0
                        out.write(
                            ("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\nCache-Control: no-cache\r\n" +
                                "Connection: close\r\ntransferMode.dlna.org: Streaming\r\n" +
                                "Access-Control-Allow-Origin: *\r\n\r\n").toByteArray()
                        )
                        val client = LiveClient(socket, out)
                        liveClients.add(client)
                        keepOpen = true
                        client.runWriter() // bloquea este hilo hasta que el cliente se vaya
                    }
                }
                else -> respond(out, "200 OK", "text/plain", "GameCast".toByteArray(), head)
            }
        } catch (e: Exception) {
            // cliente desconectado
        } finally {
            if (!keepOpen) try { socket.close() } catch (e: Exception) { /* cerrado */ }
        }
    }

    private fun respond(out: OutputStream, status: String, type: String, body: ByteArray?, headOnly: Boolean) {
        val len = body?.size ?: 0
        val headers = "HTTP/1.1 $status\r\nContent-Type: $type\r\nContent-Length: $len\r\n" +
            "Access-Control-Allow-Origin: *\r\nAccess-Control-Allow-Headers: *\r\n" +
            "Access-Control-Allow-Methods: GET, HEAD, OPTIONS\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n"
        out.write(headers.toByteArray())
        if (body != null && !headOnly) out.write(body)
        out.flush()
    }

    private fun playlist(): String? {
        val list = synchronized(lock) { segments.toList() }
        if (list.isEmpty()) return null
        val target = ceil(list.maxOf { it.durationSec }).toInt().coerceAtLeast(1)
        val sb = StringBuilder()
        sb.append("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:").append(target).append('\n')
        sb.append("#EXT-X-MEDIA-SEQUENCE:").append(list.first().seq).append('\n')
        for (s in list) {
            sb.append("#EXTINF:").append(String.format(java.util.Locale.US, "%.3f", s.durationSec)).append(",\n")
            sb.append("seg").append(s.seq).append(".ts\n")
        }
        return sb.toString()
    }

    /** Cliente de /live.ts: cola propia para que uno lento no frene al codificador (si se llena, se le corta). */
    private inner class LiveClient(private val socket: Socket, private val out: OutputStream) {
        private val queue = LinkedBlockingQueue<ByteArray>(300)
        @Volatile private var started = false
        @Volatile private var dead = false

        fun offer(ts: ByteArray, key: Boolean) {
            if (dead) return
            if (!started) {
                if (!key) return // empieza en un fotograma clave
                started = true
            }
            if (!queue.offer(ts)) close()
        }

        fun runWriter() {
            try {
                while (!dead) {
                    val chunk = queue.poll(1, TimeUnit.SECONDS) ?: continue
                    out.write(chunk)
                }
            } catch (e: Exception) {
                // la TV cerró la conexión
            } finally {
                close()
            }
        }

        fun close() {
            dead = true
            liveClients.remove(this)
            try { socket.close() } catch (e: Exception) { /* cerrado */ }
        }
    }

    private companion object {
        const val SEGMENT_US = 1_800_000L // los segmentos acaban en el primer clave tras ~1,8 s (≈2 s con GOP de 1 s)
        const val KEEP_SEGMENTS = 6
    }
}
