package com.khumasi.game.render

/** Looks unlocked through the reward island. Colors are sRGB 0xRRGGBB. */
data class TileTheme(val face: Int, val ink: Int)

data class SkyTheme(
    val zenith: Int,
    val horizon: Int,
    val sun: Int,
    val seaDeep: Int,
    val seaShallow: Int,
    val foam: Int,
    val ambientSky: Int,
    val ground: Int,
    val stars: Float = 0f,
    val aurora: Float = 0f,
    val sunGlow: Float = 1f,
)

object Themes {
    fun tile(id: String): TileTheme = when (id) {
        "sand" -> TileTheme(0xE9CF9E, 0x5A3B1E)
        "marble" -> TileTheme(0xF4F4F1, 0x33363D)
        "night" -> TileTheme(0x2A3550, 0xF2C55C)
        "gold" -> TileTheme(0xE7C267, 0x4A3410)
        else -> TileTheme(Palette.TILE_IVORY, Palette.TILE_INK)
    }

    fun sky(id: String): SkyTheme = when (id) {
        "sunset" -> SkyTheme(0x4C5FA8, 0xFFB27A, 0xFFD3A0, 0x284E7A, 0x3E9DB0, 0xFFF0E0, 0xF5C6A5, 0x8C6A55, sunGlow = 1.3f)
        "night" -> SkyTheme(0x0B1430, 0x2C3E6E, 0xC9D8FF, 0x0E2944, 0x1C5470, 0xB8D0F0, 0x6E80B8, 0x3A3D55, stars = 1f, sunGlow = 0.45f)
        "dawn" -> SkyTheme(0x7C8FD6, 0xFFC9D6, 0xFFE6EC, 0x2C6A92, 0x5CC3C9, 0xFFF6FA, 0xE8D3F0, 0x9A8079, sunGlow = 0.9f)
        "aurora" -> SkyTheme(0x07122A, 0x1D3B5A, 0xBFFFE6, 0x0B2A3E, 0x1A5E6E, 0xC8FFF0, 0x6FA8B8, 0x2F3D48, stars = 0.8f, aurora = 1f, sunGlow = 0.35f)
        else -> SkyTheme(Palette.SKY_ZENITH, Palette.SKY_HORIZON, Palette.SUN, Palette.SEA_DEEP, Palette.SEA_SHALLOW, Palette.FOAM, 0xBFDFFF, Palette.GROUND)
    }
}
