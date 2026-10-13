package com.khumasi.game.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Bottom sheets over the game (rules, settings, statistics, round result). One at a time;
 * the system back gesture closes the current one.
 */
class SheetHost(context: Context, private val fonts: Fonts) : FrameLayout(context) {

    private var current: View? = null
    private var onClose: (() -> Unit)? = null
    var reduceMotion = false
    var bottomInset = 0

    val isShowing: Boolean get() = current != null

    init {
        visibility = GONE
        layoutDirection = LAYOUT_DIRECTION_RTL
    }

    /**
     * Shows a sheet. [dismissible] = false keeps it open when the scrim is tapped (the round
     * result), though the close button and back gesture still work.
     */
    fun show(
        title: String,
        content: View,
        dismissible: Boolean = true,
        maxHeightFraction: Float = 0.88f,
        onClosed: (() -> Unit)? = null,
    ) {
        removeAllViews()
        onClose = onClosed
        val scrim = View(context).apply {
            setBackgroundColor(UiColors.SCRIM)
            setOnClickListener { if (dismissible) close() }
        }
        addView(scrim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(UiColors.CARD)
                val r = dp(26f)
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
                setStroke(dpi(1f), UiColors.CARD_LINE)
            }
            setPadding(dpi(20f), dpi(10f), dpi(20f), dpi(14f) + bottomInset)
            isClickable = true // swallow taps so they don't reach the scrim
        }
        val handle = View(context).apply { background = rounded(UiColors.CARD_LINE, dp(3f)) }
        card.addView(handle, LinearLayout.LayoutParams(dpi(44f), dpi(5f)).apply {
            gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dpi(8f)
        })
        val head = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = LAYOUT_DIRECTION_RTL
        }
        val titleView: TextView = context.label(title, 22f, fonts.heavy, UiColors.TEXT, Gravity.START or Gravity.CENTER_VERTICAL)
        head.addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val closeBtn = IconView(context, IconView.Kind.CLOSE, "إغلاق").apply { setOnClickListener { close() } }
        head.addView(closeBtn, LinearLayout.LayoutParams(dpi(44f), dpi(44f)))
        card.addView(head)

        val scroll = ScrollView(context).apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = false
            tag = "sheet_scroll"
            addView(content)
        }
        card.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val lp = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
        addView(card, lp)
        // Never taller than the given share of the screen.
        card.post {
            val max = (height * maxHeightFraction).toInt()
            if (card.height > max) {
                card.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, max, Gravity.BOTTOM)
            }
        }
        current = card
        visibility = VISIBLE
        if (!reduceMotion) {
            card.translationY = dp(420f)
            card.animate().translationY(0f).setDuration(260).start()
            scrim.alpha = 0f
            scrim.animate().alpha(1f).setDuration(200).start()
        }
    }

    fun close() {
        val card = current ?: return
        current = null
        val done: () -> Unit = {
            removeAllViews()
            visibility = GONE
            val callback = onClose
            onClose = null
            callback?.invoke()
        }
        if (reduceMotion) done() else card.animate().translationY(card.height.toFloat()).setDuration(180).withEndAction(done).start()
    }
}
