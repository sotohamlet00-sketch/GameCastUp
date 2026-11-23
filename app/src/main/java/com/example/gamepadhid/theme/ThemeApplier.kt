package com.example.gamepadhid.theme

import android.app.Activity
import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.content.res.ColorStateList
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.example.gamepadhid.R

/**
 * Aplica la paleta a las pantallas ya inflaldas. Los layouts marcan lo que se puede teñir con `android:tag`:
 * gc_primary (botón principal), gc_title (título), pad_round / pad_rect / pad_dpad (teclas del mando).
 * El icono real del launcher no puede cambiar al vuelo: la paleta vive en la app, el Lobby y el mando.
 */
object ThemeApplier {

    fun apply(activity: Activity, background: Boolean = true) {
        val t = AppTheme.current(activity)
        if (background) activity.window.setBackgroundDrawable(aurora(activity, t))
        tint(activity.window.decorView, t)
    }

    private fun dp(context: Context, v: Float) = v * context.resources.displayMetrics.density

    private fun aurora(context: Context, t: ThemeColors): Drawable {
        val w = context.resources.displayMetrics.widthPixels.toFloat()
        fun glow(cx: Float, cy: Float, radius: Float, color: Int) = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            setGradientCenter(cx, cy)
            gradientRadius = radius
            colors = intArrayOf(color, 0x00000000)
        }
        return LayerDrawable(
            arrayOf(
                ColorDrawable(t.bgBot),
                glow(0.1f, 0.05f, w * 0.8f, ColorUtils.setAlphaComponent(t.accent2, 0x4D)),
                glow(0.95f, 0.95f, w * 0.7f, ColorUtils.setAlphaComponent(t.accent, 0x3D))
            )
        )
    }

    fun tint(root: View, t: ThemeColors) {
        when (root.tag) {
            "gc_primary" -> root.background = primary(root.context, t)
            "gc_title" -> (root as? TextView)?.setTextColor(t.title)
        }
        if (root is ViewGroup) for (i in 0 until root.childCount) tint(root.getChildAt(i), t)
    }

    private fun primary(context: Context, t: ThemeColors): Drawable {
        val radius = dp(context, 16f)
        val normal = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(t.accent, t.accent2)).apply { cornerRadius = radius }
        val disabled = GradientDrawable().apply { setColor(0xFF222A39.toInt()); cornerRadius = radius }
        val states = StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), disabled)
            addState(intArrayOf(), normal)
        }
        return RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), states, null)
    }

    /** Panel y teclas del mando flotante (y de la pantalla del mando HID). Se llama ANTES de enlazar y de poner skins. */
    fun stylePad(root: View, t: ThemeColors) {
        val context = root.context
        root.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(t.panelTop, t.panelBottom)).apply {
            cornerRadius = dp(context, 24f)
            setStroke(dp(context, 1f).toInt().coerceAtLeast(1), t.panelStroke)
        }
        walkKeys(root, t)
    }

    private fun walkKeys(v: View, t: ThemeColors) {
        val shape = when (v.tag) {
            "pad_round" -> GradientDrawable.OVAL to 0f
            "pad_rect" -> GradientDrawable.RECTANGLE to 14f
            "pad_dpad" -> GradientDrawable.RECTANGLE to 9f
            else -> null
        }
        if (shape != null) {
            v.background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), key(v.context, shape, t.pressedTop, t.pressedBottom, 0x80FFFFFF.toInt()))
                addState(intArrayOf(), key(v.context, shape, t.keyTop, t.keyBottom, 0x47FFFFFF))
            }
            (v as? TextView)?.setTextColor(t.text)
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) walkKeys(v.getChildAt(i), t)
    }

    private fun key(context: Context, shape: Pair<Int, Float>, top: Int, bottom: Int, stroke: Int) =
        GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(top, bottom)).apply {
            this.shape = shape.first
            if (shape.first == GradientDrawable.RECTANGLE) cornerRadius = dp(context, shape.second)
            setStroke(dp(context, 1f).toInt().coerceAtLeast(1), stroke)
        }
}
