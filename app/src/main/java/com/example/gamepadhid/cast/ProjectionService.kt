package com.example.gamepadhid.cast

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Display
import android.view.Surface
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.gamepadhid.MainActivity
import com.example.gamepadhid.R
import com.example.gamepadhid.stream.StreamSession
import com.example.gamepadhid.transport.TransportKind
import com.example.gamepadhid.transport.TvDevice
import com.example.gamepadhid.transport.TvPlayer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * SESIÓN DE PROYECCIÓN (MediaProjection).
 *
 * Flujo:
 *  1. La Activity obtiene el consentimiento del usuario (createScreenCaptureIntent).
 *  2. Arranca este servicio en primer plano con tipo "mediaProjection".
 *  3. El servicio crea la ventana sobre la TV y, cuando su Surface existe, crea un
 *     VirtualDisplay que copia la pantalla del teléfono directamente en esa Surface.
 *
 * Cambios de Android 14/15 aplicados aquí:
 *  - Android 14: startForeground con tipo MEDIA_PROJECTION ANTES de getMediaProjection;
 *    registerCallback ANTES de createVirtualDisplay; el permiso de captura es de un solo uso.
 *  - Android 14: se pide captura de pantalla completa (MediaProjectionConfig) para que el
 *    usuario no pueda elegir "una sola app" y la TV se quede en negro al cambiar de app.
 *  - Android 15: el sistema muestra un chip en la barra de estado y detiene la captura al
 *    bloquear la pantalla; onStop() lo gestiona.
 */
class ProjectionService : Service(), TvOutputWindow.Listener {

    enum class SessionState { IDLE, STARTING, STREAMING, ERROR }

    companion object {
        const val ACTION_START = "com.example.gamepadhid.action.START_PROJECTION"
        const val ACTION_STOP = "com.example.gamepadhid.action.STOP_PROJECTION"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_DISPLAY_ID = "display_id"
        const val EXTRA_TV_KIND = "tv_kind"
        const val EXTRA_TV_ID = "tv_id"
        const val EXTRA_TV_NAME = "tv_name"

        private const val CHANNEL_ID = "projection"
        private const val NOTIFICATION_ID = 42
        private const val MAX_LONG_EDGE = 1920 // 1080p: buen equilibrio calidad/latencia
        private const val STREAM_LONG_EDGE = 1280 // por la red (Cast/DLNA) 720p: menos ancho de banda y latencia

        @Volatile
        var state: SessionState = SessionState.IDLE
            private set

        @Volatile
        var message: String? = null
            private set

        private val listeners = java.util.concurrent.CopyOnWriteArraySet<(SessionState, String?) -> Unit>()

        /** Las pantallas se suscriben aquí; se invoca en el hilo principal. */
        fun addListener(l: (SessionState, String?) -> Unit) { listeners.add(l) }

        fun removeListener(l: (SessionState, String?) -> Unit) { listeners.remove(l) }

        private val mainHandler = Handler(Looper.getMainLooper())

        private fun publish(newState: SessionState, msg: String?) {
            state = newState
            message = msg
            mainHandler.post { for (l in listeners) l(newState, msg) }
        }

        /** [device] != null y no Miracast: el vídeo va a la TV por la red (Cast/DLNA) en vez de a una pantalla externa. */
        fun start(context: Context, resultCode: Int, data: Intent, displayId: Int, device: TvDevice? = null) {
            val intent = Intent(context, ProjectionService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
                .putExtra(EXTRA_DISPLAY_ID, displayId)
            if (device != null && device.kind != TransportKind.MIRACAST) {
                intent.putExtra(EXTRA_TV_KIND, device.kind.name)
                    .putExtra(EXTRA_TV_ID, device.id)
                    .putExtra(EXTRA_TV_NAME, device.name)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ProjectionService::class.java))
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var displayManager: DisplayManager

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var outputWindow: TvOutputWindow? = null
    private var targetDisplayId = -1
    private var targetName = ""
    private var streamDevice: TvDevice? = null
    private var maxEdge = MAX_LONG_EDGE
    private var capW = 0
    private var capH = 0
    private var capDpi = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START) startSession(intent) else stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        teardown()
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (state == SessionState.STARTING || state == SessionState.STREAMING) {
            publish(SessionState.IDLE, null)
        }
        super.onDestroy()
    }

    // ------------------------------------------------------------------ Inicio

    private fun startSession(intent: Intent) {
        if (projection != null) return // ya hay una sesión
        publish(SessionState.STARTING, null)

        // Android 14+: el servicio debe estar en primer plano ANTES de getMediaProjection.
        startAsForeground()

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        targetDisplayId = intent.getIntExtra(EXTRA_DISPLAY_ID, -1)

        val kind = TransportKind.from(intent.getStringExtra(EXTRA_TV_KIND))
        val device = if (kind == TransportKind.MIRACAST) null else TvDevice(
            kind, intent.getStringExtra(EXTRA_TV_ID).orEmpty(), intent.getStringExtra(EXTRA_TV_NAME).orEmpty()
        )
        streamDevice = device
        maxEdge = if (device != null) STREAM_LONG_EDGE else MAX_LONG_EDGE

        if (resultCode != Activity.RESULT_OK || data == null) return fail(R.string.err_no_consent)
        var target: Display? = null
        if (device == null) {
            target = displayManager.getDisplay(targetDisplayId) ?: return fail(R.string.err_display_gone)
            if (!Settings.canDrawOverlays(this)) return fail(R.string.err_no_overlay)
            targetName = target.name
        } else {
            targetName = device.name
        }

        try {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val proj = manager.getMediaProjection(resultCode, data)
            // Android 14+: obligatorio registrar el callback antes de crear el VirtualDisplay
            proj.registerCallback(projectionCallback, handler)
            projection = proj

            computeCaptureSize()
            if (device != null) { // Cast / DLNA: el vídeo se codifica y se sirve por la red local
                startStream(proj, device)
                return
            }
            displayManager.registerDisplayListener(displayListener, handler)

            // Cuando el SurfaceView tenga Surface, onSurfaceReady() crea el VirtualDisplay
            val window = TvOutputWindow(this, target!!, this)
            outputWindow = window
            window.show(capW, capH)
        } catch (e: SecurityException) {
            fail(R.string.err_security)
        } catch (e: RuntimeException) {
            fail(R.string.err_generic)
        }
    }

