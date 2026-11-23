package com.example.gamepadhid.theme

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.net.Uri
import android.provider.OpenableColumns
import com.example.gamepadhid.R
import com.example.gamepadhid.mapping.ControlIds
import java.io.File

/**
 * Sonido propio para cada botón con SoundPool (latencia mínima). Los archivos (.mp3 o .ogg) se copian a
 * filesDir/sounds/<botón>.snd. Sin sonido asignado no suena nada.
 */
object SoundBank {

    private const val MAX_BYTES = 1_000_000L // SoundPool carga el audio entero en memoria: mejor clips cortos

    private var pool: SoundPool? = null
    private val ids = HashMap<String, Int>()
    private val ready = HashSet<Int>()

    private fun dir(context: Context) = File(context.filesDir, "sounds").apply { mkdirs() }

    fun init(context: Context) {
        if (pool != null) return
        val p = SoundPool.Builder()
            .setMaxStreams(8)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .build()
        p.setOnLoadCompleteListener { _, soundId, status -> if (status == 0) ready.add(soundId) }
        pool = p
        reload(context)
    }

    fun reload(context: Context) {
        val p = pool ?: return
        for (id in ids.values) p.unload(id)
        ids.clear()
        ready.clear()
        for (control in ControlIds.ALL) {
            val f = File(dir(context), "$control.snd")
            if (f.exists()) ids[control] = p.load(f.path, 1)
        }
    }

    fun has(context: Context, controlId: String) = File(dir(context), "$controlId.snd").exists()

    fun play(controlId: String?) {
        val id = ids[controlId ?: return] ?: return
        if (id in ready) pool?.play(id, 1f, 1f, 1, 0, 1f)
    }

    /** Copia el audio elegido. Devuelve null si fue bien o el texto del error. */
    fun save(context: Context, controlId: String, uri: Uri): String? {
        val resolver = context.contentResolver
        var name = ""
        var size = -1L
        resolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = c.getString(it).orEmpty() }
                c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { size = c.getLong(it) }
            }
        }
        val type = resolver.getType(uri).orEmpty()
        val lower = name.lowercase()
        val ok = lower.endsWith(".mp3") || lower.endsWith(".ogg") || lower.endsWith(".oga") ||
            type == "audio/mpeg" || type == "audio/mp3" || type == "audio/ogg" || type == "application/ogg"
        if (!ok) return context.getString(R.string.cust_sound_bad)
        if (size > MAX_BYTES) return context.getString(R.string.cust_sound_big)
        return try {
            val target = File(dir(context), "$controlId.snd")
            resolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } }
                ?: return context.getString(R.string.cust_sound_bad)
            if (target.length() > MAX_BYTES) {
                target.delete()
                return context.getString(R.string.cust_sound_big)
            }
            init(context)
            reload(context)
            null
        } catch (e: Exception) {
            context.getString(R.string.cust_sound_bad)
        }
    }

    fun remove(context: Context, controlId: String) {
        File(dir(context), "$controlId.snd").delete()
        reload(context)
    }
}
