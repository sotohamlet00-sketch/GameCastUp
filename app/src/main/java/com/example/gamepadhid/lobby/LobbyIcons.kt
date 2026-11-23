package com.example.gamepadhid.lobby

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import androidx.core.graphics.drawable.toBitmap
import java.util.concurrent.Executors

/** Portadas: el icono real de cada app (applicationInfo.loadIcon(pm)) cargado en segundo plano y guardados en caché; mientras llegan, [get] devuelve null. */
class LobbyIcons(context: Context, private val onLoaded: () -> Unit) {

    private val appContext = context.applicationContext
    private val cache = LruCache<String, Bitmap>(96)
    private val pending = HashSet<String>()
    private val failed = HashSet<String>()
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun get(packageName: String): Bitmap? {
        cache.get(packageName)?.let { return it }
        if (packageName in failed || !pending.add(packageName)) return null
        executor.execute {
            val bitmap = try {
                appContext.packageManager.let { pm -> pm.getApplicationInfo(packageName, 0).loadIcon(pm) }.toBitmap(SIZE, SIZE)
            } catch (e: Exception) {
                null
            }
            main.post {
                pending.remove(packageName)
                if (bitmap != null) {
                    cache.put(packageName, bitmap)
                    onLoaded()
                } else {
                    failed.add(packageName)
                }
            }
        }
        return null
    }

    fun shutdown() {
        executor.shutdownNow()
    }

    private companion object {
        const val SIZE = 192
    }
}
