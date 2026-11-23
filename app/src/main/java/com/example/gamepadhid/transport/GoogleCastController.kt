package com.example.gamepadhid.transport

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.example.gamepadhid.shizuku.DiagLog
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability

/**
 * Google Cast con el SDK oficial y el receptor por defecto. Descubre con MediaRouter y reproduce la lista HLS
 * del teléfono (STREAM_TYPE_LIVE). Todo se llama desde el hilo principal.
 * El SDK NO puede duplicar la pantalla en vivo: por eso se envía un flujo HLS (latencia ≈6–10 s).
 */
class GoogleCastController(context: Context) {

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val router: MediaRouter by lazy { MediaRouter.getInstance(appContext) }
    private val selector = MediaRouteSelector.Builder()
        .addControlCategory(CastMediaControlIntent.categoryForCast(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID))
        .build()

    private var onDevices: ((List<TvDevice>) -> Unit)? = null
    private var discovering = false
    private var sessionListener: SessionManagerListener<CastSession>? = null

    private val callback = object : MediaRouter.Callback() {
        override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = publish()
        override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) = publish()
        override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = publish()
    }

    /** false si el teléfono no tiene Google Play Services (el SDK de Cast los exige). */
    fun isAvailable(): Boolean =
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(appContext) == ConnectionResult.SUCCESS

    /** Empieza a buscar. Devuelve false si Cast no está disponible. */
    fun startDiscovery(listener: (List<TvDevice>) -> Unit): Boolean {
        if (!isAvailable()) return false
        return try {
            CastContext.getSharedInstance(appContext) // inicializa el SDK (necesario para abrir sesiones)
            onDevices = listener
            if (!discovering) {
                router.addCallback(selector, callback, MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY)
                discovering = true
            }
            publish()
            true
        } catch (e: Exception) {
            DiagLog.add("Cast no disponible: $e")
            false
        }
    }

    fun stopDiscovery() {
        if (discovering) {
            router.removeCallback(callback)
            discovering = false
        }
        onDevices = null
    }

    private fun publish() {
        val list = router.routes
            .filter { !it.isDefault && it.matchesSelector(selector) }
            .map { TvDevice(TransportKind.CAST, it.id, it.name, it.description ?: "Google Cast") }
        onDevices?.invoke(list)
    }

    /** Abre la sesión en la TV y le manda [url] (lista HLS). [done]: null si fue bien, o el motivo del fallo. */
    fun play(device: TvDevice, url: String, done: (String?) -> Unit) {
        if (!isAvailable()) return done("Google Play Services no está disponible.")
        val cc = try {
            CastContext.getSharedInstance(appContext)
        } catch (e: Exception) {
            return done("No se pudo iniciar Google Cast: ${e.message}")
        }
        val sm = cc.sessionManager
        var finished = false
        fun finish(error: String?) {
            if (finished) return
            finished = true
            sessionListener?.let { sm.removeSessionManagerListener(it, CastSession::class.java) }
            sessionListener = null
            done(error)
        }

        fun load(session: CastSession) {
            val client = session.remoteMediaClient ?: return finish("La TV no ofrece control de medios.")
            val info = MediaInfo.Builder(url)
                .setStreamType(MediaInfo.STREAM_TYPE_LIVE)
                .setContentType("application/x-mpegURL")
                .build()
            val request = MediaLoadRequestData.Builder().setMediaInfo(info).setAutoplay(true).build()
            client.load(request).setResultCallback { result ->
                if (result.status.isSuccess) finish(null)
                else finish("La TV rechazó el vídeo (${result.status.statusMessage ?: result.status.statusCode}).")
            }
        }

        val listener = object : SessionManagerListener<CastSession> {
            override fun onSessionStarting(s: CastSession) {}
            override fun onSessionStarted(s: CastSession, sessionId: String) = load(s)
            override fun onSessionStartFailed(s: CastSession, error: Int) = finish("No se pudo conectar con la TV (código $error).")
            override fun onSessionEnding(s: CastSession) {}
            override fun onSessionEnded(s: CastSession, error: Int) {}
            override fun onSessionResuming(s: CastSession, sessionId: String) {}
            override fun onSessionResumed(s: CastSession, wasSuspended: Boolean) = load(s)
            override fun onSessionResumeFailed(s: CastSession, error: Int) = finish("No se pudo reanudar la sesión (código $error).")
            override fun onSessionSuspended(s: CastSession, reason: Int) {}
        }
        sessionListener = listener
        sm.addSessionManagerListener(listener, CastSession::class.java)

        val route = router.routes.firstOrNull { it.id == device.id } ?: router.routes.firstOrNull { it.name == device.name }
        if (route == null) {
            finish("La TV no está visible ahora. Vuelve a buscarla.")
            return
        }
        // Con el SDK inicializado, seleccionar la ruta abre la sesión de Cast
        val current = sm.currentCastSession
        if (current != null && current.isConnected && router.selectedRoute.id == route.id) load(current)
        else router.selectRoute(route)
        main.postDelayed({ finish("La TV no respondió a tiempo.") }, 20_000)
    }

    fun stop() {
        try {
            CastContext.getSharedInstance(appContext).sessionManager.endCurrentSession(true)
        } catch (e: Exception) {
            // sin sesión o sin SDK
        }
    }
}
