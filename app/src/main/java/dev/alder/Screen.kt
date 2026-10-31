package dev.alder

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.util.concurrent.Executors

/**
 * A title's art, sharp, fading out into the glass on its left (under the text) and towards the bottom, from
 * [bottomFrom] to [bottomTo] of its height. A mask on the picture itself, not a colour painted over it, so there's no
 * box where the fade meets the blurred scene behind.
 */
class FadedArt(context: Context, private val bottomFrom: Float = 0.45f, private val bottomTo: Float = 1f) : ImageView(context) {
    private val mask = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private var left: Shader? = null
    private var bottom: Shader? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        // Under the text (the left 60%) only a hint of the picture stays, so bright art can't fight the words.
        left = LinearGradient(0f, 0f, w * 0.8f, 0f, intArrayOf(0x1F000000, 0x40000000, Color.BLACK), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        bottom = LinearGradient(0f, h * bottomFrom, 0f, h * bottomTo, intArrayOf(Color.BLACK, 0x59000000, 0), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        val layer = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        super.onDraw(canvas)
        for (s in listOf(left, bottom)) {
            mask.shader = s
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), mask)
        }
        canvas.restoreToCount(layer)
    }
}

/** Keeps text readable straight on art: a soft dark shadow instead of a panel over the picture. */
fun TextView.onArt() = apply { setShadowLayer(resources.displayMetrics.density * 8, 0f, resources.displayMetrics.density, 0xCC000000.toInt()) }

internal fun filmGlow(pixels: IntArray): Int {
    require(pixels.isNotEmpty())
    val red = pixels.sumOf { it ushr 16 and 255 } / pixels.size
    val green = pixels.sumOf { it ushr 8 and 255 } / pixels.size
    val blue = pixels.sumOf { it and 255 } / pixels.size
    val lightness = (maxOf(red, green, blue) + minOf(red, green, blue)) / 2f
    val scale = if (lightness > 178.5f) 178.5f / lightness else 1f
    return (0x8C shl 24) or ((red * scale).toInt() shl 16) or ((green * scale).toInt() shl 8) or (blue * scale).toInt()
}

/**
 * A [w] x [h] copy of [src]'s pixels, or null. Decoded art is often a hardware bitmap, which can't be read: scaling
 * keeps it hardware, so the small copy is moved to software before reading. Every pixel sample goes through here.
 */
internal fun samplePixels(src: Bitmap, w: Int, h: Int): IntArray? = runCatching {
    val small = Bitmap.createScaledBitmap(src, w, h, true)
    val soft = if (small.config == Bitmap.Config.HARDWARE) small.copy(Bitmap.Config.ARGB_8888, false) else small
    IntArray(w * h).also { soft.getPixels(it, 0, w, 0, 0, w, h) }
        .also { if (small !== src) small.recycle(); if (soft !== small) soft.recycle() }
}.getOrNull()

internal fun brightLeft(pixels: IntArray, width: Int): Boolean {
    require(width > 0 && pixels.isNotEmpty() && pixels.size % width == 0)
    val left = maxOf(1, width / 2)
    val luminance = pixels.indices.filter { it % width < left }.sumOf {
        val color = pixels[it]
        (color ushr 16 and 255) * 0.2126 + (color ushr 8 and 255) * 0.7152 + (color and 255) * 0.0722
    }
    return luminance / (left * (pixels.size / width) * 255) > 0.5
}

/**
 * Once per process, before anything goes to the network: every screen, and the TV's screensaver, which can start
 * without any screen open.
 */
internal fun prepareNetwork(c: Context) {
    if (Http.saved != null) return
    // Pictures load many at a time from a few hosts: keep enough connections open to reuse (Android keeps 5).
    System.setProperty("http.maxConnections", "16")
    // Disk cache for pictures (and anything else whose server allows caching), and last time's answers. Opening the
    // cache reads its journal and the answers are trimmed by listing every file: both off the main thread, so the first
    // screen isn't held up by the disk. The first few pictures may miss the disk cache; they're fetched as usual.
    val dir = c.cacheDir
    Http.saved = java.io.File(dir, "answers")
    Thread {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
        runCatching { android.net.http.HttpResponseCache.install(java.io.File(dir, "http"), 250L * 1024 * 1024) }
        Http.trim()
    }.start()
    Http.userAgent = "Canopy/${Updates.mine(c)} (Android ${android.os.Build.VERSION.RELEASE}; ${android.os.Build.MODEL})"
}

