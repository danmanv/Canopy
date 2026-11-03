package dev.alder

import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager

/**
 * Taps and swipes on a phone, for screens built for a remote. A tap first moves the focus to what was tapped, as the
 * remote would have, so everything a screen does when something takes the focus (the hero, a lit row, a page opening)
 * still happens; then the tapped view's own click runs as usual. A tap on nothing that takes focus goes to [bare], and a
 * fling to [swipe] (true when it used it).
 */
internal class Taps(
    context: Context,
    private val root: () -> View?,
    private val bare: (MotionEvent) -> Unit = {},
    private val swipe: (Float, Float) -> Boolean = { _, _ -> false },
) {
    private val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val target = root()?.let { focusableAt(it, e.rawX.toInt(), e.rawY.toInt()) }
            when {
                target == null -> bare(e)
                // Focusable in touch mode, so the view's own release finds it focused and clicks, instead of taking
                // the focus and stopping there (a second tap).
                !target.isFocused -> { target.isFocusableInTouchMode = true; target.requestFocus() }
            }
            return false
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float) = swipe(velocityX, velocityY)
    }).apply { setOnDoubleTapListener(null) } // every tap counts at once: quick taps on two letters are two letters

    fun feed(event: MotionEvent) { detector.onTouchEvent(event) }

    /** Wraps [window]'s touch handling (a dialog's) so its taps move the focus too. */
    fun into(window: Window) {
        val inner = window.callback
        window.callback = object : Window.Callback by inner {
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                feed(event)
                return inner.dispatchTouchEvent(event)
            }
        }
    }

    private companion object {
        private val at = IntArray(2)

        /** The top-most view under the finger that takes the remote's focus, the way touches are handed out: last child first. */
        fun focusableAt(view: View, x: Int, y: Int): View? {
            if (view.visibility != View.VISIBLE || view.alpha <= 0.01f || !view.isAttachedToWindow) return null
            view.getLocationOnScreen(at)
            if (x < at[0] || y < at[1] || x >= at[0] + view.width || y >= at[1] + view.height) return null
            if (view is ViewGroup) for (i in view.childCount - 1 downTo 0) focusableAt(view.getChildAt(i), x, y)?.let { return it }
            return view.takeIf { it.isFocusable && it.isEnabled }
        }
    }
}

/** A phone shows the whole screen, as a TV does: no status bar or navigation bar until swiped in from an edge. */
@Suppress("DEPRECATION")
internal fun Activity.fullScreen() {
    if (Build.VERSION.SDK_INT >= 28)
        window.attributes = window.attributes.apply { layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES }
    if (Build.VERSION.SDK_INT >= 30) {
        window.setDecorFitsSystemWindows(false)
        window.insetsController?.apply {
            hide(WindowInsets.Type.systemBars())
            systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    } else window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
    // The camera's notch sits on a short edge when the phone is sideways: the screen's content keeps clear of it, the
    // window's dark background fills the strip.
    if (Build.VERSION.SDK_INT >= 28) findViewById<View>(android.R.id.content).setOnApplyWindowInsetsListener { v, insets ->
        val cut = insets.displayCutout
        v.setPadding(cut?.safeInsetLeft ?: 0, 0, cut?.safeInsetRight ?: 0, 0)
        insets
    }
}

/** A remote's key, pressed and let go, through the window as a real one would come: focus moves, OK clicks. */
internal fun Activity.press(keyCode: Int) {
    val now = SystemClock.uptimeMillis()
    window.injectInputEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
    window.injectInputEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
}
