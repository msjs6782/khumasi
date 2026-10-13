package com.khumasi.game.render

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The reward island behind the board. Each level adds landmarks; everything is procedural
 * low-poly geometry so it costs nothing to download. Moving parts (windmill sails, boat,
 * balloon) are separate meshes animated by the renderer.
 */
internal class IslandModel(
    val ground: Mesh,
    val sails: Mesh?,
    val sailsPivot: FloatArray,
    val boat: Mesh?,
    val boatPos: FloatArray,
    val balloon: Mesh?,
    val balloonPos: FloatArray,
) {
    fun release() {
        ground.release(); sails?.release(); boat?.release(); balloon?.release()
    }
}

internal object IslandBuilder {
    const val RADIUS = 5.2f

    // Colors
    private const val SAND = 0xEAD39C
    private const val SAND_WET = 0xC9B27E
    private const val GRASS = 0x6CBF52
    private const val GRASS_DARK = 0x58A647
    private const val ROCK = 0x9AA0A6
    private const val ROCK_DARK = 0x7D838A
    private const val TRUNK = 0x8A5A3B
    private const val PALM = 0x3E9E4C
    private const val PALM_LIGHT = 0x58B85A
    private const val BUSH = 0x4FA548
    private const val WOOD = 0xA9744A
    private const val WOOD_DARK = 0x7B5233
    private const val WALL = 0xF4E7CF
    private const val ROOF = 0xCB5A3E
    private const val THATCH = 0xD8B062
    private const val WHITE = 0xF7F7F2
    private const val RED = 0xD9443A
    private const val GLASS = 0xFFE7A0
    private const val CLOTH = 0xF2C14E
    private const val TEAL = 0x2F9C95
    private const val STONE = 0xD9CBB0
    private const val DOME = 0x3D8FB8
    private const val FLOWER_A = 0xF06A8A
    private const val FLOWER_B = 0xFFD54A

    /** Terrain height at a point (shared by the mesh and object placement). */
    fun height(x: Float, z: Float): Float {
        val r = sqrt(x * x + z * z) / RADIUS
        val a = kotlin.math.atan2(z, x)
        val wobble = 0.06f * sin(a * 3f + 0.7f) + 0.04f * sin(a * 5f + 2.1f)
        val rr = r * (1f - wobble)
        val dome = 1.05f * (1f - rr.coerceIn(0f, 1.2f).pow(2.3f))
        val hill = 0.45f * kotlin.math.exp(-(((x + 1.2f) * (x + 1.2f) + (z + 1.4f) * (z + 1.4f)) / 3.2f))
        return max(dome + hill - 0.12f, -0.6f)
    }

    fun build(level: Int): IslandModel {
        val b = MeshBuilder()
        terrain(b)
        val rnd = Random(7)

        // Level 0: a lonely palm, rocks and a bush.
        palm(b, 1.3f, -0.6f, 1.0f, 15f)
        rock(b, -2.6f, 2.3f, 0.35f)
        rock(b, 3.1f, 1.7f, 0.25f)
        bush(b, 0.4f, 1.9f, 0.32f)

        if (level >= 1) tent(b, -1.7f, 0.9f)
        if (level >= 2) {
            palm(b, 2.2f, 0.6f, 0.85f, -20f)
            palm(b, -0.6f, -2.3f, 1.1f, 40f)
            bush(b, -2.7f, -0.6f, 0.35f)
            bush(b, 2.6f, -1.0f, 0.28f)
            flowers(b, rnd, 0.9f, 2.6f)
        }
        if (level >= 3) hut(b, -0.4f, 0.4f)
        if (level >= 4) dock(b)
        if (level >= 5) lighthouse(b, 2.7f, -2.0f)
        if (level >= 6) {
            well(b, 1.0f, 2.2f)
            fence(b, -3.2f, 0.2f, 6)
            pine(b, -3.0f, -1.9f, 1.0f)
            pine(b, -2.2f, -2.6f, 0.8f)
        }
        if (level >= 7) house(b, 0.7f, -1.7f)
        val sailsPivot = floatArrayOf(-3.0f, 0f, 1.1f)
        if (level >= 8) {
            sailsPivot[1] = windmillTower(b, sailsPivot[0], sailsPivot[2])
        }
        if (level >= 9) minaret(b, 3.2f, 0.3f)
        if (level >= 10) bunting(b)

        val ground = b.build()
        val sails = if (level >= 8) MeshBuilder().also { windmillSails(it) }.build() else null
        val boat = if (level >= 4) MeshBuilder().also { boat(it) }.build() else null
        val balloon = if (level >= 10) MeshBuilder().also { balloon(it) }.build() else null
        return IslandModel(
            ground, sails, sailsPivot,
            boat, floatArrayOf(4.1f, 0f, 3.4f),
            balloon, floatArrayOf(-2.2f, 2.5f, -1.8f),
        )
    }

