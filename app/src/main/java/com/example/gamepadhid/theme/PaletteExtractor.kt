package com.example.gamepadhid.theme

import android.graphics.Bitmap
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette

/** Extrae el color dominante de una imagen con androidx.palette. */
object PaletteExtractor {

    /**
     * El color dominante; si es casi gris (poca saturación) se prefiere el más vivo, porque un acento gris no se nota.
     * Devuelve un color ARGB opaco.
     */
    fun accentFrom(bitmap: Bitmap): Int {
        val palette = Palette.from(bitmap).maximumColorCount(24).generate()
        val dominant = palette.getDominantColor(Preset.INDIGO.c1)
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(dominant, hsl)
        if (hsl[1] >= 0.2f && hsl[2] in 0.12f..0.9f) return dominant
        val vivid = palette.vibrantSwatch ?: palette.lightVibrantSwatch ?: palette.darkVibrantSwatch
        return vivid?.rgb ?: dominant
    }
}
