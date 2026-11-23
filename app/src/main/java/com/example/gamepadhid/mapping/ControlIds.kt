package com.example.gamepadhid.mapping

/** Ids de los controles virtuales. El id del dedo = índice en [ALL] + 1. */
object ControlIds {
    const val A = "A"
    const val B = "B"
    const val X = "X"
    const val Y = "Y"
    const val L1 = "L1"
    const val R1 = "R1"
    const val L2 = "L2"
    const val R2 = "R2"
    const val START = "START"
    const val SELECT = "SELECT"
    const val STICK_L = "STICK_L"
    const val STICK_R = "STICK_R"
    const val D_UP = "D-up"
    const val D_DOWN = "D-down"
    const val D_LEFT = "D-left"
    const val D_RIGHT = "D-right"
    const val MOUSEPAD = "MOUSEPAD"

    enum class Kind { BUTTON, STICK, MOUSEPAD }

    val ALL: List<String> = listOf(
        A, B, X, Y, L1, R1, L2, R2, START, SELECT,
        STICK_L, STICK_R, D_UP, D_DOWN, D_LEFT, D_RIGHT, MOUSEPAD
    )

    private val DPAD = listOf(D_UP, D_DOWN, D_LEFT, D_RIGHT)
    private val FACE = listOf(A, B, X, Y)

    /** Controles disponibles en cada plantilla. */
    fun idsFor(layout: PadLayout): List<String> = when (layout) {
        PadLayout.XBOX -> FACE + listOf(L1, R1, L2, R2, START, SELECT, STICK_L, STICK_R) + DPAD
        PadLayout.PSP -> FACE + listOf(L1, R1, START, SELECT, STICK_L) + DPAD
        PadLayout.DS -> FACE + listOf(L1, R1, START, SELECT) + DPAD + MOUSEPAD
    }

    /** Id de puntero para el dedo virtual (>= 1). */
    fun fingerId(id: String): Int = ALL.indexOf(id) + 1

    fun kind(id: String): Kind = when (id) {
        STICK_L, STICK_R -> Kind.STICK
        MOUSEPAD -> Kind.MOUSEPAD
        else -> Kind.BUTTON
    }

    /** Rótulo corto para dibujar en el mapeador. */
    fun label(id: String): String = when (id) {
        D_UP -> "↑"
        D_DOWN -> "↓"
        D_LEFT -> "←"
        D_RIGHT -> "→"
        STICK_L -> "LS"
        STICK_R -> "RS"
        MOUSEPAD -> "TOUCH"
        else -> id
    }
}
