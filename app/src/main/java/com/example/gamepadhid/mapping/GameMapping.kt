package com.example.gamepadhid.mapping

import org.json.JSONObject

enum class PadLayout(val key: String) {
    XBOX("xbox"), PSP("psp"), DS("ds");

    companion object {
        fun fromKey(key: String?): PadLayout = values().firstOrNull { it.key == key } ?: XBOX
    }
}

/**
 * Posición de un control. Todo en FRACCIONES de la pantalla virtual:
 * x, y = centro (0..1). size = radio como fracción del ALTO.
 * w, h = ancho/alto como fracción de la pantalla (solo MOUSEPAD).
 */
data class ControlPoint(
    var x: Float,
    var y: Float,
    var size: Float = DEFAULT_SIZE,
    var w: Float = 0f,
    var h: Float = 0f
) {
    companion object { const val DEFAULT_SIZE = 0.07f }
}

class GameMapping(
    val packageName: String,
    var layout: PadLayout,
    val controls: MutableMap<String, ControlPoint> = LinkedHashMap()
) {
    /** Cambia de plantilla conservando las posiciones de los controles compartidos. */
    fun switchLayout(newLayout: PadLayout) {
        layout = newLayout
        val ids = ControlIds.idsFor(newLayout)
        controls.keys.retainAll(ids.toSet())
        for (id in ids) if (id !in controls) controls[id] = defaultPoint(newLayout, id)
    }

    fun toJson(): String {
        val root = JSONObject()
        root.put("version", 1)
        root.put("package", packageName)
        root.put("layout", layout.key)
        val c = JSONObject()
        for ((id, p) in controls) {
            c.put(id, JSONObject()
                .put("x", p.x.toDouble()).put("y", p.y.toDouble())
                .put("size", p.size.toDouble())
                .put("w", p.w.toDouble()).put("h", p.h.toDouble()))
        }
        root.put("controls", c)
        return root.toString()
    }

    companion object {
        fun defaultFor(pkg: String, layout: PadLayout): GameMapping {
            val m = GameMapping(pkg, layout)
            for (id in ControlIds.idsFor(layout)) m.controls[id] = defaultPoint(layout, id)
            return m
        }

        fun fromJson(json: String, fallbackPkg: String): GameMapping? = try {
            val o = JSONObject(json)
            val layout = PadLayout.fromKey(o.optString("layout"))
            val m = GameMapping(o.optString("package", fallbackPkg), layout)
            val c = o.optJSONObject("controls")
            for (id in ControlIds.idsFor(layout)) {
                val j = c?.optJSONObject(id)
                m.controls[id] = if (j != null) ControlPoint(
                    x = j.optDouble("x", 0.5).toFloat().coerceIn(0f, 1f),
                    y = j.optDouble("y", 0.5).toFloat().coerceIn(0f, 1f),
                    size = j.optDouble("size", ControlPoint.DEFAULT_SIZE.toDouble())
                        .toFloat().coerceIn(0.02f, 0.3f),
                    w = j.optDouble("w", 0.0).toFloat().coerceIn(0f, 1f),
                    h = j.optDouble("h", 0.0).toFloat().coerceIn(0f, 1f)
                ) else defaultPoint(layout, id)
            }
            m
        } catch (e: Exception) { null }

        fun defaultPoint(layout: PadLayout, id: String): ControlPoint {
            val t = when (layout) {
                PadLayout.XBOX -> XBOX_DEFAULTS
                PadLayout.PSP -> PSP_DEFAULTS
                PadLayout.DS -> DS_DEFAULTS
            }
            val p = t[id] ?: ControlPoint(0.5f, 0.5f)
            return p.copy()
        }

        private fun p(x: Float, y: Float, s: Float = ControlPoint.DEFAULT_SIZE) = ControlPoint(x, y, s)

        private val XBOX_DEFAULTS = mapOf(
            ControlIds.L2 to p(0.08f, 0.10f), ControlIds.L1 to p(0.08f, 0.24f),
            ControlIds.R2 to p(0.92f, 0.10f), ControlIds.R1 to p(0.92f, 0.24f),
            ControlIds.SELECT to p(0.42f, 0.10f, 0.05f), ControlIds.START to p(0.58f, 0.10f, 0.05f),
            ControlIds.STICK_L to p(0.14f, 0.62f, 0.12f), ControlIds.STICK_R to p(0.66f, 0.80f, 0.12f),
            ControlIds.D_UP to p(0.31f, 0.70f, 0.06f), ControlIds.D_DOWN to p(0.31f, 0.90f, 0.06f),
            ControlIds.D_LEFT to p(0.25f, 0.80f, 0.06f), ControlIds.D_RIGHT to p(0.37f, 0.80f, 0.06f),
            ControlIds.A to p(0.86f, 0.84f), ControlIds.B to p(0.93f, 0.70f),
            ControlIds.X to p(0.79f, 0.70f), ControlIds.Y to p(0.86f, 0.56f)
        )

        private val PSP_DEFAULTS = mapOf(
            ControlIds.L1 to p(0.08f, 0.10f), ControlIds.R1 to p(0.92f, 0.10f),
            ControlIds.D_UP to p(0.12f, 0.38f, 0.06f), ControlIds.D_DOWN to p(0.12f, 0.60f, 0.06f),
            ControlIds.D_LEFT to p(0.06f, 0.49f, 0.06f), ControlIds.D_RIGHT to p(0.18f, 0.49f, 0.06f),
            ControlIds.STICK_L to p(0.12f, 0.82f, 0.12f),
            ControlIds.A to p(0.86f, 0.62f), ControlIds.B to p(0.93f, 0.50f),
            ControlIds.X to p(0.79f, 0.50f), ControlIds.Y to p(0.86f, 0.38f),
            ControlIds.SELECT to p(0.82f, 0.88f, 0.05f), ControlIds.START to p(0.92f, 0.88f, 0.05f)
        )

        private val DS_DEFAULTS = mapOf(
            ControlIds.L1 to p(0.08f, 0.10f), ControlIds.R1 to p(0.92f, 0.10f),
            ControlIds.D_UP to p(0.10f, 0.30f, 0.06f), ControlIds.D_DOWN to p(0.10f, 0.52f, 0.06f),
            ControlIds.D_LEFT to p(0.04f, 0.41f, 0.06f), ControlIds.D_RIGHT to p(0.16f, 0.41f, 0.06f),
            ControlIds.A to p(0.88f, 0.52f), ControlIds.B to p(0.95f, 0.40f),
            ControlIds.X to p(0.81f, 0.40f), ControlIds.Y to p(0.88f, 0.28f),
            ControlIds.SELECT to p(0.90f, 0.88f, 0.05f), ControlIds.START to p(0.96f, 0.88f, 0.05f),
            ControlIds.MOUSEPAD to ControlPoint(0.50f, 0.72f, 0f, 0.44f, 0.46f)
        )
    }
}
