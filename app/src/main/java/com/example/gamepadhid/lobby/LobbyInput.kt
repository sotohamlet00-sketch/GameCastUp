package com.example.gamepadhid.lobby

import android.os.Handler
import android.os.Looper

/**
 * Canal de entrada del Lobby. El mando flotante (FloatingPadService, en modo Lobby) NO inyecta toques:
 * publica aquí teclas lógicas y el Lobby (misma app, mismo proceso) las recibe. Todo en el hilo principal.
 * Las direcciones se repiten al mantenerlas pulsadas.
 */
object LobbyInput {

    enum class Key { UP, DOWN, LEFT, RIGHT, SELECT, BACK, PAGE_UP, PAGE_DOWN }

    private const val REPEAT_DELAY_MS = 380L
    private const val REPEAT_INTERVAL_MS = 110L
    private const val STICK_THRESHOLD = 0.55f

    private val main = Handler(Looper.getMainLooper())
    private var listener: ((Key) -> Unit)? = null
    private var held: Key? = null
    private var stickKey: Key? = null

    private val repeat = object : Runnable {
        override fun run() {
            val key = held ?: return
            listener?.invoke(key)
            main.postDelayed(this, REPEAT_INTERVAL_MS)
        }
    }

    fun setListener(l: ((Key) -> Unit)?) {
        listener = l
        if (l == null) releaseAll()
    }

    private fun repeats(key: Key) = key == Key.UP || key == Key.DOWN || key == Key.LEFT || key == Key.RIGHT

    fun press(key: Key) {
        listener?.invoke(key)
        main.removeCallbacks(repeat)
        held = if (repeats(key)) key else null
        if (held != null) main.postDelayed(repeat, REPEAT_DELAY_MS)
    }

    fun release(key: Key) {
        if (held == key) {
            held = null
            main.removeCallbacks(repeat)
        }
    }

    fun releaseAll() {
        held = null
        stickKey = null
        main.removeCallbacks(repeat)
    }

    /** Stick izquierdo como cruceta: eje dominante, con umbral. Y negativo = arriba. */
    fun stick(x: Float, y: Float) {
        val key = when {
            maxOf(kotlin.math.abs(x), kotlin.math.abs(y)) < STICK_THRESHOLD -> null
            kotlin.math.abs(x) > kotlin.math.abs(y) -> if (x > 0) Key.RIGHT else Key.LEFT
            else -> if (y > 0) Key.DOWN else Key.UP
        }
        if (key == stickKey) return
        stickKey?.let { release(it) }
        stickKey = key
        if (key != null) press(key)
    }
}
