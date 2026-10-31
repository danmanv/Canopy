package dev.alder

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextClock
import android.widget.TextView
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

internal fun episodeDate(value: String?): String? {
    val day = value?.take(10)?.takeIf { it.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) } ?: return null
    val source = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false; timeZone = TimeZone.getTimeZone("UTC") }
    val parsed = runCatching { source.parse(day) }.getOrNull() ?: return null
    return SimpleDateFormat("d MMM yyyy", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(parsed)
}

// A title stays in place over its art; episodes and related titles open over the same scene.
class DetailsActivity : Screen() {
    private val type by lazy { intent.getStringExtra("type") ?: "movie" }
    private val metaId by lazy { intent.getStringExtra("id")!! }
    private val name by lazy { intent.getStringExtra("name") ?: "" }
    private val poster by lazy { intent.getStringExtra("poster") }
    private val initialArt by lazy { intent.getStringExtra("background") }

    private lateinit var key: String
    override val backdrop get() = heroArt
    private lateinit var root: FrameLayout
    private lateinit var hero: FrameLayout
    private lateinit var heroArt: ImageView
    private lateinit var guide: FrameLayout
    private lateinit var guideRows: LinearLayout
    private lateinit var seasonStrip: LinearLayout
    private var seasonButtons = emptyMap<Int, TextView>()
    private lateinit var previewImage: ImageView
    private lateinit var previewTitle: TextView
    private lateinit var previewFacts: TextView
    private lateinit var previewAbout: TextView
    private lateinit var previewProgress: FrameLayout
    private lateinit var similarOverlay: FrameLayout
    private var similarFirst: View? = null
    private lateinit var listButton: TextView
    private lateinit var playButton: TextView
    private lateinit var infoButton: TextView
    private lateinit var infoOverlay: FrameLayout
    private var guideOpen = false
    private var similarOpen = false
    private var infoOpen = false
    private var selectedSeason = 1
    private var episodeRows = emptyList<View>()
    private var meta: Meta? = null
    private var item: JSONObject? = null // the title's library item: progress and whether it's in My List
    private var itemChangedHere = false
    private var similarRequested = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        key = authKey ?: return finish()
        val artWidth = resources.displayMetrics.widthPixels
        heroArt = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f }
        hero = FrameLayout(this).apply { clipChildren = false }
        root = FrameLayout(this).apply {
            clipChildren = false
            addView(hero, FrameLayout.LayoutParams(-1, -1))
            addView(TextClock(this@DetailsActivity).apply {
                format12Hour = "h:mm a"
                format24Hour = "HH:mm"
                textSize = SMALL
                setTextColor(TEXT_2)
                typeface = text("", SMALL).typeface
            }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
                topMargin = dp(36); rightMargin = dp(PAD)
            })
        }
        setWindowed(root, heroArt)

        (initialArt ?: poster)?.let { Images.load(it, heroArt, artWidth, urgent = true) { heroArt.alpha = 1f; refrost() } }

        // The title page is drawn once from last time's details and then hears from the meta add-on and the library.
        // Each answer used to rebuild the whole block: the logo went and came back and Play lost its light for a
        // frame, the flicker just after the page opened. Now only what changed is redrawn.
        fun show(m: Meta) {
            meta = m
            val art = m.background ?: initialArt ?: poster
            // The art the page opened on stays: swapping it for the add-on's own a moment later blanked the screen for
            // a frame (Images.load clears the picture first) and then cut to a different one. Only a stand-in (the
            // poster, or nothing) is replaced, once the new art is in memory so it cuts straight across.
            val keep = heroArt.drawable != null && heroArt.tag == initialArt && initialArt != null
            if (!keep && art != null && heroArt.tag != art) {
                fun put() = Images.load(art, heroArt, artWidth, urgent = true) {
                    heroArt.animate().alpha(1f).setDuration(Motion.STANDARD).setInterpolator(Motion.EASE).start()
                    refrost()
                }
                if (heroArt.drawable == null || Images.cached(art, artWidth)) put()
                else background({ Images.warm(art, artWidth) }) { if (meta === m) put() }
            }
            if (!::playButton.isInitialized || heroKey != heroKey(m)) showHero(m)
            else { fillWatch(m); updateMoreCaption(m) }
            if (type == "series" && m.videos.isNotEmpty() && guideFor != m.videos.map { it.id }) buildGuide(m.videos)
            if (type == "movie" && !similarRequested) { similarRequested = true; loadSimilar() }
        }
        background({
            (Stremio.savedAddons(key) ?: Stremio.addons(key)).savedMeta(type, metaId) to Stremio.savedLibrary(key)?.firstOrNull { it.optString("_id") == metaId }
        }) { result ->
            result.getOrNull()?.let { (saved, libraryItem) ->
                if (!itemChangedHere && item == null) item = libraryItem
                if (meta == null) saved?.let(::show)
            }
        }
        background({ Stremio.addons(key).meta(type, metaId, together = true) }) { result ->
            val fresh = result.getOrNull()
            if (fresh != null) show(fresh)
            else if (meta == null) showHero(null)
        }
        background({ Stremio.library(key, listOf(metaId)).firstOrNull() }) { result ->
            if (!itemChangedHere) {
                item = result.getOrNull() ?: item
                // Progress and My List only: the watch line and buttons change in place.
                meta?.let { m -> if (::playButton.isInitialized && heroKey == heroKey(m)) fillWatch(m) else showHero(m) }
            }
        }
    }

    // ---------- title and watch window ----------

    /** Everything the title block shows of [m] apart from the watch line: a change here redraws the block. */
    private fun heroKey(m: Meta) = listOf(m.id, m.name, m.logo, m.year, m.genres.firstOrNull(), m.rating, m.runtime,
        m.description, m.videos.map { it.season }.filter { it > 0 }.distinct().size, m.videos.isNotEmpty()).joinToString("\u0000")

    private var heroKey: String? = null
    private var guideFor: List<String>? = null
    private var moreCaption: View? = null

    private fun showHero(m: Meta?) {
        heroKey = m?.let(::heroKey)
        moreCaption = null
        hero.removeAllViews()
        val title = text(m?.name ?: name, SERIF_HERO, serif = true).onArt().apply {
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.CENTER_VERTICAL
            // The box is a fixed height: at large font sizes a two-line name shrinks to fit instead of clipping.
            setAutoSizeTextTypeUniformWithConfiguration(20, SERIF_HERO.toInt(), 1, android.util.TypedValue.COMPLEX_UNIT_SP)
        }
        val titleArea = FrameLayout(this).apply {
            addView(title, FrameLayout.LayoutParams(-1, -1))
            if (m != null) {
                val logo = ImageView(this@DetailsActivity).apply {
                    scaleType = ImageView.ScaleType.FIT_START
                    visibility = View.GONE
                }
                addView(logo, FrameLayout.LayoutParams(dp(260), -1))
                Images.load(m.logo ?: Images.logoFor(m.id), logo, dp(520), urgent = true, failed = {
                    title.visibility = View.VISIBLE; logo.visibility = View.GONE
                }) {
                    title.visibility = View.GONE; logo.visibility = View.VISIBLE
                }
            }
        }
        val stack = column(Gravity.START).apply { addView(titleArea, LinearLayout.LayoutParams(-1, dp(75))) }
        if (m == null) {
            val error = failed("Couldn't load this title's details.") { recreate() }
            stack.addView(error.marginTop(16))
            error.post { error.getChildAt(2).requestFocus() }
        } else {
            val length = if (type == "series") m.videos.map { it.season }.filter { it > 0 }.distinct().size
                .takeIf { it > 0 }?.let(::seasons) else m.runtime
            val facts = listOfNotNull(m.year, m.genres.firstOrNull(), length).joinToString(DOT)
            val factRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            factRow.addView(text(facts, SMALL, color = TEXT_2).onArt().apply {
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            })
            m.rating?.let { rating ->
                factRow.addView(text("·", SMALL, color = TEXT_2).onArt(), wrap().apply { marginStart = dp(7); marginEnd = dp(7) })
                factRow.addView(imdbTag()) // the same size as the words beside it
                factRow.addView(text(rating, SMALL, color = TEXT_2).onArt(), wrap().apply { marginStart = dp(6) })
            }
            stack.addView(factRow, wrap().apply { topMargin = dp(12) })
            m.description?.takeIf { it.isNotBlank() }?.let {
                stack.addView(text(it, BODY, color = TEXT).onArt().apply {
                    maxLines = 3; ellipsize = TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(dp(470), -2).apply { topMargin = dp(8) })
            }
            addWatch(m, stack)
        }
        hero.addView(stack, FrameLayout.LayoutParams(dp(HERO_W), -2, Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = dp(PAD); bottomMargin = dp(118)
        })
        updateMoreCaption(m)
    }

    /** "▾ Episodes" or "▾ More like this" under the block, added on its own when the similar titles come in. */
    private fun updateMoreCaption(m: Meta?) {
        val hasEpisodes = type == "series" && m?.videos?.isNotEmpty() == true
        val words = if (hasEpisodes) "▾  Episodes" else if (::similarOverlay.isInitialized) "▾  More like this" else null
        val current = moreCaption as? TextView
        if (current?.text?.toString() == words?.uppercase()) return
        current?.let { if (it.hasFocus() && ::playButton.isInitialized) playButton.requestFocus(); hero.removeView(it) }
        moreCaption = null
        if (words == null) return
        moreCaption = caps(words, TEXT_2).onArt().apply {
            isFocusable = true
            setOnClickListener { if (hasEpisodes) showGuide() else showSimilar() }
            rowFocus(rest = { null })
        }.also {
            hero.addView(it, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply {
                leftMargin = dp(PAD); bottomMargin = dp(28)
            })
        }
    }

    private lateinit var whereLine: TextView
    private lateinit var progressSlot: FrameLayout
    private lateinit var startOverButton: TextView

    /** Where you are, then the main action and three small glass controls. */
    private fun addWatch(m: Meta, stack: LinearLayout) {
        whereLine = text("", SMALL, bold = true).onArt().apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        stack.addView(whereLine.marginTop(16))
        progressSlot = FrameLayout(this)
        stack.addView(progressSlot, LinearLayout.LayoutParams(dp(240), dp(3)).apply { topMargin = dp(8) })
        // Each press asks what to open then, so a refreshed watch line never leaves a button pointing at the old episode.
        fun target() = playTarget(meta)?.first ?: metaId
        playButton = pill("Play", R.drawable.ic_play).apply {
            setOnClickListener { openSources(target(), startOver = false) }
            // Held OK: every source, to choose one by hand.
            setOnLongClickListener { openSources(target(), startOver = false, pick = true); true }
        }
        startOverButton = iconButton(R.drawable.ic_restart, "Start over") { openSources(target(), startOver = true) }
        infoButton = iconButton(R.drawable.ic_info, "More info") { showInfo() }
        listButton = iconButton(R.drawable.ic_add, "Add to My List") { toggleList() }
        stack.addView(LinearLayout(this).apply {
            clipChildren = false
            gravity = Gravity.CENTER_VERTICAL
            addView(playButton, wrap().apply { marginEnd = dp(14) })
            addView(cell(startOverButton), LinearLayout.LayoutParams(dp(ICON_BUTTON), dp(ICON_BUTTON)).apply { marginEnd = dp(10) })
            addView(cell(listButton), LinearLayout.LayoutParams(dp(ICON_BUTTON), dp(ICON_BUTTON)).apply { marginEnd = dp(10) })
            addView(cell(infoButton), LinearLayout.LayoutParams(dp(ICON_BUTTON), dp(ICON_BUTTON)))
        }, wrap().apply { topMargin = dp(16) })
        fillWatch(m)
        playButton.post { if (!guideOpen && !infoOpen && !similarOpen) playButton.requestFocus() }
    }

    /**
     * The watch line and buttons for where the viewer is now. Back from the player only changes these words in place:
     * rebuilding the page reloaded the logo and re-lit Play, a visible flash.
     */
    private fun fillWatch(m: Meta) {
        val target = playTarget(m)
        val episode = target?.second
        // Under a minute in is not started: a title opened and closed at 0:00 offers Play, not Resume.
        val resumeAt = (item?.let { Library.resumeAt(it, target?.first ?: metaId) } ?: 0L).takeIf { it >= STARTED } ?: 0L
        val duration = item?.optJSONObject("state")?.optLong("duration") ?: 0L
        val remaining = if (resumeAt > 0 && duration > resumeAt) runtime("${(duration - resumeAt) / 60_000} min") + " left" else null
        val where = listOfNotNull(episode?.let { "S${it.season} E${it.episode}" }, episode?.title?.takeIf { it.isNotBlank() }, remaining).joinToString(DOT)
        whereLine.text = where
        whereLine.visibility = if (where.isNotEmpty() && resumeAt > 0) View.VISIBLE else View.GONE
        progressSlot.removeAllViews()
        if (remaining != null) progressSlot.addView(progressBar(resumeAt.toFloat() / duration), FrameLayout.LayoutParams(-1, -1))
        progressSlot.visibility = if (remaining != null) View.VISIBLE else View.GONE
        playButton.text = if (resumeAt > 0) "Resume" else "Play"
        // Start over goes when there's nothing to start over; the remote moves to Play rather than into nothing.
        if (resumeAt <= 0 && startOverButton.hasFocus()) playButton.requestFocus()
        (startOverButton.parent as? View)?.visibility = if (resumeAt > 0) View.VISIBLE else View.GONE
        listButton.icon(if (Library.inList(item)) R.drawable.ic_check else R.drawable.ic_add)
        labelOf(listButton, if (Library.inList(item)) "In My List" else "Add to My List")
    }

    // Drawn icons, dead centre in their squares: the font's ↺, + and ⓘ sat off-centre, each by a different amount.
    // As in the player: the focused button says its name underneath, so a ↺ or an ⓘ never has to be guessed.
    private val iconLabels = mutableMapOf<View, TextView>()

    private fun iconButton(icon: Int, description: String, action: () -> Unit): TextView {
        val label = text(description, SMALL, color = TEXT).onArt().apply {
            gravity = Gravity.CENTER_HORIZONTAL; maxLines = 1; alpha = 0f
        }
        return text("", 18f).apply {
            gravity = Gravity.CENTER
            icon(icon)
            contentDescription = description
            iconLabels[this] = label
            rowFocus(16, rest = { glass(16) }) { has ->
                grow(has, 1.06f).start()
                label.animate().alpha(if (has) 1f else 0f).setDuration(Motion.QUICK).start()
            }
            setOnClickListener { action() }
        }
    }

    /** A button in its square, with its name hung below it (outside the square, so the row doesn't move). */
    private fun cell(button: TextView) = FrameLayout(this).apply {
        clipChildren = false
        clipToPadding = false
        addView(button, FrameLayout.LayoutParams(-1, -1))
        addView(iconLabels.getValue(button), FrameLayout.LayoutParams(dp(160), -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = dp(ICON_BUTTON + 6)
        })
    }

    private fun labelOf(button: TextView, words: String) {
        button.contentDescription = words
        iconLabels[button]?.text = words
    }

    private fun TextView.icon(res: Int) {
        foreground = getDrawable(res)?.mutate()?.let { d ->
            d.setTint(TEXT)
            android.graphics.drawable.LayerDrawable(arrayOf(d)).apply {
                setLayerSize(0, dp(18), dp(18)); setLayerGravity(0, Gravity.CENTER)
            }
        }
    }

    /** What Play opens: the episode Continue Watching points at, or the first episode, or the movie itself. */
    private fun playTarget(m: Meta?): Pair<String, Video?>? {
        val videos = m?.videos.orEmpty()
        if (videos.isEmpty()) return metaId to null
        val current = item?.optJSONObject("state")?.text("video_id")
        val video = videos.firstOrNull { it.id == current } ?: videos.firstOrNull { it.season > 0 } ?: videos.first()
        return video.id to video
    }

    private fun toggleList() {
        itemChangedHere = true
        val entry = item ?: Library.newItem(metaId, type, meta?.name ?: name, meta?.poster ?: poster)
        Library.setInList(entry, !Library.inList(item))
        item = entry
        val inList = Library.inList(entry)
        listButton.icon(if (inList) R.drawable.ic_check else R.drawable.ic_add)
        labelOf(listButton, if (inList) "In My List" else "Add to My List")
        notice(if (inList) "Added to My List" else "Removed from My List", meta?.name ?: name)
        val snapshot = JSONObject(entry.toString())
        background({ Stremio.saveLibrary(key, listOf(snapshot)) }) {}
    }

    // ---------- glass and episode guide ----------

    private fun glassPane() = FrameLayout(this).apply {
        background = glassOver(this, 14, shade = PANE_SHADE)
        clipToOutline = true
        outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) =
                outline.setRect(0, 0, view.width, view.height)
        }
    }

    private fun buildGuide(videos: List<Video>) {
        guideFor = videos.map { it.id }
        if (::guide.isInitialized) root.removeView(guide)
        guideOpen = false
        val x = dp(PAD)
        val top = dp(128)
        val width = resources.displayMetrics.widthPixels - x * 2
        val height = resources.displayMetrics.heightPixels - top - dp(27)
        val largeText = resources.configuration.fontScale > 1.15f
        guide = FrameLayout(this).apply { visibility = View.GONE }
        val smallLogo = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_START }
        guide.addView(smallLogo, FrameLayout.LayoutParams(dp(150), dp(42)).apply {
            leftMargin = x; topMargin = dp(27)
        })
        val fallback = text(meta?.name ?: name, SERIF_TITLE, serif = true).onArt().apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END; visibility = View.GONE
        }
        guide.addView(fallback, FrameLayout.LayoutParams(dp(250), -2).apply {
            leftMargin = x; topMargin = dp(28)
        })
        Images.load(meta?.logo ?: Images.logoFor(metaId), smallLogo, dp(300), urgent = true, failed = {
            smallLogo.visibility = View.GONE; fallback.visibility = View.VISIBLE
        })

        seasonStrip = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        // Room around the chips for the focused one to grow into: flush with the strip, its left edge was cut flat.
        val room = dp(8)
        guide.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(room, room, room, room)
            clipToPadding = false
            addView(seasonStrip)
        }, FrameLayout.LayoutParams(width + 2 * room, dp(32) + 2 * room).apply { leftMargin = x - room; topMargin = dp(88) - room })

        val pane = glassPane()
        guideRows = column(Gravity.START)
        pane.addView(ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            // The row past the panel's foot fades out instead of running into the edge.
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength(dp(40))
            addView(guideRows)
        }, FrameLayout.LayoutParams(dp(380), height - dp(28)).apply {
            leftMargin = dp(14); topMargin = dp(14)
        })
        val previewX = dp(414)
        val previewWidth = width - previewX - dp(14)
        previewImage = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = rounded(BASE, 8)
            clipToOutline = true
        }
        pane.addView(previewImage, FrameLayout.LayoutParams(previewWidth, dp(226)).apply {
            leftMargin = previewX; topMargin = dp(14)
        })
        previewProgress = FrameLayout(this)
        pane.addView(previewProgress, FrameLayout.LayoutParams(previewWidth - dp(20), dp(3)).apply {
            leftMargin = previewX + dp(10); topMargin = dp(229)
        })
        previewTitle = text("", 22f, serif = true).onArt().apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        pane.addView(previewTitle, FrameLayout.LayoutParams(previewWidth, -2).apply {
            leftMargin = previewX; topMargin = dp(251)
        })
        previewFacts = text("", SMALL, color = TEXT_2).onArt().apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        pane.addView(previewFacts, FrameLayout.LayoutParams(previewWidth, -2).apply {
            leftMargin = previewX; topMargin = dp(if (largeText) 285 else 280)
        })
        previewAbout = text("", SMALL, color = TEXT).onArt().apply {
            maxLines = if (largeText) 2 else 3
            ellipsize = TextUtils.TruncateAt.END
        }
        pane.addView(previewAbout, FrameLayout.LayoutParams(previewWidth, -2).apply {
            leftMargin = previewX; topMargin = dp(if (largeText) 313 else 304)
        })
        guide.addView(pane, FrameLayout.LayoutParams(width, height).apply {
            leftMargin = x; topMargin = top
        })
        root.addView(guide, FrameLayout.LayoutParams(-1, -1))
        selectedSeason = playTarget(meta)?.second?.season ?: videos.first().season
        fillGuide()
    }

    private fun fillGuide() {
        val videos = meta?.videos.orEmpty()
        val seasons = videos.map { it.season }.distinct()
        seasonStrip.removeAllViews()
        seasonButtons = seasons.associateWith { season ->
            chip(if (season == selectedSeason) {
                if (season == 0) "Specials" else "Season $season"
            } else if (season == 0) "Specials" else season.toString(), season == selectedSeason).apply {
                setOnClickListener {
                    selectedSeason = season
                    fillGuide()
                    seasonButtons[season]?.requestFocus()
                }
                seasonStrip.addView(this, LinearLayout.LayoutParams(-2, dp(24)).apply {
                    if (seasonStrip.childCount > 0) marginStart = dp(8)
                })
            }
        }
        guideRows.removeAllViews()
        val watched = Library.watched(item, videos)
        val currentId = playTarget(meta)?.first
        episodeRows = videos.filter { it.season == selectedSeason }.map { video ->
            val row = FrameLayout(this).apply { tag = video.id }
            val number = text("%02d".format(video.episode), BODY, color = TEXT_3)
            row.addView(number, FrameLayout.LayoutParams(dp(32), -2, Gravity.CENTER_VERTICAL).apply {
                leftMargin = dp(12)
            })
            val resumeAt = item?.let { Library.resumeAt(it, video.id) } ?: 0L
            val duration = item?.optJSONObject("state")?.takeIf { it.text("video_id") == video.id }
                ?.optLong("duration") ?: 0L
            val status = when {
                video.id in watched -> "✓"
                resumeAt > 0 && duration > resumeAt -> "${(duration - resumeAt) / 60_000} min left"
                else -> meta?.runtime.orEmpty()
            }
            row.addView(text(video.title.ifBlank { "Episode ${video.episode}" }, BODY,
                color = if (video.id in watched) TEXT_2 else TEXT).apply {
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            }, FrameLayout.LayoutParams(dp(225), -2, Gravity.CENTER_VERTICAL).apply { leftMargin = dp(46) })
            if (status.isNotEmpty()) row.addView(text(status, SMALL,
                color = if (video.id in watched) TUNGSTEN else TEXT_2).apply { gravity = Gravity.END },
                FrameLayout.LayoutParams(dp(98), -2, Gravity.CENTER_VERTICAL or Gravity.END).apply {
                    rightMargin = dp(12)
                })
            row.rowFocus(9) { focused -> if (focused) updatePreview(video) }
            row.setOnClickListener {
                val next = videos.getOrNull(videos.indexOf(video) + 1)?.id
                openSources(video.id, startOver = false, next = next, label = "S${video.season} E${video.episode}")
            }
            row.setOnLongClickListener {
                val next = videos.getOrNull(videos.indexOf(video) + 1)?.id
                openSources(video.id, startOver = false, next = next,
                    label = "S${video.season} E${video.episode}", pick = true)
                true
            }
            guideRows.addView(row, LinearLayout.LayoutParams(-1, dp(45)))
            row
        }
        videos.firstOrNull { it.id == currentId && it.season == selectedSeason }?.let(::updatePreview)
            ?: videos.firstOrNull { it.season == selectedSeason }?.let(::updatePreview)
    }

    private fun updatePreview(video: Video) {
        Images.load(video.thumbnail, previewImage, dp(870), orElse = meta?.background)
        previewTitle.text = video.title.ifBlank { "Episode ${video.episode}" }
        previewFacts.text = listOfNotNull("S${video.season} E${video.episode}", meta?.runtime,
            episodeDate(video.released)).joinToString(DOT)
        previewAbout.text = video.overview.orEmpty()
        previewAbout.visibility = if (video.overview.isNullOrBlank()) View.GONE else View.VISIBLE
        previewProgress.removeAllViews()
        val state = item?.optJSONObject("state")?.takeIf { it.text("video_id") == video.id }
        val resumeAt = item?.let { Library.resumeAt(it, video.id) } ?: 0L
        val duration = state?.optLong("duration") ?: 0L
        if (resumeAt > 0 && duration > resumeAt) {
            previewProgress.addView(progressBar(resumeAt.toFloat() / duration),
                FrameLayout.LayoutParams(-1, -1))
        }
    }

    private fun showGuide() {
        if (!::guide.isInitialized || guideOpen) return
        guideOpen = true
        hero.visibility = View.INVISIBLE
        guide.visibility = View.VISIBLE
        guide.alpha = 0f
        guide.translationY = dp(28).toFloat()
        guide.animate().alpha(1f).translationY(0f).setDuration(Motion.STANDARD).setInterpolator(Motion.EASE).start()
        // The film steps back behind the guide, so the chips and panel read over a bright backdrop.
        heroArt.animate().alpha(GUIDE_ART).setDuration(Motion.STANDARD).setInterpolator(Motion.EASE).withEndAction { refrost() }.start()
        leftShade(false) // the episode list sits on glass: the art can run to the left edge as it does on the right
        val currentId = playTarget(meta)?.first
        (episodeRows.firstOrNull { it.tag == currentId } ?: episodeRows.firstOrNull())?.requestFocus()
    }

    private fun hideGuide() {
        guideOpen = false
        heroArt.animate().alpha(1f).setDuration(Motion.STANDARD).setInterpolator(Motion.EASE).withEndAction { refrost() }.start()
        leftShade(true)
        guide.animate().alpha(0f).translationY(dp(24).toFloat()).setDuration(Motion.STANDARD).setInterpolator(Motion.EASE).withEndAction {
            guide.visibility = View.GONE
            hero.visibility = View.VISIBLE
            if (::playButton.isInitialized) playButton.requestFocus()
        }.start()
    }

    /**
     * The whole story, in the title page's place and layout: the title's logo (its name when there's none), the facts,
     * then the story, which scrolls with the remote when it's long, and who's in it and who made it. Back returns to the
     * title; there's nothing else to press.
     */
    private fun showInfo() {
        val m = meta ?: return
        if (::infoOverlay.isInitialized) root.removeView(infoOverlay)
        infoOpen = true
        hero.visibility = View.INVISIBLE
        infoOverlay = FrameLayout(this)
        // Darker and wider than the title page's shade, and from the foot too: this page is mostly reading, and the
        // words sat on busy art.
        infoOverlay.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(0xF0000808.toInt(), 0xD9000808.toInt(), 0x66000808, 0x00000000))
        }, FrameLayout.LayoutParams(resources.displayMetrics.widthPixels * 3 / 4, -1))
        infoOverlay.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xCC000808.toInt(), 0x00000000))
        }, FrameLayout.LayoutParams(-1, resources.displayMetrics.heightPixels / 2, Gravity.BOTTOM))
        val facts = listOfNotNull(m.year, m.runtime,
            m.genres.take(3).joinToString(" / ").ifEmpty { null }, m.rating?.let { "IMDb $it" }).joinToString(DOT)
        fun people(label: String, names: List<String>, max: Int) = column(Gravity.START,
            caps(label, TEXT_2).onArt(),
            text(names.take(max).joinToString(DOT), BODY, color = TEXT).onArt().apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }.marginTop(6),
        ).marginTop(20)
        val story = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            isFocusable = true // takes the remote, so Up and Down scroll a long story
            addView(column(Gravity.START).apply {
                m.description?.takeIf { it.isNotBlank() }?.let {
                    addView(text(it, BODY, color = TEXT).onArt())
                }
                if (m.cast.isNotEmpty()) addView(people("Starring", m.cast, 8))
                if (m.directors.isNotEmpty()) addView(people(if (m.directors.size > 1) "Directors" else "Director", m.directors, 3))
            })
        }
        // The logo, as on the title page; the name in words until it arrives, or when there's none.
        val name = text(m.name, SERIF_TITLE, bold = true, serif = true).onArt().apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        val logo = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_START; visibility = View.GONE }
        val titleArea = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2)
            addView(name, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
            addView(logo, FrameLayout.LayoutParams(dp(300), dp(90), Gravity.BOTTOM or Gravity.START))
        }
        Images.load(m.logo ?: Images.logoFor(m.id), logo, dp(600), urgent = true, failed = {}) {
            name.visibility = View.GONE; logo.visibility = View.VISIBLE
        }
        infoOverlay.addView(column(Gravity.START,
            titleArea,
            text(facts, SMALL, color = TEXT).onArt().apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }.marginTop(12),
            text("The story", SMALL, bold = true, color = TEXT).onArt().marginTop(SECTION_GAP),
        ).apply {
            addView(story, LinearLayout.LayoutParams(-1, dp(260)).apply { topMargin = dp(8) })
        }, FrameLayout.LayoutParams(dp(HERO_W + 120), -2, Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = dp(PAD); bottomMargin = dp(72)
        })
        root.addView(infoOverlay, FrameLayout.LayoutParams(-1, -1))
        story.requestFocus()
    }

    private fun hideInfo() {
        infoOpen = false
        root.removeView(infoOverlay)
        hero.visibility = View.VISIBLE
        infoButton.requestFocus()
    }

    private fun loadSimilar() {
        background({
            Stremio.addons(key).firstNotNullOfOrNull { addon ->
                runCatching { addon.similar(type, metaId) }.getOrNull()?.takeIf { it.isNotEmpty() }
            }.orEmpty()
        }) { result ->
            result.getOrNull()?.let { titles ->
                titles.asSequence().filter { it.id != metaId && it.type == type }
                    .distinctBy { it.id }.take(12).toList().takeIf { it.isNotEmpty() }
                    ?.let { showSimilar("More like this", it) }
            }
        }
    }

    /** Movies reveal one Home-sized poster row without moving the title off the screen. */
    private fun showSimilar(heading: String, titles: List<Meta>) {
        if (::similarOverlay.isInitialized) root.removeView(similarOverlay)
        similarOverlay = FrameLayout(this).apply { visibility = View.GONE }
        similarOverlay.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(baseAt(0xF7), baseAt(0xA6), baseAt(0x00)))
        }, FrameLayout.LayoutParams(-1, dp(280), Gravity.BOTTOM))
        val content = column(Gravity.START)
        content.addView(caps(heading).onArt(), LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(12)
        })
        val row = LinearLayout(this).apply { clipChildren = false; clipToPadding = false }
        similarFirst = null
        titles.forEachIndexed { index, title ->
            val card = FrameLayout(this).apply {
                background = rounded(TILE, 6) // held for the poster
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: android.graphics.Outline) =
                        outline.setRect(0, 0, view.width, view.height)
                }
                clipToOutline = true
                val image = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
                addView(image, FrameLayout.LayoutParams(-1, -1))
                val fallback = text(title.name, SMALL, bold = true).apply {
                    maxLines = 3; ellipsize = TextUtils.TruncateAt.END
                    setPadding(dp(8), 0, dp(8), dp(8)); visibility = View.GONE
                }
                addView(fallback, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
                cardFocus(6)
                setOnClickListener {
                    startActivity(Intent(this@DetailsActivity, DetailsActivity::class.java)
                        .putExtra("type", title.type).putExtra("id", title.id).putExtra("name", title.name)
                        .putExtra("poster", title.poster).putExtra("background", title.background))
                }
                Images.load(title.poster ?: title.background, image, dp(POSTER_W), failed = {
                    fallback.visibility = View.VISIBLE
                })
            }
            if (similarFirst == null) similarFirst = card
            row.addView(card, LinearLayout.LayoutParams(dp(POSTER_W), dp(POSTER_H)).apply {
                if (index > 0) marginStart = dp(CARD_GAP)
            })
        }
        content.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipChildren = false
            clipToPadding = false
            addView(row)
        }, LinearLayout.LayoutParams(-1, dp(POSTER_H + 8)))
        similarOverlay.addView(content, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            leftMargin = dp(PAD); rightMargin = dp(PAD); bottomMargin = dp(38)
        })
        root.addView(similarOverlay, FrameLayout.LayoutParams(-1, -1))
        updateMoreCaption(meta)
    }

    private fun showSimilar() {
        if (!::similarOverlay.isInitialized || similarOpen) return
        similarOpen = true
        hero.visibility = View.INVISIBLE
        similarOverlay.visibility = View.VISIBLE
        similarOverlay.alpha = 0f
        similarOverlay.translationY = dp(20).toFloat()
        similarOverlay.animate().alpha(1f).translationY(0f).setDuration(Motion.STANDARD).setInterpolator(Motion.EASE).start()
        similarFirst?.requestFocus()
    }

    private fun hideSimilar() {
        similarOpen = false
        similarOverlay.animate().alpha(0f).translationY(dp(20).toFloat()).setDuration(Motion.QUICK).setInterpolator(Motion.EASE).withEndAction {
            similarOverlay.visibility = View.GONE
            hero.visibility = View.VISIBLE
            playButton.requestFocus()
        }.start()
    }

    // ---------- shared ----------

    private fun openSources(videoId: String, startOver: Boolean, next: String? = null, label: String? = null, pick: Boolean = false) {
        val m = meta
        val nextId = next ?: m?.videos?.let { vs -> vs.getOrNull(vs.indexOfFirst { it.id == videoId } + 1)?.id }
        val video = m?.videos?.firstOrNull { it.id == videoId }
        val short = label ?: video?.let { "S${it.season} E${it.episode}" }
        startActivity(Intent(this, StreamsActivity::class.java)
            .putExtra("type", type).putExtra("id", metaId).putExtra("name", m?.name ?: name).putExtra("poster", m?.poster ?: poster)
            .putExtra("videoId", videoId).putExtra("nextVideoId", nextId).putExtra("startOver", startOver).putExtra("pick", pick)
            .putExtra("background", m?.background)
            .putExtra("label", listOfNotNull(m?.name ?: name, short).joinToString("  ·  "))
            // For the sources page: the title's logo, the episode's still and "S1 E3 · OutKast".
            .putExtra("logo", m?.logo).putExtra("still", video?.thumbnail).putExtra("runtime", m?.runtime)
            .putExtra("episode", video?.let { v -> listOfNotNull("S${v.season} E${v.episode}", v.title.takeIf { it.isNotBlank() }).joinToString("  ·  ") }))
    }

    // Back from the player: progress, the resume point and My List may have changed.
    override fun onRestart() {
        super.onRestart()
        background({ runCatching { Stremio.library(key, listOf(metaId)).firstOrNull() }.getOrNull() }) { result ->
            item = result.getOrNull() ?: item
            val m = meta
            val wasGuide = guideOpen
            if (infoOpen) { root.removeView(infoOverlay); infoOpen = false; hero.visibility = View.VISIBLE }
            // Only the watch line and buttons change: the page stays as it was, focus included.
            if (m != null && ::playButton.isInitialized) fillWatch(m) else showHero(m)
            m?.videos?.takeIf { type == "series" && it.isNotEmpty() }?.let(::buildGuide)
            if (wasGuide) showGuide()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (guideOpen) {
                val focused = currentFocus
                if (focused in seasonButtons.values) when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_DOWN -> { episodeRows.firstOrNull()?.requestFocus(); return true }
                    KeyEvent.KEYCODE_DPAD_UP -> return true
                }
                val at = episodeRows.indexOf(focused)
                if (at >= 0) when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> return true
                    KeyEvent.KEYCODE_DPAD_UP -> if (at == 0) { seasonButtons[selectedSeason]?.requestFocus(); return true }
                    KeyEvent.KEYCODE_DPAD_DOWN -> if (at == episodeRows.lastIndex) return true
                }
            } else if (similarOpen && event.keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                hideSimilar()
                return true
            } else if (!infoOpen && !similarOpen && event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN &&
                currentFocus?.let { isInside(it, hero) } == true &&
                (if (type == "series") ::guide.isInitialized else ::similarOverlay.isInitialized)) {
                if (type == "series") showGuide() else showSimilar()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onBackPressed() {
        when {
            infoOpen -> hideInfo()
            guideOpen -> hideGuide()
            similarOpen -> hideSimilar()
            else -> super.onBackPressed()
        }
    }

    private companion object {
        const val PANE_SHADE = 0.55f // the episode panel's frost, darkened to the mock's smoked glass
        const val GUIDE_ART = 0.6f // the film's brightness behind the episode guide
        const val STARTED = 60_000L // ms watched before a title counts as started
        const val ICON_BUTTON = Screen.BUTTON_H // the small square buttons beside Play are as tall as Play
    }
}
