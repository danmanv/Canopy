package dev.alder

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * "See all" from the end of a Home, Shows or Movies row: the whole catalog as a poster grid, a page at a time as the
 * remote moves down. The row it came from shows a short preview; this is where every title lives.
 */
class CatalogActivity : Screen() {
    private lateinit var key: String
    private lateinit var grid: LinearLayout
    private lateinit var scroller: ScrollView
    private lateinit var focusedName: TextView
    private lateinit var focusedFacts: TextView
    private lateinit var count: TextView
    private val titles = mutableListOf<Meta>()
    private var currentRow: LinearLayout? = null
    private var catalogs = emptyList<Addon.Catalog>()
    private var skips = IntArray(0)
    private var exhausted = BooleanArray(0)
    private var fetching = false
    private var done = false
    private var posterW = 0
    private var posterH = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        key = authKey ?: return finish()
        val screenW = resources.displayMetrics.widthPixels / resources.displayMetrics.density
        posterW = ((screenW - 2 * PAD - (COLUMNS - 1) * GAP) / COLUMNS).toInt()
        posterH = posterW * 3 / 2

        focusedName = text("", SERIF_NAME, serif = true).onArt().apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        focusedFacts = text("", SMALL, color = TEXT_2).onArt().apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        count = text("", SMALL, color = TEXT_3)
        grid = column(Gravity.START).apply {
            clipChildren = false
            clipToPadding = false
            setPadding(dp(PAD), dp(10), dp(PAD), dp(60))
        }
        scroller = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            clipChildren = false
            isFocusable = false
            addView(grid, FrameLayout.LayoutParams(-1, -2))
        }
        val head = column(Gravity.START,
            caps(intent.getStringExtra("title").orEmpty(), TUNGSTEN),
            focusedName.marginTop(8),
            LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(focusedFacts)
                addView(count, wrap().apply { marginStart = dp(14) })
            }.marginTop(2),
        ).apply { setPadding(dp(PAD), dp(30), dp(PAD), dp(12)) }
        setContentView(FrameLayout(this).apply {
            background = sky()
            addView(column(Gravity.START).apply {
                addView(head, LinearLayout.LayoutParams(-1, -2))
                addView(scroller, LinearLayout.LayoutParams(-1, 0, 1f))
            }, FrameLayout.LayoutParams(-1, -1))
        })
        // Placeholders hold the first screenful's places while the first page is fetched.
        repeat(COLUMNS * 2) { addCard(null) }
        load()
    }

    /** The catalogs the row came from (its add-ons' own), then their first pages. */
    private fun load() = background({
        val wanted = intent.getStringArrayListExtra("catalogs").orEmpty()
        val genre = intent.getStringExtra("genre")
        val all = Stremio.addons(key).flatMap { it.catalogs }
        catalogs = wanted.mapNotNull { w -> all.firstOrNull { ref(it) == w } }
        skips = IntArray(catalogs.size)
        exhausted = BooleanArray(catalogs.size) { genre != null || !catalogs[it].pages }
        catalogs.mapIndexed { i, c ->
            runCatching { c.addon.catalog(c, genre) }.getOrDefault(emptyList()).also { skips[i] = it.size }
        }.flatten()
    }) { r ->
        grid.removeAllViews()
        currentRow = null
        val first = r.getOrNull().orEmpty().distinctBy { it.id }.filterNot { it.name.contains("Authentication Required", true) }
        if (first.isEmpty()) {
            grid.addView(failed("Couldn't load this list.") { recreate() })
            return@background
        }
        add(first)
        (grid.getChildAt(0) as? LinearLayout)?.getChildAt(0)?.requestFocus()
    }

    private fun add(more: List<Meta>) {
        val known = titles.map { it.id }.toSet()
        val fresh = more.filter { it.id !in known }.distinctBy { it.id }
        if (fresh.isEmpty()) { done = true; updateCount(); return }
        fresh.forEach { addCard(it) }
        updateCount()
    }

    private fun updateCount() {
        count.text = if (titles.isEmpty()) "" else "${titles.size}${if (done || exhausted.all { it }) "" else "+"} titles"
    }

    /** The next page from each catalog that pages, once the remote nears the bottom. */
    private fun fetchMore() {
        if (fetching || done || exhausted.all { it }) return
        fetching = true
        background({
            catalogs.indices.filter { !exhausted[it] }.flatMap { i ->
                val page = runCatching { catalogs[i].addon.catalog(catalogs[i], skips[i]) }.getOrDefault(emptyList())
                skips[i] += page.size
                if (page.isEmpty()) exhausted[i] = true
                page
            }
        }) { r ->
            fetching = false
            add(r.getOrNull().orEmpty())
        }
    }

    /** A poster in the grid; null holds a place while the list loads. */
    private fun addCard(meta: Meta?) {
        val row = currentRow?.takeIf { it.childCount < COLUMNS } ?: LinearLayout(this).apply {
            clipChildren = false
            clipToPadding = false
            grid.addView(this, LinearLayout.LayoutParams(-1, -2).apply { if (grid.childCount > 0) topMargin = dp(GAP) })
        }.also { currentRow = it }
        val image = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f }
        val card = FrameLayout(this).apply {
            setBackgroundColor(TILE)
            addView(image, FrameLayout.LayoutParams(-1, -1))
        }
        row.addView(card, LinearLayout.LayoutParams(dp(posterW), dp(posterH)).apply { if (row.childCount > 0) marginStart = dp(GAP) })
        if (meta == null) return
        titles += meta
        val index = titles.size - 1
        if (meta.poster == null) card.addView(text(meta.name, SMALL, bold = true).apply {
            maxLines = 3; ellipsize = TextUtils.TruncateAt.END; setPadding(dp(10), 0, dp(10), dp(10))
        }, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        Images.load(meta.poster, image, dp(posterW) * 2) {
            image.animate().alpha(1f).setDuration(Motion.QUICK).setInterpolator(Motion.EASE).start()
        }
        card.cardFocus(2) { has ->
            card.grow(has).start()
            if (!has) return@cardFocus
            focusedName.text = meta.name
            focusedFacts.text = listOfNotNull(meta.year, meta.genres.firstOrNull(), meta.rating?.let { "IMDb $it" }).joinToString(DOT)
            // The focused row rides near the top, so the one below always shows what's next.
            val rowTop = (card.parent as View).top
            scroller.smoothScrollTo(0, (rowTop - dp(GAP)).coerceAtLeast(0))
            if (index >= titles.size - COLUMNS * 2) fetchMore()
        }
        card.setOnClickListener {
            startActivity(Intent(this, DetailsActivity::class.java)
                .putExtra("type", meta.type).putExtra("id", meta.id).putExtra("name", meta.name)
                .putExtra("poster", meta.poster).putExtra("background", meta.background))
        }
    }

    companion object {
        private const val COLUMNS = 7
        private const val GAP = 16

        /** How a catalog is named between screens: its add-on, its type and its id. */
        fun ref(c: Addon.Catalog) = "${c.addon.transportUrl}|${c.type}|${c.id}"

        fun open(context: Context, title: String, catalogs: List<Addon.Catalog>, genre: String?) {
            context.startActivity(Intent(context, CatalogActivity::class.java)
                .putExtra("title", title)
                .putExtra("genre", genre)
                .putStringArrayListExtra("catalogs", ArrayList(catalogs.map(::ref))))
        }
    }
}
