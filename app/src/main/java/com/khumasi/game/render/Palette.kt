package com.khumasi.game.render

import com.khumasi.game.core.LetterState

/** Colors shared by the 3D scene and the 2D interface (sRGB, 0xRRGGBB). */
object Palette {
    const val TILE_IVORY = 0xF7F0E1
    const val TILE_INK = 0x24314C
    const val CORRECT = 0x3BA466
    const val PRESENT = 0xE3A73A
    const val ABSENT = 0x6B7486
    const val RESULT_INK = 0xFFFFFF

    const val SLAB = 0x1E2C46
    const val SOCKET = 0x152037
    const val ACTIVE = 0x7FB8FF

    const val SKY_ZENITH = 0x5DA9EC
    const val SKY_HORIZON = 0xF7D8AE
    const val SUN = 0xFFF1D6
    const val GROUND = 0x9C8466
    const val SEA_DEEP = 0x1A6E95
    const val SEA_SHALLOW = 0x47C2C4
    const val FOAM = 0xF4FBFF

    fun forState(state: LetterState): Int = when (state) {
        LetterState.CORRECT -> CORRECT
        LetterState.PRESENT -> PRESENT
        LetterState.ABSENT -> ABSENT
        LetterState.UNKNOWN -> TILE_IVORY
    }

    fun rgb(hex: Int): FloatArray = floatArrayOf(
        ((hex shr 16) and 0xFF) / 255f,
        ((hex shr 8) and 0xFF) / 255f,
        (hex and 0xFF) / 255f,
    )
}
