package com.khumasi.game.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Small line icons drawn in code (no image assets), white by default. */
@SuppressLint("ViewConstructor")
class IconView(context: Context, private val kind: Kind, description: String) : View(context) {

    enum class Kind { HELP, STATS, SETTINGS, CLOSE, SHARE, FLAG, NEXT, ISLAND, HINT }

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = UiColors.TEXT
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = UiColors.TEXT }
    private val path = Path()
    private val oval = RectF()

    var tint: Int
        get() = stroke.color
        set(value) { stroke.color = value; fill.color = value; invalidate() }

    init {
        contentDescription = description
        isClickable = true
        isFocusable = true
    }

    override fun onDraw(canvas: Canvas) {
        val s = min(width, height) * 0.5f
        val cx = width / 2f
        val cy = height / 2f
        stroke.strokeWidth = s * 0.13f
        when (kind) {
            Kind.HELP -> {
                canvas.drawCircle(cx, cy, s * 0.72f, stroke)
                // Arabic question mark (opens to the left): arc from the right over the top.
                path.reset()
                oval.set(cx - s * 0.26f, cy - s * 0.44f, cx + s * 0.26f, cy + s * 0.02f)
                path.arcTo(oval, 0f, -270f)
                path.lineTo(cx, cy + s * 0.12f)
                canvas.drawPath(path, stroke)
                canvas.drawCircle(cx, cy + s * 0.36f, s * 0.07f, fill)
            }
            Kind.STATS -> {
                val w = s * 0.24f
                val base = cy + s * 0.6f
                canvas.drawRoundRect(cx - s * 0.62f, cy - s * 0.05f, cx - s * 0.62f + w, base, w / 3, w / 3, fill)
                canvas.drawRoundRect(cx - w / 2, cy - s * 0.6f, cx + w / 2, base, w / 3, w / 3, fill)
                canvas.drawRoundRect(cx + s * 0.62f - w, cy - s * 0.3f, cx + s * 0.62f, base, w / 3, w / 3, fill)
            }
            Kind.SETTINGS -> {
                for (i in 0..2) {
                    val y = cy + (i - 1) * s * 0.48f
                    canvas.drawLine(cx - s * 0.66f, y, cx + s * 0.66f, y, stroke)
                    val kx = cx + (if (i == 1) -0.3f else 0.3f) * s
                    canvas.drawCircle(kx, y, s * 0.17f, fill)
                }
            }
            Kind.CLOSE -> {
                val d = s * 0.42f
                canvas.drawLine(cx - d, cy - d, cx + d, cy + d, stroke)
                canvas.drawLine(cx - d, cy + d, cx + d, cy - d, stroke)
            }
            Kind.SHARE -> {
                val a = floatArrayOf(cx + s * 0.4f, cy - s * 0.45f)
                val b = floatArrayOf(cx - s * 0.45f, cy)
                val c = floatArrayOf(cx + s * 0.4f, cy + s * 0.45f)
                canvas.drawLine(a[0], a[1], b[0], b[1], stroke)
                canvas.drawLine(c[0], c[1], b[0], b[1], stroke)
                for (p in arrayOf(a, b, c)) canvas.drawCircle(p[0], p[1], s * 0.17f, fill)
            }
            Kind.FLAG -> {
                canvas.drawLine(cx - s * 0.45f, cy - s * 0.6f, cx - s * 0.45f, cy + s * 0.65f, stroke)
                path.reset()
                path.moveTo(cx - s * 0.45f, cy - s * 0.55f)
                path.lineTo(cx + s * 0.55f, cy - s * 0.3f)
                path.lineTo(cx - s * 0.45f, cy + s * 0.05f)
                path.close()
                canvas.drawPath(path, fill)
            }
            Kind.HINT -> {
                // light bulb
                canvas.drawCircle(cx, cy - s * 0.15f, s * 0.42f, stroke)
                canvas.drawLine(cx - s * 0.18f, cy + s * 0.42f, cx + s * 0.18f, cy + s * 0.42f, stroke)
                canvas.drawLine(cx - s * 0.12f, cy + s * 0.62f, cx + s * 0.12f, cy + s * 0.62f, stroke)
                canvas.drawLine(cx - s * 0.16f, cy + s * 0.22f, cx - s * 0.18f, cy + s * 0.42f, stroke)
                canvas.drawLine(cx + s * 0.16f, cy + s * 0.22f, cx + s * 0.18f, cy + s * 0.42f, stroke)
            }
            Kind.ISLAND -> {
                // island with a palm
                oval.set(cx - s * 0.7f, cy + s * 0.18f, cx + s * 0.7f, cy + s * 0.62f)
                canvas.drawOval(oval, fill)
                path.reset()
                path.moveTo(cx - s * 0.05f, cy + s * 0.3f)
                path.quadTo(cx + s * 0.05f, cy - s * 0.15f, cx + s * 0.18f, cy - s * 0.42f)
                canvas.drawPath(path, stroke)
                val tx = cx + s * 0.18f; val ty = cy - s * 0.42f
                canvas.drawLine(tx, ty, tx - s * 0.42f, ty + s * 0.1f, stroke)
                canvas.drawLine(tx, ty, tx + s * 0.38f, ty + s * 0.16f, stroke)
                canvas.drawLine(tx, ty, tx - s * 0.12f, ty - s * 0.3f, stroke)
                canvas.drawLine(tx, ty, tx + s * 0.28f, ty - s * 0.22f, stroke)
            }
            Kind.NEXT -> {
                // circular arrow (new word)
                oval.set(cx - s * 0.55f, cy - s * 0.55f, cx + s * 0.55f, cy + s * 0.55f)
                canvas.drawArc(oval, -60f, 290f, false, stroke)
                val ang = Math.toRadians(-60.0)
                val ex = cx + s * 0.55f * cos(ang).toFloat()
                val ey = cy + s * 0.55f * sin(ang).toFloat()
                path.reset()
                path.moveTo(ex + s * 0.05f, ey - s * 0.32f)
                path.lineTo(ex + s * 0.08f, ey + s * 0.08f)
                path.lineTo(ex - s * 0.3f, ey + s * 0.02f)
                canvas.drawPath(path, stroke)
            }
        }
    }
}
