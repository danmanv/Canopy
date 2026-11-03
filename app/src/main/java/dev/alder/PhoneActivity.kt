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
class PhoneActivity : Screen() {
    override val phoneLayout get() = true
    override val backArrow get() = false

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
    private var generation = 0 // which page answers belong to: late ones from an earlier page are dropped
    private var library: List<JSONObject> = emptyList()
    private val metas = mutableMapOf<String, Meta>()
    private var refreshLibrary: (() -> Unit)? = null

    private lateinit var scroll: ScrollView
    private lateinit var content: LinearLayout
    private lateinit var wash: ImageView
    private val tabs = mutableMapOf<Page, LinearLayout>()

    private val screenW by lazy { resources.displayMetrics.widthPixels }
    private val posterW get() = dp(POSTER_W)
    private val posterH get() = dp(POSTER_W * 3 / 2)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        profile = Profiles.active(this) ?: return switchProfile()
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(TOP_BAR), 0, dp(TAB_BAR + 24))
        }
        scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            isFocusable = false
            addView(content, FrameLayout.LayoutParams(-1, -2))
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
            addView(topBar(), FrameLayout.LayoutParams(-1, dp(TOP_BAR), Gravity.TOP))
            addView(tabBar(), FrameLayout.LayoutParams(-1, dp(TAB_BAR), Gravity.BOTTOM))
        })
        // The wash scrolls away with the carousel above it.
        scroll.setOnScrollChangeListener { _, _, y, _, _ -> wash.translationY = -y.toFloat() }
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
        refreshLibrary?.invoke()
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
            startActivity(Intent(this@PhoneActivity, SearchActivity::class.java))
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

    private fun iconButton(icon: Int, label: String, click: () -> Unit) = ImageView(this).apply {
        setImageResource(icon)
        setColorFilter(TEXT)
        scaleType = ImageView.ScaleType.CENTER
        contentDescription = label
        setOnClickListener { click() }
    }

    private fun tabBar(): View = LinearLayout(this).apply {
        background = GradientDrawable().apply { setColor(baseAt(0xF0)); setStroke(dp(1), GLASS_EDGE) }
        Page.entries.forEach { p ->
            val icon = ImageView(context).apply { setImageResource(p.icon); scaleType = ImageView.ScaleType.FIT_CENTER }
            val tab = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                contentDescription = p.label
                addView(icon, LinearLayout.LayoutParams(dp(24), dp(24)))
                addView(text(p.label, 12f, bold = true), wrap().apply { topMargin = dp(4) })
                setOnClickListener { if (p != page) showPage(p) else scroll.smoothScrollTo(0, 0) }
            }
            tabs[p] = tab
            addView(tab, LinearLayout.LayoutParams(0, -1, 1f))
        }
    }

    private fun styleTabs() = tabs.forEach { (p, tab) ->
        val color = if (p == page) TUNGSTEN else TEXT_2
        (tab.getChildAt(0) as ImageView).setColorFilter(color)
        (tab.getChildAt(1) as TextView).setTextColor(color)
        tab.background = if (p == page) GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(withAlpha(TUNGSTEN, 0x33), 0)) else null
    }

    // ---------- pages ----------

    private fun showPage(p: Page) {
        page = p
        val g = ++generation
        styleTabs()
        content.removeAllViews()
        scroll.scrollTo(0, 0)
        wash.translationY = 0f
        wash.animate().alpha(0f).setDuration(Motion.QUICK).start()
        refreshLibrary = null
        val key = profile.authKey
        val carouselSlot = slot()
        val rowsSlot = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(rowsSlot, LinearLayout.LayoutParams(-1, -2))
        var carouselFrom: String? = null // "library" or a catalog: Continue Watching wins when there is any

        if (p == Page.HOME || p == Page.LIST) {
            var shown: String? = null
            fun show(list: List<JSONObject>) {
                if (g != generation) return
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
                    carouselSlot.addView(carousel("Continue watching", items.map(::libraryItem)), FrameLayout.LayoutParams(-1, -2))
                }
            }
            background({ Stremio.savedLibrary(key) }) { r -> r.getOrNull()?.let(::show) }
            fun ask() = background({ Stremio.library(key) }) { r ->
                r.fold(::show) { e -> if (shown == null && g == generation) failedToLoad(e) }
            }
            ask()
            refreshLibrary = ::ask
        }
        if (p == Page.LIST) return

        var built = false
        fun build(addons: List<Addon>) {
            if (built || g != generation) return
            built = true
            val catalogs = if (p == Page.HOME) HomeRows.arranged(this, addons.flatMap { it.catalogs }).filter { it.second }.map { it.first }
            else addons.flatMap { it.catalogs }.filter { it.type == if (p == Page.MOVIES) "movie" else "series" }
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
                    if (g != generation) return
                    val items = metas.filterNot { isAddonMessage(it.name) }.distinctBy { it.id }.take(ROW_MAX).map(::metaItem)
                    if (items.isEmpty()) return
                    // Shows and Movies open on their first catalog; Home on it only while nothing is in progress.
                    if (i == 0 && (carouselFrom == null || carouselFrom == c.id)) {
                        carouselFrom = c.id
                        carouselSlot.removeAllViews()
                        carouselSlot.addView(carousel(name, items), FrameLayout.LayoutParams(-1, -2))
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
        background({ Stremio.addons(key, refresh = true) }) { r -> r.fold(::build) { e -> if (!built && g == generation) failedToLoad(e) } }
    }

    private fun slot() = FrameLayout(this).also { content.addView(it, LinearLayout.LayoutParams(-1, -2)) }

    private fun failedToLoad(error: Throwable) {
        if ((error as? Stremio.ApiException)?.code == Stremio.NO_SESSION) {
            Profiles.markSignedOut(this, profile.id)
            startActivity(Intent(this, ProfilesActivity::class.java).putExtra("signIn", profile.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            return finish()
        }
        if (content.findViewWithTag<View>("failed") != null) return
        content.addView(column(Gravity.START,
            text("Couldn't reach Stremio. Check the phone's connection.", BODY_SP, color = TEXT_2),
            button("Try again", primary = false) { showPage(page) }.apply { layoutParams = LinearLayout.LayoutParams(dp(160), dp(TOUCH)).apply { topMargin = dp(14) } },
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
     * moves, and stop with one in it. The framed title's name, facts and Resume (or Play) sit under it.
     */
    private fun carousel(title: String, items: List<Item>): View {
        val cardW = dp(CAROUSEL_W)
        val cardH = cardW * 3 / 2
        val gap = dp(14)
        val step = cardW + gap
        val side = (screenW - cardW) / 2
        val cards = mutableListOf<ImageView>()
        var selected = -1

        val name = text("", 22f, serif = true).apply {
            gravity = Gravity.CENTER; maxLines = 2; ellipsize = TextUtils.TruncateAt.END
        }
        val facts = text("", BODY_SP, color = TEXT_2).apply { gravity = Gravity.CENTER; maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        val bar = FrameLayout(this).apply { background = rounded(PROGRESS_TRACK, 0) }
        val done = View(this).apply { setBackgroundColor(TUNGSTEN) }
        bar.addView(done, FrameLayout.LayoutParams(0, -1))
        val go = button("Resume", primary = true) {}
        val info = iconButton(R.drawable.ic_info, "More info") {}.apply {
            background = GradientDrawable().apply { setColor(withAlpha(TEXT, 0x14)); setStroke(dp(1), withAlpha(TEXT, 0x40)); cornerRadius = dp(CORNER).toFloat() }
        }

        lateinit var strip: HorizontalScrollView
        fun select(i: Int) {
            if (i == selected || i !in items.indices) return
            if (selected >= 0) strip.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            selected = i
            val item = items[i]
            name.text = item.name
            facts.text = item.facts
            bar.visibility = if (item.resume) View.VISIBLE else View.INVISIBLE
            done.layoutParams = (done.layoutParams as FrameLayout.LayoutParams).apply { width = (cardW * item.progress).toInt() }
            setLabel(go, if (item.resume) "Resume" else if (item.type == "series") "Episodes" else "Play")
            go.setOnClickListener { if (item.resume || item.type != "series") play(item) else openDetails(item) }
            go.setOnLongClickListener { play(item, pick = true); true }
            info.setOnClickListener { openDetails(item) }
            showBackdrop(item)
            fill(item) { if (selected == i) { facts.text = item.facts; showBackdrop(item) } }
            items.getOrNull(i + 1)?.let { fill(it) {} }
        }

        fun shape() {
            val middle = strip.scrollX + side + cardW / 2f
            cards.forEachIndexed { i, card ->
                val d = (abs(side + i * step + cardW / 2f - middle) / step).coerceAtMost(1f)
                card.scaleX = 1f - 0.14f * d
                card.scaleY = 1f - 0.14f * d
                card.alpha = 1f - 0.55f * d
            }
        }

        fun settle(velocity: Int) {
            val at = strip.scrollX / step.toFloat()
            val extra = (abs(velocity) / dp(2200)).coerceAtMost(3)
            val to = when {
                velocity > 0 -> ceil(at).toInt() + extra
                velocity < 0 -> floor(at).toInt() - extra
                else -> at.roundToInt()
            }.coerceIn(0, items.lastIndex)
            strip.smoothScrollTo(to * step, 0)
        }

        strip = object : HorizontalScrollView(this) {
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
            val card = poster(item, cardW).apply {
                setOnClickListener { if (i == selected) openDetails(item) else strip.smoothScrollTo(i * step, 0) }
                setOnLongClickListener { titleMenu(item); true }
            }
            cards += card
            line.addView(card, LinearLayout.LayoutParams(cardW, cardH).apply { rightMargin = gap })
        }
        strip.addView(line, FrameLayout.LayoutParams(-2, -1))
        strip.setOnScrollChangeListener { _, x, _, _, _ ->
            shape()
            select((x / step.toFloat()).roundToInt())
        }

        // The frame stays put; the posters move through it.
        val ring = dp(5)
        val frame = View(this).apply {
            background = GradientDrawable().apply { setStroke(dp(3), TUNGSTEN_RIM); cornerRadius = dp(CORNER + 2).toFloat() }
            elevation = dp(2).toFloat()
        }
        val stage = FrameLayout(this).apply {
            clipChildren = false
            addView(strip, FrameLayout.LayoutParams(-1, cardH + 2 * ring, Gravity.CENTER_VERTICAL))
            addView(frame, FrameLayout.LayoutParams(cardW + 2 * ring, cardH + 2 * ring, Gravity.CENTER))
        }

        val buttons = LinearLayout(this).apply {
            addView(go, LinearLayout.LayoutParams(0, dp(BIG_BUTTON), 1f))
            addView(info, LinearLayout.LayoutParams(dp(BIG_BUTTON), dp(BIG_BUTTON)).apply { leftMargin = dp(10) })
        }
        val block = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(heading(title), LinearLayout.LayoutParams(-1, -2))
            addView(stage, LinearLayout.LayoutParams(-1, cardH + 2 * ring).apply { topMargin = dp(6) })
            addView(bar, LinearLayout.LayoutParams(cardW, dp(4)).apply { topMargin = dp(8) })
            addView(name, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12); leftMargin = dp(20); rightMargin = dp(20) })
            addView(facts, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4); leftMargin = dp(20); rightMargin = dp(20) })
            addView(buttons, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14); leftMargin = dp(20); rightMargin = dp(20) })
        }
        select(0)
        strip.post { shape() }
        return block
    }

    private fun showBackdrop(item: Item) {
        val url = item.art ?: Images.backgroundFor(item.id) ?: item.poster
        if (wash.tag == url) return
        wash.animate().cancel()
        wash.alpha = 0f
        Images.load(url, wash, screenW) { wash.animate().alpha(0.55f).setDuration(Motion.STANDARD).start() }
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

    private fun poster(item: Item, widthPx: Int) = ImageView(this).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        background = rounded(TILE, CORNER)
        clipToOutline = true
        contentDescription = item.name
        Images.load(item.poster, this, widthPx, orElse = item.art)
    }

    private fun button(label: String, primary: Boolean, click: () -> Unit) = LinearLayout(this).apply {
        gravity = Gravity.CENTER
        background = if (primary) rounded(TUNGSTEN, CORNER)
            else GradientDrawable().apply { setColor(withAlpha(TEXT, 0x14)); setStroke(dp(1), withAlpha(TEXT, 0x40)); cornerRadius = dp(CORNER).toFloat() }
        if (primary) addView(ImageView(context).apply { setImageResource(R.drawable.ic_play); setColorFilter(BASE) },
            LinearLayout.LayoutParams(dp(20), dp(20)).apply { rightMargin = dp(8) })
        addView(text(label, 16f, bold = true, color = if (primary) BASE else TEXT))
        setOnClickListener { click() }
    }

    private fun setLabel(button: LinearLayout, label: String) {
        (button.getChildAt(button.childCount - 1) as TextView).text = label
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

    /** Press and hold a poster: the title's page, or a source chosen by hand; a title in progress can leave Continue Watching. */
    private fun titleMenu(item: Item) {
        val inProgress = item.resume && page == Page.HOME
        val options = listOfNotNull("More info", "Choose a source".takeIf { item.resume || item.type != "series" },
            "Remove from Continue Watching".takeIf { inProgress })
        choose(item.name, options) { i ->
            when (options[i]) {
                "More info" -> openDetails(item)
                "Choose a source" -> play(item, pick = true)
                else -> hide(item)
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
                if (page == Page.HOME) showPage(Page.HOME)
            }) { if (!isFinishing) choose("Couldn't remove it", listOf("OK"), "Check the connection and try again.") { } }
        }
    }

    private fun play(item: Item, pick: Boolean = false) {
        val videoId = item.videoId ?: item.id
        val meta = metas[item.id]
        val videos = meta?.videos.orEmpty()
        val at = videos.indexOfFirst { it.id == videoId }
        val next = if (at >= 0) videos.getOrNull(at + 1) else null
        val episode = item.left?.substringBefore(DOT_SHORT)?.takeIf { item.type == "series" && it.startsWith("S") }
        startActivity(Intent(this, StreamsActivity::class.java)
            .putExtra("type", item.type).putExtra("id", item.id).putExtra("name", item.name).putExtra("poster", item.poster)
            .putExtra("videoId", videoId).putExtra("nextVideoId", next?.id).putExtra("pick", pick)
            .putExtra("background", meta?.background ?: item.art)
            .putExtra("logo", meta?.logo ?: Images.logoFor(item.id))
            .putExtra("still", videos.getOrNull(at)?.thumbnail)
            .putExtra("runtime", meta?.runtime)
            .putExtra("episode", videos.getOrNull(at)?.let { "S${it.season} E${it.episode}  ·  ${it.title}" })
            .putExtra("label", listOfNotNull(item.name, episode).joinToString("  ·  ")))
    }

    private fun openDetails(item: Item) {
        startActivity(Intent(this, DetailsActivity::class.java)
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
        const val TOUCH = 48 // dp: the smallest thing a thumb is asked to hit
        const val BIG_BUTTON = 52
        const val CAROUSEL_W = 200 // dp: the framed poster
        const val POSTER_W = 112 // dp: a row's posters
        const val BACKDROP_H = 560
        const val MAX_ROWS = 14
        const val ROW_MAX = 40
        const val BODY_SP = 15f
        const val SMALL_SP = 13f
        const val DOT_SHORT = " · "
    }
}
