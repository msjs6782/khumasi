package com.khumasi.game.render

import android.opengl.GLES30
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** GPU mesh: interleaved position(3) normal(3) color(4), 32-bit indices, drawn as triangles. */
internal class Mesh(vertices: FloatArray, indices: IntArray) {
    private val vao = IntArray(1)
    private val buffers = IntArray(2)
    val indexCount = indices.size

    init {
        GLES30.glGenVertexArrays(1, vao, 0)
        GLES30.glGenBuffers(2, buffers, 0)
        GLES30.glBindVertexArray(vao[0])

        val vb = ByteBuffer.allocateDirect(vertices.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        vb.put(vertices).position(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buffers[0])
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, vertices.size * 4, vb, GLES30.GL_STATIC_DRAW)

        val ib = ByteBuffer.allocateDirect(indices.size * 4).order(ByteOrder.nativeOrder()).asIntBuffer()
        ib.put(indices).position(0)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, buffers[1])
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, indices.size * 4, ib, GLES30.GL_STATIC_DRAW)

        val stride = STRIDE_FLOATS * 4
        GLES30.glEnableVertexAttribArray(ATTR_POSITION)
        GLES30.glVertexAttribPointer(ATTR_POSITION, 3, GLES30.GL_FLOAT, false, stride, 0)
        GLES30.glEnableVertexAttribArray(ATTR_NORMAL)
        GLES30.glVertexAttribPointer(ATTR_NORMAL, 3, GLES30.GL_FLOAT, false, stride, 12)
        GLES30.glEnableVertexAttribArray(ATTR_COLOR)
        GLES30.glVertexAttribPointer(ATTR_COLOR, 4, GLES30.GL_FLOAT, false, stride, 24)
        GLES30.glBindVertexArray(0)
    }

    fun draw() {
        if (indexCount == 0) return
        GLES30.glBindVertexArray(vao[0])
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, indexCount, GLES30.GL_UNSIGNED_INT, 0)
        GLES30.glBindVertexArray(0)
    }

    fun release() {
        GLES30.glDeleteBuffers(2, buffers, 0)
        GLES30.glDeleteVertexArrays(1, vao, 0)
    }

    companion object {
        const val ATTR_POSITION = 0
        const val ATTR_NORMAL = 1
        const val ATTR_COLOR = 2
        const val STRIDE_FLOATS = 10
    }
}

/**
 * Builds meshes on the CPU. Shapes are appended through a transform stack. Low-poly shapes use
 * flat shading (one normal per face); the letter tile uses smooth normals for its rounded edges.
 * Color alpha is free for shaders to use (the island uses it as "sway" weight for wind).
 */
internal class MeshBuilder {
    private var verts = FloatArray(4096)
    private var vCount = 0
    private var idx = IntArray(4096)
    private var iCount = 0

    private val stack = ArrayList<FloatArray>()
    private var m = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private val tmp = FloatArray(16)
    private var normalDirty = true

    var r = 1f; var g = 1f; var b = 1f; var a = 0f

    val vertexCount: Int get() = vCount

    fun color(hex: Int, sway: Float = 0f): MeshBuilder {
        r = ((hex shr 16) and 0xFF) / 255f
        g = ((hex shr 8) and 0xFF) / 255f
        b = (hex and 0xFF) / 255f
        a = sway
        return this
    }

    fun push() { stack.add(m.copyOf()) }
    fun pop() { m = stack.removeAt(stack.size - 1); normalDirty = true }
    fun translate(x: Float, y: Float, z: Float) { Matrix.translateM(m, 0, x, y, z) }
    fun rotate(deg: Float, x: Float, y: Float, z: Float) { Matrix.rotateM(m, 0, deg, x, y, z); normalDirty = true }
    fun scale(x: Float, y: Float, z: Float) { Matrix.scaleM(m, 0, x, y, z); normalDirty = true }

