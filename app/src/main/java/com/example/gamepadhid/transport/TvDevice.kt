package com.example.gamepadhid.transport

enum class TransportKind {
    /** Pantalla externa (Miracast / Wi-Fi Direct): la TV se elige en el panel Cast del sistema. */
    MIRACAST,
    /** Google Cast / Chromecast: flujo HLS desde el teléfono. */
    CAST,
    /** DLNA / UPnP: flujo MPEG-TS continuo desde el teléfono. */
    DLNA;

    companion object {
        fun from(name: String?): TransportKind = values().firstOrNull { it.name == name } ?: MIRACAST
    }
}

/**
 * Una TV encontrada. [id] identifica el destino según el tipo: nombre de la pantalla (MIRACAST),
 * id de ruta (CAST) o URL de la descripción UPnP (DLNA).
 */
data class TvDevice(
    val kind: TransportKind,
    val id: String,
    val name: String,
    val detail: String = "",
    val connected: Boolean = true
)