    private fun terrain(b: MeshBuilder) {
        val rings = 9
        val segs = 36
        val maxR = RADIUS * 1.18f
        fun pt(ring: Int, seg: Int): FloatArray {
            val rr = maxR * ring / rings
            val a = (2 * PI * seg / segs).toFloat()
            val x = rr * cos(a); val z = rr * sin(a)
            return floatArrayOf(x, height(x, z), z)
        }
        for (ring in 0 until rings) for (seg in 0 until segs) {
            val p0 = pt(ring, seg); val p1 = pt(ring, seg + 1); val p2 = pt(ring + 1, seg + 1); val p3 = pt(ring + 1, seg)
            // counter-clockwise seen from above
            for (tri in arrayOf(arrayOf(p0, p1, p2), arrayOf(p0, p2, p3))) {
                val h = (tri[0][1] + tri[1][1] + tri[2][1]) / 3f
                b.color(
                    when {
                        h > 0.42f -> if ((ring + seg) % 3 == 0) GRASS_DARK else GRASS
                        h > 0.05f -> SAND
                        else -> SAND_WET
                    }
                )
                b.flatTri(tri[0][0], tri[0][1], tri[0][2], tri[1][0], tri[1][1], tri[1][2], tri[2][0], tri[2][1], tri[2][2])
            }
        }
    }

    private fun palm(b: MeshBuilder, x: Float, z: Float, size: Float, lean: Float) {
        val y = height(x, z)
        b.push(); b.translate(x, y, z); b.rotate(lean * 0.35f, 0f, 0f, 1f); b.scale(size, size, size)
        val segments = 5
        var top = 0f
        for (i in 0 until segments) {
            b.color(TRUNK, sway = 0.6f)
            b.push(); b.translate(0.04f * i * i * 0.3f, top, 0f)
            b.cylinder(0.12f - i * 0.012f, 0.105f - i * 0.012f, 0.42f, 6, capBottom = i == 0)
            b.pop()
            top += 0.4f
        }
        val crownX = 0.04f * (segments - 1) * (segments - 1) * 0.3f
        for (k in 0 until 7) {
            b.push(); b.translate(crownX, top, 0f); b.rotate(k * 360f / 7 + 10f, 0f, 1f, 0f); b.rotate(-28f, 0f, 0f, 1f)
            b.color(if (k % 2 == 0) PALM else PALM_LIGHT, sway = 1.0f)
            b.flatTri(0f, 0f, -0.07f, 1.05f, -0.28f, 0f, 0f, 0.04f, 0.07f)
            b.flatTri(0f, 0.04f, 0.07f, 1.05f, -0.28f, 0f, 0f, 0f, -0.07f)
            b.pop()
        }
        b.color(TRUNK); b.at(crownX, top - 0.05f, 0f) { sphere(0.1f, 3, 5) }
        b.pop()
    }

    private fun pine(b: MeshBuilder, x: Float, z: Float, size: Float) {
        val y = height(x, z)
        b.push(); b.translate(x, y, z); b.scale(size, size, size)
        b.color(TRUNK); b.cylinder(0.09f, 0.07f, 0.45f, 5)
        for (i in 0 until 3) {
            b.color(if (i % 2 == 0) 0x2F7D46 else 0x3A9152, sway = 0.5f)
            b.at(0f, 0.35f + i * 0.38f, 0f) { cylinder(0.55f - i * 0.13f, 0f, 0.7f, 7) }
        }
        b.pop()
    }

    private fun rock(b: MeshBuilder, x: Float, z: Float, s: Float) {
        val y = height(x, z)
        b.color(ROCK); b.at(x, y + s * 0.2f, z) { scale(1.2f, 0.75f, 1f); sphere(s, 3, 6) }
        b.color(ROCK_DARK); b.at(x + s * 0.9f, y + s * 0.1f, z + s * 0.4f) { sphere(s * 0.55f, 3, 5) }
    }