    inline fun at(x: Float, y: Float, z: Float, block: MeshBuilder.() -> Unit) {
        push(); translate(x, y, z); block(); pop()
    }

    fun vertex(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float): Int {
        if ((vCount + 1) * Mesh.STRIDE_FLOATS > verts.size) verts = verts.copyOf(verts.size * 2)
        val px = m[0] * x + m[4] * y + m[8] * z + m[12]
        val py = m[1] * x + m[5] * y + m[9] * z + m[13]
        val pz = m[2] * x + m[6] * y + m[10] * z + m[14]
        // Normal via inverse-transpose of the upper 3x3 (handles non-uniform scale).
        if (normalDirty) { Matrix.invertM(tmp, 0, m, 0); normalDirty = false }
        var qx = tmp[0] * nx + tmp[1] * ny + tmp[2] * nz
        var qy = tmp[4] * nx + tmp[5] * ny + tmp[6] * nz
        var qz = tmp[8] * nx + tmp[9] * ny + tmp[10] * nz
        val len = sqrt(qx * qx + qy * qy + qz * qz).takeIf { it > 1e-6f } ?: 1f
        qx /= len; qy /= len; qz /= len
        val o = vCount * Mesh.STRIDE_FLOATS
        verts[o] = px; verts[o + 1] = py; verts[o + 2] = pz
        verts[o + 3] = qx; verts[o + 4] = qy; verts[o + 5] = qz
        verts[o + 6] = r; verts[o + 7] = g; verts[o + 8] = b; verts[o + 9] = a
        return vCount++
    }

    fun tri(i0: Int, i1: Int, i2: Int) {
        if (iCount + 3 > idx.size) idx = idx.copyOf(idx.size * 2)
        idx[iCount++] = i0; idx[iCount++] = i1; idx[iCount++] = i2
    }

    /** Flat triangle with its own face normal (counter-clockwise = front). */
    fun flatTri(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, cx: Float, cy: Float, cz: Float) {
        val ux = bx - ax; val uy = by - ay; val uz = bz - az
        val vx = cx - ax; val vy = cy - ay; val vz = cz - az
        var nx = uy * vz - uz * vy; var ny = uz * vx - ux * vz; var nz = ux * vy - uy * vx
        val len = sqrt(nx * nx + ny * ny + nz * nz)
        if (len < 1e-9f) return
        nx /= len; ny /= len; nz /= len
        val i0 = vertex(ax, ay, az, nx, ny, nz)
        val i1 = vertex(bx, by, bz, nx, ny, nz)
        val i2 = vertex(cx, cy, cz, nx, ny, nz)
        tri(i0, i1, i2)
    }

    fun flatQuad(p: FloatArray) { // 4 corners x,y,z counter-clockwise
        flatTri(p[0], p[1], p[2], p[3], p[4], p[5], p[6], p[7], p[8])
        flatTri(p[0], p[1], p[2], p[6], p[7], p[8], p[9], p[10], p[11])
    }

    /** Axis-aligned flat-shaded box centered at the origin. */
    fun box(w: Float, h: Float, d: Float) {
        val x = w / 2; val y = h / 2; val z = d / 2
        flatQuad(floatArrayOf(-x, -y, z, x, -y, z, x, y, z, -x, y, z))      // +Z
        flatQuad(floatArrayOf(x, -y, -z, -x, -y, -z, -x, y, -z, x, y, -z))  // -Z
        flatQuad(floatArrayOf(x, -y, z, x, -y, -z, x, y, -z, x, y, z))      // +X
        flatQuad(floatArrayOf(-x, -y, -z, -x, -y, z, -x, y, z, -x, y, -z))  // -X
        flatQuad(floatArrayOf(-x, y, z, x, y, z, x, y, -z, -x, y, -z))      // +Y
        flatQuad(floatArrayOf(-x, -y, -z, x, -y, -z, x, -y, z, -x, -y, z))  // -Y
    }

