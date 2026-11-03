package dev.alder

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject

/**
 * A title's page on a phone, held upright: its art and logo, where you are, a big Resume, the actions under it, the story,
 * then the episodes by season (a show) or More like this (a film). Sources and the player are still the TV's screens.
 */
class PhoneDetailsActivity : PhoneScreen() {
    private val type by lazy { intent.getStringExtra("type") ?: "movie" }
    private val metaId by lazy { intent.getStringExtra("id")!! }
    private val name by lazy { intent.getStringExtra("name") ?: "" }
    private val poster by lazy { intent.getStringExtra("poster") }
    private val initialArt by lazy { intent.getStringExtra("background") }

    private lateinit var key: String
    private var meta: Meta? = null
    private var item: JSONObject? = null // the library entry: progress and My List
    private var changedHere = false
    private var season = -1

    private lateinit var art: ImageView
    private lateinit var logo: ImageView
    private lateinit var title: TextView
    private lateinit var facts: TextView
    private lateinit var where: TextView
    private lateinit var progress: FrameLayout
    private lateinit var progressFill: View
    private lateinit var play: LinearLayout
    private lateinit var actions: LinearLayout
    private lateinit var restart: LinearLayout
    private lateinit var listAction: LinearLayout
    private lateinit var trailer: LinearLayout
    private lateinit var about: TextView
    private lateinit var people: TextView
    private lateinit var below: LinearLayout // episodes or More like this

