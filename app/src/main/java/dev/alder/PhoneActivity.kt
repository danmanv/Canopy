package dev.alder

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Canopy on a phone, held upright: its own screens at the phone's own size, made for a thumb, not the TV's screens
 * shrunk. Home, Shows, Movies and My List sit on tabs along the bottom. Each page opens on a carousel: one big poster in a
 * fixed frame in the middle, with the posters either side sliding through it, and the title's Resume (or Play) under it.
 * Rows of posters follow. Titles, sources and the player are still the TV's screens, held sideways.
 */
class PhoneActivity : PhoneScreen() {

    private enum class Page(val label: String, val icon: Int) {
        HOME("Home", R.drawable.ic_home), SHOWS("Shows", R.drawable.ic_tv), MOVIES("Movies", R.drawable.ic_movie), LIST("My List", R.drawable.ic_add)
    }

    /** A title on a page. [left]: Continue Watching's "S1 E3 · 31 min left". */
    private class Item(
        val id: String, val type: String, val name: String, val poster: String?, var art: String?,
        val videoId: String?, val progress: Float, val left: String?, var facts: String,
    ) {
        val resume get() = progress > 0f
    }

    private lateinit var profile: Profile
    private var page = Page.HOME
    // Each page stays made once it has been opened, so going back to a tab shows it at once, posters and all.
    private val pages = mutableMapOf<Page, LinearLayout>()
    private val builds = mutableMapOf<Page, Int>() // which build of a page an answer belongs to: older ones are dropped
    private val scrolledTo = mutableMapOf<Page, Int>()
    private val framed = mutableMapOf<Page, Item>() // the title in each page's frame, whose art is behind it
    private val refreshLibrary = mutableMapOf<Page, () -> Unit>()
    private var library: List<JSONObject> = emptyList()
    private val metas = mutableMapOf<String, Meta>()

    private lateinit var scroll: ScrollView
    private lateinit var host: FrameLayout
    private lateinit var wash: ImageView
    private val barShade by lazy { View(this).apply { setBackgroundColor(baseAt(0xF5)); alpha = 0f; elevation = 0f } }
    private val tabs = mutableMapOf<Page, LinearLayout>()