    private fun bush(b: MeshBuilder, x: Float, z: Float, s: Float) {
        val y = height(x, z)
        b.color(BUSH, sway = 0.2f)
        b.at(x, y + s * 0.5f, z) { sphere(s, 3, 7) }
        b.color(GRASS_DARK, sway = 0.2f)
        b.at(x + s * 0.8f, y + s * 0.35f, z + s * 0.2f) { sphere(s * 0.7f, 3, 6) }
    }

    private fun flowers(b: MeshBuilder, rnd: Random, cx: Float, cz: Float) {
        repeat(9) {
            val x = cx + rnd.nextFloat() * 1.4f - 0.7f
            val z = cz + rnd.nextFloat() * 0.9f - 0.45f
            val y = height(x, z)
            b.color(0x3F8F3A); b.at(x, y, z) { cylinder(0.015f, 0.015f, 0.14f, 3, capBottom = false) }
            b.color(if (it % 2 == 0) FLOWER_A else FLOWER_B); b.at(x, y + 0.16f, z) { sphere(0.05f, 2, 4) }
        }
    }

    private fun tent(b: MeshBuilder, x: Float, z: Float) {
        val y = height(x, z)
        b.push(); b.translate(x, y, z); b.rotate(25f, 0f, 1f, 0f)
        b.color(CLOTH); b.gableRoof(1.1f, 0.9f, 0.75f)
        b.color(WOOD_DARK); b.at(0.6f, 0f, 0f) { cylinder(0.03f, 0.03f, 0.85f, 4) }
        // campfire
        b.color(WOOD_DARK); b.at(0f, 0.04f, 0.95f) { rotate(90f, 0f, 0f, 1f); translate(-0.04f, -0.2f, 0f); cylinder(0.04f, 0.04f, 0.4f, 4) }
        b.color(0xFF8A2A); b.at(0f, 0.04f, 0.95f) { cylinder(0.12f, 0f, 0.3f, 5) }
        b.color(0xFFD45A); b.at(0f, 0.05f, 0.95f) { cylinder(0.06f, 0f, 0.2f, 5) }
        b.pop()
    }

    private fun hut(b: MeshBuilder, x: Float, z: Float) {
        val y = height(x, z)
        b.push(); b.translate(x, y, z); b.rotate(-15f, 0f, 1f, 0f)
        b.color(WOOD); b.at(0f, 0.32f, 0f) { box(0.95f, 0.64f, 0.8f) }
        b.color(WOOD_DARK); b.at(0f, 0.22f, 0.405f) { box(0.24f, 0.44f, 0.02f) }
        b.color(THATCH); b.at(0f, 0.64f, 0f) { pyramid(1.35f, 1.2f, 0.62f) }
        b.pop()
    }

    private fun house(b: MeshBuilder, x: Float, z: Float) {
        val y = height(x, z)
        b.push(); b.translate(x, y, z); b.rotate(10f, 0f, 1f, 0f)
        b.color(WALL); b.at(0f, 0.45f, 0f) { box(1.4f, 0.9f, 0.95f) }
        b.color(ROOF); b.at(0f, 0.9f, 0f) { gableRoof(1.6f, 1.15f, 0.6f) }
        b.color(0x8C3B2C); b.at(0.45f, 1.0f, -0.2f) { box(0.18f, 0.55f, 0.18f) }
        b.color(TEAL); b.at(0f, 0.3f, 0.48f) { box(0.28f, 0.6f, 0.03f) }
        b.color(GLASS); b.at(-0.45f, 0.55f, 0.48f) { box(0.26f, 0.26f, 0.03f) }
        b.color(GLASS); b.at(0.45f, 0.55f, 0.48f) { box(0.26f, 0.26f, 0.03f) }
        b.pop()
    }

    private fun lighthouse(b: MeshBuilder, x: Float, z: Float) {
        val y = height(x, z)
        b.push(); b.translate(x, y, z)
        b.color(STONE); b.cylinder(0.55f, 0.5f, 0.25f, 10)
        val bands = 5
        for (i in 0 until bands) {
            b.color(if (i % 2 == 0) WHITE else RED)
            b.at(0f, 0.25f + i * 0.42f, 0f) { cylinder(0.42f - i * 0.04f, 0.38f - i * 0.04f, 0.42f, 10, capBottom = false) }
        }
        val top = 0.25f + bands * 0.42f
        b.color(0x3B3F4A); b.at(0f, top, 0f) { cylinder(0.32f, 0.32f, 0.06f, 10) }
        b.color(GLASS); b.at(0f, top + 0.06f, 0f) { cylinder(0.2f, 0.2f, 0.3f, 8) }
        b.color(RED); b.at(0f, top + 0.36f, 0f) { cylinder(0.26f, 0f, 0.32f, 8) }
        b.pop()
    }

