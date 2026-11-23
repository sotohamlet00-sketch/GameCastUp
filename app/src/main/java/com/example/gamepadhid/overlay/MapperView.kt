package com.example.gamepadhid.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.example.gamepadhid.R
import com.example.gamepadhid.mapping.ControlIds
import com.example.gamepadhid.mapping.ControlPoint
import com.example.gamepadhid.mapping.GameMapping
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * Capa transparente sobre la vista previa de la pantalla virtual.
 *  - PLACE: un marcador por control (círculo, o rectángulo para el MOUSEPAD) que se arrastra al botón real.
 *  - PLAY: no dibuja y reenvía los toques al juego.
 * Posiciones y tamaños son fracciones de esta vista (= pantalla virtual).
 */
class MapperView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class Mode { PLACE, PLAY }

    var mapping: GameMapping? = null
        set(value) { field = value; invalidate() }
    var mode: Mode = Mode.PLACE
        set(value) { field = value; invalidate() }
    var selectedId: String? = null
        set(value) { field = value; invalidate() }

    var onSelectionChanged: ((String?) -> Unit)? = null
    var onEdited: (() -> Unit)? = null
    var onPlayTouch: ((action: Int, fx: Float, fy: Float) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(2f) }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textSize = dp(11f)
    }

    private var dragging: String? = null
    private var grabDx = 0f
    private var grabDy = 0f

    private fun isPad(id: String) = ControlIds.kind(id) == ControlIds.Kind.MOUSEPAD
    private fun radiusOf(p: ControlPoint) = max(p.size * height, dp(14f))

    private fun contains(id: String, p: ControlPoint, x: Float, y: Float): Boolean {
        val cx = p.x * width
        val cy = p.y * height
        return if (isPad(id)) abs(x - cx) <= p.w * width / 2 && abs(y - cy) <= p.h * height / 2
        else hypot(x - cx, y - cy) <= max(radiusOf(p), dp(24f))
    }

    override fun onDraw(canvas: Canvas) {
        if (mode == Mode.PLAY) return
        val m = mapping ?: return
        val accent = ContextCompat.getColor(context, R.color.pad_accent2)
        for ((id, p) in m.controls) {
            val cx = p.x * width
            val cy = p.y * height
            fill.color = ColorUtils.setAlphaComponent(accent, 90)
            stroke.color = if (id == selectedId) 0xFFFFFFFF.toInt() else accent
            if (isPad(id)) { // zona rectangular: el dedo se mueve de forma relativa dentro
                val r = RectF(cx - p.w * width / 2, cy - p.h * height / 2, cx + p.w * width / 2, cy + p.h * height / 2)
                canvas.drawRoundRect(r, dp(10f), dp(10f), fill)
                canvas.drawRoundRect(r, dp(10f), dp(10f), stroke)
                canvas.drawText(ControlIds.label(id), cx, cy + dp(4f), text)
            } else {
                val r = radiusOf(p)
                canvas.drawCircle(cx, cy, r, fill)
                canvas.drawCircle(cx, cy, r, stroke)
                canvas.drawLine(cx - dp(6f), cy, cx + dp(6f), cy, stroke)
                canvas.drawLine(cx, cy - dp(6f), cx, cy + dp(6f), stroke)
                canvas.drawText(ControlIds.label(id), cx, cy - r - dp(4f), text)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (width == 0 || height == 0) return false
        if (mode == Mode.PLAY) {
            val fx = (event.x / width).coerceIn(0f, 1f)
            val fy = (event.y / height).coerceIn(0f, 1f)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP ->
                    onPlayTouch?.invoke(event.actionMasked, fx, fy)
                MotionEvent.ACTION_CANCEL -> onPlayTouch?.invoke(MotionEvent.ACTION_UP, fx, fy)
            }
            return true
        }
        val m = mapping ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val hit = m.controls.entries.reversed().firstOrNull { contains(it.key, it.value, event.x, event.y) }
                dragging = hit?.key
                if (hit != null) {
                    grabDx = hit.value.x * width - event.x
                    grabDy = hit.value.y * height - event.y
                }
                selectedId = hit?.key
                onSelectionChanged?.invoke(hit?.key)
            }
            MotionEvent.ACTION_MOVE -> dragging?.let { id ->
                m.controls[id]?.let { p ->
                    p.x = ((event.x + grabDx) / width).coerceIn(0f, 1f)
                    p.y = ((event.y + grabDy) / height).coerceIn(0f, 1f)
                    onEdited?.invoke()
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = null
        }
        return true
    }
}