    /** Flat-shaded frustum along +Y from y=0 to y=h. rTop may be 0 (cone). */
    fun cylinder(rBottom: Float, rTop: Float, h: Float, segments: Int, capBottom: Boolean = true, capTop: Boolean = true) {
        for (i in 0 until segments) {
            val a0 = (2 * PI * i / segments).toFloat()
            val a1 = (2 * PI * (i + 1) / segments).toFloat()
            val c0 = cos(a0); val s0 = sin(a0); val c1 = cos(a1); val s1 = sin(a1)
            if (rTop > 0f) {
                // bottom0, top0, top1, bottom1 is counter-clockwise seen from outside
                flatQuad(floatArrayOf(rBottom * c0, 0f, rBottom * s0, rTop * c0, h, rTop * s0, rTop * c1, h, rTop * s1, rBottom * c1, 0f, rBottom * s1))
            } else {
                flatTri(rBottom * c0, 0f, rBottom * s0, 0f, h, 0f, rBottom * c1, 0f, rBottom * s1)
            }
            if (capBottom) flatTri(0f, 0f, 0f, rBottom * c0, 0f, rBottom * s0, rBottom * c1, 0f, rBottom * s1)
            if (capTop && rTop > 0f) flatTri(0f, h, 0f, rTop * c1, h, rTop * s1, rTop * c0, h, rTop * s0)
        }
    }

    /** Low-poly flat-shaded sphere centered at the origin. */
    fun sphere(radius: Float, rings: Int, segments: Int, squashY: Float = 1f) {
        fun p(ring: Int, seg: Int): FloatArray {
            val theta = PI * ring / rings
            val phi = 2 * PI * seg / segments
            return floatArrayOf(
                (radius * sin(theta) * cos(phi)).toFloat(),
                (radius * cos(theta) * squashY).toFloat(),
                (radius * sin(theta) * sin(phi)).toFloat(),
            )
        }
        for (ring in 0 until rings) for (seg in 0 until segments) {
            val a = p(ring, seg); val b = p(ring + 1, seg); val c = p(ring + 1, seg + 1); val d = p(ring, seg + 1)
            if (ring != 0) flatTri(a[0], a[1], a[2], d[0], d[1], d[2], b[0], b[1], b[2])
            if (ring != rings - 1) flatTri(b[0], b[1], b[2], d[0], d[1], d[2], c[0], c[1], c[2])
        }
    }

    /** Square pyramid roof, base centered at origin on y=0. */
    fun pyramid(w: Float, d: Float, h: Float) {
        val x = w / 2; val z = d / 2
        flatTri(-x, 0f, z, x, 0f, z, 0f, h, 0f)
        flatTri(x, 0f, z, x, 0f, -z, 0f, h, 0f)
        flatTri(x, 0f, -z, -x, 0f, -z, 0f, h, 0f)
        flatTri(-x, 0f, -z, -x, 0f, z, 0f, h, 0f)
    }

    /** Gable roof along X: base w x d at y=0, ridge at height h. */
    fun gableRoof(w: Float, d: Float, h: Float) {
        val x = w / 2; val z = d / 2
        flatQuad(floatArrayOf(-x, 0f, z, x, 0f, z, x, h, 0f, -x, h, 0f))
        flatQuad(floatArrayOf(x, 0f, -z, -x, 0f, -z, -x, h, 0f, x, h, 0f))
        flatTri(x, 0f, z, x, 0f, -z, x, h, 0f)
        flatTri(-x, 0f, -z, -x, 0f, z, -x, h, 0f)
    }

