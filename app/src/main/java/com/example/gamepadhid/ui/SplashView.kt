package com.example.gamepadhid.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.animation.doOnEnd
import androidx.core.graphics.ColorUtils
import com.example.gamepadhid.theme.ThemeColors
import kotlin.math.min

/**
 * Animación de carga: un teléfono aparece, gira a horizontal y se TRANSFORMA en un gamepad (crecen las empuñaduras,
 * aparecen cruceta, botones y sticks), late con un pulso y da paso a la pantalla siguiente (la app o el Lobby).
 * Tocarla lanza ondas de pulso en cualquier momento. Todo en Canvas: sin librerías ni recursos.
 */
class SplashView(context: Context, private val theme: ThemeColors) : View(context) {

    var onFinished: (() -> Unit)? = null

    private class Ring(val x: Float, val y: Float, val t0: Long)

    private var p = 0f
    private var anim: ValueAnimator? = null
    private val rings = ArrayList<Ring>()

    private val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val screen = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val detail = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val r = RectF()

    fun start(durationMs: Long = 3000) {
        anim?.cancel()
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            interpolator = LinearInterpolator()
            addUpdateListener { p = it.animatedValue as Float; invalidate() }
            doOnEnd { onFinished?.invoke() }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        anim?.removeAllListeners()
        anim?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            rings.add(Ring(e.x, e.y, SystemClock.uptimeMillis()))
            invalidate()
        }
        return true
    }

    private fun seg(a: Float, b: Float): Float {
        val t = ((p - a) / (b - a)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f) return
        val u = min(w, h) / 100f
        val cx = w / 2f
        val cy = h * 0.44f

        canvas.drawColor(theme.bgBot)
        glow.shader = RadialGradient(cx, cy, 80f * u, ColorUtils.setAlphaComponent(theme.accent, 0x70), 0x00000000, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, glow)

        val intro = seg(0f, 0.22f)
        val rot = seg(0.28f, 0.52f)
        val morph = seg(0.50f, 0.80f)
        val parts = seg(0.66f, 0.86f)
        val pulse = seg(0.84f, 1f)

        val bodyColor = ColorUtils.blendARGB(0xFF161B2B.toInt(), ColorUtils.setAlphaComponent(theme.accent, 0xFF), 0.18f + 0.12f * morph)
        body.color = bodyColor
        line.color = theme.accent
        line.strokeWidth = 1.7f * u

        // cuerpo (teléfono 24x48 que gira y se ensancha en gamepad 30x58)
        val bw = lerp(24f * u, 30f * u, morph)
        val bh = lerp(48f * u, 58f * u, morph)
        val corner = lerp(5f * u, 12f * u, morph)
        val landscapeHalf = bh / 2f // tras girar, el alto local pasa a ser el ancho en pantalla

        // empuñaduras: salen de las esquinas bajas al transformarse
        if (morph > 0f) {
            val gr = 11f * u * morph
            for (side in intArrayOf(-1, 1)) {
                val gx = cx + side * (landscapeHalf - 9f * u)
                val gy = cy + 8f * u
                canvas.drawCircle(gx, gy, gr, body)
                canvas.drawCircle(gx, gy, gr, line)
            }
        }

        canvas.save()
        canvas.translate(cx, cy)
        val s = 0.7f + 0.3f * intro
        canvas.scale(s, s)
        canvas.rotate(-90f * rot)
        r.set(-bw / 2f, -bh / 2f, bw / 2f, bh / 2f)
        body.alpha = (255 * intro).toInt()
        line.alpha = (255 * intro).toInt()
        canvas.drawRoundRect(r, corner, corner, body)
        canvas.drawRoundRect(r, corner, corner, line)
        // pantalla del teléfono: se apaga mientras se transforma
        val screenAlpha = (1f - morph) * intro
        if (screenAlpha > 0f) {
            r.inset(2.2f * u, 3.4f * u)
            screen.color = ColorUtils.setAlphaComponent(theme.accent2, (0xA0 * screenAlpha).toInt())
            canvas.drawRoundRect(r, 3f * u, 3f * u, screen)
            detail.color = ColorUtils.setAlphaComponent(0xFF0D1017.toInt(), (255 * screenAlpha).toInt())
            canvas.drawRoundRect(-3.5f * u, -bh / 2f + 4.2f * u, 3.5f * u, -bh / 2f + 5.8f * u, u, u, detail) // muesca
        }
        canvas.restore()

        // mando: cruceta, botones y sticks
        if (parts > 0f) {
            val a = (255 * parts).toInt()
            val dx = cx - landscapeHalf * 0.5f
            val bx = cx + landscapeHalf * 0.5f
            detail.color = ColorUtils.setAlphaComponent(theme.title, a)
            canvas.drawRoundRect(dx - 4.6f * u, cy - 1.3f * u - 1f * u, dx + 4.6f * u, cy + 1.3f * u - 1f * u, u, u, detail)
            canvas.drawRoundRect(dx - 1.3f * u, cy - 4.6f * u - 1f * u, dx + 1.3f * u, cy + 4.6f * u - 1f * u, u, u, detail)
            detail.color = ColorUtils.setAlphaComponent(theme.accent2, a)
            for ((ox, oy) in listOf(0f to -3.8f, 3.8f to -1f, 0f to 1.8f, -3.8f to -1f)) {
                canvas.drawCircle(bx + ox * u, cy + oy * u - 1f * u + 1f * u, 1.9f * u, detail)
            }
            line.alpha = a
            line.strokeWidth = 1.2f * u
            canvas.drawCircle(cx - 8f * u, cy + 7f * u, 3.4f * u, line)
            canvas.drawCircle(cx + 8f * u, cy + 7f * u, 3.4f * u, line)
            line.strokeWidth = 1.7f * u
        }

        // pulso
        val ringBase = lerp(8f * u, 64f * u, pulse)
        if (pulse > 0f && pulse < 1f) drawRing(canvas, cx, cy, ringBase, (1f - pulse) * 0.85f, u)
        if (pulse > 0.25f && pulse < 1f) {
            val t2 = (pulse - 0.25f) / 0.75f
            drawRing(canvas, cx, cy, lerp(8f * u, 64f * u, t2), (1f - t2) * 0.6f, u)
        }

        // título
        val ta = seg(0.72f, 0.92f)
        if (ta > 0f) {
            label.textSize = 9f * u
            label.color = ColorUtils.setAlphaComponent(theme.title, (255 * ta).toInt())
            canvas.drawText("GameCast", cx, cy + 42f * u + (1f - ta) * 4f * u, label)
        }

        // ondas de los toques
        if (rings.isNotEmpty()) {
            val now = SystemClock.uptimeMillis()
            val it = rings.iterator()
            while (it.hasNext()) {
                val ring = it.next()
                val t = (now - ring.t0) / 900f
                if (t >= 1f) { it.remove(); continue }
                drawRing(canvas, ring.x, ring.y, lerp(3f * u, 46f * u, t), (1f - t) * 0.9f, u)
            }
            postInvalidateOnAnimation()
        }
    }

    private fun drawRing(canvas: Canvas, x: Float, y: Float, radius: Float, alpha: Float, u: Float) {
        line.color = theme.glow
        line.alpha = (255 * alpha).toInt().coerceIn(0, 255)
        line.strokeWidth = 1.6f * u
        canvas.drawCircle(x, y, radius, line)
        line.color = theme.accent
    }
}
