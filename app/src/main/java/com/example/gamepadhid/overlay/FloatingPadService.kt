package com.example.gamepadhid.overlay

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.gamepadhid.MainActivity
import com.example.gamepadhid.R
import com.example.gamepadhid.console.ConsoleService
import com.example.gamepadhid.console.ConsoleSession
import com.example.gamepadhid.hardware.HardwareKeyMonitor
import com.example.gamepadhid.hardware.HwActions
import com.example.gamepadhid.hardware.HwKey
import com.example.gamepadhid.hardware.HwKeyDispatcher
import com.example.gamepadhid.lobby.LobbyInput
import com.example.gamepadhid.mapping.ControlIds
import com.example.gamepadhid.mapping.ControlProfile
import com.example.gamepadhid.mapping.ControlPoint
import com.example.gamepadhid.mapping.GameMapping
import com.example.gamepadhid.mapping.MappingStore
import com.example.gamepadhid.mapping.PadLayout
import com.example.gamepadhid.theme.AppTheme
import com.example.gamepadhid.theme.ButtonSkins
import com.example.gamepadhid.theme.SoundBank
import com.example.gamepadhid.theme.ThemeApplier
import com.example.gamepadhid.ui.AspectFrameLayout
import com.example.gamepadhid.ui.Fx
import com.example.gamepadhid.ui.JoystickView
import com.example.gamepadhid.virtual.GameVirtualDisplay
import com.example.gamepadhid.virtual.ScreenInfo
import com.example.gamepadhid.virtual.TouchInjectorShizuku
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * MANDO TÁCTIL FLOTANTE en la PANTALLA PRINCIPAL del teléfono (TYPE_APPLICATION_OVERLAY).
 *
 * Cada pulsación se convierte en un toque en la PANTALLA VIRTUAL del juego (TouchInjectorShizuku), en las
 * coordenadas guardadas en el mapa del juego. El mando no aparece en la TV porque la TV recibe la pantalla
 * virtual, que es otra distinta.
 *
 * Flags: FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL (los toques fuera del mando llegan al sistema). La ventana
 * mide solo lo que mide el mando. La barra central tiene un asa para arrastrarlo y una X para cerrar todo.
 *
 * Servicio en primer plano de tipo specialUse (ver ConsoleService).
 */
class FloatingPadService : Service() {

