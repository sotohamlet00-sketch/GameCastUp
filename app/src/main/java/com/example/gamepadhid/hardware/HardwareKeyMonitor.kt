package com.example.gamepadhid.hardware

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.example.gamepadhid.shizuku.DiagLog
import com.example.gamepadhid.shizuku.GameCastClient
import com.example.gamepadhid.shizuku.IKeyListener
import com.example.gamepadhid.shizuku.ShizukuManager

/**
 * Escucha Volumen y Encendido a través del servicio de usuario de Shizuku (getevent en el proceso shell).
 * Sirve incluso con la pantalla apagada y sin pedir accesibilidad. Limitación honesta: solo OBSERVA las teclas;
 * el volumen sigue cambiando y Encendido sigue apagando la pantalla (por eso, tras una acción de Encendido se
 * vuelve a encender). Varias piezas pueden escuchar a la vez (cada una con su etiqueta).
 */
object HardwareKeyMonitor {

    private val main = Handler(Looper.getMainLooper())
    private val users = LinkedHashMap<String, (HwKey, Boolean) -> Unit>()
    private var running = false

    private val stub = object : IKeyListener.Stub() {
        override fun onKey(keyCode: Int, down: Boolean) {
            val key = HwKey.fromKeyCode(keyCode) ?: return
            main.post { for (l in users.values.toList()) l(key, down) }
        }
    }

    fun register(context: Context, tag: String, listener: (HwKey, Boolean) -> Unit) {
        users[tag] = listener
        if (!running) start(context.applicationContext)
    }

    fun unregister(tag: String) {
        users.remove(tag)
        if (users.isEmpty() && running) stop()
    }

    private fun start(app: Context) {
        if (ShizukuManager.state(app) != ShizukuManager.State.READY) {
            DiagLog.add("Botones físicos: Shizuku no está listo; no se escuchan")
            return
        }
        running = true
        GameCastClient.whenReady(app, 5000) { service ->
            if (service == null || !running) {
                running = false
                return@whenReady
            }
            Thread({
                val r = try { service.startKeyMonitor(stub) } catch (e: Throwable) { "ERR:${e.message}" }
                DiagLog.add("Botones físicos: $r")
                if (r != "OK") running = false
            }, "gamecast-keys-start").start()
        }
    }

    private fun stop() {
        running = false
        Thread({
            try { GameCastClient.service?.stopKeyMonitor() } catch (e: Throwable) { /* servicio ya cerrado */ }
        }, "gamecast-keys-stop").start()
    }
}
