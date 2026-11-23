package com.example.gamepadhid.lobby

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import com.example.gamepadhid.theme.Preset
import com.example.gamepadhid.theme.ThemeColors
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import com.example.gamepadhid.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Lobby estilo XMB (PS3): categorías en horizontal y, bajo la seleccionada, su lista vertical.
 * Todo se dibuja con Canvas y las medidas salen del alto (u = alto / 1080), así que se ve igual
 * en cualquier pantalla virtual. Fondo: degradado violeta con cintas ondulantes y partículas.
 */
class LobbyView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private class Column(val category: LobbyCategory, val title: String, val items: List<LobbyItem>) {
        var index = 0
        var pos = 0f
    }

    var onActivate: ((LobbyItem) -> Unit)? = null

    private var columns: List<Column> = emptyList()
    private var catIndex = 0
    private var catPos = 0f
    private var lastFrame = SystemClock.uptimeMillis()

    private val icons = LobbyIcons(context) { postInvalidateOnAnimation() }

    // ---- mensaje temporal y reloj
    private var messageText: String? = null
    private var messageUntil = 0L
    private val clockFormat = SimpleDateFormat("d/M  HH:mm", Locale.getDefault())
    private var clockText = ""
    private var clockSecond = -1L

    // ---- pinturas
    private val bgPaint = Paint()
    private val glowPaint = Paint()
    private val ribbon = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val glassFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0x33FFFFFF }
    private val glassStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x66FFFFFF }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; color = Color.WHITE
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setShadowLayer(8f, 0f, 2f, 0x99000000.toInt())
    }
    private val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFDDE3FF.toInt(); typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }

    private val path = Path()
    private val iconCache = HashMap<Int, Drawable>()
    private var theme = ThemeColors(Preset.INDIGO.c1, Preset.INDIGO.c2)
    private val coverPath = Path()
    private val halo = RectF()
    private val rect = RectF()

    // partículas (posiciones relativas 0..1)
    private val dots = 90
    private val dx = FloatArray(dots) { Random.nextFloat() }
    private val dy = FloatArray(dots) { Random.nextFloat() }
    private val dr = FloatArray(dots) { 1f + Random.nextFloat() * 2.5f }
    private val ds = FloatArray(dots) { 0.4f + Random.nextFloat() }

    // ------------------------------------------------------------------ Contenido y navegación

    fun setItems(items: List<LobbyItem>) {
        columns = LobbyCategory.values().mapNotNull { cat ->
            val list = items.filter { it.category == cat }
            if (list.isEmpty()) null else Column(cat, context.getString(cat.titleRes), list)
        }
        catIndex = 0
        catPos = 0f
        invalidate()
    }

    fun showMessage(text: String) {
        messageText = text
        messageUntil = SystemClock.uptimeMillis() + 6000L
        invalidate()
    }

    fun handle(key: LobbyInput.Key) {
        val col = columns.getOrNull(catIndex) ?: return
        val last = col.items.lastIndex
        when (key) {
            LobbyInput.Key.LEFT -> catIndex = (catIndex - 1).coerceAtLeast(0)
            LobbyInput.Key.RIGHT -> catIndex = (catIndex + 1).coerceAtMost(columns.lastIndex)
            LobbyInput.Key.UP -> col.index = (col.index - 1).coerceAtLeast(0)
            LobbyInput.Key.DOWN -> col.index = (col.index + 1).coerceAtMost(last)
            LobbyInput.Key.PAGE_UP -> col.index = (col.index - 5).coerceAtLeast(0)
            LobbyInput.Key.PAGE_DOWN -> col.index = (col.index + 5).coerceAtMost(last)
            LobbyInput.Key.BACK -> col.index = 0
            LobbyInput.Key.SELECT -> col.items.getOrNull(col.index)?.let { onActivate?.invoke(it) }
        }
        invalidate()
    }

    override fun onDetachedFromWindow() {
        icons.shutdown()
        super.onDetachedFromWindow()
    }

    // ------------------------------------------------------------------ Dibujo

    /** Paleta del usuario (colores base o extraída de una imagen): fondo, cintas, brillos y textos. */
    fun applyTheme(t: ThemeColors) {
        theme = t
        title.color = t.title
        label.color = t.text
        sub.color = t.sub
        small.color = t.text
        ribbon.color = t.ribbon
        if (width > 0 && height > 0) buildShaders(width, height)
        invalidate()
    }

    private fun buildShaders(w: Int, h: Int) {
        bgPaint.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(theme.bgTop, theme.bgMid, theme.bgBot),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
        )
        glowPaint.shader = RadialGradient(
            w * 0.85f, h * 0.08f, w * 0.65f,
            theme.glow, theme.glow and 0x00FFFFFF, Shader.TileMode.CLAMP
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        buildShaders(w, h)
    }

    /** Icono vectorial (sin emojis), teñido con el color del texto del tema. */
    private fun vector(res: Int): Drawable = iconCache.getOrPut(res) { ContextCompat.getDrawable(context, res)!!.mutate() }

    private fun drawVector(canvas: Canvas, res: Int, cx: Float, cy: Float, size: Float, alpha: Int) {
        val d = vector(res)
        val half = (size / 2f).toInt()
        d.setBounds(cx.toInt() - half, cy.toInt() - half, cx.toInt() + half, cy.toInt() + half)
        d.setTint(theme.text)
        d.alpha = alpha.coerceIn(0, 255)
        d.draw(canvas)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val now = SystemClock.uptimeMillis()
        val dt = ((now - lastFrame).coerceIn(1L, 100L)) / 1000f
        lastFrame = now
        val k = 1f - exp(-dt * 12f)
        catPos += (catIndex - catPos) * k
        for (c in columns) c.pos += (c.index - c.pos) * k
        val u = h / 1080f

        drawBackground(canvas, w, h, u, now)
        drawClock(canvas, w, h, u, now)
        for ((ci, c) in columns.withIndex()) {
            val d = ci - catPos
            val weight = 1f - min(1f, abs(d))
            if (weight > 0.03f) drawColumn(canvas, c, d, weight, w, h, u)
        }
        drawCategories(canvas, w, h, u)
        drawFooter(canvas, w, h, u, now)
        postInvalidateOnAnimation()
    }

    private fun anchorX(w: Float) = w * 0.27f
    private fun spacing(u: Float) = 230f * u

    private fun drawBackground(canvas: Canvas, w: Float, h: Float, u: Float, now: Long) {
        canvas.drawRect(0f, 0f, w, h, bgPaint)
        canvas.drawRect(0f, 0f, w, h, glowPaint)
        val steps = 48
        for (r in 0 until 3) {
            val baseY = h * (0.66f + 0.06f * r)
            val amp = (70f + 22f * r) * u
            val phase = now * 0.00035f * (1f + 0.3f * r) + r * 2.1f
            for (j in 0 until 7) {
                path.rewind()
                val off = (j - 3) * 7f * u
                for (s in 0..steps) {
                    val x = w * s / steps
                    val t = x / w * 6.28f
                    val y = baseY + off + amp * sin(t * 0.9f + phase) + amp * 0.45f * sin(t * 2.1f - phase * 0.7f)
                    if (s == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                ribbon.strokeWidth = 2f * u
                ribbon.alpha = 22 + (3 - abs(j - 3)) * 11
                canvas.drawPath(path, ribbon)
            }
        }
        for (i in 0 until dots) {
            val x = ((dx[i] + now * 0.00002f * ds[i]) % 1f) * w
            val y = (dy[i] + 0.012f * sin(now * 0.001f * ds[i] + i)) * h
            dot.alpha = (80 + 110 * (0.5f + 0.5f * sin(now * 0.002f * ds[i] + i))).toInt()
            canvas.drawCircle(x, y, dr[i] * u, dot)
        }
    }

    private fun drawClock(canvas: Canvas, w: Float, h: Float, u: Float, now: Long) {
        val sec = now / 1000L
        if (sec != clockSecond) {
            clockSecond = sec
            clockText = clockFormat.format(Date())
        }
        rect.set(w * 0.70f, h * 0.055f, w * 0.97f, h * 0.115f)
        glassStroke.strokeWidth = 2f * u
        canvas.drawRoundRect(rect, 10f * u, 10f * u, glassFill)
        canvas.drawRoundRect(rect, 10f * u, 10f * u, glassStroke)
        small.textSize = 38f * u
        small.textAlign = Paint.Align.RIGHT
        small.alpha = 255
        val fm = small.fontMetrics
        canvas.drawText(clockText, rect.right - 24f * u, rect.centerY() - (fm.ascent + fm.descent) / 2f, small)
        small.textAlign = Paint.Align.LEFT
    }

    private fun drawCategories(canvas: Canvas, w: Float, h: Float, u: Float) {
        val y = h * 0.28f
        for ((i, c) in columns.withIndex()) {
            val d = i - catPos
            val x = anchorX(w) + d * spacing(u)
            if (x < -200f * u || x > w + 200f * u) continue
            val sel = 1f - min(1f, abs(d))
            val scale = 0.78f + 0.47f * sel
            drawVector(canvas, c.category.iconRes, x, y, 84f * u * scale, (255 * (0.42f + 0.58f * sel)).toInt())
            if (sel > 0.25f) {
                title.textSize = 30f * u
                title.alpha = (255 * sel).toInt()
                canvas.drawText(c.title, x, y + 78f * u * scale, title)
            }
        }
    }

    private fun drawColumn(canvas: Canvas, c: Column, d: Float, weight: Float, w: Float, h: Float, u: Float) {
        val x = anchorX(w) + d * spacing(u)
        val selY = h * 0.54f
        val itemSpacing = 112f * u
        val gap = 64f * u
        val topLimit = h * 0.28f + 100f * u
        for ((i, item) in c.items.withIndex()) {
            val rel = i - c.pos
            if (abs(rel) > 8f) continue
            val y = selY + rel * itemSpacing + rel.coerceIn(-1f, 1f) * gap
            if (y > h + 60f * u) continue
            val fade = ((y - topLimit) / (55f * u)).coerceIn(0f, 1f)
            if (fade <= 0f) continue
            val focus = 1f - min(1f, abs(rel))
            val alpha = weight * fade * (0.55f + 0.45f * focus)
            val size = (72f + 40f * focus) * u
            drawIcon(canvas, item, x, y, size, alpha, u)

            val textX = x + size / 2f + 28f * u
            val maxW = w - textX - w * 0.04f
            label.textSize = (30f + 14f * focus) * u
            label.alpha = (255 * alpha).toInt()
            canvas.drawText(fit(item.label, label, maxW), textX, y + (if (focus > 0.5f) -2f else 10f) * u, label)
            if (focus > 0.5f) {
                sub.textSize = 26f * u
                sub.alpha = (255 * alpha).toInt()
                canvas.drawText(fit(item.subtitle, sub, maxW), textX, y + 38f * u, sub)
            }
        }
    }

    private fun drawIcon(canvas: Canvas, item: LobbyItem, cx: Float, cy: Float, size: Float, alpha: Float, u: Float) {
        rect.set(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f)
        val a = (255 * alpha).toInt()
        val pkg = item.packageName
        val bmp = if (pkg != null) icons.get(pkg) else null
        if (bmp != null) { // portada: icono real de la app con esquinas redondas, halo y marco de cristal
            val r = size * 0.22f
            halo.set(rect.left - 6f * u, rect.top - 6f * u, rect.right + 6f * u, rect.bottom + 8f * u)
            glassFill.alpha = (0x40 * alpha).toInt()
            canvas.drawRoundRect(halo, r + 6f * u, r + 6f * u, glassFill)
            canvas.save()
            coverPath.rewind()
            coverPath.addRoundRect(rect, r, r, Path.Direction.CW)
            canvas.clipPath(coverPath)
            bitmapPaint.alpha = a
            canvas.drawBitmap(bmp, null, rect, bitmapPaint)
            canvas.restore()
            glassStroke.strokeWidth = 2f * u
            glassStroke.alpha = (0xB0 * alpha).toInt()
            canvas.drawRoundRect(rect, r, r, glassStroke)
            glassFill.alpha = 0x33
            glassStroke.alpha = 0x66
            return
        }
        glassFill.alpha = (0x55 * alpha).toInt()
        glassStroke.alpha = (0xAA * alpha).toInt()
        canvas.drawRoundRect(rect, size * 0.24f, size * 0.24f, glassFill)
        canvas.drawRoundRect(rect, size * 0.24f, size * 0.24f, glassStroke)
        if (item.action == LobbyAction.EXIT_CONSOLE) {
            drawVector(canvas, R.drawable.ic_close, cx, cy, size * 0.5f, a)
        }
        glassFill.alpha = 0x33
        glassStroke.alpha = 0x66
    }

    private fun drawFooter(canvas: Canvas, w: Float, h: Float, u: Float, now: Long) {
        small.textSize = 26f * u
        small.alpha = 170
        canvas.drawText(context.getString(R.string.lobby_hint), w * 0.05f, h - 40f * u, small)

        val text = messageText
        if (text != null && now < messageUntil) {
            small.textSize = 30f * u
            small.alpha = 255
            val shown = fit(text, small, w * 0.8f)
            val tw = small.measureText(shown)
            rect.set(w / 2f - tw / 2f - 30f * u, h * 0.86f - 34f * u, w / 2f + tw / 2f + 30f * u, h * 0.86f + 34f * u)
            canvas.drawRoundRect(rect, 34f * u, 34f * u, glassFill)
            canvas.drawRoundRect(rect, 34f * u, 34f * u, glassStroke)
            small.textAlign = Paint.Align.CENTER
            canvas.drawText(shown, rect.centerX(), rect.centerY() + 10f * u, small)
            small.textAlign = Paint.Align.LEFT
        }
    }

    private fun fit(text: String, paint: Paint, maxWidth: Float): String {
        if (maxWidth <= 0f) return ""
        val n = paint.breakText(text, true, maxWidth, null)
        return if (n >= text.length) text else text.substring(0, (n - 1).coerceAtLeast(0)) + "…"
    }
}
