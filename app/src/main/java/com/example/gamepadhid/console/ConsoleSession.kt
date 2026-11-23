package com.example.gamepadhid.console

import android.os.Handler
import android.os.Looper
import android.view.Surface
import com.example.gamepadhid.mapping.ControlProfile
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Estado compartido del Modo Consola.
 * Fases: IDLE → STARTING → LOBBY → (elegir app) STARTING → MAPPING → RUNNING; «Inicio» vuelve a LOBBY (ERROR aparte).
 * LOBBY = menú en la TV; el mando navega por él. MAPPING espera a que el usuario
 * coloque los controles; la única vía a RUNNING es [confirmMapping].
 */
object ConsoleSession {

    enum class Phase { IDLE, STARTING, LOBBY, MAPPING, RUNNING, ERROR }

    @Volatile
    var phase = Phase.IDLE
        private set

    @Volatile
    var message: String? = null
        private set

    @Volatile
    var gamePackage: String? = null
        private set

    /** true si el juego no respetó la pantalla virtual y apareció en el teléfono. */
    @Volatile
    var incompatible = false
        private set

    /** Perfil elegido al iniciar: AUTO, MANUAL (con mapeo) o APP (sin mapeo, trackpad). */
    @Volatile
    var profile: ControlProfile = ControlProfile.AUTO

    /** Surface de la ventana de la TV. El mapeador la presta y debe devolverla. */
    @Volatile
    var tvSurface: Surface? = null

    val active: Boolean get() = phase == Phase.STARTING || phase == Phase.LOBBY || phase == Phase.MAPPING || phase == Phase.RUNNING

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private var mapperRequested = false

    fun update(newPhase: Phase, game: String? = gamePackage, text: String? = null, notCompatible: Boolean = false) {
        if (newPhase != Phase.MAPPING) mapperRequested = false
        phase = newPhase
        gamePackage = game
        message = text
        incompatible = notCompatible
        main.post { for (l in listeners) l() }
    }

    /** true UNA sola vez por sesión: quien lo reciba abre el mapeador. */
    @Synchronized
    fun requestMapperLaunch(): Boolean {
        if (phase != Phase.MAPPING || mapperRequested) return false
        mapperRequested = true
        return true
    }

    /** Única vía a RUNNING. */
    fun confirmMapping() {
        if (phase == Phase.MAPPING) update(Phase.RUNNING, gamePackage, null)
    }

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
}
