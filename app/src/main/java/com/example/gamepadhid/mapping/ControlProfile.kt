package com.example.gamepadhid.mapping

import com.example.gamepadhid.R

/** Perfil de control del Modo Consola (se elige al iniciar). */
enum class ControlProfile(val titleRes: Int, val descRes: Int) {
    /** Usa el mapa guardado de la app; si no hay, ML Kit intenta detectar los botones. Pasa por el mapeo. */
    AUTO(R.string.profile_auto, R.string.profile_auto_desc),

    /** Los botones del mando, colocados a mano. Pasa por el mapeo, sin detección automática. */
    MANUAL(R.string.profile_manual, R.string.profile_manual_desc),

    /** Trackpad flotante (pantalla en miniatura) y botones Atrás/Inicio para apps como Instagram o TikTok. Sin mapeo. */
    APP(R.string.profile_app, R.string.profile_app_desc);

    companion object {
        fun from(name: String?): ControlProfile = values().firstOrNull { it.name == name } ?: AUTO
    }
}
