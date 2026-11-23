package com.example.gamepadhid.mapping

import android.content.Context

/** Guarda/lee `mapping_<paquete>.json` como cadena en SharedPreferences. */
object MappingStore {
    private const val PREFS = "gamecast_mappings"

    private fun key(pkg: String) = "mapping_$pkg.json"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun has(ctx: Context, pkg: String): Boolean = prefs(ctx).contains(key(pkg))

    fun load(ctx: Context, pkg: String): GameMapping? {
        val json = prefs(ctx).getString(key(pkg), null) ?: return null
        return GameMapping.fromJson(json, pkg)
    }

    fun loadOrDefault(ctx: Context, pkg: String, layout: PadLayout = PadLayout.XBOX): GameMapping =
        load(ctx, pkg) ?: GameMapping.defaultFor(pkg, layout)

    fun save(ctx: Context, mapping: GameMapping) {
        prefs(ctx).edit().putString(key(mapping.packageName), mapping.toJson()).apply()
    }

    fun delete(ctx: Context, pkg: String) {
        prefs(ctx).edit().remove(key(pkg)).apply()
    }
}