    // ------------------------------------------------------------------ Cast / DLNA

    private val streamListener: () -> Unit = {
        if (StreamSession.state == StreamSession.State.ERROR) failText(StreamSession.message ?: getString(R.string.err_generic))
    }

    private val playerListener: () -> Unit = {
        when (TvPlayer.state) {
            TvPlayer.State.PLAYING -> publish(SessionState.STREAMING, TvPlayer.message)
            TvPlayer.State.ERROR -> failText(TvPlayer.message ?: getString(R.string.err_generic))
            else -> {}
        }
    }

    /** MediaProjection → Surface del codificador (HLS/TS en el teléfono) → la TV lo reproduce. */
    private fun startStream(proj: MediaProjection, device: TvDevice) {
        val surface = StreamSession.start(this, capW, capH, getString(R.string.tvc_stream_no_network))
            ?: return failText(StreamSession.message ?: getString(R.string.err_generic))
        StreamSession.addListener(streamListener)
        TvPlayer.addListener(playerListener)
        virtualDisplay = proj.createVirtualDisplay(
            "GameCast", capW, capH, capDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null
        )
        publish(SessionState.STREAMING, getString(R.string.status_streaming_net, device.name))
        TvPlayer.start(this, device)
    }

    // ------------------------------------------------------------------ Surface de la TV

    override fun onSurfaceReady(surface: Surface) {
        val proj = projection ?: return
        val existing = virtualDisplay
        if (existing != null) { // la Surface se recreó: reutilizamos el VirtualDisplay
            existing.surface = surface
            return
        }
        try {
            // AUTO_MIRROR: copia el contenido de la pantalla principal
            virtualDisplay = proj.createVirtualDisplay(
                "GameCast", capW, capH, capDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface, null, null
            )
            publish(SessionState.STREAMING, getString(R.string.status_streaming, targetName))
        } catch (e: RuntimeException) {
            fail(R.string.err_generic)
        }
    }

    override fun onSurfaceLost() {
        virtualDisplay?.surface = null
    }

    // ------------------------------------------------------------------ Eventos del sistema

    private val projectionCallback = object : MediaProjection.Callback() {
        /** El usuario o el sistema (bloqueo de pantalla, chip de Android 15) detuvo la captura. */
        override fun onStop() {
            publish(SessionState.IDLE, getString(R.string.status_stopped_system))
            stopSelf()
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}

        override fun onDisplayRemoved(displayId: Int) {
            if (displayId == targetDisplayId) {
                publish(SessionState.IDLE, getString(R.string.status_tv_lost))
                stopSelf()
            }
        }

        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) applyCaptureSizeChange()
        }
    }

    // ------------------------------------------------------------------ Tamaño de captura

    @Suppress("DEPRECATION")
    private fun computeCaptureSize() {
        val metrics = DisplayMetrics()
        displayManager.getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(metrics)
        val scale = min(1f, maxEdge / max(metrics.widthPixels, metrics.heightPixels).toFloat())
        // El codificador de la TV prefiere dimensiones pares
        capW = (metrics.widthPixels * scale).roundToInt().and(1.inv()).coerceAtLeast(2)
        capH = (metrics.heightPixels * scale).roundToInt().and(1.inv()).coerceAtLeast(2)
        capDpi = (metrics.densityDpi * scale).roundToInt().coerceAtLeast(1)
    }

    /** El teléfono giró (vertical/horizontal): adaptamos captura y ventana. */
    private fun applyCaptureSizeChange() {
        val oldW = capW
        val oldH = capH
        computeCaptureSize()
        if (oldW == capW && oldH == capH) return
        outputWindow?.resize(capW, capH)
        virtualDisplay?.resize(capW, capH, capDpi)
    }

    // ------------------------------------------------------------------ Utilidades

    private fun fail(messageRes: Int) = failText(getString(messageRes))

    private fun failText(text: String) {
        teardown()
        publish(SessionState.ERROR, text)
        stopSelf()
    }

    private fun teardown() {
        if (streamDevice != null) {
            StreamSession.removeListener(streamListener)
            TvPlayer.removeListener(playerListener)
            TvPlayer.stop()
            StreamSession.stop()
            streamDevice = null
        }
        displayManager.unregisterDisplayListener(displayListener)
        virtualDisplay?.release()
        virtualDisplay = null
        outputWindow?.hide()
        outputWindow = null
        projection?.let {
            it.unregisterCallback(projectionCallback)
            it.stop()
        }
        projection = null
    }

    private fun startAsForeground() {
        val channel = NotificationChannel(
            CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW
        )
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
    }

    private fun buildNotification(): Notification {
        val stop = PendingIntent.getService(
            this, 0,
            Intent(this, ProjectionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_cast)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setContentIntent(open)
            .addAction(0, getString(R.string.notif_stop), stop)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
}
