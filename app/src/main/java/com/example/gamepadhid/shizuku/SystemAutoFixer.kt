package com.example.gamepadhid.shizuku

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.example.gamepadhid.Prefs

/**
 * AUTO-FIX: la app se concede a sí misma, con Shizuku, lo que Android bloquea a las apps instaladas a mano
 * (Samsung y otros): superponer sobre otras apps (SYSTEM_ALERT_WINDOW) y los límites de segundo plano y batería.
 *
 * Cómo (investigado): Shizuku.newProcess está privado/obsoleto y se retirará; la vía recomendada es un UserService,
 * que ya tenemos (GameCastUserService, proceso shell). Ahí se ejecutan `cmd appops set <paquete> <OP> allow` y
 * `cmd deviceidle whitelist +<paquete>`, solo sobre el propio paquete. En Android 13+ se añade primero
 * ACCESS_RESTRICTED_SETTINGS (los ajustes restringidos de las apps instaladas a mano).
 * Nada de esto es posible sin Shizuku activo: si falla, la app muestra el aviso clásico.
 */
object SystemAutoFixer {

    class Result(val shizukuOk: Boolean, val overlayGranted: Boolean, val report: String)

    /** Hace falta si falta el permiso de superposición o si nunca se aplicó el arreglo. */
    fun needsFix(context: Context): Boolean =
        !Settings.canDrawOverlays(context) || !Prefs.autoFixDone(context)

    /** [callback] se invoca siempre en el hilo principal. */
    fun run(context: Context, callback: (Result) -> Unit) {
        val app = context.applicationContext
        val main = Handler(Looper.getMainLooper())

        val state = ShizukuManager.state(app)
        if (state != ShizukuManager.State.READY) {
            callback(Result(false, Settings.canDrawOverlays(app), "Shizuku no está listo ($state)"))
            return
        }
        GameCastClient.whenReady(app, 6000) { service ->
            if (service == null) {
                callback(Result(false, Settings.canDrawOverlays(app), "El servicio de Shizuku no respondió"))
                return@whenReady
            }
            Thread({
                val report = try {
                    service.autoFix(app.packageName)
                } catch (e: Throwable) {
                    "ERR ${e.javaClass.simpleName}: ${e.message}"
                }
                DiagLog.add("Auto-Fix:\n$report")
                // El cambio puede tardar un instante en verse desde la app
                var granted = Settings.canDrawOverlays(app)
                var tries = 0
                while (!granted && tries < 8) {
                    try { Thread.sleep(250) } catch (e: InterruptedException) { break }
                    granted = Settings.canDrawOverlays(app)
                    tries++
                }
                if (granted) Prefs.setAutoFixDone(app, true)
                main.post { callback(Result(!report.startsWith("ERR "), granted, report)) }
            }, "gamecast-autofix").start()
        }
    }
}
