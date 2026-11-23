package com.example.gamepadhid.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.view.View
import android.widget.TextView
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import java.io.File

/** Imagen propia como fondo de cada botón del mando. Se guarda recortada y reducida en filesDir/skins/<botón>.png. */
object ButtonSkins {

    private const val SIZE = 192
    private val cache = HashMap<String, Bitmap>()

    private fun file(context: Context, controlId: String) =
        File(File(context.filesDir, "skins").apply { mkdirs() }, "$controlId.png")

    fun has(context: Context, controlId: String) = file(context, controlId).exists()

    /** Lee la imagen elegida, la recorta a cuadrado y la guarda. Devuelve el bitmap o null si no se pudo leer. */
    fun save(context: Context, controlId: String, uri: Uri): Bitmap? = try {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= SIZE * 2 && bounds.outHeight / (sample * 2) >= SIZE * 2) sample *= 2
        val raw = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val side = minOf(raw.width, raw.height)
        val square = Bitmap.createBitmap(raw, (raw.width - side) / 2, (raw.height - side) / 2, side, side)
        val bitmap = Bitmap.createScaledBitmap(square, SIZE, SIZE, true)
        file(context, controlId).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        cache[controlId] = bitmap
        bitmap
    } catch (e: Exception) {
        null
    }

    fun remove(context: Context, controlId: String) {
        file(context, controlId).delete()
        cache.remove(controlId)
    }

    fun load(context: Context, controlId: String): Bitmap? {
        cache[controlId]?.let { return it }
        val f = file(context, controlId)
        if (!f.exists()) return null
        return BitmapFactory.decodeFile(f.path)?.also { cache[controlId] = it }
    }

    /** Pone la imagen de fondo del botón (redonda o con esquinas, según el tag de la tecla) y deja el texto legible. */
    fun applyTo(view: View, controlId: String) {
        val bitmap = load(view.context, controlId) ?: return
        val density = view.resources.displayMetrics.density
        val image = RoundedBitmapDrawableFactory.create(view.resources, bitmap)
        if (view.tag == "pad_round") image.isCircular = true else image.cornerRadius = 12f * density
        val ring = GradientDrawable().apply {
            if (view.tag == "pad_round") shape = GradientDrawable.OVAL else cornerRadius = 12f * density
            setStroke((1.5f * density).toInt().coerceAtLeast(1), 0xB0FFFFFF.toInt())
        }
        view.background = LayerDrawable(arrayOf(image, ring))
        (view as? TextView)?.apply {
            setTextColor(0xFFFFFFFF.toInt())
            setShadowLayer(5f, 0f, 1f, 0xCC000000.toInt())
        }
    }
}
