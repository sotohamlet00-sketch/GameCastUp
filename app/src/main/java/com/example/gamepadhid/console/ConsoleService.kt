package com.example.gamepadhid.console

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Surface
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.gamepadhid.MainActivity
import com.example.gamepadhid.Prefs
import com.example.gamepadhid.mapping.ControlProfile
import com.example.gamepadhid.R
import com.example.gamepadhid.stream.StreamSession
import com.example.gamepadhid.transport.TvDevice
import com.example.gamepadhid.transport.TvPlayer
import com.example.gamepadhid.cast.TvOutputWindow
import com.example.gamepadhid.lobby.ConsoleLobbyActivity
import com.example.gamepadhid.overlay.FloatingPadService
import com.example.gamepadhid.shizuku.DiagLog
import com.example.gamepadhid.shizuku.GameCastClient
import com.example.gamepadhid.shizuku.ShizukuManager
import com.example.gamepadhid.virtual.GameLauncher
import com.example.gamepadhid.virtual.GameVirtualDisplay

/**
 * SESIÓN DEL MODO CONSOLA.
 *  1. Muestra una ventana en la TV (TvOutputWindow) con un SurfaceView.
 *  2. Crea la pantalla virtual "GameCast" apuntando DIRECTAMENTE a esa Surface (sin MediaProjection).
 *  3. Lanza en ella el Lobby (ConsoleLobbyActivity) y arranca el mando flotante en modo menú.
 *  4. Al elegir una app en el Lobby (ACTION_LAUNCH) la lanza encima, comprueba que respetó la pantalla
 *     secundaria y pasa al mapeo; «Inicio» (ACTION_HOME) la cierra y vuelve al Lobby.
 *
 * Servicio en primer plano de tipo specialUse (con su permiso y su propiedad en el manifiesto): connectedDevice
 * falla en Android 14+ si no hay permisos de Bluetooth/USB/red concedidos.
 */
class ConsoleService : Service(), TvOutputWindow.Listener {

    companion object {
        const val ACTION_STOP = "com.example.gamepadhid.action.STOP_CONSOLE"
        private const val STREAM_LONG_EDGE = 1280 // por la red: 720p
        const val ACTION_LAUNCH = "com.example.gamepadhid.action.LAUNCH_APP"
        const val ACTION_HOME = "com.example.gamepadhid.action.HOME_LOBBY"
        const val EXTRA_PACKAGE = "package"
        const val EXTRA_TV_DISPLAY_ID = "tv_display_id"
        private const val CHANNEL_ID = "console"
        private const val NOTIFICATION_ID = 44

        /** Inicia el Modo Consola: abre el Lobby en la TV. */
        fun start(context: Context, tvDisplayId: Int) {
            val intent = Intent(context, ConsoleService::class.java)
                .putExtra(EXTRA_TV_DISPLAY_ID, tvDisplayId)
            ContextCompat.startForegroundService(context, intent)
        }

        /** Desde el Lobby: abre una app en la pantalla virtual (pasa por el mapeo de controles). */
        fun launchApp(context: Context, packageName: String) {
            context.startService(
                Intent(context, ConsoleService::class.java).setAction(ACTION_LAUNCH).putExtra(EXTRA_PACKAGE, packageName)
            )
        }

        /** Cierra la app actual y vuelve al Lobby (también si se cancela el mapeo). */
        fun goHome(context: Context) {
            context.startService(Intent(context, ConsoleService::class.java).setAction(ACTION_HOME))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ConsoleService::class.java))
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var displayManager: DisplayManager
    private var window: TvOutputWindow? = null
    private var size: GameVirtualDisplay.Size? = null
    private var tvDisplayId = -1
    private var gamePackage = ""
    private var destroyed = false
    private var streamDevice: TvDevice? = null

