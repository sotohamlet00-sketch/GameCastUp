package com.example.gamepadhid.transport

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.example.gamepadhid.R
import com.example.gamepadhid.stream.StreamSession
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Manda a la TV (Cast o DLNA) el flujo de [StreamSession]. Espera a que haya vídeo suficiente:
 * HLS necesita ≥3 segmentos (≈6 s); el MPEG-TS continuo, un fotograma clave.
 */
object TvPlayer {

    enum class State { IDLE, WAITING, PLAYING, ERROR }

    @Volatile var state = State.IDLE
        private set
    @Volatile var message: String? = null
        private set

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private var cast: GoogleCastController? = null
    private var dlna: DlnaController? = null
    private var current: TvDevice? = null
    private var generation = 0

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    private fun publish(s: State, msg: String?) {
        state = s
        message = msg
        for (l in listeners) l()
    }

    /** Hilo principal. [device] debe ser CAST o DLNA. */
    fun start(context: Context, device: TvDevice) {
        val app = context.applicationContext
        stopPlayback()
        val gen = ++generation
        current = device
        publish(State.WAITING, app.getString(R.string.tvp_waiting))
        val deadline = SystemClock.uptimeMillis() + 45_000

        val poll = object : Runnable {
            override fun run() {
                if (gen != generation) return
                val ready = when (device.kind) {
                    TransportKind.CAST -> StreamSession.segmentCount >= 3
                    TransportKind.DLNA -> StreamSession.keyFrames >= 1
                    TransportKind.MIRACAST -> true
                }
                when {
                    ready -> push(app, device, gen)
                    SystemClock.uptimeMillis() > deadline -> publish(State.ERROR, app.getString(R.string.tvp_no_video))
                    else -> main.postDelayed(this, 500)
                }
            }
        }
        main.post(poll)
    }

    private fun push(app: Context, device: TvDevice, gen: Int) {
        val finish: (String?) -> Unit = { error ->
            if (gen == generation) {
                if (error == null) publish(State.PLAYING, app.getString(R.string.tvp_playing, device.name))
                else publish(State.ERROR, error)
            }
        }
        when (device.kind) {
            TransportKind.CAST -> {
                val url = StreamSession.hlsUrl ?: return finish(app.getString(R.string.tvp_no_video))
                (cast ?: GoogleCastController(app).also { cast = it }).play(device, url, finish)
            }
            TransportKind.DLNA -> {
                val url = StreamSession.tsUrl ?: return finish(app.getString(R.string.tvp_no_video))
                (dlna ?: DlnaController(app).also { dlna = it }).play(device, url, finish)
            }
            TransportKind.MIRACAST -> finish(null)
        }
    }

    private fun stopPlayback() {
        generation++
        when (current?.kind) {
            TransportKind.CAST -> cast?.stop()
            TransportKind.DLNA -> dlna?.stop()
            else -> {}
        }
        current = null
    }

    fun stop() {
        stopPlayback()
        publish(State.IDLE, null)
    }
}
