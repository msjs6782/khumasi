package com.khumasi.game.ui

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.TextView

/** The game font (Baloo Bhaijaan 2, SIL OFL) in the weights we use, with a system fallback. */
class Fonts(assets: AssetManager) {
    val regular: Typeface = load(assets, 500, Typeface.DEFAULT)
    val bold: Typeface = load(assets, 700, Typeface.DEFAULT_BOLD)
    val heavy: Typeface = load(assets, 800, Typeface.DEFAULT_BOLD)

    private fun load(assets: AssetManager, weight: Int, fallback: Typeface): Typeface = try {
        Typeface.Builder(assets, "fonts/BalooBhaijaan2.ttf")
            .setFontVariationSettings("'wght' $weight")
            .build() ?: fallback
    } catch (t: Throwable) {
        Log.w("Khumasi", "Font fallback", t)
        fallback
    }
}

fun Context.dp(v: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
fun Context.dpi(v: Float): Int = dp(v).toInt()
fun View.dp(v: Float): Float = context.dp(v)
fun View.dpi(v: Float): Int = context.dpi(v)

fun rounded(color: Int, radiusPx: Float, strokeColor: Int = 0, strokePx: Int = 0): GradientDrawable =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = radiusPx
        if (strokePx > 0) setStroke(strokePx, strokeColor)
    }

fun withAlpha(color: Int, alpha: Float): Int =
    Color.argb((alpha * 255).toInt().coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

fun opaque(rgb: Int): Int = 0xFF000000.toInt() or rgb

/** Arabic-Indic digits for numbers shown in the interface. */
fun ar(n: Int): String = com.khumasi.game.core.ShareText.toArabicDigits(n)

fun Context.label(text: CharSequence, size: Float, typeface: Typeface, color: Int = Color.WHITE, gravity: Int = Gravity.CENTER): TextView =
    TextView(this).apply {
        this.text = text
        textSize = size
        this.typeface = typeface
        setTextColor(color)
        this.gravity = gravity
        includeFontPadding = false
        textDirection = View.TEXT_DIRECTION_RTL
    }

object UiColors {
    val PANEL = Color.parseColor("#F2131D31")
    val CARD = Color.parseColor("#FF18243B")
    val CARD_LINE = Color.parseColor("#33FFFFFF")
    val TEXT = Color.parseColor("#FFF4F1EA")
    val MUTED = Color.parseColor("#FFA9B6CC")
    val ACCENT = Color.parseColor("#FF7FB8FF")
    val PRIMARY = Color.parseColor("#FF3BA466")
    val PRIMARY_TEXT = Color.WHITE
    val KEY = Color.parseColor("#FF2B3B5A")
    val KEY_PRESSED = Color.parseColor("#FF41557D")
    val KEY_ABSENT = Color.parseColor("#FF262D3B")
    val KEY_ABSENT_TEXT = Color.parseColor("#FF79839A")
    val TOAST = Color.parseColor("#F2F7F0E1")
    val TOAST_TEXT = Color.parseColor("#FF24314C")
    val SCRIM = Color.parseColor("#99060B16")
}