    private val screenW by lazy { resources.displayMetrics.widthPixels }
    private val posterW get() = dp(POSTER_W)
    private val carouselW get() = minOf(screenW * 62 / 100, dp(CAROUSEL_MAX)) // the framed poster: 62% of the width, as on the iPhone
    private val posterH get() = dp(POSTER_W * 3 / 2)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        profile = Profiles.active(this) ?: return switchProfile()
        host = FrameLayout(this)
        scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            isFocusable = false
            addView(host, FrameLayout.LayoutParams(-1, -2))
        }
        wash = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f }
        val fade = View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(baseAt(0x80), baseAt(0x99), baseAt(0xFF)))
        }
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(BASE))
        setContentView(FrameLayout(this).apply {
            addView(wash, FrameLayout.LayoutParams(-1, dp(BACKDROP_H)))
            addView(fade, FrameLayout.LayoutParams(-1, dp(BACKDROP_H)))
            addView(scroll, FrameLayout.LayoutParams(-1, -1))
            addView(barShade, FrameLayout.LayoutParams(-1, dp(TOP_BAR), Gravity.TOP))
            addView(topBar(), FrameLayout.LayoutParams(-1, dp(TOP_BAR), Gravity.TOP))
            addView(tabBar(), FrameLayout.LayoutParams(-1, dp(TAB_BAR), Gravity.BOTTOM))
        })
        // The wash scrolls away with the carousel above it.
        // Scrolled, the top bar gets a solid backing, so the logo and buttons never sit on the posters.
        scroll.setOnScrollChangeListener { _, _, y, _, _ ->
            wash.translationY = -y.toFloat()
            barShade.alpha = (y / dp(60).toFloat()).coerceIn(0f, 1f)
        }
        showPage(pageNamed(intent.getStringExtra("page")))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra("page")?.let { showPage(pageNamed(it)) }
    }

    // Back from a title or the player: Continue Watching and My List show where things stand now.
    override fun onRestart() {
        super.onRestart()
        refreshLibrary.values.forEach { it() }
    }

    override fun onResume() {
        super.onResume()
        if (Updates.due(this)) background({ Updates.latest(applicationContext) }) { r ->
            val release = r.getOrNull()?.takeIf { newerVersion(it.version, Updates.mine(this)) } ?: return@background
            if (Updates.offerOnce(this, release.version)) choose("Canopy ${release.version} is ready", listOf("Later", "Install"),
                "Install it now? It takes a minute, and your profiles and settings stay.") { if (it == 1) installUpdate(release) }
        }
    }

    @Deprecated("Activity API")
    override fun onBackPressed() {
        if (page != Page.HOME) return showPage(Page.HOME)
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    private fun pageNamed(label: String?) = Page.entries.firstOrNull { it.label == label } ?: Page.HOME

    private fun switchProfile() {
        startActivity(Intent(this, ProfilesActivity::class.java).putExtra("pick", true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        finish()
    }

    // ---------- bars ----------

    private fun topBar(): View = FrameLayout(this).apply {
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(baseAt(0xE6), baseAt(0x00)))
        addView(brand(), FrameLayout.LayoutParams(-2, -2, Gravity.START or Gravity.CENTER_VERTICAL).apply { leftMargin = dp(20) })
        val buttons = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        buttons.addView(iconButton(R.drawable.ic_search, "Search") {
            startActivity(Intent(this@PhoneActivity, PhoneSearchActivity::class.java))
        }, LinearLayout.LayoutParams(dp(TOUCH), dp(TOUCH)))
        buttons.addView(FrameLayout(context).apply {
            contentDescription = "Profile ${profile.name}"
            addView(text(profile.name.take(1).uppercase(), 15f, bold = true, color = BASE).apply {
                gravity = Gravity.CENTER
                background = rounded(profile.color, CORNER)
            }, FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER))
            setOnClickListener {
                choose(profile.name, listOf("Switch profile", "Settings"), "Watching now") { i ->
                    if (i == 0) switchProfile() else startActivity(Intent(this@PhoneActivity, SettingsActivity::class.java))
                }
            }
        }, LinearLayout.LayoutParams(dp(TOUCH), dp(TOUCH)))
        addView(buttons, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL).apply { rightMargin = dp(8) })
    }

    // Soft: the bar fades up out of the page instead of starting at a hard line, and the open tab glows.
    private fun tabBar(): View = LinearLayout(this).apply {
        background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(baseAt(0xFA), baseAt(0xEB), baseAt(0x00)))
        setPadding(dp(8), dp(14), dp(8), dp(4))
        Page.entries.forEach { p ->
            val icon = ImageView(context).apply { setImageResource(p.icon); scaleType = ImageView.ScaleType.FIT_CENTER }
            val tab = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                contentDescription = p.label
                addView(icon, LinearLayout.LayoutParams(dp(24), dp(24)))
                addView(text(p.label, 12f, bold = true), wrap().apply { topMargin = dp(4) })
                setOnClickListener { if (p != page || pages[p] == null) showPage(p) else scroll.smoothScrollTo(0, 0) }
            }
            tabs[p] = tab
            addView(tab, LinearLayout.LayoutParams(0, -1, 1f))
        }
    }

    private fun styleTabs() = tabs.forEach { (p, tab) ->
        val color = if (p == page) TUNGSTEN else TEXT_2
        (tab.getChildAt(0) as ImageView).setColorFilter(color)
        (tab.getChildAt(1) as TextView).setTextColor(color)
        tab.background = if (p == page) GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            gradientRadius = dp(44).toFloat()
            setGradientCenter(0.5f, 0.4f)
            colors = intArrayOf(withAlpha(TUNGSTEN, 0x38), withAlpha(TUNGSTEN, 0x00))
        } else null
    }

    // ---------- pages ----------

    /** Opens [p] as it was left, or makes it the first time (and again with [remake]). */
    private fun showPage(p: Page, remake: Boolean = false) {
        if (pages[page]?.visibility == View.VISIBLE) scrolledTo[page] = scroll.scrollY
        page = p
        styleTabs()
        pages.values.forEach { it.visibility = View.GONE }
        val kept = pages[p]?.takeIf { !remake }
        if (kept != null) {
            kept.visibility = View.VISIBLE
            val y = scrolledTo[p] ?: 0
            scroll.scrollTo(0, y)
            scroll.post { scroll.scrollTo(0, y) }
            wash.translationY = -y.toFloat()
            framed[p]?.let { showBackdrop(it, p) } ?: wash.animate().alpha(0f).setDuration(Motion.QUICK).start()
            return
        }
        pages.remove(p)?.let { host.removeView(it) }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(TOP_BAR), 0, dp(TAB_BAR + 24))
        }
        pages[p] = content
        host.addView(content, FrameLayout.LayoutParams(-1, -2))
        scroll.scrollTo(0, 0)
        scrolledTo[p] = 0
        framed.remove(p)
        wash.translationY = 0f
        wash.animate().alpha(0f).setDuration(Motion.QUICK).start()
        val g = (builds[p] ?: 0) + 1
        builds[p] = g
        fun current() = builds[p] == g
        refreshLibrary.remove(p)
        val key = profile.authKey
        fun slot() = FrameLayout(this).also { content.addView(it, LinearLayout.LayoutParams(-1, -2)) }
        fun failed(error: Throwable) = failedToLoad(error, content)
        val carouselSlot = slot()
        val rowsSlot = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(rowsSlot, LinearLayout.LayoutParams(-1, -2))
        var carouselFrom: String? = null // "library" or a catalog: Continue Watching wins when there is any

        if (p == Page.HOME || p == Page.LIST) {
            var shown: String? = null
            fun show(list: List<JSONObject>) {
                if (!current()) return
                library = list
                val items = if (p == Page.HOME) {
                    val cw = Library.continueWatching(list)
                    splitTonight(cw, { it.optString("_id") }, HomeRows.tonight(this)).let { (a, b) -> a + b }
                } else list.filter { !it.optBoolean("removed") && it.optString("type") in listOf("movie", "series") }
                    .sortedByDescending { it.optString("_ctime") }
                val what = items.joinToString { it.optString("_id") + it.optJSONObject("state")?.optLong("timeOffset") }
                if (what == shown) return
                shown = what
                if (p == Page.LIST) {
                    carouselSlot.removeAllViews()
                    carouselSlot.addView(grid(items.map(::libraryItem)), FrameLayout.LayoutParams(-1, -2))
                } else if (items.isNotEmpty()) {
                    carouselFrom = "library"
                    carouselSlot.removeAllViews()
                    carouselSlot.addView(carousel("Continue watching", items.map(::libraryItem), p), FrameLayout.LayoutParams(-1, -2))
                }
            }
            background({ Stremio.savedLibrary(key) }) { r -> r.getOrNull()?.let(::show) }
            fun ask() = background({ Stremio.library(key) }) { r ->
                r.fold(::show) { e -> if (shown == null && current()) failed(e) }
            }
            ask()
            refreshLibrary[p] = ::ask
        }
        if (p == Page.LIST) return

        var built = false
        fun build(addons: List<Addon>) {
            if (built || !current()) return
            built = true
            val all = addons.flatMap { it.catalogs }
            // Home has the rows chosen in Settings; with none chosen, the add-ons' first film and show catalogs.
            val catalogs = if (p == Page.HOME) HomeRows.arranged(this, all).filter { it.second }.map { it.first }
                .ifEmpty { all.filter { it.type == "movie" || it.type == "series" }.take(HOME_FALLBACK) }
            else all.filter { it.type == if (p == Page.MOVIES) "movie" else "series" }
            if (p == Page.HOME) warmTabs(all)
            if (catalogs.isEmpty()) {
                rowsSlot.addView(text(if (p == Page.HOME) "Choose rows for Home in Settings, under Home."
                    else "None of your add-ons has ${p.label.lowercase()}. Add one in Stremio.", BODY_SP, color = TEXT_2)
                    .apply { setPadding(dp(20), dp(24), dp(20), 0) })
                return
            }
            catalogs.take(MAX_ROWS).forEachIndexed { i, c ->
                val slot = FrameLayout(this).also { rowsSlot.addView(it, LinearLayout.LayoutParams(-1, -2)) }
                val name = rowName(c, p)
                fun place(metas: List<Meta>) {
                    if (!current()) return
                    val items = metas.filterNot { isAddonMessage(it.name) }.distinctBy { it.id }.take(ROW_MAX).map(::metaItem)
                    if (items.isEmpty()) return
                    // Shows and Movies open on their first catalog; Home on it only while nothing is in progress.
                    if (i == 0 && (carouselFrom == null || carouselFrom == c.id)) {
                        carouselFrom = c.id
                        carouselSlot.removeAllViews()
                        carouselSlot.addView(carousel(name, items, p), FrameLayout.LayoutParams(-1, -2))
                        if (p != Page.HOME) return
                    }
                    slot.removeAllViews()
                    slot.addView(row(name, items), FrameLayout.LayoutParams(-1, -2))
                }
                background({ runCatching { c.addon.savedCatalog(c) }.getOrNull() }) { saved ->
                    val had = saved.getOrNull()?.takeIf { it.isNotEmpty() }
                    had?.let(::place)
                    background({ c.addon.catalog(c) }) { fresh ->
                        fresh.getOrNull()?.takeIf { f -> f.map { it.id }.take(ROW_MAX) != had?.map { it.id }?.take(ROW_MAX) }?.let(::place)
                    }
                }
            }
        }
        background({ Stremio.savedAddons(key) }) { r -> r.getOrNull()?.let(::build) }
        background({ Stremio.addons(key, refresh = true) }) { r -> r.fold(::build) { e -> if (!built && current()) failed(e) } }
    }

    /** Shows' and Movies' first catalogs, fetched while Home shows: their tabs then open with posters, not grey boxes. */
    private fun warmTabs(all: List<Addon.Catalog>) = listOf("series", "movie").forEach { type ->
        val c = all.firstOrNull { it.type == type } ?: return@forEach
        val px = carouselW
        background({
            val metas = runCatching { c.addon.savedCatalog(c) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: c.addon.catalog(c)
            Images.warmAll(metas.take(4).mapNotNull { it.poster }, px, 4000)
            Images.warmAll(metas.take(12).mapNotNull { it.poster }, posterW, 4000)
        }) {}
    }

    private fun failedToLoad(error: Throwable, content: LinearLayout) {
        if ((error as? Stremio.ApiException)?.code == Stremio.NO_SESSION) {
            Profiles.markSignedOut(this, profile.id)
            startActivity(Intent(this, ProfilesActivity::class.java).putExtra("signIn", profile.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            return finish()
        }
        if (content.findViewWithTag<View>("failed") != null) return
        content.addView(column(Gravity.START,
            text("Couldn't reach Stremio. Check the phone's connection.", BODY_SP, color = TEXT_2),
            button("Try again", primary = false) { showPage(page, remake = true) }.apply { layoutParams = LinearLayout.LayoutParams(dp(160), dp(TOUCH)).apply { topMargin = dp(14) } },
        ).apply { tag = "failed"; setPadding(dp(20), dp(24), dp(20), 0) }, 0)
    }

    /** A catalog's name on its page: on Home, where films and shows share a page, it says which it holds. */
    private fun rowName(c: Addon.Catalog, p: Page): String {
        if (HomeRows.onByDefault(c)) return homeRowName(c.name)
        val name = c.name.removePrefix("TV Shows: ").removePrefix("MyTrakt - ").split(" ").filter { it.length < 16 }.joinToString(" ")
        if (p != Page.HOME || Regex("(?i)\\b(movies?|films?|shows?|series|tv)\\b").containsMatchIn(name)) return name
        return when (c.type) { "movie" -> "$name · Movies"; "series" -> "$name · Shows"; else -> name }
    }

    // ---------- the carousel ----------

    /**
     * One big poster in a fixed frame in the middle; the posters either side slide through the frame as the finger
     * moves, and stop with one in it. A glass strip across the framed poster's foot is its Resume (or Play) button, with
     * the time left, frosted from the poster and warmed with its colour, as on the iPhone; it fades while the posters move.
     * The framed title's name sits under it.
     */
    private fun carousel(title: String, items: List<Item>, p: Page): View {
        val cardW = carouselW
        val cardH = cardW * 3 / 2
        val gap = dp(14)
        val step = cardW + gap
        val side = (screenW - cardW) / 2
        val cards = mutableListOf<ImageView>()
        var selected = -1

        val name = text("", 20f, serif = true).apply {
            gravity = Gravity.CENTER; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        val strip = ResumeStrip(STRIP_H)
        val done = View(this).apply { setBackgroundColor(TUNGSTEN) }
        // The strip and, under it along the poster's very bottom edge, how far along it is.
        val foot = FrameLayout(this).apply {
            addView(strip, FrameLayout.LayoutParams(-1, dp(STRIP_H), Gravity.TOP))
            addView(done, FrameLayout.LayoutParams(0, dp(3), Gravity.BOTTOM or Gravity.START))
            // Only the bottom corners round, with the poster's.
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) =
                    outline.setRoundRect(0, -dp(CORNER), view.width, view.height, dp(CORNER).toFloat())
            }
            clipToOutline = true
        }

        lateinit var scroller: HorizontalScrollView
        fun select(i: Int) {
            if (i == selected || i !in items.indices) return
            if (selected >= 0) scroller.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            selected = i
            val item = items[i]
            name.text = item.name
            done.visibility = if (item.resume) View.VISIBLE else View.GONE
            done.layoutParams = (done.layoutParams as FrameLayout.LayoutParams).apply { width = (cardW * item.progress).toInt() }
            val timeLeft = item.left?.split(DOT_SHORT)?.let { parts ->
                if (item.type == "series") parts.joinToString(DOT_SHORT) else parts.lastOrNull { it.endsWith("left") }
            }
            // A film, or a show Continue Watching has an episode for (the next one, not started yet): Play starts it.
            val playable = item.resume || item.type != "series" || item.videoId != null
            strip.bind(cards[i], if (item.resume) "Resume" else if (playable) "Play" else "Episodes", timeLeft)
            strip.setOnClickListener { if (playable) play(item) else openDetails(item) }
            strip.setOnLongClickListener { titleMenu(item); true }
            framed[p] = item
            showBackdrop(item, p)
            fill(item) { if (selected == i) showBackdrop(item, p) }
            items.getOrNull(i + 1)?.let { fill(it) {} }
        }

        fun shape() {
            val middle = scroller.scrollX + side + cardW / 2f
            cards.forEachIndexed { i, card ->
                val d = (abs(side + i * step + cardW / 2f - middle) / step).coerceAtMost(1f)
                card.scaleX = 1f - 0.14f * d
                card.scaleY = 1f - 0.14f * d
                card.alpha = 1f - 0.55f * d
            }
        }

        fun settle(velocity: Int) {
            val at = scroller.scrollX / step.toFloat()
            val extra = (abs(velocity) / dp(2200)).coerceAtMost(3)
            val to = when {
                velocity > 0 -> ceil(at).toInt() + extra
                velocity < 0 -> floor(at).toInt() - extra
                else -> at.roundToInt()
            }.coerceIn(0, items.lastIndex)
            scroller.smoothScrollTo(to * step, 0)
        }

        // While the posters move (a finger, or the glide to a stop) the strip steps aside, and comes back once they rest.
        val rest = Runnable { foot.animate().alpha(1f).setDuration(300).start() }
        scroller = object : HorizontalScrollView(this) {
            var flung = false
            override fun fling(velocityX: Int) { flung = true; settle(velocityX) }
            override fun onTouchEvent(ev: MotionEvent): Boolean {
                if (ev.actionMasked == MotionEvent.ACTION_DOWN) flung = false
                val used = super.onTouchEvent(ev)
                if ((ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) && !flung) settle(0)
                return used
            }
        }.apply {
            isHorizontalScrollBarEnabled = false
            isFocusable = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
        }
        val line = LinearLayout(this).apply { setPadding(side, 0, side - gap, 0); gravity = Gravity.CENTER_VERTICAL }
        items.forEachIndexed { i, item ->
            val card = poster(item, cardW) { if (i == selected) strip.invalidate() }.apply {
                setOnClickListener { if (i == selected) openDetails(item) else scroller.smoothScrollTo(i * step, 0) }
                setOnLongClickListener { titleMenu(item); true }
            }
            cards += card
            line.addView(card, LinearLayout.LayoutParams(cardW, cardH).apply { rightMargin = gap })
        }
        scroller.addView(line, FrameLayout.LayoutParams(-2, -1))
        var lastX = 0
        scroller.setOnScrollChangeListener { _, x, _, _, _ ->
            shape()
            select((x / step.toFloat()).roundToInt())
            if (x != lastX) {
                lastX = x
                if (foot.alpha > 0f) foot.animate().alpha(0f).setDuration(120).start()
                main.removeCallbacks(rest)
                main.postDelayed(rest, 160)
            }
        }

        // The frame stays put; the posters move through it.
        val ring = dp(5)
        val frame = View(this).apply {
            background = GradientDrawable().apply { setStroke(dp(3), TUNGSTEN_RIM); cornerRadius = dp(CORNER + 2).toFloat() }
            elevation = dp(2).toFloat()
        }
        val stage = FrameLayout(this).apply {
            clipChildren = false
            addView(scroller, FrameLayout.LayoutParams(-1, cardH + 2 * ring, Gravity.CENTER_VERTICAL))
            addView(foot, FrameLayout.LayoutParams(cardW, dp(STRIP_H + 3), Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM).apply {
                bottomMargin = ring
            })
            addView(frame, FrameLayout.LayoutParams(cardW + 2 * ring, cardH + 2 * ring, Gravity.CENTER))
        }
        foot.elevation = dp(1).toFloat()

        val block = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(heading(title).apply { setPadding(dp(20), dp(6), dp(20), dp(4)); gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(-1, -2))
            addView(stage, LinearLayout.LayoutParams(-1, cardH + 2 * ring).apply { topMargin = dp(4) })
            addView(name, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12); leftMargin = dp(20); rightMargin = dp(20) })
        }
        select(0)
        scroller.post { shape() }
        return block
    }

    private fun showBackdrop(item: Item, p: Page) {
        if (p != page) return
        val url = item.art ?: Images.backgroundFor(item.id) ?: item.poster
        if (wash.tag == url && wash.drawable != null) { wash.animate().alpha(WASH).setDuration(Motion.QUICK).start(); return }
        wash.animate().cancel()
        wash.alpha = 0f
        Images.load(url, wash, screenW) { wash.animate().alpha(WASH).setDuration(Motion.STANDARD).start() }
    }

    // ---------- rows and the grid ----------

    private fun heading(title: String) = text(title, 17f, serif = true).apply {
        setPadding(dp(20), dp(18), dp(20), dp(8))
        maxLines = 1; ellipsize = TextUtils.TruncateAt.END
    }

    private fun row(title: String, items: List<Item>): View {
        val line = LinearLayout(this).apply { setPadding(dp(20), 0, dp(10), 0) }
        items.forEach { item ->
            line.addView(poster(item, posterW).apply {
                setOnClickListener { openDetails(item) }
                setOnLongClickListener { titleMenu(item); true }
            }, LinearLayout.LayoutParams(posterW, posterH).apply { rightMargin = dp(10) })
        }
        val strip = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isFocusable = false
            addView(line)
        }
        return column(Gravity.START, heading(title).apply { layoutParams = LinearLayout.LayoutParams(-1, -2) },
            strip.apply { layoutParams = LinearLayout.LayoutParams(-1, posterH) })
    }

    private fun grid(items: List<Item>): View {
        if (items.isEmpty()) return text("Titles you save with + appear here", BODY_SP, color = TEXT_2).apply { setPadding(dp(20), dp(30), dp(20), 0) }
        val gap = dp(10)
        val w = (screenW - dp(40) - 2 * gap) / 3
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), 0, dp(20), 0) }
        box.addView(text("${items.size} titles", SMALL_SP, color = TEXT_2).apply { setPadding(0, dp(12), 0, dp(10)) })
        items.chunked(3).forEach { three ->
            val line = LinearLayout(this)
            three.forEachIndexed { i, item ->
                line.addView(poster(item, w).apply {
                    setOnClickListener { openDetails(item) }
                    setOnLongClickListener { titleMenu(item); true }
                }, LinearLayout.LayoutParams(w, w * 3 / 2).apply { if (i > 0) leftMargin = gap })
            }
            box.addView(line, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = gap })
        }
        return box
    }

    private fun poster(item: Item, widthPx: Int, loaded: (() -> Unit)? = null) = ImageView(this).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        background = rounded(TILE, CORNER)
        clipToOutline = true
        contentDescription = item.name
        Images.load(item.poster, this, widthPx, orElse = item.art) { loaded?.invoke() }
    }

    // ---------- titles ----------

    /** Details the catalog or library didn't send (art, year, runtime, episodes), fetched once per title. */
    private fun fill(item: Item, done: () -> Unit) {
        metas[item.id]?.let { applyMeta(item, it); return done() }
        val key = profile.authKey
        background({ Stremio.addons(key).let { it.savedMeta(item.type, item.id) ?: it.meta(item.type, item.id) } }) { r ->
            r.getOrNull()?.let { m -> metas[item.id] = m; applyMeta(item, m); done() }
        }
    }

    private fun applyMeta(item: Item, m: Meta) {
        if (item.art == null) item.art = m.background
        val about = listOfNotNull(m.year, m.genres.firstOrNull(), m.runtime).joinToString(DOT_SHORT)
        item.facts = listOfNotNull(item.left, about.ifEmpty { null }).joinToString(DOT_SHORT)
    }

    /**
     * Press and hold a poster: the title's page, or a source chosen by hand. A title in progress can also start over, be
     * marked watched (a show moves on to its next episode) or leave Continue Watching.
     */
    private fun titleMenu(item: Item) {
        val inProgress = item.resume && page == Page.HOME
        val show = item.type == "series"
        val startOver = if (show) "Start episode over" else "Start over"
        val watched = if (show) "Mark episode as watched" else "Mark as watched"
        val options = listOfNotNull(startOver.takeIf { inProgress }, watched.takeIf { inProgress }, "More info",
            "Choose a source".takeIf { item.resume || item.type != "series" }, "Remove from Continue Watching".takeIf { inProgress })
        choose(item.name, options) { i ->
            when (options[i]) {
                startOver -> play(item, startOver = true)
                watched -> markWatched(item)
                "More info" -> openDetails(item)
                "Choose a source" -> play(item, pick = true)
                else -> hide(item)
            }
        }
    }

    /** "Mark as watched" from Continue Watching: a film is done and leaves the row; a show moves to its next episode. */
    private fun markWatched(item: Item) {
        val original = library.firstOrNull { it.optString("_id") == item.id } ?: return
        val updated = JSONObject(original.toString())
        val key = profile.authKey
        val known = metas[item.id]
        background({
            if (item.type == "series") Library.finishEpisode(updated,
                (known ?: Stremio.addons(key).let { it.savedMeta(item.type, item.id) ?: it.meta(item.type, item.id) })?.videos.orEmpty())
            else Library.markTitleWatched(updated)
            Stremio.saveLibrary(key, listOf(updated))
        }) { result ->
            result.fold({ refreshLibrary[Page.HOME]?.invoke() }) {
                if (!isFinishing) choose("Couldn't mark it watched", listOf("OK"), "Check the connection and try again.") { }
            }
        }
    }

    private fun hide(item: Item) {
        val original = library.firstOrNull { it.optString("_id") == item.id } ?: return
        val updated = JSONObject(original.toString())
        val offset = Library.hideFromContinueWatching(updated)
        background({ Stremio.saveLibrary(profile.authKey, listOf(updated)) }) { result ->
            result.fold({
                Settings.setHidden(this, Settings.hidden(this).put(item.id, JSONObject().put("name", item.name).put("timeOffset", offset)))
                HomeRows.pin(this, item.id, false)
                if (page == Page.HOME) showPage(Page.HOME, remake = true) else pages.remove(Page.HOME)?.let { host.removeView(it) }
            }) { if (!isFinishing) choose("Couldn't remove it", listOf("OK"), "Check the connection and try again.") { } }
        }
    }

    private fun play(item: Item, pick: Boolean = false, startOver: Boolean = false) {
        val videoId = item.videoId ?: item.id
        val meta = metas[item.id]
        val videos = meta?.videos.orEmpty()
        val at = videos.indexOfFirst { it.id == videoId }
        val next = if (at >= 0) videos.getOrNull(at + 1) else null
        val episode = item.left?.substringBefore(DOT_SHORT)?.takeIf { item.type == "series" && it.startsWith("S") }
        startActivity(Intent(this, if (pick) PhoneStreamsActivity::class.java else StreamsActivity::class.java)
            .putExtra("type", item.type).putExtra("id", item.id).putExtra("name", item.name).putExtra("poster", item.poster)
            .putExtra("videoId", videoId).putExtra("nextVideoId", next?.id).putExtra("pick", pick).putExtra("startOver", startOver)
            .putExtra("background", meta?.background ?: item.art)
            .putExtra("logo", meta?.logo ?: Images.logoFor(item.id))
            .putExtra("still", videos.getOrNull(at)?.thumbnail)
            .putExtra("runtime", meta?.runtime)
            .putExtra("episode", videos.getOrNull(at)?.let { "S${it.season} E${it.episode}  ·  ${it.title}" })
            .putExtra("label", listOfNotNull(item.name, episode).joinToString("  ·  ")))
    }

    private fun openDetails(item: Item) {
        startActivity(Intent(this, PhoneDetailsActivity::class.java)
            .putExtra("type", item.type).putExtra("id", item.id).putExtra("name", item.name)
            .putExtra("poster", item.poster).putExtra("background", item.art).putExtra("videoId", item.videoId))
    }

    private fun metaItem(m: Meta) = Item(m.id, m.type, m.name, m.poster, m.background, null, 0f, null,
        listOfNotNull(m.year, m.genres.firstOrNull(), m.runtime).joinToString(DOT_SHORT))

    private fun libraryItem(item: JSONObject): Item {
        val s = item.optJSONObject("state") ?: JSONObject()
        val videoId = s.text("video_id")
        val duration = s.optLong("duration")
        val offset = s.optLong("timeOffset")
        val episode = videoId?.split(":")?.takeIf { it.size == 3 }?.let { "S${it[1]} E${it[2]}" }
        val mins = (duration - offset) / 60_000
        val left = if (duration > 0 && offset > 1) (if (mins >= 60) "${mins / 60}h ${mins % 60}m left" else "$mins min left") else null
        val progress = if (duration > 0 && offset > 1) (offset.toFloat() / duration).coerceIn(0.01f, 1f) else 0f
        val line = listOfNotNull(episode, left).joinToString(DOT_SHORT).ifEmpty { null }
        return Item(item.getString("_id"), item.optString("type"), item.optString("name"), item.text("poster"),
            null, videoId, progress, line, line ?: "")
    }

    private companion object {
        const val TOP_BAR = 60
        const val TAB_BAR = 64
        const val CAROUSEL_MAX = 330 // dp: the framed poster at most
        const val STRIP_H = 66 // dp: the glass Resume strip across the framed poster's foot
        const val POSTER_W = 112 // dp: a row's posters
        const val BACKDROP_H = 560
        const val MAX_ROWS = 14
        const val HOME_FALLBACK = 6
        const val WASH = 0.55f // the framed title's art behind the page
        const val ROW_MAX = 40
    }
}
