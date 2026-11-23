package com.example.gamepadhid.hardware

import android.content.Context
import android.view.KeyEvent
import com.example.gamepadhid.R
import com.example.gamepadhid.mapping.ControlIds
import org.json.JSONObject

/** Botones físicos del teléfono que se pueden reasignar. */
enum class HwKey(val keyCode: Int, val labelRes: Int) {
    VOLUME_UP(KeyEvent.KEYCODE_VOLUME_UP, R.string.hw_key_volume_up),
    VOLUME_DOWN(KeyEvent.KEYCODE_VOLUME_DOWN, R.string.hw_key_volume_down),
    POWER(KeyEvent.KEYCODE_POWER, R.string.hw_key_power);

    companion object {
        fun fromKeyCode(code: Int): HwKey? = values().firstOrNull { it.keyCode == code }
    }
}

/**
 * Qué puede hacer un botón físico. Una acción es una de las constantes o el id de un botón del mando
 * (ControlIds: A, B, X, Y, L1, R1, L2, R2, START, SELECT y la cruceta).
 */
object HwActions {
    const val NONE = "NONE"
    const val CLOSE_APP = "CLOSE_APP"       // cierra la app y vuelve al Lobby
    const val BACK = "BACK"                 // tecla Atrás dentro de la app
    const val EXIT_CONSOLE = "EXIT_CONSOLE" // sale del Modo Consola

    val buttons = listOf(
        ControlIds.A, ControlIds.B, ControlIds.X, ControlIds.Y, ControlIds.L1, ControlIds.R1,
        ControlIds.L2, ControlIds.R2, ControlIds.START, ControlIds.SELECT,
        ControlIds.D_UP, ControlIds.D_DOWN, ControlIds.D_LEFT, ControlIds.D_RIGHT
    )
    val all = listOf(NONE, CLOSE_APP, BACK, EXIT_CONSOLE) + buttons

    fun label(context: Context, action: String): String = when (action) {
        NONE -> context.getString(R.string.hw_act_none)
        CLOSE_APP -> context.getString(R.string.hw_act_close_app)
        BACK -> context.getString(R.string.hw_act_back)
        EXIT_CONSOLE -> context.getString(R.string.hw_act_exit)
        else -> if (action.startsWith("D-")) context.getString(R.string.hw_act_dpad, ControlIds.label(action))
        else context.getString(R.string.hw_act_button, ControlIds.label(action))
    }

    fun encode(map: Map<HwKey, String>): String {
        val o = JSONObject()
        for ((k, v) in map) o.put(k.name, v)
        return o.toString()
    }

    fun decode(json: String?): Map<HwKey, String> {
        val out = HashMap<HwKey, String>()
        if (json != null) try {
            val o = JSONObject(json)
            for (k in HwKey.values()) o.optString(k.name, "").takeIf { it in all }?.let { out[k] = it }
        } catch (e: Exception) {
            // ajustes dañados: se usan los de por defecto
        }
        return out
    }

    /** Ajustes globales con los de la app encima (si [packageName] define los suyos). */
    fun effective(context: Context, packageName: String?): Map<HwKey, String> {
        val global = com.example.gamepadhid.Prefs.hwKeyMap(context)
        val game = if (packageName.isNullOrEmpty()) emptyMap() else com.example.gamepadhid.Prefs.gameHwKeys(context, packageName)
        return HwKey.values().associateWith { k -> game[k] ?: global[k] ?: NONE }
    }
}