    private fun dock(b: MeshBuilder) {
        val a = 40f
        b.push(); b.rotate(-a, 0f, 1f, 0f); b.translate(RADIUS * 0.82f, 0f, 0f)
        b.color(WOOD); b.at(0.9f, 0.16f, 0f) { box(2.0f, 0.06f, 0.55f) }
        b.color(WOOD_DARK)
        for (i in 0..3) {
            b.at(i * 0.6f, -0.4f, 0.25f) { cylinder(0.05f, 0.05f, 0.6f, 5) }
            b.at(i * 0.6f, -0.4f, -0.25f) { cylinder(0.05f, 0.05f, 0.6f, 5) }
        }
        b.pop()
    }

    private fun boat(b: MeshBuilder) {
        b.color(0xB5523B)
        b.push(); b.scale(1f, 0.6f, 0.5f)
        b.flatQuad(floatArrayOf(-0.6f, 0f, 0.4f, 0.6f, 0f, 0.4f, 0.75f, 0.35f, 0.5f, -0.75f, 0.35f, 0.5f))
        b.flatQuad(floatArrayOf(0.6f, 0f, -0.4f, -0.6f, 0f, -0.4f, -0.75f, 0.35f, -0.5f, 0.75f, 0.35f, -0.5f))
        b.flatQuad(floatArrayOf(-0.6f, 0f, -0.4f, 0.6f, 0f, -0.4f, 0.6f, 0f, 0.4f, -0.6f, 0f, 0.4f))
        b.flatQuad(floatArrayOf(0.6f, 0f, 0.4f, 0.6f, 0f, -0.4f, 0.75f, 0.35f, -0.5f, 0.75f, 0.35f, 0.5f))
        b.flatQuad(floatArrayOf(-0.6f, 0f, -0.4f, -0.6f, 0f, 0.4f, -0.75f, 0.35f, 0.5f, -0.75f, 0.35f, -0.5f))
        b.pop()
        b.color(WOOD_DARK); b.at(0f, 0.15f, 0f) { cylinder(0.03f, 0.03f, 1.1f, 4) }
        b.color(WHITE, sway = 0.3f)
        b.flatTri(0.04f, 0.3f, 0f, 0.04f, 1.2f, 0f, 0.6f, 0.35f, 0f)
        b.flatTri(0.04f, 0.3f, 0f, 0.6f, 0.35f, 0f, 0.04f, 1.2f, 0f)
    }

    private fun well(b: MeshBuilder, x: Float, z: Float) {
        val y = height(x, z)
        b.push(); b.translate(x, y, z)
        b.color(STONE); b.cylinder(0.32f, 0.32f, 0.35f, 8)
        b.color(0x2E5E86); b.at(0f, 0.34f, 0f) { cylinder(0.25f, 0.25f, 0.01f, 8) }
        b.color(WOOD_DARK); b.at(0.28f, 0.3f, 0f) { cylinder(0.03f, 0.03f, 0.5f, 4) }
        b.at(-0.28f, 0.3f, 0f) { cylinder(0.03f, 0.03f, 0.5f, 4) }
        b.color(ROOF); b.at(0f, 0.8f, 0f) { gableRoof(0.75f, 0.55f, 0.3f) }
        b.pop()
    }

    private fun fence(b: MeshBuilder, x: Float, z: Float, posts: Int) {
        b.color(WOOD)
        for (i in 0 until posts) {
            val px = x + 0.05f * i
            val pz = z + i * 0.38f
            val y = height(px, pz)
            b.at(px, y, pz) { box(0.06f, 0.5f, 0.06f) }
            if (i < posts - 1) {
                val y2 = height(px, pz + 0.19f)
                b.at(px, y2 + 0.32f, pz + 0.19f) { box(0.04f, 0.05f, 0.4f) }
                b.at(px, y2 + 0.15f, pz + 0.19f) { box(0.04f, 0.05f, 0.4f) }
            }
        }
    }

