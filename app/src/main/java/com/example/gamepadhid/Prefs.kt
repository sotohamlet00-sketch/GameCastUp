package com.example.gamepadhid

import android.content.Context
import com.example.gamepadhid.hardware.HwActions
import com.example.gamepadhid.hardware.HwKey
import com.example.gamepadhid.mapping.ControlProfile
import com.example.gamepadhid.transport.TransportKind
import com.example.gamepadhid.transport.TvDevice

/** Ajustes mínimos guardados en SharedPreferences. */
object Prefs {
    private const val FILE = "gamecast_prefs"
    private const val KEY_BT_NAME = "bt_name"
    private const val KEY_TV_NAME = "tv_name"
    private const val KEY_TV_KIND = "tv_kind"
    private const val KEY_AUTOFIX = "autofix_done"
    private const val KEY_PROFILE = "control_profile"
    private const val KEY_HW = "hw_keys"
    private const val KEY_THEME_1 = "theme_accent"
    private const val KEY_THEME_2 = "theme_accent2"
    private const val KEY_THEME_SRC = "theme_source"
    private const val KEY_TV_ID = "tv_id"

    private fun sp(c: Context) = c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun btName(c: Context): String =
        sp(c).getString(KEY_BT_NAME, null) ?: c.getString(R.string.default_bt_name)

    fun setBtName(c: Context, value: String) {
        sp(c).edit().putString(KEY_BT_NAME, value).apply()
    }

    /** Nombre de la última TV elegida (el id del display cambia en cada conexión; el nombre no). */
    fun tvName(c: Context): String? = sp(c).getString(KEY_TV_NAME, null)

    fun setTvName(c: Context, value: String?) {
        sp(c).edit().putString(KEY_TV_NAME, value).apply()
    }

    /** Vía de la última TV elegida: MIRACAST (por defecto), CAST o DLNA. */
    fun tvKind(c: Context): TransportKind = TransportKind.from(sp(c).getString(KEY_TV_KIND, null))

    /** Id de la TV elegida: ruta Cast o URL de la descripción UPnP (en Miracast se usa el nombre). */
    fun tvId(c: Context): String? = sp(c).getString(KEY_TV_ID, null)

    fun setTvDevice(c: Context, device: TvDevice) {
        sp(c).edit()
            .putString(KEY_TV_KIND, device.kind.name)
            .putString(KEY_TV_ID, device.id)
            .putString(KEY_TV_NAME, device.name)
            .apply()
    }

    /** La TV guardada si es de las que reciben un flujo por la red (Cast o DLNA); null si es Miracast o no hay. */
    fun streamDevice(c: Context): TvDevice? {
        val kind = tvKind(c)
        val id = tvId(c)
        val name = tvName(c)
        return if (kind == TransportKind.MIRACAST || id == null || name == null) null else TvDevice(kind, id, name)
    }

    /** El Auto-Fix (permisos concedidos con Shizuku) ya se aplicó con éxito. */
    fun autoFixDone(c: Context): Boolean = sp(c).getBoolean(KEY_AUTOFIX, false)

    fun setAutoFixDone(c: Context, value: Boolean) {
        sp(c).edit().putBoolean(KEY_AUTOFIX, value).apply()
    }

    /** Perfil de control del Modo Consola: AUTO (por defecto), MANUAL o APP. */
    fun controlProfile(c: Context): ControlProfile = ControlProfile.from(sp(c).getString(KEY_PROFILE, null))

    fun setControlProfile(c: Context, value: ControlProfile) {
        sp(c).edit().putString(KEY_PROFILE, value.name).apply()
    }

    /** Acción de cada botón físico (global). Los que no figuran no hacen nada. */
    fun hwKeyMap(c: Context): Map<HwKey, String> = HwActions.decode(sp(c).getString(KEY_HW, null))

    fun setHwKeyMap(c: Context, map: Map<HwKey, String>) {
        sp(c).edit().putString(KEY_HW, HwActions.encode(map)).apply()
    }

    /** Acciones propias de una app (anulan las globales). Vacío = usa las globales. */
    fun gameHwKeys(c: Context, packageName: String): Map<HwKey, String> =
        HwActions.decode(sp(c).getString("hwk_$packageName", null))

    fun setGameHwKeys(c: Context, packageName: String, map: Map<HwKey, String>?) {
        val e = sp(c).edit()
        if (map == null) e.remove("hwk_$packageName") else e.putString("hwk_$packageName", HwActions.encode(map))
        e.apply()
    }

    /** Colores del tema (acento y segundo acento) o null si se usa el índigo por defecto. */
    fun themeColors(c: Context): Pair<Int, Int>? {
        val p = sp(c)
        return if (p.contains(KEY_THEME_1)) p.getInt(KEY_THEME_1, 0) to p.getInt(KEY_THEME_2, 0) else null
    }

    fun themeSource(c: Context): String? = sp(c).getString(KEY_THEME_SRC, null)

    fun setThemeColors(c: Context, accent: Int, accent2: Int, source: String) {
        sp(c).edit().putInt(KEY_THEME_1, accent).putInt(KEY_THEME_2, accent2).putString(KEY_THEME_SRC, source).apply()
    }

    fun clearTheme(c: Context) {
        sp(c).edit().remove(KEY_THEME_1).remove(KEY_THEME_2).remove(KEY_THEME_SRC).apply()
    }
}