    /**
     * Box with rounded edges and smooth normals (the letter tile). Built by projecting a cube
     * grid onto an inner box plus radius, with more grid lines in the bevel for a smooth curve.
     */
    fun roundedBox(w: Float, h: Float, d: Float, radius: Float, bevelSegments: Int) {
        val half = floatArrayOf(w / 2, h / 2, d / 2)
        val inner = floatArrayOf(w / 2 - radius, h / 2 - radius, d / 2 - radius)
        fun samples(axis: Int): FloatArray {
            val list = ArrayList<Float>()
            val hI = inner[axis]
            for (k in bevelSegments downTo 0) list.add(-hI - radius * tan(PI / 4 * k / bevelSegments).toFloat())
            for (k in 0..bevelSegments) list.add(hI + radius * tan(PI / 4 * k / bevelSegments).toFloat())
            return list.toFloatArray()
        }
        // (normal axis, sign, u axis, v axis) chosen so u x v points outward.
        val faces = arrayOf(
            intArrayOf(2, 1, 0, 1), intArrayOf(2, -1, 1, 0),
            intArrayOf(0, 1, 1, 2), intArrayOf(0, -1, 2, 1),
            intArrayOf(1, 1, 2, 0), intArrayOf(1, -1, 0, 2),
        )
        val p = FloatArray(3)
        for (f in faces) {
            val nAxis = f[0]; val sign = f[1]; val uAxis = f[2]; val vAxis = f[3]
            val us = samples(uAxis); val vs = samples(vAxis)
            val base = vCount
            for (j in vs.indices) for (i in us.indices) {
                p[nAxis] = sign * half[nAxis]; p[uAxis] = us[i]; p[vAxis] = vs[j]
                val qx = p[0].coerceIn(-inner[0], inner[0])
                val qy = p[1].coerceIn(-inner[1], inner[1])
                val qz = p[2].coerceIn(-inner[2], inner[2])
                var dx = p[0] - qx; var dy = p[1] - qy; var dz = p[2] - qz
                val len = sqrt(dx * dx + dy * dy + dz * dz)
                dx /= len; dy /= len; dz /= len
                vertex(qx + dx * radius, qy + dy * radius, qz + dz * radius, dx, dy, dz)
            }
            val cols = us.size
            for (j in 0 until vs.size - 1) for (i in 0 until cols - 1) {
                val a0 = base + j * cols + i
                val a1 = a0 + 1
                val a2 = a0 + cols + 1
                val a3 = a0 + cols
                tri(a0, a1, a2); tri(a0, a2, a3)
            }
        }
    }

    /** Smooth-shaded flat panel in the XY plane facing +Z (used for full-screen and sky quads). */
    fun quadXY(w: Float, h: Float) {
        val x = w / 2; val y = h / 2
        val i0 = vertex(-x, -y, 0f, 0f, 0f, 1f)
        val i1 = vertex(x, -y, 0f, 0f, 0f, 1f)
        val i2 = vertex(x, y, 0f, 0f, 0f, 1f)
        val i3 = vertex(-x, y, 0f, 0f, 0f, 1f)
        tri(i0, i1, i2); tri(i0, i2, i3)
    }

    /** Flat disc in the XZ plane facing +Y, as rings (the sea). */
    fun disc(radius: Float, rings: Int, segments: Int) {
        val center = vertex(0f, 0f, 0f, 0f, 1f, 0f)
        var prevStart = -1
        for (ring in 1..rings) {
            val rr = radius * (ring.toFloat() / rings).let { it * it }
            val start = vCount
            for (s in 0 until segments) {
                val a = 2 * PI * s / segments
                vertex((rr * cos(a)).toFloat(), 0f, (rr * sin(a)).toFloat(), 0f, 1f, 0f)
            }
            for (s in 0 until segments) {
                val n = (s + 1) % segments
                if (prevStart < 0) tri(center, start + n, start + s)
                else {
                    tri(prevStart + s, prevStart + n, start + n)
                    tri(prevStart + s, start + n, start + s)
                }
            }
            prevStart = start
        }
    }

    fun angleOf(x: Float, z: Float): Float = atan2(z, x)

    fun build(): Mesh = Mesh(verts.copyOf(vCount * Mesh.STRIDE_FLOATS), idx.copyOf(iCount))
}
