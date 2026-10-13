package com.khumasi.game.render

import android.opengl.Matrix
import kotlin.random.Random

/** A short burst of paper confetti over the board (board space, fixed seed per burst). */
internal class Confetti(val count: Int) {
    private val x = FloatArray(count); private val y = FloatArray(count); private val z = FloatArray(count)
    private val vx = FloatArray(count); private val vy = FloatArray(count)
    private val spin = FloatArray(count); private val ax = FloatArray(count); private val ay = FloatArray(count)
    private val delay = FloatArray(count)

    fun reset(seed: Long) {
        val r = Random(seed)
        for (i in 0 until count) {
            x[i] = r.nextFloat() * 6f - 3f
            y[i] = 3.2f + r.nextFloat() * 2.2f
            z[i] = 1.2f + r.nextFloat() * 1.6f
            vx[i] = r.nextFloat() * 2.4f - 1.2f
            vy[i] = r.nextFloat() * 3.5f + 1f
            spin[i] = 240f + r.nextFloat() * 520f
            ax[i] = r.nextFloat() * 2f - 1f
            ay[i] = r.nextFloat()
            delay[i] = r.nextFloat() * 0.25f
        }
    }

    /** Writes the model matrix for particle [i] at [tSec]; false when it is not visible. */
    fun pose(i: Int, tSec: Float, out: FloatArray, base: FloatArray): Boolean {
        val t = tSec - delay[i]
        if (t < 0f) return false
        val px = x[i] + vx[i] * t
        val py = y[i] + vy[i] * t - 4.2f * t * t
        if (py < -6f) return false
        System.arraycopy(base, 0, out, 0, 16)
        Matrix.translateM(out, 0, px, py, z[i])
        Matrix.rotateM(out, 0, spin[i] * t, ax[i], ay[i], 0.4f)
        Matrix.scaleM(out, 0, 0.16f, 0.26f, 1f)
        return true
    }

    companion object {
        const val DURATION_MS = 2400L
    }
}