    /** Returns the hub height for the sails. */
    private fun windmillTower(b: MeshBuilder, x: Float, z: Float): Float {
        val y = height(x, z)
        b.push(); b.translate(x, y, z)
        b.color(WALL); b.cylinder(0.5f, 0.36f, 1.7f, 8)
        b.color(ROOF); b.at(0f, 1.7f, 0f) { cylinder(0.42f, 0f, 0.5f, 8) }
        b.color(WOOD_DARK); b.at(0f, 0.25f, 0.44f) { box(0.25f, 0.45f, 0.06f) }
        b.pop()
        return y + 1.55f
    }

    private fun windmillSails(b: MeshBuilder) {
        // Built around the hub at the origin, facing +Z; the renderer spins it about Z.
        b.color(WOOD_DARK); b.at(0f, 0f, 0.2f) { box(0.16f, 0.16f, 0.4f) }
        for (k in 0 until 4) {
            b.push(); b.translate(0f, 0f, 0.42f); b.rotate(k * 90f, 0f, 0f, 1f)
            b.color(WOOD_DARK); b.at(0f, 0.6f, 0f) { box(0.05f, 1.2f, 0.04f) }
            b.color(WHITE); b.at(0.13f, 0.72f, 0f) { box(0.22f, 0.85f, 0.02f) }
            b.pop()
        }
    }

    private fun minaret(b: MeshBuilder, x: Float, z: Float) {
        val y = height(x, z)
        b.push(); b.translate(x, y, z)
        b.color(STONE); b.at(0f, 0.35f, 0f) { box(1.0f, 0.7f, 1.0f) }
        b.color(DOME); b.at(0f, 0.7f, 0f) { sphere(0.45f, 4, 10, 0.9f) }
        b.color(GLASS); b.at(0f, 1.15f, 0f) { cylinder(0.03f, 0.03f, 0.25f, 4) }
        b.color(STONE); b.at(0.65f, 0f, -0.55f) { cylinder(0.17f, 0.15f, 2.2f, 8) }
        b.color(0xC9B892); b.at(0.65f, 1.55f, -0.55f) { cylinder(0.24f, 0.24f, 0.08f, 8) }
        b.color(DOME); b.at(0.65f, 2.2f, -0.55f) { cylinder(0.16f, 0f, 0.35f, 8) }
        b.pop()
    }

    private fun bunting(b: MeshBuilder) {
        val colors = intArrayOf(RED, FLOWER_B, TEAL, WHITE, FLOWER_A)
        for (i in 0 until 10) {
            val a = (i / 10f) * 2f * PI.toFloat() * 0.45f + 2.4f
            val r = RADIUS * 0.62f
            val x = r * cos(a); val z = r * sin(a)
            val y = height(x, z) + 1.0f + 0.12f * sin(i * 1.3f)
            b.color(colors[i % colors.size], sway = 0.8f)
            b.flatTri(x - 0.12f, y, z, x + 0.12f, y, z, x, y - 0.22f, z)
            b.flatTri(x + 0.12f, y, z, x - 0.12f, y, z, x, y - 0.22f, z)
        }
    }

    private fun balloon(b: MeshBuilder) {
        val bands = intArrayOf(RED, FLOWER_B, TEAL, WHITE)
        for (i in 0 until 8) {
            b.color(bands[i % bands.size])
            b.push(); b.rotate(i * 45f, 0f, 1f, 0f)
            b.flatTri(0f, 1.15f, 0f, 0.6f, 0.45f, -0.25f, 0.6f, 0.45f, 0.25f)
            b.flatTri(0.6f, 0.45f, -0.25f, 0.18f, -0.25f, -0.07f, 0.18f, -0.25f, 0.07f)
            b.flatTri(0.6f, 0.45f, -0.25f, 0.18f, -0.25f, 0.07f, 0.6f, 0.45f, 0.25f)
            b.pop()
        }
        b.color(WOOD); b.at(0f, -0.6f, 0f) { box(0.28f, 0.2f, 0.28f) }
        b.color(WOOD_DARK)
        b.at(0.12f, -0.5f, 0.12f) { cylinder(0.01f, 0.01f, 0.3f, 3) }
        b.at(-0.12f, -0.5f, -0.12f) { cylinder(0.01f, 0.01f, 0.3f, 3) }
    }
}