/** What every screen shares: the signed-in account, background work, and the few view helpers the plain UI uses. */
abstract class Screen : Activity() {
    protected val main = Handler(Looper.getMainLooper())
    /** The Stremio account of the profile in use. */
    protected val authKey: String? get() = Profiles.active(this)?.authKey
    /** The colours this screen was drawn in; when Settings changes them, the screen draws itself again on return. */
    private var drawnIn: Theme? = null

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        Theme.load(this)
        drawnIn = Theme.now
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(BASE)) // never a grey frame between screens
        prepareNetwork(this)
        // Every key starts the screensaver's quiet time again, before a screen's own key handling can swallow it.
        val screen = window.callback
        window.callback = object : android.view.Window.Callback by screen {
            override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
                armScreensaver()
                return screen.dispatchKeyEvent(event)
            }
        }
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    /** The remote's search or mic button: Search opens listening, from any screen that hasn't a use of its own for it. */
    override fun onSearchRequested(): Boolean {
        if (authKey == null) return false
        startActivity(Intent(this, SearchActivity::class.java).putExtra(SearchActivity.LISTEN, true))
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // Most TVs give the mic button to the Assistant; the few that hand it to the app get the same as Search.
        if (keyCode == KeyEvent.KEYCODE_VOICE_ASSIST && onSearchRequested()) return true
        return super.onKeyDown(keyCode, event)
    }

    /**
     * Downloads [release] and hands it to Android's installer. Canopy closes to be replaced and doesn't open again by
     * itself, so the viewer is told first. Without permission to install apps, Android's page for it opens, and the
     * install goes on when the viewer comes back with it allowed; a TV without that page gets the steps instead.
     */
    protected fun installUpdate(release: Updates.Release) {
        if (!Updates.canInstall(this)) {
            pendingInstall = release
            if (!Updates.askToInstall(this)) {
                pendingInstall = null
                choose("Allow Canopy to install updates", listOf("OK"),
                    "On Fire TV: Settings › My Fire TV › Developer options › Install unknown apps, then turn on Canopy. " +
                        "On Google TV: Settings › Apps › Security & restrictions › Unknown sources, then turn on Canopy. " +
                        "Then select Install again.") {}
            }
            return
        }
        notice("Downloading Canopy ${release.version}", "Canopy closes to update. Open it again after.")
        background({ Updates.install(applicationContext, release) }) { r ->
            if (r.isFailure) choose("Couldn't download the update", listOf("OK"), "Check the TV's connection and try again from Settings › About.") {}
        }
    }

    // An update waiting on the TV's permission to install apps: it goes on when the viewer comes back with it allowed.
    private var pendingInstall: Updates.Release? = null

    /** False where the screensaver must never cover the screen: the player, and the profile picker (PINs). */
    protected open val screensaver: Boolean get() = true

    private val saverTimer = Runnable {
        startActivity(android.content.Intent(this, ScreensaverActivity::class.java))
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    private fun armScreensaver() {
        main.removeCallbacks(saverTimer)
        val minutes = Screensaver.minutes(this)
        if (screensaver && minutes > 0) main.postDelayed(saverTimer, minutes * 60_000L)
    }

    override fun onPause() {
        main.removeCallbacks(saverTimer)
        if (front?.get() === this) front = null
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        front = java.lang.ref.WeakReference(this)
        openingOnce()
        if (drawnIn != null && drawnIn !== Theme.now) return recreate()
        armScreensaver()
        val release = pendingInstall ?: return
        pendingInstall = null
        if (Updates.canInstall(this)) return installUpdate(release)
        // Still not allowed, though Android's page may show Canopy as Allowed (a Google TV with more than one profile
        // showed the toggle of another profile): opening that page again only went round in a loop.
        choose("Canopy can't install updates on this TV", listOf("OK"),
            "Android doesn't let Canopy install apps here. Install the update with the Downloader app instead: " +
                "enter code ${Updates.DOWNLOADER_CODE} in Downloader. Your profiles and settings stay.") {}
    }

    /**
     * The first screen Canopy shows after it starts: the canopy mark and name fade up in the middle, then the whole
     * cover dissolves into the screen drawn behind it. Under a second, once per start, never over the player.
     */
    private fun openingOnce() {
        if (opened) return
        opened = true
        if (this is PlayerActivity) return
        val root = window.decorView as? FrameLayout ?: return
        val mark = brand(WORDMARK * 1.8f).apply { alpha = 0f; scaleX = 0.94f; scaleY = 0.94f }
        val cover = FrameLayout(this).apply {
            setBackgroundColor(BASE)
            addView(mark, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        }
        root.addView(cover, FrameLayout.LayoutParams(-1, -1))
        mark.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(OPENING_IN).setInterpolator(Motion.EASE).withEndAction {
            cover.animate().alpha(0f).setStartDelay(OPENING_HOLD).setDuration(OPENING_OUT).setInterpolator(Motion.EASE)
                .withEndAction { root.removeView(cover) }.start()
        }.start()
    }

    /**
     * Canopy's own message, in place of Android's grey toast: a square glass card top right with a line of the accent
     * along its top, a bold [title] and an optional plain [line] under it. It slides in, stays a few seconds and fades.
     * A new one replaces any still showing.
     */
    fun notice(title: String, line: String? = null, stay: Long = NOTICE_MS) {
        val root = window.decorView as? FrameLayout ?: return
        root.findViewWithTag<View>(NOTICE_TAG)?.let { it.animate().cancel(); root.removeView(it) }
        val card = column(Gravity.START, text(title, BODY, bold = true).apply { maxWidth = dp(NOTICE_W) }).apply {
            tag = NOTICE_TAG
            line?.let { addView(text(it, SMALL, color = TEXT_2).apply { maxWidth = dp(NOTICE_W) }.marginTop(3)) }
            setPadding(dp(18), dp(13), dp(20), dp(14))
            val edge = GradientDrawable().apply { setColor(TUNGSTEN) }
            background = LayerDrawable(arrayOf(rounded(baseAt(0xF0), 2), glass(2), edge)).apply {
                setLayerGravity(2, Gravity.TOP or Gravity.FILL_HORIZONTAL)
                setLayerHeight(2, dp(3))
            }
            elevation = dp(16).toFloat()
            alpha = 0f
            translationY = -dp(14).toFloat()
        }
        root.addView(card, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
            topMargin = dp(26); rightMargin = dp(PAD)
        })
        card.animate().alpha(1f).translationY(0f).setDuration(Motion.STANDARD).setInterpolator(Motion.EASE).start()
        card.postDelayed({
            card.animate().alpha(0f).translationY(-dp(8).toFloat()).setDuration(Motion.STANDARD).setInterpolator(Motion.EASE)
                .withEndAction { (card.parent as? android.view.ViewGroup)?.removeView(card) }.start()
        }, stay)
    }

    /** Runs [work] off the main thread and hands its result back on it, unless the screen is gone. */
    protected fun <T> background(work: () -> T, done: (Result<T>) -> Unit) {
        pool.execute {
            val result = runCatching(work)
            main.post { if (!isDestroyed) done(result) }
        }
    }

    // Read once for the whole app, not per screen: each font is read from the APK on the main thread.
    private val fonts get() = loadedFonts ?: Fonts(applicationContext.assets).also { loadedFonts = it }

    /** Shared House Lights typography. */
    protected fun text(value: String, sp: Float, bold: Boolean = false, color: Int = TEXT, serif: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = sp
            setTextColor(color)
            typeface = if (serif) fonts.serif else if (bold) fonts.semiBold else fonts.medium
            LEADING[sp]?.let { lead ->
                val want = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, lead, resources.displayMetrics)
                setLineSpacing(want - paint.getFontMetricsInt(null), 1f)
            }
        }

    protected fun caps(value: String, color: Int = TEXT_2) = text(value.uppercase(), CAPS, bold = true, color = color).apply {
        letterSpacing = 0.12f
    }

    protected fun column(gravity: Int, vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        clipChildren = false // focused pills and pictures grow past their own bounds
        this.gravity = gravity
        // Wrap-content by default: a vertical LinearLayout would otherwise stretch every child to full width.
        views.forEach { addView(it, it.layoutParams ?: wrap()) }
    }

    protected fun <V : View> V.marginTop(top: Int) = apply { layoutParams = wrap().apply { topMargin = dp(top) } }

    protected fun wrap() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)

    protected fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private var windowShade: View? = null
    private var windowArt: ImageView? = null
    private var shadeSource: Bitmap? = null

    /** The left shade is for text over bare art; a screen that puts its text on glass can lift it off the art. */
    protected fun leftShade(on: Boolean) {
        windowShade?.animate()?.alpha(if (on) 1f else 0f)?.setDuration(Motion.STANDARD)?.setInterpolator(Motion.EASE)?.start()
    }

    private fun updateWindowShade() {
        val art = (windowArt?.drawable as? BitmapDrawable)?.bitmap ?: return
        if (art === shadeSource) return
        shadeSource = art
        // Sampled off the main thread: reading a hardware bitmap back from the GPU stalls it for several ms.
        background({ samplePixels(art, 16, 24)?.let { brightLeft(it, 16) } }) { r ->
            val bright = r.getOrNull() ?: return@background
            if (art !== shadeSource) return@background
            // A bright film keeps the shade dark across the whole text column, not just at the screen's edge.
            windowShade?.background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                if (bright) intArrayOf(baseAt(0xF7), baseAt(0xE0), baseAt(0x80), baseAt(0x00))
                else intArrayOf(baseAt(0xEB), baseAt(0x00), baseAt(0x00)))
        }
    }

    /** Sharp film art fills the screen; shade stays behind text and the bottom row. */
    protected fun setWindowed(content: View, backdrop: View = View(this).apply { background = mood() }) {
        windowArt = backdrop as? ImageView
        shadeSource = null
        window.setBackgroundDrawable(null) // the scene paints the whole screen
        setContentView(FrameLayout(this).apply {
            addView(backdrop, FrameLayout.LayoutParams(-1, -1))
            windowShade = View(context).apply {
                background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(baseAt(0xEB), baseAt(0x00), baseAt(0x00)))
            }.also { addView(it, FrameLayout.LayoutParams(-1, -1)) }
            addView(View(context).apply {
                background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(baseAt(0xF7), baseAt(0x00), baseAt(0x00)))
            }, FrameLayout.LayoutParams(-1, -1))
            addView(View(context).apply {
                background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(baseAt(0x8C), baseAt(0x00), baseAt(0x00), baseAt(0x00), baseAt(0x00), baseAt(0x00), baseAt(0x00)))
            }, FrameLayout.LayoutParams(-1, -1))
            for (edge in listOf(GradientDrawable.Orientation.LEFT_RIGHT, GradientDrawable.Orientation.RIGHT_LEFT,
                GradientDrawable.Orientation.TOP_BOTTOM, GradientDrawable.Orientation.BOTTOM_TOP)) {
                addView(View(context).apply {
                    background = GradientDrawable(edge,
                        intArrayOf(0x73000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000))
                }, FrameLayout.LayoutParams(-1, -1))
            }
            addView(content, FrameLayout.LayoutParams(-1, -1))
        })
        updateWindowShade()
    }

    /** A quiet tinted row on the pane (episodes, sources): no edge, no glass of its own. */
    protected fun tile(radiusDp: Int = TILE_RADIUS) = GradientDrawable().apply {
        setColor(0x14FFFFFF)
        cornerRadius = dp(minOf(radiusDp, CORNER)).toFloat()
    }

    /** Solid fallback until a title's art is available. */
    private fun mood() = rounded(BASE, 0)

    protected fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(minOf(radiusDp, CORNER)).toFloat() }

    /** House Lights' single glass recipe; focus brightens the face (no rim: dots are the only indicator). */
    protected fun glass(radius: Int, focused: Boolean = false): Drawable {
        val radiusDp = minOf(radius, CORNER)
        val face = GradientDrawable().apply {
            setColor(if (focused) GLASS_FOCUSED else GLASS)
            cornerRadius = dp(minOf(radiusDp, CORNER)).toFloat()
            setStroke(dp(1), GLASS_EDGE)
        }
        return LayerDrawable(arrayOf(face, Highlight(dp(radiusDp).toFloat())))
    }

    /** The glass's top highlight, like CSS `inset 0 1px 0`: a 1 dp sliver that follows the corners and thins out down
     *  their curve, rather than a flat bar that stops short of them and reads as a stray line. */
    private inner class Highlight(private val radius: Float) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GLASS_HIGHLIGHT }
        override fun draw(canvas: Canvas) = canvas.drawPath(highlight(RectF(bounds), radius), paint)
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Drawable API") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    private fun highlight(box: RectF, radius: Float): Path {
        val r = minOf(radius, box.height() / 2, box.width() / 2) // as GradientDrawable clamps a pill's radius
        val shape = Path().apply { addRoundRect(box, r, r, Path.Direction.CW) }
        val lower = Path().apply { addRoundRect(RectF(box).apply { offset(0f, dp(1).toFloat()) }, r, r, Path.Direction.CW) }
        return shape.apply { op(lower, Path.Op.DIFFERENCE) }
    }

    /** The warm-white primary action; focus lifts and grows it. */
    protected fun pill(label: String, icon: Int = 0): TextView = text(label, SMALL, bold = true).apply {
        isFocusable = true
        gravity = Gravity.CENTER_VERTICAL
        minHeight = dp(BUTTON_H)
        setPadding(dp(if (icon != 0) 14 else 16), 0, dp(16), 0)
        compoundDrawablePadding = dp(6)
        if (icon != 0) setCompoundDrawablesRelative(getDrawable(icon)?.mutate()?.apply { setBounds(0, 0, dp(16), dp(16)) }, null, null, null)
        fun style(focused: Boolean) {
            background = GradientDrawable().apply {
                setColor(if (focused) TUNGSTEN else TEXT)
                cornerRadius = dp(minOf(BUTTON_R, CORNER)).toFloat()
            }
            setTextColor(BASE)
            compoundDrawablesRelative[0]?.setTint(BASE)
            elevation = if (focused) dp(8).toFloat() else 0f
            if (android.os.Build.VERSION.SDK_INT >= 28) outlineSpotShadowColor = TUNGSTEN_GLOW
        }
        style(false)
        setOnFocusChangeListener { v, has ->
            style(has)
            v.grow(has)
        }
    }

    /**
     * Focus growth for every control: scales by [by] and first lets its layout parents draw it past their bounds, so a
     * plain container can't cut the grown edge, rim and glow flat. It stops at the first scrolling view and leaves that
     * one alone: a scroller's clip is what hides rows and cards that have scrolled out, and unclipping it let Home's
     * rows slide over the title. Rows give their cards room to grow with their own padding.
     */
    protected fun View.grow(on: Boolean, by: Float = CARD_GROW): android.view.ViewPropertyAnimator {
        if (on) generateSequence(parent as? android.view.ViewGroup) { it.parent as? android.view.ViewGroup }
            .takeWhile { it !is android.widget.ScrollView && it !is android.widget.HorizontalScrollView && it !is android.widget.AbsListView }
            .forEach { it.clipChildren = false; it.clipToPadding = false }
        return animate().scaleX(if (on) by else 1f).scaleY(if (on) by else 1f).setDuration(Motion.QUICK).setInterpolator(Motion.EASE)
    }

    /** A persistent choice: its dot remains after remote focus moves. */
    protected fun chip(label: String, selected: Boolean = false) = text(label, SMALL).apply {
        select(label, selected)
        isFocusable = true
        gravity = Gravity.CENTER
        minHeight = dp(CHIP_H)
        setPadding(dp(16), 0, dp(16), 0)
        background = outline(false)
        setOnFocusChangeListener { v, has -> v.background = outline(has); v.grow(has, CHIP_GROW).start() }
    }

    /** A [chip]'s open state: the tungsten dot and full-strength words. */
    protected fun TextView.select(label: String, selected: Boolean) {
        setTextColor(if (selected) TEXT else TEXT_2)
        text = if (!selected) label else android.text.SpannableString("\u25AA  $label").apply {
            setSpan(android.text.style.ForegroundColorSpan(TUNGSTEN), 0, 1, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    protected fun caption(value: String) = text(value, SMALL).apply {
        setPadding(dp(12), dp(6), dp(12), dp(6))
        background = glassOver(this, 16)
    }

    /**
     * Canopy's one tag style ("BEST MATCH", "NEW EPISODE", "OK", "RD ⚡"): small bold capitals, never under [TAG_SP],
     * square-cornered. [filled] tags are solid [color] with dark words; the others are outlined in it.
     */
    protected fun badge(label: String, color: Int = TUNGSTEN, filled: Boolean = true) = text(label, TAG_SP, bold = true, color = if (filled) BASE else color).apply {
        includeFontPadding = false
        maxLines = 1
        letterSpacing = 0.04f
        setPadding(dp(5), dp(2), dp(5), dp(2))
        background = GradientDrawable().apply {
            cornerRadius = dp(minOf(2, CORNER)).toFloat()
            if (filled) setColor(color) else setStroke(dp(1), withAlpha(color, 0x99))
        }
    }

    /** "[OK] Play / pause": the key in a small box, then what it does. */
    protected fun keyHint(key: String, does: String) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(badge(key, TEXT_2))
        addView(text(does, SMALL, color = TEXT_2).onArt(), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) })
    }

    /** A quiet outlined tag beside a row's name ("Built-in", "ElfHosted", "THIS PROFILE"). */
    protected fun tag(label: String, color: Int = TEXT_3) = text(label, SMALL, color = color).apply {
        includeFontPadding = false
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        setPadding(dp(5), dp(2), dp(5), dp(2))
        background = GradientDrawable().apply { cornerRadius = dp(minOf(4, CORNER)).toFloat(); setStroke(dp(1), GLASS_EDGE) }
    }

    /** A hairline under a list row. */
    protected fun divider() = View(this).apply { setBackgroundColor(GLASS_EDGE) }

    /** Canopy's mark beside the lowercase "canopy" wordmark: top left of the picker and sign-in, and Settings › About. */
    protected fun brand(size: Float = WORDMARK) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        val mark = (size * 1.3f).toInt() // the mark's art fills about two thirds of its square
        addView(ImageView(context).apply { setImageResource(R.drawable.logo_transparent) }, LinearLayout.LayoutParams(dp(mark), dp(mark)))
        addView(text("canopy", size).onArt().apply { typeface = fonts.wordmark }, wrap().apply { marginStart = dp(4) })
    }

    /** The IMDb tag beside a rating, as tall as the letters. */
    protected fun imdbTag() = text("IMDb", SMALL, bold = true, color = BASE).apply {
        background = rounded(RATING, 2)
        includeFontPadding = false
        setLineSpacing(0f, 1f)
        setPadding(dp(4), dp(1), dp(4), dp(1))
    }

    /**
     * The loading and countdown mark: a small square outline, never a circle. Null animates loading (a short lit run
     * travelling round the square); for countdowns set progress to fraction × max and the square fills clockwise.
     */
    protected fun ring(fraction: Float? = null): ProgressBar = object : ProgressBar(this, null,
        // A countdown needs a style that keeps its progress; the default one is indeterminate-only and reads 0.
        android.R.attr.progressBarStyleHorizontal) {
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(2).toFloat()
            strokeCap = Paint.Cap.SQUARE
            strokeJoin = Paint.Join.MITER
        }
        private val square = Path()
        private val run = Path()
        private val measure = android.graphics.PathMeasure()

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            val inset = stroke.strokeWidth / 2
            square.reset()
            square.moveTo(inset, inset)
            square.lineTo(w - inset, inset)
            square.lineTo(w - inset, h - inset)
            square.lineTo(inset, h - inset)
            square.close()
            measure.setPath(square, true)
        }

        override fun onDraw(canvas: Canvas) {
            val length = measure.length
            if (length <= 0f) return
            stroke.color = PROGRESS_TRACK
            canvas.drawPath(square, stroke)
            stroke.color = TUNGSTEN
            run.reset()
            if (fraction == null) {
                // A quarter of the square, once round every 1.2 s.
                val start = (android.os.SystemClock.uptimeMillis() % 1200L) / 1200f * length
                val end = start + length / 4
                measure.getSegment(start, minOf(end, length), run, true)
                if (end > length) measure.getSegment(0f, end - length, run, true)
                canvas.drawPath(run, stroke)
                if (isShown) postInvalidateOnAnimation()
            } else {
                measure.getSegment(0f, progress.toFloat() / max * length, run, true)
                canvas.drawPath(run, stroke)
            }
        }
    }.apply {
        require(fraction == null || (fraction.isFinite() && fraction in 0f..1f))
        isIndeterminate = false // drawn here, not by Android's round spinner
        max = 1000
        if (fraction != null) progress = (fraction * max).toInt()
    }

    /** The one screen between Play and the picture: what [startingScreen] builds, and the line under its ring. */
    protected class Starting(val view: FrameLayout, val status: TextView, val spinner: View) {
        /** A problem instead of progress: the ring stops and the line says what happened. */
        fun fail(message: String) { spinner.visibility = View.GONE; status.text = message; status.setTextColor(TEXT) }
    }

    /**
     * Pressing Play shows this until the picture does, on the sources lookup and the player alike, so the two read as one
     * screen: the title's art (the episode's still first) under a dark fade, its logo or name and the episode at the
     * lower left, and a small emerald ring with one quiet line. It reads the intent the title page started Play with.
     */
    protected fun startingScreen(): Starting {
        val name = intent.getStringExtra("name").orEmpty()
        val art = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f }
        val still = intent.getStringExtra("still")
        val backArt = intent.getStringExtra("background") ?: intent.getStringExtra("poster")
        Images.load(still ?: backArt, art, resources.displayMetrics.widthPixels, orElse = backArt.takeIf { still != null }, urgent = true) {
            android.animation.ObjectAnimator.ofFloat(art, View.ALPHA, 1f).setDuration(Motion.SLOW).start()
            // A slow push in while it loads, so the wait feels alive rather than frozen.
            art.animate().scaleX(1.06f).scaleY(1.06f).setDuration(12_000).setInterpolator(android.view.animation.LinearInterpolator()).start()
        }
        val logo = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_START; adjustViewBounds = true }
        val title = text(name, SERIF_TITLE, serif = true).onArt().apply {
            maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; maxWidth = dp(HERO_W)
        }
        Images.load(intent.getStringExtra("logo") ?: Images.logoFor(intent.getStringExtra("id").orEmpty()),
            logo, dp(320), urgent = true, failed = { logo.visibility = View.GONE }) { title.visibility = View.GONE }
        val episode = intent.getStringExtra("episode")?.takeIf { intent.getStringExtra("type") == "series" }
        val spinner = ring()
        val status = text("Getting ready", SMALL, color = TEXT_2).onArt()
        val block = column(Gravity.START).apply {
            addView(FrameLayout(context).apply {
                addView(title, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START))
                addView(logo, FrameLayout.LayoutParams(-2, -1, Gravity.BOTTOM or Gravity.START))
            }, LinearLayout.LayoutParams(dp(HERO_W), dp(96)))
            if (episode != null) addView(text(episode, BODY, bold = true, color = TEXT_2).onArt().apply {
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; maxWidth = dp(HERO_W)
            }, wrap().apply { topMargin = dp(10) })
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(spinner, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(10) })
                addView(status, wrap())
            }, wrap().apply { topMargin = dp(18) })
        }
        val view = FrameLayout(this).apply {
            setBackgroundColor(BASE)
            addView(art, FrameLayout.LayoutParams(-1, -1))
            addView(View(context).apply {
                background = GradientDrawable(GradientDrawable.Orientation.BL_TR, intArrayOf(baseAt(0xF2), baseAt(0x99), baseAt(0x33)))
            }, FrameLayout.LayoutParams(-1, -1))
            addView(View(context).apply {
                background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(baseAt(0xCC), baseAt(0x00)))
            }, FrameLayout.LayoutParams(-1, -1))
            addView(block, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply {
                leftMargin = dp(PAD); bottomMargin = dp(PAD + 8)
            })
        }
        return Starting(view, status, spinner)
    }

    /** One list-row focus treatment across settings, episodes, sources and menus. */
    protected fun View.rowFocus(radiusDp: Int = 8, rest: () -> android.graphics.drawable.Drawable? = { null }, also: (Boolean) -> Unit = {}) {
        isFocusable = true
        background = rest()
        setOnFocusChangeListener { v, has ->
            v.background = if (has) lineFocus() else rest()
            also(has)
        }
    }

    /**
     * Light line, for lists (settings, episodes, sources, menus): no shape, a faint wash that fades out to the right and
     * a glowing mint edge on the left.
     */
    protected fun lineFocus(gloss: Boolean = false): Drawable = LayerDrawable(listOfNotNull(
        GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
            if (gloss) intArrayOf(0x33FFFFFF, withAlpha(ICE, 0x14), 0x05FFFFFF) else intArrayOf(0x29FFFFFF, 0x0FFFFFFF, 0x00FFFFFF)),
        android.graphics.drawable.ColorDrawable(TUNGSTEN),
        // [gloss] (Settings): a bright hairline along the top edge, like light catching polished glass.
        if (gloss) GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xD9FFFFFF.toInt(), 0x26FFFFFF)) else null,
    ).toTypedArray()).apply {
        setLayerWidth(1, dp(3))
        if (gloss) { setLayerGravity(2, Gravity.TOP or Gravity.FILL_HORIZONTAL); setLayerHeight(2, dp(1)) }
    }

    /** Aurora's sky for screens without a title's art behind them (Settings): midnight, lit mint and violet from above. */
    protected fun sky(): Drawable {
        val w = resources.displayMetrics.widthPixels.toFloat()
        fun light(color: Int, x: Float, y: Float, r: Float) = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            colors = intArrayOf(color, color and 0x00FFFFFF)
            setGradientCenter(x, y)
            gradientRadius = w * r
        }
        return LayerDrawable(arrayOf(
            GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Theme.now.glass, BASE, Theme.now.deep)),
            light(Theme.now.lights[0], 0.9f, -0.05f, 0.45f),
            light(Theme.now.lights[1], 0.6f, -0.1f, 0.4f),
            light(Theme.now.lights[2], 1f, 1.1f, 0.5f),
            // A faint diagonal sheen across the glass.
            GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0, 0, 0x0BFFFFFF, 0x04FFFFFF, 0, 0)),
        ))
    }

    /** Light line, for secondary buttons and chips: a sharp outline, lit white with a faint fill when focused. */
    protected fun outline(focused: Boolean): Drawable = GradientDrawable().apply {
        setColor(if (focused) 0x26FFFFFF else 0)
        cornerRadius = dp(minOf(BUTTON_R, CORNER)).toFloat()
        setStroke(dp(1), if (focused) TEXT else 0x59FFFFFF)
    }

    /** One poster/portrait focus treatment; the poster itself supplies its glow colour. */
    protected fun View.cardFocus(radiusDp: Int = 6, also: (Boolean) -> Unit = {}) {
        isFocusable = true
        setOnFocusChangeListener { v, has ->
            v.lift(has, radiusDp)
            also(has)
        }
    }

    private val glowCache = java.util.WeakHashMap<View, Pair<Bitmap, Int>>()

    private fun posterBitmap(view: View): Bitmap? = when (view) {
        is ImageView -> (view.drawable as? BitmapDrawable)?.bitmap
        is android.view.ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { posterBitmap(view.getChildAt(it)) }
        else -> null
    }

    protected fun View.lift(on: Boolean, radiusDp: Int = 6) {
        foreground = if (on) GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            cornerRadius = dp(minOf(radiusDp, CORNER)).toFloat()
            setStroke(dp(2), TUNGSTEN_RIM) // the emerald ring; the glow and the lift carry the rest
        } else null
        if (on && android.os.Build.VERSION.SDK_INT >= 28) {
            val poster = posterBitmap(this)
            val known = if (poster == null) TUNGSTEN_GLOW else glowCache[this]?.takeIf { it.first === poster }?.second
            outlineSpotShadowColor = known ?: TUNGSTEN_GLOW
            // Reading a poster's pixels back from the GPU took ~7 ms on the main thread per press: the glow is sampled
            // off it and tints the shadow a moment later, while the lift is still growing.
            if (known == null && poster != null) background({ samplePixels(poster, 16, 24)?.let(::filmGlow) }) { r ->
                r.getOrNull()?.let { glow -> glowCache[this] = poster to glow; if (hasFocus()) outlineSpotShadowColor = glow }
            }
        }
        grow(on)
            .translationZ(if (on) dp(CARD_LIFT).toFloat() else 0f).setDuration(Motion.QUICK).start()
    }

    /** The screen's art, behind its glass: what [glassOver] frosts. Screens with art behind them name it. */
    protected open val backdrop: ImageView? get() = null

    private var frostSource: Bitmap? = null
    private var frostBitmap: Bitmap? = null
    private val frostHosts = mutableListOf<View>()

    /**
     * Frosted glass, as the episode list draws it: the art behind, blurred, seen through a rounded pane under the
     * glass tint. A background for [host] (pills, the top bar, panels); without a [backdrop] it's the plain tint.
     */
    /** [shade]: how much of the screen's own shade lies over the art there (0..1), so glass over a shaded part of the art
     *  is as dark as the art around it instead of a milky patch of the unshaded picture. */
    protected fun glassOver(host: View, radius: Int, shade: Float = 0f): Drawable {
        val radiusDp = minOf(radius, CORNER)
        // No art to frost (Settings): the glass sits on the base colour, so a dialog doesn't show the rows under it.
        val behind = backdrop ?: return LayerDrawable(arrayOf(rounded(BASE, radiusDp), glass(radiusDp)))
        frostHosts += host
        return Frost(host, behind, dp(radiusDp).toFloat(), shade)
    }

    /** The art behind changed: glass redraws with the new picture once it's blurred. */
    protected fun refrost() {
        frostHosts.forEach { it.invalidate() }
        updateWindowShade()
    }

    /** The art blurred once per picture ([Images.frost]); the last one stays up while the next is made. */
    private fun frosted(d: Drawable?): Bitmap? {
        val src = (d as? BitmapDrawable)?.bitmap ?: return frostBitmap
        if (src !== frostSource) {
            frostSource = src
            background({ Images.frost(src, FROST_W) }) { r -> if (frostSource === src) { frostBitmap = r.getOrNull(); refrost() } }
        }
        return frostBitmap
    }

    private inner class Frost(private val host: View, private val behind: ImageView, private val radius: Float,
                              private val shade: Float = 0f) : Drawable() {
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val tint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GLASS }
        private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GLASS_EDGE; style = Paint.Style.STROKE; strokeWidth = dp(1).toFloat() }
        private val shine = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GLASS_HIGHLIGHT }
        private val path = Path()
        private val at = IntArray(2)
        private val art = IntArray(2)

        override fun draw(canvas: Canvas) {
            path.reset()
            path.addRoundRect(RectF(bounds), radius, radius, Path.Direction.CW)
            val d = behind.drawable
            frosted(d)?.let { f ->
                canvas.save()
                canvas.clipPath(path)
                // The blurred art lines up with the sharp art behind: where the host sits on screen, through the same crop.
                // On screen, not in the window: a menu's glass lives in a dialog's window over the screen's art.
                host.getLocationOnScreen(at)
                behind.getLocationOnScreen(art)
                canvas.translate((art[0] - at[0]).toFloat(), (art[1] - at[1]).toFloat())
                canvas.concat(behind.imageMatrix)
                // As bright as the sharp art around it: a dimmed backdrop (Settings') dims its frosted copy too.
                if (behind.alpha < 1f) canvas.drawColor(BASE)
                paint.alpha = (behind.alpha * 255).toInt()
                canvas.drawBitmap(f, null, Rect(0, 0, d?.intrinsicWidth ?: f.width, d?.intrinsicHeight ?: f.height), paint)
                if (shade > 0f) canvas.drawColor(Color.argb((shade * 255).toInt(), 14, 12, 10))
                canvas.restore()
            }
            canvas.drawPath(path, tint)
            canvas.drawPath(path, edge)
            canvas.drawPath(highlight(RectF(bounds), radius), shine)
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Drawable API") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    /** A thin watched-so-far bar over a dark track. */
    protected fun progressBar(progress: Float, color: Int = TUNGSTEN) = LinearLayout(this).apply {
        val p = progress.coerceIn(0f, 1f)
        setBackgroundColor(PROGRESS_TRACK)
        addView(View(context).apply { setBackgroundColor(color) }, LinearLayout.LayoutParams(0, -1, p))
        addView(View(context), LinearLayout.LayoutParams(0, -1, 1f - p))
    }

    protected fun isInside(v: View, parent: View): Boolean = generateSequence(v) { it.parent as? View }.any { it === parent }

    /**
     * A short list to pick from (a menu, a genre, a yes or no), on glass with tungsten focus, in
     * place of Android's grey dialog. [checked] gets a tick (the choice in use); the remote starts on [focus].
     */
    protected fun choose(title: String, items: List<String>, message: String? = null, checked: Int = -1,
                         focus: Int = maxOf(checked, 0), dropDown: Boolean = false, dropFrom: Int = Gravity.END,
                         dropY: Int = 60, pick: (Int) -> Unit) {
        if (fullChoices && !dropDown) return chooseFull(title, items, message, checked, focus, pick)
        val dialog = android.app.Dialog(this).apply { requestWindowFeature(android.view.Window.FEATURE_NO_TITLE) }
        val list = column(Gravity.START).apply { setPadding(dp(12), dp(16), dp(12), dp(12)) }
        val words = column(Gravity.START, text(title, BODY, bold = true)).apply { setPadding(dp(16), 0, dp(16), dp(10)) }
        message?.let { words.addView(text(it, SMALL, color = TEXT_2).marginTop(6)) }
        list.addView(words, LinearLayout.LayoutParams(-1, -2))
        val rows = items.mapIndexed { i, label ->
            text(label, BODY).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(10), dp(16), dp(10))
                if (i == checked) setCompoundDrawablesRelative(null, null, getDrawable(R.drawable.ic_check)?.mutate()?.apply { setBounds(0, 0, dp(16), dp(16)) }, null)
                rowFocus()
                setOnClickListener { dialog.dismiss(); pick(i) }
                list.addView(this, LinearLayout.LayoutParams(-1, -2))
            }
        }
        dialog.setContentView(android.widget.ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            // The episode list's glass, over the screen's art, smoked well down: lightly tinted, a bright poster behind
            // turned the whole menu its colour (lilac over Moana).
            background = glassOver(this, 16, shade = 0.72f)
            addView(list)
        })
        dialog.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0))
            setLayout(dp(if (dropDown) 260 else 340), -2)
            setDimAmount(0.6f)
            // [dropDown]: hangs from the control it opened from ([dropFrom] side, [dropY] dp down; the profile's by
            // default, top right) instead of the screen's middle.
            if (dropDown) attributes = attributes.apply { gravity = Gravity.TOP or dropFrom; x = dp(PAD - 12); y = dp(dropY) }
        }
        dialog.show()
        rows.getOrNull(focus)?.requestFocus()
    }

    /** Screens whose choices take the whole screen ([chooseFull]) instead of a small panel: Settings. */
    protected open val fullChoices: Boolean get() = false

    /**
     * [choose] over the whole screen, Netflix-style: the question large on the left, the answers as big words on the
     * right, the remote's one lit with the glossy Light line.
     */
    private fun chooseFull(title: String, items: List<String>, message: String?, checked: Int, focus: Int, pick: (Int) -> Unit) {
        val dialog = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val words = column(Gravity.START, text(title, SERIF_HERO, serif = true).apply { maxWidth = dp(360) }).apply {
            message?.let { addView(text(it, BODY, color = TEXT_2).apply { maxWidth = dp(360) }.marginTop(12)) }
        }
        val list = column(Gravity.START)
        val rows = items.mapIndexed { i, label ->
            text(label, SERIF_NAME, serif = true, color = if (i == checked) TEXT else TEXT_2).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(20), dp(14), dp(20), dp(14))
                if (i == checked) setCompoundDrawablesRelative(null, null, getDrawable(R.drawable.ic_check)?.mutate()?.apply {
                    setBounds(0, 0, dp(18), dp(18)); setTint(TUNGSTEN)
                }, null)
                isFocusable = true
                val rest = { android.graphics.drawable.LayerDrawable(arrayOf(android.graphics.drawable.ColorDrawable(0x12FFFFFF))).apply {
                    setLayerGravity(0, Gravity.BOTTOM or Gravity.FILL_HORIZONTAL); setLayerHeight(0, dp(1))
                } }
                background = rest()
                setOnFocusChangeListener { _, has ->
                    background = if (has) lineFocus(gloss = true) else rest()
                    setTextColor(if (has || i == checked) TEXT else TEXT_2)
                    setShadowLayer(if (has) dp(10).toFloat() else 0f, 0f, 0f, if (has) 0x66FFFFFF else 0)
                }
                setOnClickListener { dialog.dismiss(); pick(i) }
                list.addView(this, LinearLayout.LayoutParams(-1, -2))
            }
        }
        dialog.setContentView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = LayerDrawable(arrayOf(sky(), android.graphics.drawable.ColorDrawable(0x99040812.toInt())))
            setPadding(dp(64), dp(40), dp(64), dp(40))
            addView(words, LinearLayout.LayoutParams(0, -2, 1f))
            addView(android.widget.ScrollView(context).apply {
                isVerticalScrollBarEnabled = false
                isFillViewport = false
                addView(list)
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(48) })
        })
        dialog.show()
        rows.getOrNull(focus)?.requestFocus()
    }

    /**
     * "What's new" for [version]: its entries from CHANGELOG.md (packaged with the app), the first [SHORT_LIST] on glass
     * with Got it, and See all changes to open the rest in a taller panel that the remote steps through line by line.
     * [quiet]: nothing at all when there are no notes (the pop-up after an update), else a short message (Settings).
     */
    protected fun whatsNew(version: String = Updates.mine(this), quiet: Boolean = false) {
        val changes = runCatching { assets.open("CHANGELOG.md").bufferedReader().use { it.readText() } }.getOrNull()
            ?.let { changesFor(it, version) }.orEmpty()
        if (changes.isEmpty()) {
            if (!quiet) choose("What's new", listOf("OK"), "There are no notes for version $version.") {}
            return
        }
        val dialog = android.app.Dialog(this).apply { requestWindowFeature(android.view.Window.FEATURE_NO_TITLE) }
        val list = column(Gravity.START).apply { setPadding(dp(22), dp(20), dp(22), dp(18)) }
        list.addView(caps("What's new", TUNGSTEN))
        list.addView(text("Canopy $version", SERIF_TITLE, serif = true).marginTop(4))
        val entries = column(Gravity.START)
        list.addView(entries, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        fun entry(change: String) = text("", BODY, color = TEXT).apply {
            text = changeLine(change)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            entries.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
        changes.take(SHORT_LIST).forEach(::entry)
        val gotIt = pill("Got it").apply { setOnClickListener { dialog.dismiss() } }
        val buttons = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; addView(gotIt) }
        list.addView(buttons, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(16) })
        if (changes.size > SHORT_LIST) buttons.addView(chip("See all changes").apply {
            setOnClickListener {
                // Taller, every entry, each one a stop for the remote so Down moves through them; Got it at the end.
                changes.drop(SHORT_LIST).forEach(::entry)
                (0 until entries.childCount).forEach { entries.getChildAt(it).rowFocus() }
                buttons.removeView(this)
                dialog.window?.setLayout(dp(WHATS_NEW_W), resources.displayMetrics.heightPixels * 82 / 100)
                entries.getChildAt(SHORT_LIST).requestFocus()
            }
        }, LinearLayout.LayoutParams(-2, dp(CHIP_H + 6)).apply { marginStart = dp(8) })
        dialog.setContentView(android.widget.ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            background = glassOver(this, 16)
            addView(list)
        })
        dialog.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0))
            setLayout(dp(WHATS_NEW_W), -2)
            setDimAmount(0.6f)
        }
        dialog.show()
        gotIt.requestFocus()
    }

    /** One glass error card with a plain message and a next action. */
    protected fun failed(message: String, retry: () -> Unit) = column(Gravity.START,
        text("!", SERIF_NAME, color = TUNGSTEN, serif = true),
        text(message, BODY, color = TEXT).marginTop(10),
        pill("Try again").apply { setOnClickListener { retry() } }.marginTop(16),
    ).apply {
        setPadding(dp(18), dp(18), dp(18), dp(18))
        background = glassOver(this, 14)
    }

    // House Lights' shared values. Screens keep their layout and content; the helpers above own component styling.
    // Aurora: Manrope for words, Sora for titles. The wordmark keeps Outfit Light, as on the icon.
    private class Fonts(assets: android.content.res.AssetManager) {
        val medium: Typeface = face(assets, "manrope", 500)
        val semiBold: Typeface = face(assets, "manrope", 700)
        val serif: Typeface = face(assets, "sora", 600) // titles: Sora, in place of Alder's serif
        val wordmark: Typeface = face(assets, "outfit", 300)

        private fun face(assets: android.content.res.AssetManager, font: String, weight: Int): Typeface =
            Typeface.Builder(assets, "fonts/$font.ttf").setFontVariationSettings("'wght' $weight").setWeight(weight).build()
    }

    companion object {
        /** The screen in front, for messages that start outside any screen (an update failing to install). */
        private var front: java.lang.ref.WeakReference<Screen>? = null
        private var opened = false // the opening has played in this run of the app
        private const val OPENING_IN = 350L
        private const val OPENING_HOLD = 100L
        private const val OPENING_OUT = 300L
        private const val NOTICE_TAG = "canopy-notice"
        private const val NOTICE_MS = 4_000L
        private const val NOTICE_W = 340

        /** [Screen.notice] on the screen in front, or Android's toast when no Canopy screen is showing. */
        fun notice(c: android.content.Context, title: String, line: String? = null) {
            val screen = front?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }
            if (screen != null) screen.runOnUiThread { screen.notice(title, line, NOTICE_MS * 2) }
            else android.widget.Toast.makeText(c, listOfNotNull(title, line).joinToString(". "), android.widget.Toast.LENGTH_LONG).show()
        }

        private var loadedFonts: Fonts? = null // main thread only
        const val PAD = 48
        const val TILE_RADIUS = 0
        const val GLASS_INSET = 28 // dp a glass panel sits in from the screen's edges
        const val FROST_W = 360 // px the art is shrunk to before the blur: wider is less blur (240 read as fog, 480 as smeared)
        const val HERO_W = 520 // dp: a title's block on Home and its page, half the screen so the story reads in full lines
        const val BUTTON_H = 38 // TV-sized: Play and the squares beside it
        const val BUTTON_R = 0 // dp: buttons and chips are sharp rectangles
        const val SHORT_LIST = 5 // "What's new" entries shown before See all changes
        const val WHATS_NEW_W = 480 // dp
        const val CHIP_H = 24 // the mocks' 46–48 px chips
        const val CARD_GROW = 1.08f
        const val CHIP_GROW = 1.04f // a wide chip grown as much as a card filled the gap to its neighbour
        const val CARD_LIFT = 8
        // Spacing, everywhere rows of pictures appear: between cards, from a row's heading to its cards (plus room for
        // the focused card to grow), and above a heading, so each heading belongs to the row under it.
        const val CARD_GAP = 14
        const val POSTER_W = 88
        const val POSTER_H = 132
        const val HEADING_GAP = 24
        const val SECTION_GAP = 32
        const val ROW_H = 42 // one-line list row (Settings)
        const val ROW_H2 = 48 // two-line list row (name over what it does)
        const val PANEL_W = 0.573f // share of the screen a list panel takes, beside its side note
        const val DOT = "  ·  "

        const val WORDMARK = 22f // sp, "Canopy" beside its mark
        const val SERIF_HERO = 38f
        const val SERIF_TITLE = 28.5f
        const val SERIF_NAME = 19f
        const val BODY = 14.5f
        const val SMALL = 12.5f
        const val TAG_SP = 11f // the smallest text anywhere: tags and key hints still read from the sofa
        const val CAPS = SMALL
        const val CODE = 36f
        val LEADING = mapOf(SERIF_HERO to 40f, SERIF_TITLE to 30f, SERIF_NAME to 21f, BODY to 20f, SMALL to 17f, CODE to 40f)

        // The colours come from the chosen Theme; Tangerine unless the viewer picks another in Settings.
        val BASE get() = Theme.now.base
        val TEXT get() = Theme.now.text
        val TEXT_2 get() = withAlpha(Theme.now.dim, 0xB8)
        val TEXT_3 get() = withAlpha(Theme.now.dim, 0x73)
        // The accent keeps Alder's name (fewer conflicts when merging upstream).
        val TUNGSTEN get() = Theme.now.accent
        const val TUNGSTEN_RIM = 0xFFFFFFFF.toInt() // Light line: a focused card's ring is white
        val TUNGSTEN_GLOW get() = withAlpha(Theme.now.accent, 0x73)
        val TILE get() = Theme.now.tile // a poster's placeholder
        val ICE get() = Theme.now.second // values and the far end of a bar
        const val RATING = 0xFFF3C969.toInt() // the IMDb tag's gold
        val GLASS get() = withAlpha(Theme.now.glass, 0x4D)
        const val GLASS_FOCUSED = 0x33FFFFFF
        val GLASS_EDGE get() = withAlpha(Theme.now.text, 0x24)
        const val GLASS_HIGHLIGHT = 0x4DF0F6FF
        const val ROW_FOCUSED = 0x1FFFFFFF // Light line: the faint wash a focused row starts with
        const val CORNER = 6 // dp: soft corners everywhere, capped here so nothing becomes a capsule or circle.
        const val PROGRESS_TRACK = 0x4DFFFFFF // light enough that a short fill still reads as a bar, not a dash
        private val pool = Executors.newFixedThreadPool(6)
    }
}

/** The entries under "## [version]" in CHANGELOG.md, without their "- "; empty when that version has no section. */
internal fun changesFor(changelog: String, version: String): List<String> {
    val lines = changelog.lines()
    val start = lines.indexOfFirst { it.trim() == "## $version" }.takeIf { it >= 0 } ?: return emptyList()
    return lines.drop(start + 1).takeWhile { !it.startsWith("## ") }
        .filter { it.startsWith("- ") }.map { it.removePrefix("- ").trim() }
}

/** A changelog entry as it reads on screen: **bold** in bold, `code` and the other marks left out. */
internal fun changeLine(change: String): CharSequence {
    val out = android.text.SpannableStringBuilder()
    Regex("""\*\*(.+?)\*\*|`(.+?)`""").let { marks ->
        var at = 0
        marks.findAll(change).forEach { m ->
            out.append(change, at, m.range.first)
            val bold = m.groupValues[1]
            if (bold.isNotEmpty()) {
                val start = out.length
                out.append(bold)
                out.setSpan(android.text.style.StyleSpan(Typeface.BOLD), start, out.length, 0)
            } else out.append(m.groupValues[2])
            at = m.range.last + 1
        }
        out.append(change, at, change.length)
    }
    return out
}