    private val streamListener: () -> Unit = {
        if (StreamSession.state == StreamSession.State.ERROR) fail(StreamSession.message ?: getString(R.string.err_generic))
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {
            if (displayId == tvDisplayId) {
                ConsoleSession.update(ConsoleSession.Phase.IDLE, gamePackage, getString(R.string.status_tv_lost))
                stopSelf()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
            ACTION_LAUNCH -> intent?.getStringExtra(EXTRA_PACKAGE)?.let { launchApp(it) }
            ACTION_HOME -> goHome()
            else -> if (intent != null) begin(intent)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        destroyed = true
        if (streamDevice != null) {
            StreamSession.removeListener(streamListener)
            TvPlayer.stop()
            StreamSession.stop()
            streamDevice = null
        }
        displayManager.unregisterDisplayListener(displayListener)
        FloatingPadService.stop(this)
        GameVirtualDisplay.release()
        ConsoleSession.tvSurface = null
        window?.hide()
        window = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (ConsoleSession.active) ConsoleSession.update(ConsoleSession.Phase.IDLE, gamePackage, null)
        super.onDestroy()
    }

    // ------------------------------------------------------------------ Arranque

    private fun begin(intent: Intent) {
        if (ConsoleSession.active) return
        gamePackage = ""
        ConsoleSession.profile = Prefs.controlProfile(this)
        tvDisplayId = intent.getIntExtra(EXTRA_TV_DISPLAY_ID, -1)
        ConsoleSession.update(ConsoleSession.Phase.STARTING, null, null)
        DiagLog.add("Modo Consola: inicio del Lobby (TV=$tvDisplayId)")
        startAsForeground()

        // Cast/DLNA: sin pantalla externa; el vídeo de la pantalla virtual se codifica y se envía por la red
        streamDevice = Prefs.streamDevice(this)
        val stream = streamDevice
        val tv = if (stream == null) {
            displayManager.getDisplay(tvDisplayId) ?: return fail(getString(R.string.err_display_gone))
        } else {
            null
        }
        if (!Settings.canDrawOverlays(this)) return fail(getString(R.string.err_no_overlay))
        if (ShizukuManager.state(this) != ShizukuManager.State.READY) return fail(getString(R.string.console_err_shizuku))
        displayManager.registerDisplayListener(displayListener, handler)

        // Si el servicio de usuario no conecta, se sigue con el respaldo (BinderWrapper / pantalla creada en la app)
        GameCastClient.whenReady(this, 5000) {
            if (destroyed) return@whenReady
            val s = GameVirtualDisplay.computeSize(this, if (stream != null) STREAM_LONG_EDGE else 1920)
            size = s
            if (stream != null) {
                val surface = StreamSession.start(this, s.width, s.height, getString(R.string.tvc_stream_no_network))
                if (surface == null) {
                    fail(StreamSession.message ?: getString(R.string.err_generic))
                    return@whenReady
                }
                StreamSession.addListener(streamListener)
                onSurfaceReady(surface) // la Surface del codificador hace de "pantalla de la TV"
            } else {
                val w = TvOutputWindow(this, tv!!, this)
                window = w
                w.show(s.width, s.height) // cuando la Surface existe, llega onSurfaceReady()
            }
        }
    }

    override fun onSurfaceReady(surface: Surface) {
        val s = size ?: return
        ConsoleSession.tvSurface = surface
        if (GameVirtualDisplay.displayId >= 0) { // la Surface se recreó: se reconecta la misma pantalla
            if (ConsoleSession.phase != ConsoleSession.Phase.MAPPING) GameVirtualDisplay.setSurface(surface)
            return
        }
        val error = GameVirtualDisplay.create(this, surface, s)
        if (error != null) {
            fail(getString(R.string.console_err_display, error))
            return
        }
        streamDevice?.let { TvPlayer.start(this, it) } // Cast/DLNA: la TV empieza a reproducir cuando haya vídeo
        launchLobby()
    }

    override fun onSurfaceLost() {
        ConsoleSession.tvSurface = null
        if (ConsoleSession.phase != ConsoleSession.Phase.MAPPING) GameVirtualDisplay.setSurface(null)
    }

    /** El Lobby es la primera "app" de la pantalla virtual; el mando flotante nace aquí, en modo menú. */
    private fun launchLobby() {
        GameLauncher.launchLobby(this, GameVirtualDisplay.displayId) { result ->
            if (destroyed) return@launchLobby
            when (result) {
                GameLauncher.Result.OK, GameLauncher.Result.UNVERIFIED -> {
                    ConsoleSession.update(ConsoleSession.Phase.LOBBY, null, null)
                    FloatingPadService.start(this)
                }
                else -> fail(getString(R.string.lobby_err_launch))
            }
        }
    }

    /** Pedido del Lobby: abre la app encima del Lobby. Después viene el mapeo (MAPPING). */
    private fun launchApp(pkg: String) {
        if (destroyed || ConsoleSession.phase != ConsoleSession.Phase.LOBBY || pkg == packageName) return
        gamePackage = pkg
        ConsoleSession.update(ConsoleSession.Phase.STARTING, pkg, null)
        GameLauncher.launchAndVerify(this, pkg, GameVirtualDisplay.displayId) { result ->
            if (destroyed) return@launchAndVerify
            when (result) {
                GameLauncher.Result.OK, GameLauncher.Result.UNVERIFIED -> {
                    val note = if (result == GameLauncher.Result.UNVERIFIED) getString(R.string.console_unverified) else null
                    // Perfil APP: sin mapeo, directo al mando de navegación (trackpad + Atrás/Inicio)
                    val next = if (ConsoleSession.profile == ControlProfile.APP) ConsoleSession.Phase.RUNNING
                    else ConsoleSession.Phase.MAPPING
                    ConsoleSession.update(next, pkg, note)
                }
                GameLauncher.Result.NOT_ON_VIRTUAL -> {
                    forceStop(pkg) // apareció en el teléfono: se cierra
                    backToLobby(getString(R.string.console_incompatible))
                }
                GameLauncher.Result.NOT_INSTALLED -> backToLobby(getString(R.string.console_err_not_installed))
                GameLauncher.Result.FAILED -> backToLobby(getString(R.string.console_err_launch))
            }
        }
    }

    /** Cierra la app en curso y deja el Lobby al frente. */
    private fun goHome() {
        if (destroyed || ConsoleSession.phase == ConsoleSession.Phase.IDLE || ConsoleSession.phase == ConsoleSession.Phase.ERROR) return
        if (gamePackage.isNotEmpty()) forceStop(gamePackage)
        backToLobby(null)
        // Si el Lobby ya no está (lo cerró el sistema), se lanza de nuevo
        handler.postDelayed({
            if (!destroyed && !ConsoleLobbyActivity.alive) launchLobby()
        }, 900)
    }

    private fun backToLobby(message: String?) {
        gamePackage = ""
        ConsoleSession.update(ConsoleSession.Phase.LOBBY, null, message)
    }

    private fun forceStop(pkg: String) {
        if (pkg.isEmpty() || pkg == packageName) return
        try {
            val r = GameCastClient.service?.forceStop(pkg)
            DiagLog.add("forceStop($pkg): $r")
        } catch (e: Throwable) {
            DiagLog.add("forceStop falló: $e")
        }
    }

    private fun fail(message: String) {
        DiagLog.add("Modo Consola: error: $message")
        ConsoleSession.update(ConsoleSession.Phase.ERROR, gamePackage, message)
        stopSelf()
    }

    // ------------------------------------------------------------------ Notificación

    private fun startAsForeground() {
        val channel = NotificationChannel(
            CHANNEL_ID, getString(R.string.notif_console_channel), NotificationManager.IMPORTANCE_LOW
        )
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
    }

    private fun buildNotification(): Notification {
        val stop = PendingIntent.getService(
            this, 0, Intent(this, ConsoleService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_cast)
            .setContentTitle(getString(R.string.notif_console_title))
            .setContentText(getString(R.string.notif_console_text))
            .setContentIntent(open)
            .addAction(0, getString(R.string.notif_stop), stop)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
}
