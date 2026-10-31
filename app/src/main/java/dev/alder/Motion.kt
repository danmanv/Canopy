package dev.alder

import android.view.animation.DecelerateInterpolator
import android.view.animation.Interpolator

/**
 * Canopy's three speeds and one curve, so everything that moves keeps the same rhythm: focus is quick, panels and
 * pages are standard, art is slow. Everything eases out (fast start, soft landing).
 */
object Motion {
    /** Focus, small state changes. */
    const val QUICK = 150L
    /** Panels, pages, overlays coming and going. */
    const val STANDARD = 250L
    /** Full-screen art and ambient fades. */
    const val SLOW = 600L
    val EASE: Interpolator = DecelerateInterpolator(1.5f)
}
