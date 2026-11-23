package com.example.gamepadhid.theme

import android.content.Context
import androidx.core.graphics.ColorUtils
import com.example.gamepadhid.Prefs
import com.example.gamepadhid.R

/** Todos los colores derivados de un acento: Lobby, mando flotante e interfaz de la app. */
class ThemeColors(val accent: Int, val accent2: Int) {
    private val hsl = FloatArray(3).also { ColorUtils.colorToHSL(accent, it) }
    private val sat = hsl[1].coerceIn(0.25f, 0.6f)

    private fun c(s: Float, l: Float, a: Int = 255, hue: Float = hsl[0]): Int =
        ColorUtils.setAlphaComponent(ColorUtils.HSLToColor(floatArrayOf(hue, s.coerceIn(0f, 1f), l.coerceIn(0f, 1f))), a)

    // Lobby: el mismo degradado violeta de siempre, pero con el tono del acento
    val bgTop = c(sat * 0.9f, 0.30f)
    val bgMid = c(sat * 0.9f, 0.22f)
    val bgBot = 0xFF0D1017.toInt()
    val glow = c(0.9f, 0.78f, 0x66)
    val ribbon = c(0.8f, 0.86f)

    // Textos y brillos
    val text = c(0.55f, 0.96f)
    val title = c(0.95f, 0.84f)
    val sub = c(0.6f, 0.88f)

    // Mando flotante
    val keyTop = c(sat * 0.6f, 0.30f, 0x99)
    val keyBottom = c(sat * 0.6f, 0.20f, 0x80)
    val pressedTop = c(0.9f, 0.72f, 0xE6)
    val pressedBottom = ColorUtils.setAlphaComponent(accent, 0xE6)
    val panelTop = c(sat * 0.6f, 0.18f, 0xA6)
    val panelBottom = c(sat * 0.6f, 0.10f, 0x73)
    val panelStroke = ColorUtils.setAlphaComponent(accent, 0x66)
}

enum class Preset(val nameRes: Int, val c1: Int, val c2: Int) {
    INDIGO(R.string.preset_indigo, 0xFF6C7CFF.toInt(), 0xFF9A6BFF.toInt()),
    MINT(R.string.preset_mint, 0xFF2DD4BF.toInt(), 0xFF6C7CFF.toInt()),
    CORAL(R.string.preset_coral, 0xFFFF5C7A.toInt(), 0xFFFF8A66.toInt()),
    VIOLET(R.string.preset_violet, 0xFF9A6BFF.toInt(), 0xFFFF5CA8.toInt()),
    AMBER(R.string.preset_amber, 0xFFFFB02E.toInt(), 0xFFFF7A3D.toInt()),
    SKY(R.string.preset_sky, 0xFF38BDF8.toInt(), 0xFF6C7CFF.toInt())
}

object AppTheme {
    private var cached: ThemeColors? = null

    fun current(context: Context): ThemeColors {
        cached?.let { return it }
        val saved = Prefs.themeColors(context)
        val t = if (saved != null) ThemeColors(saved.first, saved.second) else ThemeColors(Preset.INDIGO.c1, Preset.INDIGO.c2)
        cached = t
        return t
    }

    fun setPreset(context: Context, preset: Preset) {
        Prefs.setThemeColors(context, preset.c1, preset.c2, "preset:${preset.name}")
        cached = null
    }

    /** Acento sacado de una imagen: se ajusta para que se lea sobre fondo oscuro y el segundo color gira el tono. */
    fun setFromImage(context: Context, rawAccent: Int) {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(rawAccent, hsl)
        hsl[1] = hsl[1].coerceAtLeast(0.45f)
        hsl[2] = hsl[2].coerceIn(0.45f, 0.68f)
        val a1 = ColorUtils.HSLToColor(hsl)
        val second = floatArrayOf((hsl[0] + 28f) % 360f, hsl[1], (hsl[2] + 0.04f).coerceAtMost(0.7f))
        Prefs.setThemeColors(context, a1, ColorUtils.HSLToColor(second), "image")
        cached = null
    }

    fun reset(context: Context) {
        Prefs.clearTheme(context)
        cached = null
    }

    /** Texto para la pantalla de personalización: de dónde salen los colores. */
    fun sourceLabel(context: Context): String {
        val src = Prefs.themeSource(context)
        return when {
            src == "image" -> context.getString(R.string.cust_source_image)
            src != null && src.startsWith("preset:") ->
                Preset.values().firstOrNull { it.name == src.removePrefix("preset:") }
                    ?.let { context.getString(R.string.cust_source_preset, context.getString(it.nameRes)) }
                    ?: context.getString(R.string.cust_source_default)
            else -> context.getString(R.string.cust_source_default)
        }
    }
}
