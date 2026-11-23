package com.example.gamepadhid.lobby

import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.gamepadhid.console.ConsoleService
import android.widget.FrameLayout
import com.example.gamepadhid.console.ConsoleSession
import com.example.gamepadhid.theme.AppTheme
import com.example.gamepadhid.ui.SplashView
import kotlin.concurrent.thread

/**
 * LOBBY DEL MODO CONSOLA. Es "la app cero" de la pantalla virtual: ConsoleService la lanza ahí (am start --display)
 * antes que cualquier juego, así se ve igual por cualquier vía de transmisión (Miracast directo, Cast o DLNA), porque
 * todas muestran la pantalla virtual. Los juegos se abren encima; al cerrarlos (force-stop) el Lobby vuelve solo.
 *
 * Entrada: el mando flotante publica teclas en [LobbyInput] (mismo proceso). No usa toques.
 *
 * exported="true" es NECESARIO: la inicia el usuario shell (am start) y no podría con una actividad no exportada.
 * Por eso se cierra sola si no hay una sesión de Modo Consola activa.
 */
class ConsoleLobbyActivity : AppCompatActivity() {

    companion object {
        @Volatile
        var alive = false
            private set
    }

    private lateinit var lobby: LobbyView
    private var lastMessage: String? = null

    private val sessionListener: () -> Unit = {
        if (!ConsoleSession.active) {
            finish()
        } else if (ConsoleSession.phase == ConsoleSession.Phase.LOBBY) {
            val msg = ConsoleSession.message
            if (msg != null && msg != lastMessage) lobby.showMessage(msg)
            lastMessage = msg
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!ConsoleSession.active) {
            finish()
            return
        }
        alive = true
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowInsetsControllerCompat(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())

        val theme = AppTheme.current(this)
        lobby = LobbyView(this)
        lobby.applyTheme(theme)
        // Animación de carga: el teléfono se transforma en gamepad, late y se desvanece dando paso al Lobby
        val root = FrameLayout(this)
        root.addView(lobby, FrameLayout.LayoutParams(-1, -1))
        val intro = SplashView(this, theme)
        root.addView(intro, FrameLayout.LayoutParams(-1, -1))
        intro.onFinished = {
            intro.animate().alpha(0f).setDuration(600).withEndAction { root.removeView(intro) }.start()
        }
        setContentView(root)
        intro.start(2600)
        lobby.onActivate = { item -> activate(item) }

        // La clasificación consulta PackageManager: fuera del hilo principal
        thread(name = "lobby-apps") {
            val items = AppClassifier.buildItems(applicationContext)
            runOnUiThread { if (!isDestroyed) lobby.setItems(items) }
        }
        ConsoleSession.addListener(sessionListener)
    }

    override fun onStart() {
        super.onStart()
        if (::lobby.isInitialized) LobbyInput.setListener { lobby.handle(it) }
    }

    override fun onStop() {
        LobbyInput.setListener(null)
        super.onStop()
    }

    override fun onDestroy() {
        alive = false
        LobbyInput.setListener(null)
        ConsoleSession.removeListener(sessionListener)
        super.onDestroy()
    }

    private fun activate(item: LobbyItem) {
        if (ConsoleSession.phase != ConsoleSession.Phase.LOBBY) return // ya se está abriendo algo
        when (item.action) {
            LobbyAction.LAUNCH_APP -> item.packageName?.let { ConsoleService.launchApp(this, it) }
            LobbyAction.EXIT_CONSOLE -> ConsoleService.stop(this)
        }
    }
}
