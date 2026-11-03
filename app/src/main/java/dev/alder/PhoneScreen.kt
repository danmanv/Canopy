package dev.alder

import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * A screen made for a phone held upright: drawn at the phone's own size, with plain taps (no remote focus), thumb-sized
 * buttons and its own back arrow where it wants one.
 */
abstract class PhoneScreen : Screen() {
    override val phoneLayout get() = true
    override val backArrow get() = false

    protected fun iconButton(icon: Int, label: String, click: () -> Unit) = ImageView(this).apply {
        setImageResource(icon)
        setColorFilter(TEXT)
        scaleType = ImageView.ScaleType.CENTER
        contentDescription = label
        setOnClickListener { click() }
    }

    /** A soft outlined square, for a button that isn't the main one. */
    protected fun outlined() = GradientDrawable().apply {
        setColor(withAlpha(TEXT, 0x14)); setStroke(dp(1), withAlpha(TEXT, 0x40)); cornerRadius = dp(CORNER).toFloat()
    }

    /** A wide button: [primary] is the page's main action, filled in the accent with a play mark. */
    protected fun button(label: String, primary: Boolean, click: () -> Unit) = LinearLayout(this).apply {
        gravity = Gravity.CENTER
        background = if (primary) rounded(TUNGSTEN, CORNER) else outlined()
        setPadding(dp(12), 0, dp(12), 0)
        if (primary) addView(ImageView(context).apply { setImageResource(R.drawable.ic_play); setColorFilter(BASE) },
            LinearLayout.LayoutParams(dp(20), dp(20)).apply { rightMargin = dp(8) })
        addView(text(label, 16f, bold = true, color = if (primary) BASE else TEXT).apply { maxLines = 1 })
        setOnClickListener { click() }
    }

    protected fun setLabel(button: LinearLayout, label: String) {
        (button.getChildAt(button.childCount - 1) as TextView).text = label
    }

    /** An icon over its name, as in the mockup's row of actions under Resume. */
    protected fun action(icon: Int, label: String, click: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        contentDescription = label
        addView(ImageView(context).apply { setImageResource(icon); setColorFilter(TEXT) }, LinearLayout.LayoutParams(dp(24), dp(24)))
        addView(text(label, 12f, bold = true).apply { maxLines = 1; gravity = Gravity.CENTER }, wrap().apply { topMargin = dp(6) })
        setOnClickListener { click() }
    }

    protected fun setAction(action: LinearLayout, icon: Int, label: String) {
        (action.getChildAt(0) as ImageView).setImageResource(icon)
        (action.getChildAt(1) as TextView).text = label
        action.contentDescription = label
    }

    companion object {
        const val TOUCH = 48 // dp: the smallest thing a thumb is asked to hit
        const val BIG_BUTTON = 52
        const val BODY_SP = 15f
        const val SMALL_SP = 13f
        const val DOT_SHORT = " · "
    }
}
