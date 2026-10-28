package dev.alder

import android.animation.ObjectAnimator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.dreams.DreamService
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import kotlin.random.Random

/**
 * The screensaver: the art of what this profile is watching and has in My List, drifting slowly (Ken Burns) under the
 * time. Canopy starts it itself after a quiet while ([Screen]), and the TV can use it as its own ([CanopyDream]).
 */
object Screensaver {
    /** One picture: the title's full-screen art, its logo, and the line under it. */
    class Slide(val id: String, val type: String, val name: String, val art: String, val logo: String?, val line: String)

    const val SLIDE_MS = 14_000L
    const val FADE_MS = 2_400L

    /** Minutes without a key press before Canopy shows it; 0 is off. */
    fun minutes(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE).getInt("saverMinutes", 5)

    /** The active profile's slides, from the library saved last time or else from Stremio. Blocks. */
    fun slides(c: Context): List<Slide> {
        val key = Profiles.active(c)?.authKey ?: return emptyList()
        val items = Stremio.savedLibrary(key) ?: runCatching { Stremio.library(key) }.getOrNull() ?: return emptyList()
        val byId = items.associateBy { it.optString("_id") }
        val watching = Library.continueWatching(items).map { it.optString("_id") }
        val listed = items.filter(Library::inList).map { it.optString("_id") }
        return saverPicks(watching, listed, Random.Default).mapNotNull { id ->
            val item = byId[id] ?: return@mapNotNull null
            val art = Images.backgroundFor(id) ?: return@mapNotNull null
            val resume = id in watching
            val episode = episodeLabel(item.optJSONObject("state")?.optString("video_id"))
            val line = if (resume) listOfNotNull("Continue watching", episode).joinToString(Screen.DOT) else "My List"
            Slide(id, item.optString("type", "movie"), item.optString("name"), art, Images.logoFor(id), line)
        }
    }
}

/**
 * The order the screensaver shows titles in: the most recent of Continue Watching first, then My List shuffled, each
 * title once. Only IMDb titles have art to show.
 */
internal fun saverPicks(watching: List<String>, listed: List<String>, random: Random, recent: Int = 12, most: Int = 40): List<String> =
    (watching.take(recent) + listed.shuffled(random)).filter { it.startsWith("tt") }.distinct().take(most)

/** "S2 · E5" from a series' video id ("tt0944947:2:5"); null for a film. */
internal fun episodeLabel(videoId: String?): String? =
    videoId?.let { Regex(""":(\d+):(\d+)$""").find(it) }?.let { "S${it.groupValues[1]} · E${it.groupValues[2]}" }

