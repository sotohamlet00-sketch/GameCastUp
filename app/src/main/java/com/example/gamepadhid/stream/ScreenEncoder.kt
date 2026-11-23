package com.example.gamepadhid.stream

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.view.Surface

/**
 * Codificador H.264 Baseline alimentado por Surface: la pantalla virtual (o la captura) dibuja en [start]'s Surface.
 *  - GOP de 1 s y repetición del último fotograma cada 100 ms (la pantalla estática también genera vídeo).
 *  - SPS/PPS se capturan del buffer de configuración y se anteponen a cada fotograma clave.
 */
class ScreenEncoder(
    private val width: Int,
    private val height: Int,
    private val bitrate: Int,
    private val fps: Int,
    private val listener: Listener
) {
    interface Listener {
        /** Se invoca en el hilo del codificador. */
        fun onFrame(data: ByteArray, ptsUs: Long, keyFrame: Boolean)
        fun onError(message: String)
    }

    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    private var csd: ByteArray? = null
    private var firstPts = -1L

    /** Devuelve la Surface de entrada. Lanza una excepción si ningún perfil de configuración funciona. */
    fun start(): Surface {
        val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        var configured = false
        var lastError: Exception? = null
        // 1) perfil Baseline + CBR; 2) sin perfil; 3) formato mínimo
        for (attempt in 0..2) {
            try {
                c.configure(format(attempt), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                configured = true
                break
            } catch (e: Exception) {
                lastError = e
                c.reset()
            }
        }
        if (!configured) {
            c.release()
            throw IllegalStateException("No se pudo configurar el codificador H.264: ${lastError?.message}")
        }
        val input = c.createInputSurface()
        c.start()
        codec = c
        surface = input
        running = true
        thread = Thread({ drain(c) }, "gamecast-encoder").also { it.start() }
        return input
    }

    private fun format(attempt: Int): MediaFormat {
        val f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        f.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        f.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        if (attempt < 2) f.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L)
        if (attempt == 0) {
            f.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            f.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
        }
        return f
    }

    private fun drain(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        try {
            while (running) {
                val index = c.dequeueOutputBuffer(info, 20_000)
                if (index < 0) continue // timeout o cambio de formato
                val buffer = c.getOutputBuffer(index)
                if (buffer != null && info.size > 0) {
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    val bytes = ByteArray(info.size)
                    buffer.get(bytes)
                    handle(bytes, info)
                }
                c.releaseOutputBuffer(index, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
        } catch (e: Exception) {
            if (running) listener.onError("Codificador: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun handle(bytes: ByteArray, info: MediaCodec.BufferInfo) {
        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
            csd = bytes // SPS + PPS
            return
        }
        val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
        if (firstPts < 0) firstPts = info.presentationTimeUs
        val pts = info.presentationTimeUs - firstPts
        val header = csd
        listener.onFrame(if (key && header != null) header + bytes else bytes, pts, key)
    }

    fun requestKeyFrame() {
        try {
            codec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        } catch (e: Exception) {
            // no crítico
        }
    }

    fun stop() {
        running = false
        try { thread?.join(800) } catch (e: InterruptedException) { /* ignorar */ }
        thread = null
        try { codec?.stop() } catch (e: Exception) { /* ya parado */ }
        try { codec?.release() } catch (e: Exception) { /* ya liberado */ }
        codec = null
        try { surface?.release() } catch (e: Exception) { /* ya liberada */ }
        surface = null
        csd = null
        firstPts = -1L
    }
}