    private val screenW by lazy { resources.displayMetrics.widthPixels }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        key = authKey ?: return finish()
        art = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f }
        val artBox = FrameLayout(this).apply {
            addView(art, FrameLayout.LayoutParams(-1, -1))
            addView(View(context).apply {
                background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(baseAt(0x33), baseAt(0x00), baseAt(0xFF)))
            }, FrameLayout.LayoutParams(-1, -1))
        }
        logo = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_START; adjustViewBounds = true; visibility = View.GONE }
        title = text(name, 30f, serif = true).apply { maxLines = 3; ellipsize = TextUtils.TruncateAt.END }
        facts = text("", BODY_SP, color = TEXT_2)
        where = text("", BODY_SP, bold = true, color = TUNGSTEN).apply { visibility = View.GONE; maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        progress = FrameLayout(this).apply { background = rounded(PROGRESS_TRACK, 0); visibility = View.GONE }
        progressFill = View(this).apply { setBackgroundColor(TUNGSTEN) }
        progress.addView(progressFill, FrameLayout.LayoutParams(0, -1))
        play = button("Play", primary = true) { openSources(target().first, startOver = false) }.apply {
            setOnLongClickListener { openSources(target().first, startOver = false, pick = true); true }
        }
        restart = action(R.drawable.ic_restart, "Restart") { openSources(target().first, startOver = true) }
        listAction = action(R.drawable.ic_add, "My List") { toggleList() }
        trailer = action(R.drawable.ic_movie, "Trailer") { openTrailer() }.apply { visibility = View.GONE }
        val stream = action(R.drawable.ic_layers, "Stream") { openSources(target().first, startOver = false, pick = true) }
        actions = LinearLayout(this).apply {
            listOf(restart, listAction, trailer, stream).forEach { addView(it, LinearLayout.LayoutParams(0, dp(68), 1f)) }
        }
        about = text("", BODY_SP, color = TEXT).apply {
            maxLines = 4; ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(dp(3).toFloat(), 1f)
            setOnClickListener { maxLines = if (maxLines == 4) 40 else 4 }
        }
        people = text("", SMALL_SP, color = TEXT_2).apply { visibility = View.GONE; maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        below = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val side = dp(20)
        val block = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(side, 0, side, 0)
            addView(logo, LinearLayout.LayoutParams(dp(LOGO_W), dp(LOGO_H)))
            addView(title, LinearLayout.LayoutParams(-1, -2))
            addView(facts, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            addView(where, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
            addView(progress, LinearLayout.LayoutParams(-1, dp(4)).apply { topMargin = dp(8) })
            addView(play, LinearLayout.LayoutParams(-1, dp(BIG_BUTTON)).apply { topMargin = dp(14) })
            addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
            addView(about, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
            addView(people, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(32))
            addView(artBox, LinearLayout.LayoutParams(-1, dp(ART_H)))
            addView(block, LinearLayout.LayoutParams(-1, -2).apply { topMargin = -dp(LOGO_OVERLAP) })
            addView(below, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })
        }
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; isFocusable = false; addView(page) }
        val back = iconButton(R.drawable.ic_back, "Back") { finish() }.apply {
            background = rounded(baseAt(0x8C), CORNER)
            elevation = dp(4).toFloat()
        }
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(BASE))
        setContentView(FrameLayout(this).apply {
            addView(scroll, FrameLayout.LayoutParams(-1, -1))
            addView(back, FrameLayout.LayoutParams(dp(TOUCH), dp(TOUCH), Gravity.TOP or Gravity.START).apply { leftMargin = dp(8); topMargin = dp(8) })
        })

        (initialArt ?: Images.backgroundFor(metaId) ?: poster)?.let { url ->
            Images.load(url, art, screenW, urgent = true) { art.animate().alpha(1f).setDuration(Motion.STANDARD).start() }
        }
        fill(null)

        background({
            (Stremio.savedAddons(key) ?: Stremio.addons(key)).savedMeta(type, metaId) to Stremio.savedLibrary(key)?.firstOrNull { it.optString("_id") == metaId }
        }) { r ->
            r.getOrNull()?.let { (saved, entry) ->
                if (!changedHere && item == null) item = entry
                if (meta == null) saved?.let(::show) ?: fill(null)
            }
        }
        background({ Stremio.addons(key).meta(type, metaId, together = true) }) { r -> r.getOrNull()?.let(::show) }
        background({ Stremio.library(key, listOf(metaId)).firstOrNull() }) { r ->
            if (!changedHere) { item = r.getOrNull() ?: item; fill(meta); meta?.let(::episodes) }
        }
    }

    // Back from the player: where you are and what's watched may have changed.
    override fun onRestart() {
        super.onRestart()
        background({ Stremio.library(key, listOf(metaId)).firstOrNull() }) { r ->
            item = r.getOrNull() ?: item
            fill(meta)
            meta?.takeIf { type == "series" }?.let(::episodes)
        }
    }

    private var shownFor: Meta? = null

    private fun show(m: Meta) {
        meta = m
        if (art.drawable == null) m.background?.let { url ->
            Images.load(url, art, screenW, urgent = true) { art.animate().alpha(1f).setDuration(Motion.STANDARD).start() }
        }
        val logoUrl = m.logo ?: Images.logoFor(metaId)
        if (logoUrl != null && logo.tag != logoUrl) Images.load(logoUrl, logo, dp(LOGO_W) * 2, failed = {
            logo.visibility = View.GONE; title.visibility = View.VISIBLE
        }) { logo.visibility = View.VISIBLE; title.visibility = View.GONE }
        title.text = m.name.ifBlank { name }
        facts.text = listOfNotNull(m.year, m.genres.take(2).joinToString(", ").ifEmpty { null },
            m.runtime ?: m.videos.map { it.season }.filter { it > 0 }.distinct().size.takeIf { type == "series" && it > 0 }?.let(::seasons),
            m.rating?.let { "IMDb $it" }).joinToString(DOT_SHORT)
        about.text = m.description.orEmpty()
        about.visibility = if (m.description.isNullOrBlank()) View.GONE else View.VISIBLE
        val names = listOfNotNull(m.cast.take(5).joinToString(", ").ifEmpty { null }?.let { "Starring $it" },
            m.directors.take(2).joinToString(", ").ifEmpty { null }?.let { "Directed by $it" })
        people.text = names.joinToString("\n")
        people.visibility = if (names.isEmpty()) View.GONE else View.VISIBLE
        trailer.visibility = if (m.trailer != null) View.VISIBLE else View.GONE
        fill(m)
        if (shownFor?.id != m.id || shownFor?.videos?.size != m.videos.size) {
            shownFor = m
            if (type == "series" && m.videos.isNotEmpty()) episodes(m) else if (type != "series") similar()
        }
    }

    /** What Play opens: the episode Continue Watching points at, or the first episode, or the film itself. */
    private fun target(m: Meta? = meta): Pair<String, Video?> {
        val videos = m?.videos.orEmpty()
        if (videos.isEmpty()) return metaId to null
        val current = item?.optJSONObject("state")?.text("video_id")
        val video = videos.firstOrNull { it.id == current } ?: videos.firstOrNull { it.season > 0 } ?: videos.first()
        return video.id to video
    }

    /** Where you are, Resume or Play, and the actions that depend on it. */
    private fun fill(m: Meta?) {
        val (id, episode) = target(m)
        val resumeAt = (item?.let { Library.resumeAt(it, id) } ?: 0L).takeIf { it >= STARTED } ?: 0L
        val duration = item?.optJSONObject("state")?.optLong("duration") ?: 0L
        val left = if (resumeAt > 0 && duration > resumeAt) runtime("${(duration - resumeAt) / 60_000} min") + " left" else null
        val short = episode?.let { "S${it.season} E${it.episode}" }
        where.text = listOfNotNull(short, episode?.title?.takeIf { it.isNotBlank() }, left).joinToString(DOT_SHORT)
        where.visibility = if (resumeAt > 0 && where.text.isNotEmpty()) View.VISIBLE else View.GONE
        progress.visibility = if (left != null) View.VISIBLE else View.GONE
        if (left != null) progress.post {
            progressFill.layoutParams = (progressFill.layoutParams as FrameLayout.LayoutParams).apply { width = (progress.width * resumeAt / duration).toInt() }
        }
        setLabel(play, listOfNotNull(if (resumeAt > 0) "Resume" else "Play", short).joinToString(" "))
        restart.visibility = if (resumeAt > 0) View.VISIBLE else View.GONE
        val inList = Library.inList(item)
        setAction(listAction, if (inList) R.drawable.ic_check else R.drawable.ic_add, if (inList) "In My List" else "My List")
    }

    // ---------- episodes ----------

    private fun episodes(m: Meta) {
        val videos = m.videos
        val seasons = videos.map { it.season }.distinct().sortedBy { if (it == 0) Int.MAX_VALUE else it }
        if (season !in seasons) season = target(m).second?.season ?: seasons.firstOrNull() ?: return
        below.removeAllViews()
        val tabs = LinearLayout(this).apply { setPadding(dp(12), 0, dp(12), 0) }
        seasons.forEach { s ->
            val open = s == season
            tabs.addView(text(if (s == 0) "Specials" else "Season $s", BODY_SP, bold = open, color = if (open) TEXT else TEXT_2).apply {
                gravity = Gravity.CENTER
                setPadding(dp(10), 0, dp(10), 0)
                background = if (open) underline() else null
                setOnClickListener { if (s != season) { season = s; episodes(m) } }
            }, LinearLayout.LayoutParams(-2, dp(TOUCH)))
        }
        below.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(tabs) }, LinearLayout.LayoutParams(-1, -2))
        below.addView(View(this).apply { setBackgroundColor(GLASS_EDGE) }, LinearLayout.LayoutParams(-1, dp(1)))
        val watched = Library.watched(item, videos)
        videos.filter { it.season == season }.forEach { v -> below.addView(episodeRow(m, v, v.id in watched), LinearLayout.LayoutParams(-1, -2)) }
    }

    private fun underline() = android.graphics.drawable.LayerDrawable(arrayOf(GradientDrawable().apply { setColor(TUNGSTEN) })).apply {
        setLayerGravity(0, Gravity.BOTTOM or Gravity.FILL_HORIZONTAL)
        setLayerHeight(0, dp(3))
    }

    private fun episodeRow(m: Meta, v: Video, seen: Boolean): View {
        val resumeAt = item?.let { Library.resumeAt(it, v.id) } ?: 0L
        val duration = item?.optJSONObject("state")?.takeIf { it.text("video_id") == v.id }?.optLong("duration") ?: 0L
        val started = resumeAt >= STARTED && duration > resumeAt
        val still = FrameLayout(this).apply { background = rounded(TILE, CORNER); clipToOutline = true }
        val image = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = if (seen) 0.6f else 1f }
        still.addView(image, FrameLayout.LayoutParams(-1, -1))
        Images.load(v.thumbnail, image, dp(STILL_W), orElse = m.background)
        if (started || seen) still.addView(View(this).apply { setBackgroundColor(TUNGSTEN) },
            FrameLayout.LayoutParams(if (seen && !started) -1 else (dp(STILL_W) * resumeAt / duration).toInt(), dp(3), Gravity.BOTTOM))
        val heading = text("${v.episode}. " + v.title.ifBlank { "Episode ${v.episode}" } + if (seen) "  ✓" else "",
            BODY_SP, bold = true, color = if (seen) TEXT_2 else TEXT).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        val line = text(listOfNotNull(m.runtime?.takeIf { !started }, episodeDate(v.released),
            if (started) "${(duration - resumeAt) / 60_000} min left" else null).joinToString(DOT_SHORT),
            SMALL_SP, color = if (started) TUNGSTEN else TEXT_2).apply { maxLines = 1 }
        val top = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(still, LinearLayout.LayoutParams(dp(STILL_W), dp(STILL_W) * 9 / 16))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(heading)
                addView(line, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(14) })
        }
        val synopsis = text(v.overview.orEmpty(), SMALL_SP, color = TEXT_2).apply {
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END
            visibility = if (v.overview.isNullOrBlank()) View.GONE else View.VISIBLE
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(12))
            addView(top, LinearLayout.LayoutParams(-1, -2))
            addView(synopsis, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(withAlpha(TEXT, 0x22)), null,
                android.graphics.drawable.ColorDrawable(0xFFFFFFFF.toInt()))
            val label = "S${v.season} E${v.episode}"
            setOnClickListener { openSources(v.id, startOver = false, label = label) }
            setOnLongClickListener { openSources(v.id, startOver = false, label = label, pick = true); true }
        }
    }

    // ---------- More like this ----------

    private var similarAsked = false

    private fun similar() {
        if (similarAsked) return
        similarAsked = true
        background({
            Stremio.addons(key).firstNotNullOfOrNull { addon -> runCatching { addon.similar(type, metaId) }.getOrNull()?.takeIf { it.isNotEmpty() } }.orEmpty()
        }) { r ->
            val titles = r.getOrNull().orEmpty().filter { it.id != metaId && it.type == type }.distinctBy { it.id }.take(12)
            if (titles.isEmpty()) return@background
            below.removeAllViews()
            below.addView(text("More like this", 17f, serif = true).apply { setPadding(dp(20), 0, dp(20), dp(10)) })
            val gap = dp(10)
            val w = (screenW - dp(40) - 2 * gap) / 3
            titles.chunked(3).forEach { three ->
                val line = LinearLayout(this).apply { setPadding(dp(20), 0, dp(20), 0) }
                three.forEachIndexed { i, t ->
                    line.addView(ImageView(this).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        background = rounded(TILE, CORNER)
                        clipToOutline = true
                        contentDescription = t.name
                        Images.load(t.poster ?: t.background, this, w)
                        setOnClickListener {
                            startActivity(Intent(this@PhoneDetailsActivity, PhoneDetailsActivity::class.java)
                                .putExtra("type", t.type).putExtra("id", t.id).putExtra("name", t.name)
                                .putExtra("poster", t.poster).putExtra("background", t.background))
                        }
                    }, LinearLayout.LayoutParams(w, w * 3 / 2).apply { if (i > 0) leftMargin = gap })
                }
                below.addView(line, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = gap })
            }
        }
    }

    // ---------- actions ----------

    private fun toggleList() {
        changedHere = true
        val entry = item ?: Library.newItem(metaId, type, meta?.name ?: name, meta?.poster ?: poster)
        Library.setInList(entry, !Library.inList(item))
        item = entry
        fill(meta)
        notice(if (Library.inList(entry)) "Added to My List" else "Removed from My List", meta?.name ?: name)
        val snapshot = JSONObject(entry.toString())
        background({ Stremio.saveLibrary(key, listOf(snapshot)) }) {}
    }

    /** The trailer in YouTube (or the browser): a phone has it to hand. */
    private fun openTrailer() {
        val id = meta?.trailer ?: return
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$id"))) }
    }

    private fun openSources(videoId: String, startOver: Boolean, label: String? = null, pick: Boolean = false) {
        val m = meta
        val videos = m?.videos.orEmpty()
        val at = videos.indexOfFirst { it.id == videoId }
        val video = videos.getOrNull(at)
        val short = label ?: video?.let { "S${it.season} E${it.episode}" }
        startActivity(Intent(this, StreamsActivity::class.java)
            .putExtra("type", type).putExtra("id", metaId).putExtra("name", m?.name ?: name).putExtra("poster", m?.poster ?: poster)
            .putExtra("videoId", videoId).putExtra("nextVideoId", if (at >= 0) videos.getOrNull(at + 1)?.id else null)
            .putExtra("startOver", startOver).putExtra("pick", pick)
            .putExtra("background", m?.background ?: initialArt)
            .putExtra("label", listOfNotNull(m?.name ?: name, short).joinToString("  ·  "))
            .putExtra("logo", m?.logo).putExtra("still", video?.thumbnail).putExtra("runtime", m?.runtime)
            .putExtra("episode", video?.let { v -> listOfNotNull("S${v.season} E${v.episode}", v.title.takeIf { it.isNotBlank() }).joinToString("  ·  ") }))
    }

    private companion object {
        const val ART_H = 260 // dp: the art across the top
        const val LOGO_W = 240
        const val LOGO_H = 84
        const val LOGO_OVERLAP = 60 // dp the logo sits up into the art's dark foot
        const val STILL_W = 136
        const val STARTED = 60_000L // ms watched before a title counts as started
    }
}