    companion object {
        const val ACTION_STOP = "com.example.gamepadhid.action.STOP_PAD"
        private const val CHANNEL_ID = "pad"
        private const val NOTIFICATION_ID = 45
        private const val ASPECT = 2.2f // ancho / alto del mando
        private const val SWIPE_POINTER = 20 // dedo virtual de los deslizamientos del perfil App

        @Volatile
        var running = false
            private set

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, FloatingPadService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingPadService::class.java))
        }
    }

    private lateinit var windowManager: WindowManager
    private var padView: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var screenW = 0
    private var screenH = 0
    private var builtGameMode = false
    private var builtMapping: GameMapping? = null
    private var hwRegistered = false
    private val ui = Handler(Looper.getMainLooper())
    private var lastX: Int? = null
    private var lastY: Int? = null
    private val sessionListener: () -> Unit = { onSessionChanged() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        ConsoleSession.addListener(sessionListener)
        SoundBank.init(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!Settings.canDrawOverlays(this) || GameVirtualDisplay.displayId < 0) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            startAsForeground()
        } catch (e: RuntimeException) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (padView == null && ConsoleSession.phase != ConsoleSession.Phase.MAPPING) {
            running = true
            buildPad()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        ConsoleSession.removeListener(sessionListener)
        if (hwRegistered) HardwareKeyMonitor.unregister("console")
        hwRegistered = false
        LobbyInput.releaseAll()
        removePad()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    // ------------------------------------------------------------------ Construcción

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    /**
     * Dos modos: LOBBY (navegar por el menú de la TV: no inyecta toques, publica teclas en LobbyInput, siempre con
     * la plantilla Xbox) y JUEGO (RUNNING: inyecta toques según el mapa de la app). Durante MAPPING no hay mando.
     */
    private fun onSessionChanged() {
        when (ConsoleSession.phase) {
            ConsoleSession.Phase.MAPPING -> removePad()
            ConsoleSession.Phase.LOBBY, ConsoleSession.Phase.RUNNING -> {
                val gameMode = ConsoleSession.phase == ConsoleSession.Phase.RUNNING
                if (padView == null || builtGameMode != gameMode) {
                    removePad()
                    buildPad()
                }
            }
            else -> {}
        }
    }

    private fun removePad() {
        padView?.let {
            params?.let { lp -> lastX = lp.x; lastY = lp.y }
            try {
                windowManager.removeViewImmediate(it)
            } catch (e: IllegalArgumentException) {
                // la ventana ya no estaba
            }
        }
        padView = null
    }

    private fun buildPad() {
        if (padView != null) return
        val gameMode = ConsoleSession.phase == ConsoleSession.Phase.RUNNING
        builtGameMode = gameMode
        val mapping = if (gameMode) {
            MappingStore.loadOrDefault(this, ConsoleSession.gamePackage.orEmpty(), PadLayout.XBOX)
        } else {
            GameMapping.defaultFor("", PadLayout.XBOX)
        }
        val screen = ScreenInfo.realSize(this)
        screenW = screen.x
        screenH = screen.y

        val padH = min(screenH * 0.72f, screenW / ASPECT).roundToInt()
        val padW = min((padH * ASPECT).roundToInt(), screenW)

        val themed = ContextThemeWrapper(this, R.style.Theme_GamepadHid)
        builtMapping = if (gameMode) mapping else null
        val appMode = gameMode && ConsoleSession.profile == ControlProfile.APP
        val layoutRes = if (appMode) R.layout.view_pad_app else when (mapping.layout) {
            PadLayout.XBOX -> R.layout.view_pad
            PadLayout.PSP -> R.layout.view_pad_psp
            PadLayout.DS -> R.layout.view_pad_ds
        }
        val view = LayoutInflater.from(themed).inflate(layoutRes, null)
        ThemeApplier.stylePad(view, AppTheme.current(this)) // paleta del usuario en panel y teclas
        if (appMode) bindAppMode(view) else bindControls(view, mapping, gameMode)
        bindBar(view, gameMode)

        val lp = WindowManager.LayoutParams(
            padW, padH,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = lastX ?: ((screenW - padW) / 2)
        lp.y = lastY ?: (screenH - padH - dp(8))
        windowManager.addView(view, lp)
        padView = view
        params = lp
        updateHwMonitor()
    }

    /**
     * Une cada control del mando con su destino. Todo se busca con findViewById<View?>: si la plantilla no tiene un
     * control, se omite. En modo JUEGO el destino es su punto en la pantalla virtual (lo no mapeado se oculta; el
     * mousepad desaparece del todo). En modo LOBBY es una tecla de LobbyInput y lo que no se usa queda atenuado.
     */
    private fun bindControls(view: View, mapping: GameMapping, gameMode: Boolean) {
        val displayId = GameVirtualDisplay.displayId
        val vw = GameVirtualDisplay.width.toFloat()
        val vh = GameVirtualDisplay.height.toFloat()

        val buttons = mapOf(
            R.id.padA to ControlIds.A, R.id.padB to ControlIds.B, R.id.padX to ControlIds.X, R.id.padY to ControlIds.Y,
            R.id.padL1 to ControlIds.L1, R.id.padR1 to ControlIds.R1, R.id.padL2 to ControlIds.L2, R.id.padR2 to ControlIds.R2,
            R.id.padStart to ControlIds.START, R.id.padSelect to ControlIds.SELECT,
            R.id.padDUp to ControlIds.D_UP, R.id.padDDown to ControlIds.D_DOWN,
            R.id.padDLeft to ControlIds.D_LEFT, R.id.padDRight to ControlIds.D_RIGHT
        )
        val lobbyKeys = mapOf(
            ControlIds.A to LobbyInput.Key.SELECT, ControlIds.B to LobbyInput.Key.BACK,
            ControlIds.D_UP to LobbyInput.Key.UP, ControlIds.D_DOWN to LobbyInput.Key.DOWN,
            ControlIds.D_LEFT to LobbyInput.Key.LEFT, ControlIds.D_RIGHT to LobbyInput.Key.RIGHT,
            ControlIds.L1 to LobbyInput.Key.PAGE_UP, ControlIds.R1 to LobbyInput.Key.PAGE_DOWN
        )
        for ((viewId, controlId) in buttons) {
            val target = view.findViewById<View?>(viewId) ?: continue
            ButtonSkins.applyTo(target, controlId) // imagen propia del botón, si la hay
            if (!gameMode) {
                val key = lobbyKeys[controlId]
                if (key != null) {
                    bindLobbyButton(target, key, controlId)
                } else {
                    target.alpha = 0.3f
                    target.isEnabled = false
                }
                continue
            }
            val spec = mapping.controls[controlId]
            if (spec == null) {
                target.visibility = View.INVISIBLE
                continue
            }
            bindButton(target, displayId, spec.x * vw, spec.y * vh, ControlIds.fingerId(controlId))
        }

        val sticks = mapOf(R.id.padLeftStick to ControlIds.STICK_L, R.id.padRightStick to ControlIds.STICK_R)
        for ((viewId, controlId) in sticks) {
            val stick = view.findViewById<JoystickView?>(viewId) ?: continue
            if (!gameMode) {
                if (controlId == ControlIds.STICK_L) {
                    stick.onEngage = { down -> if (!down) LobbyInput.releaseAll() }
                    stick.onMove = { x, y -> LobbyInput.stick(x, y) } // el stick izquierdo también hace de cruceta
                } else {
                    stick.visibility = View.INVISIBLE
                }
                continue
            }
            val spec = mapping.controls[controlId]
            if (spec == null) {
                stick.visibility = View.INVISIBLE
                continue
            }
            bindStick(stick, displayId, spec, vw, vh, ControlIds.fingerId(controlId))
        }

        val mouse = view.findViewById<View?>(R.id.padMouse)
        if (mouse != null) {
            val zone = view.findViewById<View?>(R.id.padMouseZone) ?: mouse
            val spec = mapping.controls[ControlIds.MOUSEPAD]
            if (!gameMode || spec == null) zone.visibility = View.GONE
            else {
                zone.visibility = View.VISIBLE
                bindMousepad(mouse, displayId, spec, vw, vh, ControlIds.fingerId(ControlIds.MOUSEPAD))
            }
        }
    }

    // ------------------------------------------------------------------ Botones físicos

    /** Solo se escucha getevent si algún botón físico tiene una acción (cuesta un proceso en el shell). */
    private fun updateHwMonitor() {
        val pkg = if (builtGameMode) ConsoleSession.gamePackage else null
        val needed = HwActions.effective(this, pkg).values.any { it != HwActions.NONE }
        if (needed && !hwRegistered) {
            HardwareKeyMonitor.register(this, "console") { key, down -> onHwKey(key, down) }
            hwRegistered = true
        } else if (!needed && hwRegistered) {
            HardwareKeyMonitor.unregister("console")
            hwRegistered = false
        }
    }

    private fun onHwKey(key: HwKey, down: Boolean) {
        val phase = ConsoleSession.phase
        if (phase != ConsoleSession.Phase.LOBBY && phase != ConsoleSession.Phase.RUNNING) return
        val inGame = phase == ConsoleSession.Phase.RUNNING
        val pkg = if (inGame) ConsoleSession.gamePackage else null
        val action = HwActions.effective(this, pkg)[key] ?: HwActions.NONE
        if (action == HwActions.NONE) return
        if (key == HwKey.POWER && down) HwKeyDispatcher.wakeAfterPower() // Encendido apaga la pantalla: se reenciende

        if (!inGame) { // Lobby
            if (action == HwActions.EXIT_CONSOLE) { if (down) exitConsole() } else HwKeyDispatcher.toLobby(action, down)
            return
        }
        val displayId = GameVirtualDisplay.displayId
        when (action) {
            HwActions.CLOSE_APP -> if (down) ConsoleService.goHome(this)
            HwActions.BACK -> if (down) HwKeyDispatcher.sendKey(displayId, KeyEvent.KEYCODE_BACK)
            HwActions.EXIT_CONSOLE -> if (down) exitConsole()
            else -> { // un botón del mando: toca su punto del mapa (en el perfil App no hay mapa)
                if (ConsoleSession.profile == ControlProfile.APP) return
                val spec = builtMapping?.controls?.get(action) ?: return
                val x = spec.x * GameVirtualDisplay.width
                val y = spec.y * GameVirtualDisplay.height
                val pointer = ControlIds.fingerId(action)
                if (down) TouchInjectorShizuku.holdDown(displayId, x, y, pointer) else TouchInjectorShizuku.release(displayId, pointer)
            }
        }
    }

    // ------------------------------------------------------------------ Perfil «Modo App»

    /** Trackpad (pantalla en miniatura), Atrás y deslizar arriba/abajo. No hay mapa: no se tocan "botones del juego". */
    private fun bindAppMode(view: View) {
        val displayId = GameVirtualDisplay.displayId
        val vw = GameVirtualDisplay.width.toFloat()
        val vh = GameVirtualDisplay.height.toFloat()
        view.findViewById<AspectFrameLayout?>(R.id.padTrackFrame)?.aspect = vw / vh
        view.findViewById<View?>(R.id.padTrack)?.let { bindTrackpad(it, displayId, vw, vh) }
        view.findViewById<View?>(R.id.padBack)?.let { b ->
            Fx.attachPress(b)
            b.setOnClickListener { HwKeyDispatcher.sendKey(displayId, KeyEvent.KEYCODE_BACK) }
        }
        // Dedo hacia arriba = siguiente vídeo (TikTok, Reels); hacia abajo = anterior
        view.findViewById<View?>(R.id.padSwipeUp)?.let { b ->
            Fx.attachPress(b)
            b.setOnClickListener { swipe(displayId, vw * 0.5f, vh * 0.78f, vw * 0.5f, vh * 0.22f) }
        }
        view.findViewById<View?>(R.id.padSwipeDown)?.let { b ->
            Fx.attachPress(b)
            b.setOnClickListener { swipe(displayId, vw * 0.5f, vh * 0.22f, vw * 0.5f, vh * 0.78f) }
        }
    }

    /** Cada punto del trackpad es el punto equivalente de la pantalla: tocar = tocar, arrastrar = deslizar. */
    @SuppressLint("ClickableViewAccessibility")
    private fun bindTrackpad(target: View, displayId: Int, vw: Float, vh: Float) {
        val pointer = ControlIds.fingerId(ControlIds.MOUSEPAD)
        target.setOnTouchListener { v, e ->
            val x = (e.x / v.width.coerceAtLeast(1)).coerceIn(0f, 1f) * vw
            val y = (e.y / v.height.coerceAtLeast(1)).coerceIn(0f, 1f) * vh
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    TouchInjectorShizuku.holdDown(displayId, x, y, pointer)
                }
                MotionEvent.ACTION_MOVE -> TouchInjectorShizuku.moveTo(displayId, x, y, pointer)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    TouchInjectorShizuku.release(displayId, pointer)
                }
            }
            true
        }
    }

    private fun swipe(displayId: Int, x1: Float, y1: Float, x2: Float, y2: Float) {
        val pointer = SWIPE_POINTER
        val steps = 10
        val total = 240L
        TouchInjectorShizuku.holdDown(displayId, x1, y1, pointer)
        for (i in 1..steps) {
            ui.postDelayed({
                TouchInjectorShizuku.moveTo(displayId, x1 + (x2 - x1) * i / steps, y1 + (y2 - y1) * i / steps, pointer)
            }, total * i / steps)
        }
        ui.postDelayed({ TouchInjectorShizuku.release(displayId, pointer) }, total + 30)
    }

    /** Botón del Lobby: no inyecta nada, publica una tecla (las direcciones se repiten mientras se mantienen). */
    @SuppressLint("ClickableViewAccessibility")
    private fun bindLobbyButton(target: View, key: LobbyInput.Key, controlId: String) {
        target.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    Fx.pressDown(v)
                    v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    SoundBank.play(controlId)
                    LobbyInput.press(key)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    Fx.pressUp(v)
                    LobbyInput.release(key)
                }
            }
            true
        }
    }

    /**
     * Mousepad: zona rectangular del juego (centro x,y; tamaño w,h en fracciones). El dedo virtual se mueve de
     * forma RELATIVA: arranca donde quedó la última vez (al principio, el centro), se desplaza en proporción al
     * movimiento del dedo sobre el mando y nunca sale de la zona. En la plantilla DS equivale a la pantalla táctil.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun bindMousepad(target: View, displayId: Int, spec: ControlPoint, vw: Float, vh: Float, pointerId: Int) {
        val rectW = spec.w * vw
        val rectH = spec.h * vh
        val left = spec.x * vw - rectW / 2f
        val top = spec.y * vh - rectH / 2f
        var curX = left + rectW / 2f
        var curY = top + rectH / 2f
        var lastX = 0f
        var lastY = 0f
        target.setOnTouchListener { v, e ->
            val sx = if (v.width > 0) rectW / v.width else 1f
            val sy = if (v.height > 0) rectH / v.height else 1f
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    lastX = e.x
                    lastY = e.y
                    TouchInjectorShizuku.holdDown(displayId, curX, curY, pointerId)
                }
                MotionEvent.ACTION_MOVE -> {
                    curX = (curX + (e.x - lastX) * sx).coerceIn(left, left + rectW)
                    curY = (curY + (e.y - lastY) * sy).coerceIn(top, top + rectH)
                    lastX = e.x
                    lastY = e.y
                    TouchInjectorShizuku.moveTo(displayId, curX, curY, pointerId)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    TouchInjectorShizuku.release(displayId, pointerId)
                }
            }
            true
        }
    }

    /** Botón "mantener": el dedo virtual se pone al tocar y se levanta al soltar. */
    @SuppressLint("ClickableViewAccessibility")
    private fun bindButton(target: View, displayId: Int, x: Float, y: Float, pointerId: Int) {
        target.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    Fx.pressDown(v)
                    v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    SoundBank.play(ControlIds.ALL.getOrNull(pointerId - 1)) // sonido propio del botón
                    TouchInjectorShizuku.holdDown(displayId, x, y, pointerId)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    Fx.pressUp(v)
                    TouchInjectorShizuku.release(displayId, pointerId)
                }
            }
            true
        }
    }

    /** Stick: holdDown en el centro del stick del juego, moveTo según el dedo y release al soltar. */
    private fun bindStick(stick: JoystickView, displayId: Int, spec: ControlPoint, vw: Float, vh: Float, pointerId: Int) {
        val cx = spec.x * vw
        val cy = spec.y * vh
        val travel = spec.size * min(vw, vh)
        stick.onEngage = { down ->
            if (down) TouchInjectorShizuku.holdDown(displayId, cx, cy, pointerId)
            else TouchInjectorShizuku.release(displayId, pointerId)
        }
        stick.onMove = { x, y -> TouchInjectorShizuku.moveTo(displayId, cx + x * travel, cy + y * travel, pointerId) }
    }

    // ------------------------------------------------------------------ Barra: arrastrar y cerrar

    @SuppressLint("ClickableViewAccessibility")
    private fun bindBar(view: View, gameMode: Boolean) {
        val handle = view.findViewById<View>(R.id.padHandle)
        var startRawX = 0f
        var startRawY = 0f
        var startX = 0
        var startY = 0
        handle.setOnTouchListener { v, e ->
            val lp = params ?: return@setOnTouchListener true
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startRawX = e.rawX
                    startRawY = e.rawY
                    startX = lp.x
                    startY = lp.y
                    Fx.pressDown(v)
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = (startX + (e.rawX - startRawX)).roundToInt().coerceIn(0, (screenW - lp.width).coerceAtLeast(0))
                    lp.y = (startY + (e.rawY - startRawY)).roundToInt().coerceIn(0, (screenH - lp.height).coerceAtLeast(0))
                    padView?.let {
                        try {
                            windowManager.updateViewLayout(it, lp)
                        } catch (ex: IllegalArgumentException) {
                            // ventana retirada
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> Fx.pressUp(v)
            }
            true
        }

        // ⌂ en juego: cierra la app y vuelve al Lobby. En el Lobby (✕): mantener pulsado sale del Modo Consola.
        val close = view.findViewById<TextView?>(R.id.padClose)
        close?.text = if (gameMode) "⌂" else "✕"
        close?.setOnClickListener {
            if (ConsoleSession.phase == ConsoleSession.Phase.RUNNING) {
                ConsoleService.goHome(this)
            } else {
                Toast.makeText(this, R.string.pad_hold_to_exit, Toast.LENGTH_SHORT).show()
            }
        }
        close?.setOnLongClickListener {
            exitConsole()
            true
        }
    }

    /** Cierra todo el Modo Consola y vuelve a la pantalla principal de GameCast. */
    private fun exitConsole() {
        ConsoleService.stop(this)
        try {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: RuntimeException) {
            // sin permiso para abrir actividades desde aquí: queda la notificación
        }
    }

    // ------------------------------------------------------------------ Notificación

    private fun startAsForeground() {
        val channel = NotificationChannel(
            CHANNEL_ID, getString(R.string.notif_pad_channel), NotificationManager.IMPORTANCE_LOW
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
            this, 0, Intent(this, ConsoleService::class.java).setAction(ConsoleService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_gamepad)
            .setContentTitle(getString(R.string.notif_pad_title))
            .setContentText(getString(R.string.notif_pad_text))
            .setContentIntent(open)
            .addAction(0, getString(R.string.notif_stop), stop)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
}