/** The slideshow itself: two pictures crossfading, the title's logo bottom left, the time top right. */
class SaverView(context: Context) : FrameLayout(context) {
    private val stage = FrameLayout(context)
    private val frames = List(2) { ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f } }
    private var front = 0
    private var at = -1
    private var misses = 0
    private var slides = emptyList<Screensaver.Slide>()
    private val handler = Handler(Looper.getMainLooper())
    private val next = Runnable { advance() }
    private val random = Random.Default
    private val light = font(300)
    private val medium = font(400)
    private val wide = resources.displayMetrics.widthPixels

    private val caption = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; alpha = 0f }
    private val logo = ImageView(context).apply {
        adjustViewBounds = true
        scaleType = ImageView.ScaleType.FIT_START
        maxWidth = dp(380)
        maxHeight = dp(110)
    }
    private val title = label(Screen.SERIF_HERO, light, Screen.TEXT)
    private val line = label(Screen.SMALL, font(600), Screen.TEXT_2).apply { letterSpacing = 0.12f }
    private val time = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }

    /** The title on screen now, for OK to open. */
    val current: Screensaver.Slide? get() = slides.getOrNull(at)

    init {
        setBackgroundColor(Screen.BASE)
        frames.forEach { stage.addView(it, LayoutParams(-1, -1)) }
        addView(stage, LayoutParams(-1, -1))
        // Smoke's shade: dark at the bottom and left under the words, a little at the top under the time.
        addView(shade(GradientDrawable.Orientation.BOTTOM_TOP, 0xE607090A.toInt(), 0x6607090A, 0x0007090A, 0x0007090A))
        addView(shade(GradientDrawable.Orientation.LEFT_RIGHT, 0xB307090A.toInt(), 0x0007090A, 0x0007090A))
        addView(shade(GradientDrawable.Orientation.TOP_BOTTOM, 0x8C07090A.toInt(), 0x0007090A, 0x0007090A, 0x0007090A))

        caption.addView(logo, LinearLayout.LayoutParams(-2, -2))
        caption.addView(title, LinearLayout.LayoutParams(dp(520), -2))
        caption.addView(line, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12) })
        addView(caption, LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { leftMargin = dp(64); bottomMargin = dp(56) })

        time.addView(TextClock(context).apply {
            format12Hour = "h:mm"; format24Hour = "HH:mm"
            textSize = 56f; typeface = light; setTextColor(Screen.TEXT); onArt()
        })
        time.addView(TextClock(context).apply {
            format12Hour = "EEEE d MMMM"; format24Hour = "EEEE d MMMM"
            textSize = Screen.BODY; typeface = medium; setTextColor(Screen.TEXT_2); onArt()
        })
        addView(time, LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { topMargin = dp(36); rightMargin = dp(56) })

        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            alpha = 0.55f
            addView(ImageView(context).apply { setImageResource(R.drawable.logo_transparent) }, LinearLayout.LayoutParams(dp(24), dp(24)))
            addView(label(16f, light, Screen.TEXT).apply { text = "canopy" }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(3) })
        }, LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { rightMargin = dp(56); bottomMargin = dp(56) })
    }

    /** Starts the slideshow; with no titles, Canopy's own scene drifts under the time. */
    fun start(list: List<Screensaver.Slide>) {
        stop()
        slides = list
        at = -1
        if (list.isEmpty()) {
            frames[front].setImageResource(R.drawable.onboarding_scene)
            drift(frames[front])
            ObjectAnimator.ofFloat(frames[front], View.ALPHA, 0f, 1f).setDuration(Screensaver.FADE_MS).start()
        } else advance()
    }

    fun stop() {
        handler.removeCallbacks(next)
        frames.forEach { it.animate().cancel() }
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    private fun advance() {
        if (slides.isEmpty()) return
        val index = (at + 1) % slides.size
        val slide = slides[index]
        val back = frames[1 - front]
        back.animate().cancel()
        back.alpha = 0f
        Images.load(slide.art, back, wide, urgent = true, failed = {
            // A title without art is skipped; if none load (offline), try again in a minute.
            at = index
            handler.postDelayed(next, if (++misses >= slides.size) 60_000L else 0L)
        }) { show(index, back) }
    }

    private fun show(index: Int, back: ImageView) {
        misses = 0
        at = index
        val old = frames[front]
        front = 1 - front
        stage.bringChildToFront(back)
        drift(back)
        ObjectAnimator.ofFloat(back, View.ALPHA, 0f, 1f).setDuration(Screensaver.FADE_MS).start()
        ObjectAnimator.ofFloat(old, View.ALPHA, old.alpha, 0f).apply {
            startDelay = Screensaver.FADE_MS
            duration = 1
        }.start()
        val slide = slides[index]
        caption.animate().alpha(0f).setStartDelay(0).setDuration(500).withEndAction {
            line.text = slide.line.uppercase()
            title.text = slide.name
            title.visibility = View.GONE
            logo.visibility = View.VISIBLE
            Images.load(slide.logo, logo, dp(380), urgent = true, failed = {
                logo.visibility = View.GONE
                title.visibility = View.VISIBLE
            })
            caption.animate().alpha(1f).setStartDelay(700).setDuration(900).start()
        }.start()
        // The time moves a little with each picture, so it never stays burnt into one place.
        time.animate().translationX(random.nextInt(-dp(16), dp(16)).toFloat())
            .translationY(random.nextInt(-dp(10), dp(10)).toFloat()).setDuration(Screensaver.FADE_MS).start()
        handler.postDelayed(next, Screensaver.SLIDE_MS)
    }

    /** Ken Burns: a slow zoom in or out with a pan in a random direction, lasting the picture's whole time on screen. */
    private fun drift(view: ImageView) {
        val zoomIn = random.nextBoolean()
        val (from, to) = if (zoomIn) 1.04f to 1.16f else 1.16f to 1.04f
        val dx = wide * 0.035f * (if (random.nextBoolean()) 1 else -1)
        val dy = resources.displayMetrics.heightPixels * 0.02f * (if (random.nextBoolean()) 1 else -1)
        view.scaleX = from; view.scaleY = from
        view.translationX = -dx / 2; view.translationY = -dy / 2
        view.animate().scaleX(to).scaleY(to).translationX(dx / 2).translationY(dy / 2)
            .setDuration(Screensaver.SLIDE_MS + Screensaver.FADE_MS).setInterpolator(LinearInterpolator()).start()
    }

    private fun shade(orientation: GradientDrawable.Orientation, vararg colors: Int) =
        View(context).apply { background = GradientDrawable(orientation, colors) }

    private fun label(sp: Float, face: Typeface, color: Int) = TextView(context).apply {
        textSize = sp; typeface = face; setTextColor(color); onArt()
    }

    private fun font(weight: Int): Typeface =
        Typeface.Builder(context.assets, "fonts/outfit.ttf").setFontVariationSettings("'wght' $weight").setWeight(weight).build()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}

/** Canopy's own screensaver, after a quiet while in the app. Any key ends it; OK opens the title on screen. */
class ScreensaverActivity : Activity() {
    private lateinit var saver: SaverView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prepareNetwork(this)
        window.setBackgroundDrawable(ColorDrawable(Screen.BASE))
        saver = SaverView(this)
        setContentView(saver)
        val app = applicationContext
        Thread {
            val slides = runCatching { Screensaver.slides(app) }.getOrDefault(emptyList())
            runOnUiThread { if (!isDestroyed) { if (slides.isEmpty()) finish() else saver.start(slides) } }
        }.start()
    }

    override fun onStop() {
        super.onStop()
        finish() // Home or another app: the screensaver doesn't wait behind them
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode in SYSTEM_KEYS) return super.dispatchKeyEvent(event)
        // Ends on the key's release, so the press can't also reach the screen underneath.
        if (event.action == KeyEvent.ACTION_UP) {
            val open = saver.current?.takeIf { event.keyCode in OPEN_KEYS }
            if (open != null) startActivity(Intent(this, DetailsActivity::class.java)
                .putExtra("type", open.type).putExtra("id", open.id).putExtra("name", open.name).putExtra("background", open.art))
            finish()
        }
        return true
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    private companion object {
        val SYSTEM_KEYS = setOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE,
            KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_POWER)
        val OPEN_KEYS = setOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)
    }
}

/** The same slideshow as the TV's screensaver (Android's DreamService), chosen in the TV's screensaver settings. */
class CanopyDream : DreamService() {
    private var saver: SaverView? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        prepareNetwork(this)
        setContentView(SaverView(this).also { saver = it })
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        val view = saver ?: return
        val app = applicationContext
        Thread {
            val slides = runCatching { Screensaver.slides(app) }.getOrDefault(emptyList())
            view.post { if (saver === view) view.start(slides) }
        }.start()
    }

    override fun onDreamingStopped() {
        saver?.stop()
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        saver = null
        super.onDetachedFromWindow()
    }
}
