package com.example.gamepadhid.hardware

import android.content.Context
import androidx.appcompat.app.AlertDialog
import com.example.gamepadhid.R

/**
 * Diálogo para elegir qué hace cada botón físico. Se usa en Ajustes (valores globales) y en el mapeador
 * (valores de una app concreta). Tocar un botón abre la lista de acciones y el diálogo se vuelve a mostrar.
 */
class HwKeyConfigDialog(
    private val context: Context,
    private val title: String,
    initial: Map<HwKey, String>,
    private val neutralLabel: String? = null,
    private val onNeutral: (() -> Unit)? = null,
    private val onSave: (Map<HwKey, String>) -> Unit
) {
    private val current = HwKey.values().associateWith { initial[it] ?: HwActions.NONE }.toMutableMap()

    fun show() {
        val keys = HwKey.values()
        val items = keys.map { context.getString(it.labelRes) + ": " + HwActions.label(context, current[it]!!) }.toTypedArray()
        val builder = AlertDialog.Builder(context)
            .setTitle(title)
            .setItems(items) { _, which -> pick(keys[which]) }
            .setPositiveButton(R.string.btn_save) { _, _ -> onSave(current.toMap()) }
            .setNegativeButton(android.R.string.cancel, null)
        if (neutralLabel != null) builder.setNeutralButton(neutralLabel) { _, _ -> onNeutral?.invoke() }
        builder.show()
    }

    private fun pick(key: HwKey) {
        val labels = HwActions.all.map { HwActions.label(context, it) }.toTypedArray()
        AlertDialog.Builder(context)
            .setTitle(key.labelRes)
            .setSingleChoiceItems(labels, HwActions.all.indexOf(current[key]).coerceAtLeast(0)) { d, which ->
                current[key] = HwActions.all[which]
                d.dismiss()
                show()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> show() }
            .show()
    }
}
