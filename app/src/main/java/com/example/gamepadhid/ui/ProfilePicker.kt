package com.example.gamepadhid.ui

import android.content.Context
import androidx.appcompat.app.AlertDialog
import com.example.gamepadhid.Prefs
import com.example.gamepadhid.R
import com.example.gamepadhid.mapping.ControlProfile

/** «Perfil de control» que se elige al iniciar el Modo Consola. Se recuerda la última elección. */
object ProfilePicker {
    fun show(context: Context, onChosen: (ControlProfile) -> Unit) {
        val all = ControlProfile.values()
        val items = all.map { context.getString(it.titleRes) + "\n" + context.getString(it.descRes) }.toTypedArray()
        var selected = all.indexOf(Prefs.controlProfile(context)).coerceAtLeast(0)
        AlertDialog.Builder(context)
            .setTitle(R.string.profile_title)
            .setSingleChoiceItems(items, selected) { _, which -> selected = which }
            .setPositiveButton(R.string.profile_start) { _, _ ->
                val chosen = all[selected]
                Prefs.setControlProfile(context, chosen)
                onChosen(chosen)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
