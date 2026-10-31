package dev.alder

import android.content.Context
import android.app.ActivityManager
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

/**
 * Choices kept on this TV. Most are shared by every profile (they're about the TV and the sound system); taste (release
 * type, dubs and audio language) is kept per profile under "key@profileId".
 */
object Settings {
    private fun prefs(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)
    fun maxHeight(c: Context) = prefs(c).getInt("maxHeight", 0) // 0: no limit
    fun maxSizeGb(c: Context) = prefs(c).getInt("maxSizeGb", 0) // 0: no limit
    /** "": as the addon orders them, "dv" or "hdr10": that first, "off": plain releases only. */
    fun hdrPref(c: Context): String {
        val p = prefs(c)
        if (!p.contains("hdrPref")) p.edit().putString("hdrPref", if (p.getBoolean("hdr", true)) "" else "off").remove("hdr").apply() // was On/Off
        return p.getString("hdrPref", "").orEmpty()
    }
    fun hdr(c: Context) = hdrPref(c) != "off"
    /** "": no preference, "remux", "encode" (smaller BluRay and web encodes) or "web". */
    fun release(c: Context) = prefs(c).getString(profileKey(c, "release"), "").orEmpty()
    /** "": no preference, "original": dubs last, "mine": the audio language first. */
    fun dub(c: Context) = prefs(c).getString(profileKey(c, "dub"), "").orEmpty()
    fun profileKey(c: Context, key: String) = "$key@${Profiles.active(c)?.id}"
    /**
     * Titles this profile hid from Continue Watching on this TV: id to {name, timeOffset}, so Settings can put them
     * back. ponytail: this TV only; titles hidden in Stremio elsewhere aren't listed.
     */
    fun hidden(c: Context) = runCatching { org.json.JSONObject(prefs(c).getString(profileKey(c, "hidden"), null)!!) }
        .getOrDefault(org.json.JSONObject())
    fun setHidden(c: Context, all: org.json.JSONObject) = prefs(c).edit().putString(profileKey(c, "hidden"), all.toString()).apply()
    /** A removed profile's taste goes with it, so a new profile in its place starts with none. */
    fun forget(c: Context, profileId: String) = prefs(c).edit().apply {
        listOf("release", "dub", "audioLang", "subLang", "hidden", "homeRows", "tonight").forEach { remove("$it@$profileId") }
        prefs(c).all.keys.filter { it.startsWith("title:") && it.endsWith("@$profileId") }.forEach(::remove)
    }.apply()

    /**
     * What the viewer picked in the player's Audio & subtitles for one title (a series' episodes share it), so the next
     * episode and a resume start the same way. Language codes; subtitles "" is Off; null where nothing was picked.
     */
    class TitleChoice(val audio: String?, val subtitles: String?)

    fun titleChoice(c: Context, metaId: String): TitleChoice? = prefs(c).getString(profileKey(c, "title:$metaId"), null)
        ?.let { runCatching { org.json.JSONObject(it) }.getOrNull() }
        ?.let { TitleChoice(it.text("audio"), if (it.has("subtitles")) it.optString("subtitles") else null) }

    fun pickedInPlayer(c: Context, metaId: String, audio: String? = null, subtitles: String? = null) {
        val key = profileKey(c, "title:$metaId")
        val json = prefs(c).getString(key, null)?.let { runCatching { org.json.JSONObject(it) }.getOrNull() } ?: org.json.JSONObject()
        audio?.let { json.put("audio", it) }
        subtitles?.let { json.put("subtitles", it) }
        prefs(c).edit().putString(key, json.toString()).apply()
    }
    fun alwaysList(c: Context) = prefs(c).getBoolean("alwaysList", false)
    fun matchFrameRate(c: Context) = prefs(c).getBoolean("matchFrameRate", true)
    fun passthrough(c: Context) = prefs(c).getBoolean("passthrough", false)
    fun subtitles(c: Context) = prefs(c).getBoolean("subtitles", true)
    fun trailers(c: Context) = prefs(c).getBoolean("trailers", true)
    /** Two-letter code of the dub to start with: the TV's language unless one is picked. */
    fun audioLanguage(c: Context): String = prefs(c).getString(profileKey(c, "audioLang"), prefs(c).getString("audioLang", ""))
        .orEmpty().ifEmpty { Languages.code(Locale.getDefault().toLanguageTag()) ?: "en" }
    /** Two-letter code of the subtitles to start with: the TV's language unless one is picked. */
    fun subtitleLanguage(c: Context): String = prefs(c).getString(profileKey(c, "subLang"), "")
        .orEmpty().ifEmpty { Languages.code(Locale.getDefault().toLanguageTag()) ?: "en" }
    fun subtitleSize(c: Context) = prefs(c).getInt("subSize", 100) // percent of Media3's default size
    fun subtitleBackground(c: Context) = prefs(c).getBoolean("subBackground", true)
}

/**
 * Every source writes languages its own way: the TV "es", a file's track "spa" or "es-MX", OpenSubtitles "spa", "ger"
 * or "pob", some add-ons "Spanish". [code] turns any of them into one code, so all comparisons go through it: two
 * letters, except Brazilian Portuguese ("pt-BR"), which OpenSubtitles keeps apart from Portugal's.
 */
object Languages {
    const val BRAZIL = "pt-BR"
    // ISO 639-2/B codes (OpenSubtitles sends these) where they differ from 639-2/T, OpenSubtitles' own, and Java's old ones.
    private val aliases = mapOf("alb" to "sq", "arm" to "hy", "baq" to "eu", "bur" to "my", "chi" to "zh", "cze" to "cs",
        "dut" to "nl", "fre" to "fr", "geo" to "ka", "ger" to "de", "gre" to "el", "ice" to "is", "mac" to "mk", "mao" to "mi",
        "may" to "ms", "per" to "fa", "spl" to "es", "rum" to "ro", "slo" to "sk", "tib" to "bo", "wel" to "cy", "pom" to "pt",
        "scc" to "sr", "scr" to "hr", "ze" to "zh", "zht" to "zh", "zhe" to "zh", "iw" to "he", "in" to "id", "ji" to "yi")
    private val brazilian = setOf("pob", "pb", "ptbr", "brazilian", "brazilian portuguese", "portuguese (brazil)",
        "portuguese (brazilian)", "portuguese (br)")
    private val iso by lazy { Locale.getISOLanguages().map { aliases[it] ?: it }.distinct() }
    private val threeLetter by lazy { iso.associateBy { runCatching { Locale(it).isO3Language }.getOrDefault(it) } }
    private val englishNames by lazy { iso.associateBy { Locale(it).getDisplayLanguage(Locale.ENGLISH).lowercase() } }
    private val common = listOf("en", "es", "fr", "de", "pt", BRAZIL, "it", "hi", "ar", "ja", "ko", "zh", "ru")

    /** The code ("es", "pt-BR"), or null for none or unknown ("und"). */
    fun code(raw: String?): String? {
        val s = raw?.trim()?.lowercase()?.replace('_', '-')?.ifEmpty { null } ?: return null
        if (s in brazilian) return BRAZIL
        val parts = s.split('-')
        val base = parts[0].let { aliases[it] ?: when (it.length) {
            2 -> it.takeIf { it in iso }
            3 -> threeLetter[it]
            else -> englishNames[it]
        } } ?: return null
        return if (base == "pt" && parts.getOrNull(1) == "br") BRAZIL else base
    }
    /** "pt-BR" -> "pt". */
    fun base(code: String) = code.substringBefore('-')
    fun same(a: String?, b: String?) = code(a).let { it != null && it == code(b) }
    /** 2 for the same language, 1 for the same language from another country (Portugal's for Brazil's), else 0. */
    fun match(a: String?, b: String?): Int {
        val x = code(a) ?: return 0
        val y = code(b) ?: return 0
        return if (x == y) 2 else if (base(x) == base(y)) 1 else 0
    }
    /** "Spanish", "Portuguese (Brazil)", in the TV's language. */
    fun name(raw: String): String = (code(raw) ?: raw).let { c ->
        val l = Locale.forLanguageTag(c)
        (if (l.country.isEmpty()) l.getDisplayLanguage(Locale.getDefault()) else l.getDisplayName(Locale.getDefault()))
            .ifBlank { c }.replaceFirstChar { it.titlecase(Locale.getDefault()) }
    }
    /** Every language, the common ones first, the rest by name. */
    val all: List<String> by lazy { common + iso.filter { it !in common && name(it) != it }.sortedBy(::name) }
}

internal fun nextSettingIndex(current: Int, size: Int, direction: Int): Int {
    require(size > 0 && direction in -1..1 && direction != 0)
    return if (current < 0) if (direction < 0) size - 1 else 0 else (current + direction + size) % size
}

class SettingsActivity : Screen() {
    private class Choice(val key: String, val label: String, val hint: String, val values: List<Pair<Any, String>>, val perProfile: Boolean = false) {
        init { require(values.isNotEmpty() && values.all { it.first.javaClass == values[0].first.javaClass }) }
    }
    private enum class Group(val label: String, val icon: Int = 0, val glyph: String = "") {
        STREAMS("Streams", R.drawable.ic_play),
        HOME("Home", R.drawable.ic_home),
        THEME("Theme", glyph = "◐"),
        PICTURE("Picture & Sound", R.drawable.ic_tv),
        SUBTITLES("Subtitles", R.drawable.ic_subtitles),
        ADDONS("Add-ons", R.drawable.ic_layers),
        PROFILE("Profile", glyph = "♙"),
        LANGUAGE("Language", glyph = "◎"),
        STORAGE("Storage", glyph = "▤"),
        ABOUT("About", R.drawable.ic_info),
    }

    private val streaming = listOf(
        Choice("alwaysList", "When I press Play", "What Play and Resume do. Holding OK always shows every source.",
            listOf(false to "Best source", true to "Show sources")),
        Choice("maxHeight", "Highest quality", "Releases above this are skipped when picking a source.",
            listOf(0 to "No limit", 2160 to "4K", 1080 to "1080p", 720 to "720p")),
        Choice("maxSizeGb", "Largest file", "For slow connections: big files take longer to start.",
            listOf(0 to "No limit", 40 to "40 GB", 20 to "20 GB", 10 to "10 GB", 5 to "5 GB")),
        Choice("release", "Release type", "Which kind of file is tried first for this profile. REMUX is the full Blu-ray.",
            listOf("" to "No preference", "remux" to "Full Blu-ray (REMUX)", "encode" to "Smaller encodes", "web" to "Web releases"), true),
    )
    private val pictureAndSound = listOf(
        Choice("hdrPref", "HDR and Dolby Vision", "Which goes first when a title has both. Off picks plain releases.",
            listOf("" to "On", "dv" to "Dolby Vision first", "hdr10" to "HDR10 first", "off" to "Off")),
        Choice("matchFrameRate", "Match frame rate", "The TV switches to the film's rate so pans don't judder.",
            listOf(true to "On", false to "Off")),
        Choice("passthrough", "Audio output", "Receiver sends Dolby and DTS untouched to a soundbar or receiver. TV speakers decodes the sound here, which always works.",
            listOf(false to "TV speakers", true to "Receiver")),
        Choice("trailers", "Trailer previews", "Rest on a title for a few seconds and its trailer plays silently behind it.",
            listOf(true to "On", false to "Off")),
    )
    private val saver = listOf(
        Choice("saverMinutes", "Screensaver", "Art from Continue Watching and My List, after this long without a key press.",
            listOf(5 to "After 5 minutes", 10 to "After 10 minutes", 15 to "After 15 minutes", 30 to "After 30 minutes",
                2 to "After 2 minutes", 0 to "Off")),
    )
    private val dubbed = listOf(
        Choice("dub", "Dubbed releases", "Original first puts dubbed releases last; My language first uses this profile's audio language.",
            listOf("" to "No preference", "original" to "Original first", "mine" to "My language first"), true),
    )
    private val subtitles = listOf(
        Choice("subtitles", "Subtitles by default", "Subtitles in your subtitle language start with the video when available.",
            listOf(true to "On", false to "Off")),
    )
    private val subtitleLooks = listOf(
        Choice("subSize", "Subtitle size", "How big subtitles are on screen.",
            listOf(100 to "Medium", 130 to "Large", 160 to "Extra large", 80 to "Small")),
        Choice("subBackground", "Subtitle background", "A dark box behind subtitles keeps them readable in bright scenes.",
            listOf(true to "On", false to "Off")),
    )

    private lateinit var groups: LinearLayout
    private lateinit var detailRows: LinearLayout
    private lateinit var rowsScroll: ScrollView
    private lateinit var detailTitle: TextView
    private lateinit var hint: TextView
    private lateinit var profileName: TextView
    private lateinit var avatar: TextView
    private val groupViews = mutableMapOf<Group, View>()
    private val groupLabels = mutableMapOf<Group, TextView>()
    private val choiceRows = mutableSetOf<View>()
    private val refresh = mutableListOf<() -> Unit>()
    private var selectedGroup: Group? = null
    private var firstDetail: View? = null
    private var addonValue: TextView? = null
    private var homeFocus: String? = null // the catalog the remote goes back to when Home's group is drawn again
    private var homeBuild = 0
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }

    override val fullChoices get() = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Settings.hdrPref(this) // migrate the old HDR switch before any row reads it
        val width = resources.displayMetrics.widthPixels / resources.displayMetrics.density
        val height = resources.displayMetrics.heightPixels / resources.displayMetrics.density
        // Full screen, like Netflix's: no panels. Big section names on the left, a hairline, then the settings as rows.
        val root = FrameLayout(this).apply { background = sky(); clipChildren = false; clipToPadding = false }

        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(ImageView(this).apply { setImageResource(R.drawable.logo_transparent) }, LinearLayout.LayoutParams(dp(34), dp(34)))
        header.addView(text("Settings", SERIF_TITLE, serif = true), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
        header.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        profileName = text("", BODY, bold = true, color = TEXT_2)
        header.addView(profileName, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(10) })
        avatar = text(Profiles.active(this)?.name?.take(1)?.uppercase() ?: "A", BODY, bold = true, color = BASE).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(ICE, TUNGSTEN)).apply { cornerRadius = dp(CORNER).toFloat() }
        }
        header.addView(avatar, LinearLayout.LayoutParams(dp(30), dp(30)))
        root.addView(header, FrameLayout.LayoutParams(-1, dp(44), Gravity.TOP).apply {
            leftMargin = dp(48); rightMargin = dp(48); topMargin = dp(26)
        })

        groups = column(Gravity.START)
        Group.entries.forEach(::addGroup)
        root.addView(groups, FrameLayout.LayoutParams(dp(220), -2, Gravity.TOP or Gravity.START).apply {
            leftMargin = dp(36); topMargin = dp(96)
        })
        // The hairline between the sections and their settings, fading out at both ends.
        root.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0, 0x2EFFFFFF, 0x2EFFFFFF, 0))
        }, FrameLayout.LayoutParams(dp(1), dp((height - 120).toInt()), Gravity.TOP or Gravity.START).apply {
            leftMargin = dp(272); topMargin = dp(96)
        })

        detailTitle = text("", SERIF_TITLE, serif = true).apply { setPadding(dp(16), 0, 0, 0) }
        detailRows = column(Gravity.START)
        rowsScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            clipChildren = false
            clipToPadding = false
            addView(detailRows)
        }
        // Under the section's name: what it's for, then what the focused setting does.
        hint = text("", SMALL, color = TEXT_2).apply { setPadding(dp(16), 0, dp(20), 0); minLines = 2 }
        val detail = column(Gravity.START).apply {
            clipChildren = false
            addView(detailTitle, LinearLayout.LayoutParams(-1, -2))
            addView(hint, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
            addView(rowsScroll, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        }
        root.addView(detail, FrameLayout.LayoutParams(dp((width - 344).toInt()), dp((height - 120).toInt()), Gravity.TOP or Gravity.START).apply {
            leftMargin = dp(296); topMargin = dp(92)
        })
        window.setBackgroundDrawable(null)
        setContentView(root)
        profileName.text = Profiles.active(this)?.name.orEmpty()
        val start = intent.getStringExtra(OPEN_GROUP)?.let { name -> Group.entries.firstOrNull { it.name == name } } ?: Group.STREAMS
        showGroup(start)
        groupViews[start]?.post { groupViews[start]?.requestFocus() }
    }

    override fun onResume() {
        super.onResume()
        if (::profileName.isInitialized) {
            val name = Profiles.active(this)?.name
            profileName.text = name.orEmpty()
            avatar.text = name?.take(1)?.uppercase() ?: "A"
            refresh.forEach { it() }
        }
        val key = authKey ?: return
        background({ Stremio.addons(key).size }) { result ->
            addonValue?.text = (result.getOrNull()?.let { "$it installed" } ?: "Unavailable") + "  ›"
        }
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_DOWN) {
            if (event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT &&
                groupViews.values.any { it.hasFocus() }) {
                if (firstDetail?.requestFocus() == true) return true
            }
            if (event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT &&
                currentFocus?.let { isInside(it, detailRows) && it !in choiceRows } == true) {
                if (selectedGroup?.let { groupViews[it]?.requestFocus() } == true) return true
            }
            // The rows end where they end: Down past the last one stays put instead of falling into the group list.
            if (event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN && detailRows.childCount > 0 &&
                currentFocus === detailRows.getChildAt(detailRows.childCount - 1)) return true
            if (event.keyCode == android.view.KeyEvent.KEYCODE_BACK &&
                currentFocus?.let { isInside(it, detailRows) } == true) {
                if (selectedGroup?.let { groupViews[it]?.requestFocus() } == true) return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /** A section's name, large: the open one is white with the mint line beside it; the remote's also gets the wash. */
    private fun addGroup(group: Group) {
        val label = text(group.label, GROUP_SP, serif = true, color = TEXT_3).apply { setPadding(dp(14), 0, dp(8), 0) }
        val row = FrameLayout(this).apply {
            addView(label, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_VERTICAL))
            rowFocus(rest = { if (selectedGroup == group) openLine() else null }) { has ->
                label.setTextColor(if (has || selectedGroup == group) TEXT else TEXT_3)
                label.setShadowLayer(if (has) dp(10).toFloat() else 0f, 0f, 0f, if (has) 0x73FFFFFF else 0)
                if (has) showGroup(group)
            }
            setOnClickListener { showGroup(group); firstDetail?.requestFocus() }
        }
        groupViews[group] = row
        groupLabels[group] = label
        groups.addView(row, LinearLayout.LayoutParams(-1, dp(GROUP_H)))
    }

    /** The open section's mint line, without the focus wash. */
    private fun openLine() = android.graphics.drawable.LayerDrawable(arrayOf(android.graphics.drawable.ColorDrawable(TUNGSTEN))).apply {
        setLayerWidth(0, dp(3)); setLayerInsetTop(0, dp(10)); setLayerInsetBottom(0, dp(10))
    }

    /** A settings row at rest: only the hairline under it. */
    private fun hairline() = android.graphics.drawable.LayerDrawable(arrayOf(android.graphics.drawable.ColorDrawable(0x17FFFFFF))).apply {
        setLayerGravity(0, Gravity.BOTTOM or Gravity.FILL_HORIZONTAL); setLayerHeight(0, dp(1))
    }

    private fun showGroup(group: Group) {
        if (selectedGroup == group && detailRows.childCount > 0) return
        selectedGroup = group
        groupViews.forEach { (item, view) ->
            if (!view.hasFocus()) {
                groupLabels[item]?.setTextColor(if (item == group) TEXT else TEXT_3)
                view.background = if (item == group) openLine() else null
            }
        }
        detailTitle.text = group.label
        detailRows.removeAllViews()
        rowsScroll.scrollTo(0, 0)
        choiceRows.clear()
        refresh.clear()
        firstDetail = null
        addonValue = null
        hint.text = when (group) {
            Group.STREAMS -> "Choose how Canopy finds and starts a source."
            Group.THEME -> "Colours for Canopy on this TV. The whole app changes as soon as you pick one."
            Group.HOME -> "Choose Home's rows and their order. Hold OK on a title in Continue Watching to pin it to Tonight."
            Group.PICTURE -> "Choose the picture and sound this TV can play."
            Group.SUBTITLES -> "Set how subtitles start and appear."
            Group.ADDONS -> "Manage the add-ons connected to this profile."
            Group.PROFILE -> "Profile choices stay with the person watching."
            Group.LANGUAGE -> "Canopy follows the TV's language."
            Group.STORAGE -> "Storage on this TV only."
            Group.ABOUT -> "About Canopy."
        }
        when (group) {
            Group.STREAMS -> {
                presetRow()
                streaming.forEach(::choiceRow)
                Profiles.active(this)?.let { me ->
                    lateinit var value: TextView
                    value = actionRow("Share my streaming sources", if (Profiles.sharesStreams(this, me.id)) "On" else "Off",
                        "Other profiles on this TV can find sources with this profile's stream add-ons. Your add-on's provider may see what they play.") {
                        val on = !Profiles.sharesStreams(this, me.id)
                        Profiles.setSharesStreams(this, me.id, on)
                        value.text = (if (on) "On" else "Off") + "  ›"
                    }
                }
                Profiles.streamLenders(this).forEach { lender ->
                    val value = actionRow("Shared by ${lender.name}", "…",
                        "${lender.name} shares these stream add-ons with every profile on this TV. Your history stays yours.") {}
                    background({ (Stremio.savedAddons(lender.authKey) ?: Stremio.addons(lender.authKey)).filter { it.shareable } }) { result ->
                        value.text = result.getOrNull()?.map { addonShortName(it.name) }?.ifEmpty { null }?.joinToString(", ")
                            ?: if (result.isSuccess) "No stream add-ons" else "Couldn't load"
                    }
                }
                actionRow("More filters", "", "Release groups, codecs and more are set in your streaming add-on.") {
                    choose("More filters", listOf("OK"),
                        "Release groups, codecs, languages and more are set in your streaming add-on on your phone or at web.stremio.com. This TV follows its order.") {}
                }
            }
            Group.HOME -> homeRows(homeFocus.also { homeFocus = null })
            Group.THEME -> Theme.all.forEach(::themeRow)
            Group.PICTURE -> {
                pictureAndSound.forEach(::choiceRow)
                languageRow("audioLang", "Audio language", "The dub to start with when a file has several. Changeable while watching.")
                dubbed.forEach(::choiceRow)
                saver.forEach(::choiceRow)
                actionRow("Preview screensaver", "", "Any key ends it. OK opens the title on screen.") {
                    startActivity(Intent(this, ScreensaverActivity::class.java))
                }
                actionRow("TV screensaver", "", "Use Canopy's art when the TV itself is idle, outside Canopy too.") { useAsTvScreensaver() }
            }
            Group.SUBTITLES -> {
                subtitles.forEach(::choiceRow)
                languageRow("subLang", "Subtitle language", "Which subtitles start with the video, and which come first in the list while watching.")
                subtitleLooks.forEach(::choiceRow)
            }
            Group.ADDONS -> {
                actionRow("My add-ons", "…", "Add by URL, reorder or remove this profile's add-ons.") {
                    if (authKey != null) startActivity(Intent(this, AddonsActivity::class.java))
                }.also { addonValue = it }
                actionRow("Reload add-ons from Stremio", "", "Fetch this profile's add-ons after installing some elsewhere.") {
                    val key = authKey ?: return@actionRow
                    addonValue?.text = "Reloading…"
                    background({ Stremio.addons(key, refresh = true).size }) { result ->
                        addonValue?.text = (result.getOrNull()?.let { "$it installed" } ?: "Reload failed") + "  ›"
                    }
                }
                val key = authKey
                if (key != null) background({ Stremio.addons(key).size }) { result ->
                    addonValue?.text = (result.getOrNull()?.let { "$it installed" } ?: "Unavailable") + "  ›"
                }
            }
            Group.PROFILE -> {
                actionRow("Switch profile", "", "Choose who is watching.") {
                    startActivity(Intent(this, ProfilesActivity::class.java).putExtra("pick", true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                }
                actionRow("Manage profiles", "", "Rename profiles, change PINs or add another person.") {
                    startActivity(Intent(this, ProfilesActivity::class.java).putExtra("pick", true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                }
                var hiddenValue: TextView? = null
                fun count() { hiddenValue?.text = "${Settings.hidden(this).length()}  ›" }
                hiddenValue = actionRow("Hidden from Continue Watching", "", "Put back a title you removed from Continue Watching.") {
                    unhideMenu(::count)
                }
                count()
            }
            Group.LANGUAGE -> actionRow("App language", Locale.getDefault().displayLanguage,
                "The app follows this TV's language setting.") {
                runCatching { startActivity(Intent(android.provider.Settings.ACTION_APP_LOCALE_SETTINGS)
                    .setData(android.net.Uri.parse("package:$packageName"))) }
                    .recoverCatching { startActivity(Intent(android.provider.Settings.ACTION_LOCALE_SETTINGS)) }
                    .onFailure { choose("Language settings are unavailable on this TV.", listOf("OK")) {} }
            }
            Group.STORAGE -> actionRow("Clear local storage", "", "Removes profiles, settings and saved data from this TV. Stremio accounts stay as they are.") {
                choose("Clear local storage?", listOf("Cancel", "Clear"),
                    "This removes saved profiles, settings and cached data from this TV. It does not cancel your Stremio account.") { choice ->
                    if (choice == 1) (getSystemService(ACTIVITY_SERVICE) as ActivityManager).clearApplicationUserData()
                }
            }
            Group.ABOUT -> {
                detailRows.addView(brand(SERIF_TITLE), LinearLayout.LayoutParams(-2, -2).apply {
                    marginStart = dp(12); bottomMargin = dp(14)
                })
                actionRow("Canopy", "Version " + Updates.mine(this),
                    "Canopy is a TV interface for your Stremio account.") {
                    choose("Canopy", listOf("OK"), "A TV interface for your Stremio account.") {}
                }
                actionRow("What's new", "Version " + Updates.mine(this), "The changes in the version on this TV.") { whatsNew() }
                var found: Updates.Release? = null
                val value = actionRow("Updates", "Checking…", "New versions come from Canopy's releases on GitHub.") {
                    found?.let(::installUpdate) ?: showGroup(Group.ABOUT)
                }
                background({ Updates.latest(applicationContext) }) { r ->
                    val release = r.getOrNull()
                    found = release?.takeIf { newerVersion(it.version, Updates.mine(this)) }
                    value.text = when {
                        found != null -> "Install ${found!!.version}  ›"
                        release == null -> "Couldn't check · Try again  ›"
                        else -> "Up to date"
                    }
                }
            }
        }
    }

    /** The TV's screensaver settings, where Canopy is listed; TVs that hide them (Google TV) get the ADB command. */
    private fun useAsTvScreensaver() {
        runCatching { startActivity(Intent(android.provider.Settings.ACTION_DREAM_SETTINGS)) }.onFailure {
            choose("Set the TV screensaver", listOf("OK"),
                "This TV hides its screensaver settings. From a computer, run: adb shell settings put secure " +
                    "screensaver_components $packageName/${CanopyDream::class.java.name}") {}
        }
    }

    /**
     * Home's rows for this profile: Tonight, then every catalog of its add-ons in Home's order. OK on a catalog shows or
     * hides it and moves it up or down; the page is drawn again with the remote on the same catalog.
     */
    private fun homeRows(focusOn: String?) {
        val build = ++homeBuild
        val tonight = actionRow("Tonight", "${HomeRows.tonight(this).size} pinned",
            "Titles you pin show in their own row above Continue Watching, until you finish them.") {
            if (HomeRows.tonight(this).isEmpty()) choose("Nothing is pinned", listOf("OK"),
                "Hold OK on a title in Continue Watching on Home and choose Pin to Tonight.") {}
            else choose("Tonight", listOf("Keep", "Unpin everything"), "Titles leave Tonight on their own once you finish them.") {
                if (it == 1) { HomeRows.setTonight(this, emptyList()); redrawHome(null) }
            }
        }
        actionRow("Reset Home rows", "", "Back to Canopy's own rows: Watchly's picks for you, in your add-ons' order.") {
            HomeRows.reset(this)
            redrawHome(null)
        }
        val key = authKey ?: return
        val loading = text("Loading your catalogs…", SMALL, color = TEXT_3).apply { setPadding(dp(12), dp(10), 0, 0) }
        detailRows.addView(loading)
        background({ Stremio.savedAddons(key) ?: Stremio.addons(key) }) { result ->
            if (selectedGroup != Group.HOME || build != homeBuild) return@background
            detailRows.removeView(loading)
            val rows = HomeRows.arranged(this, result.getOrNull().orEmpty().flatMap { it.catalogs })
            if (rows.isEmpty()) {
                detailRows.addView(text(if (result.isSuccess) "None of your add-ons has catalogs." else "Couldn't load your add-ons.",
                    SMALL, color = TEXT_3).apply { setPadding(dp(12), dp(10), 0, 0) })
                return@background
            }
            var shown = 0
            rows.forEachIndexed { i, (catalog, on) ->
                val type = when (catalog.type) { "movie" -> "Movies"; "series" -> "Shows"; else -> catalog.type.replaceFirstChar { it.uppercase() } }
                val label = "${catalog.name} · $type"
                val row = actionRow(label, if (on) "Row ${++shown}" else "Hidden",
                    "From ${addonShortName(catalog.addon.name)}. OK to show or hide it, or move it up and down Home.") {
                    choose(label, listOf(if (on) "Hide from Home" else "Show on Home", "Move up", "Move down", "Move to the top"),
                        "From ${addonShortName(catalog.addon.name)}.") { pick ->
                        val next = when (pick) {
                            0 -> rows.mapIndexed { j, r -> if (j == i) r.first to !on else r }
                            1 -> moved(rows, i, -1)
                            2 -> moved(rows, i, 1)
                            else -> moved(rows, i, -i)
                        }
                        HomeRows.saveArranged(this, next)
                        redrawHome(HomeRows.key(catalog))
                    }
                }
                (row.parent?.parent as? View)?.tag = HomeRows.key(catalog)
            }
            focusOn?.let { k -> detailRows.findViewWithTag<View>(k)?.requestFocus() }
        }
        if (focusOn == "") tonight.post { (tonight.parent?.parent as? View)?.requestFocus() }
    }

    /** Home's group drawn again after a change, with the remote back on [focusOn] (a catalog), or on Tonight. */
    private fun redrawHome(focusOn: String?) {
        // The remote waits on Home's name while its rows are replaced, or Android would put it on the first group.
        groupViews[Group.HOME]?.requestFocus()
        homeFocus = focusOn ?: ""
        selectedGroup = null
        showGroup(Group.HOME)
    }

    private fun settingRow(label: String, value: View, help: String, perProfile: Boolean = false,
                           focus: (Boolean) -> Unit = {}, click: () -> Unit): View {
        val line = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(text(label, ROW_SP, bold = true), LinearLayout.LayoutParams(-2, -2))
            if (perProfile) addView(badge("THIS PROFILE", filled = false), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
            addView(View(this@SettingsActivity), LinearLayout.LayoutParams(0, 1, 1f))
            addView(value, LinearLayout.LayoutParams(-2, -2))
            setPadding(dp(16), 0, dp(16), 0)
        }
        // Light line, glossy: the focused row gets a bright top edge, the mint edge and a sheen fading to the right.
        val row = FrameLayout(this).apply {
            addView(line, FrameLayout.LayoutParams(-1, -1))
            isFocusable = true
            background = hairline()
            setOnFocusChangeListener { _, has ->
                background = if (has) lineFocus(gloss = true) else hairline()
                if (has) hint.text = help
                focus(has)
            }
            setOnClickListener { click() }
        }
        detailRows.addView(row, LinearLayout.LayoutParams(-1, dp(SETTING_H)))
        if (firstDetail == null) firstDetail = row
        return row
    }

    /** The titles hidden from Continue Watching; OK on one puts it back with its resume point, here and in Stremio. */
    private fun unhideMenu(done: () -> Unit) {
        val all = Settings.hidden(this)
        val ids = all.keys().asSequence().toList()
        if (ids.isEmpty()) return choose("Nothing is hidden", listOf("OK"), "Titles you remove from Continue Watching show here.") {}
        val key = authKey ?: return
        choose("Hidden from Continue Watching", ids.map { all.getJSONObject(it).optString("name", it) }, "Select a title to put it back.") { i ->
            val id = ids[i]
            background({
                val item = Stremio.library(key, listOf(id)).firstOrNull() ?: error("not in the library")
                Library.unhide(item, all.getJSONObject(id).optLong("timeOffset"))
                Stremio.saveLibrary(key, listOf(item))
            }) { result ->
                result.fold({
                    all.remove(id)
                    Settings.setHidden(this, all)
                    done()
                }) { choose("Couldn't put it back", listOf("OK"), "Check the connection and try again.") {} }
            }
        }
    }

    /** One colour scheme: its name, a swatch of its two colours, and "In use" on the one that is. */
    private fun themeRow(theme: Theme) {
        val display = actionRow(theme.name, if (theme === Theme.now) "In use" else "", "Pick to switch the whole app to ${theme.name}.") {
            if (theme === Theme.now) return@actionRow
            Theme.choose(this, theme)
            intent.putExtra(OPEN_GROUP, Group.THEME.name)
            recreate()
        }
        display.setCompoundDrawablesRelative(GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(theme.accent, theme.second)).apply {
            cornerRadius = dp(2).toFloat(); setBounds(0, 0, dp(36), dp(14))
        }, null, null, null)
        display.compoundDrawablePadding = dp(12)
        display.gravity = Gravity.CENTER_VERTICAL
    }

    private fun actionRow(label: String, value: String, help: String, click: () -> Unit): TextView {
        val display = text(if (value.isEmpty()) "›" else "$value  ›", ROW_SP, bold = true, color = ICE)
        settingRow(label, display, help, focus = { display.setTextColor(if (it) TEXT else ICE) }, click = click)
        return display
    }

    private fun key(c: Choice) = if (c.perProfile) Settings.profileKey(this, c.key) else c.key
    private fun stored(c: Choice): Any = when (val default = c.values[0].first) {
        is Boolean -> prefs.getBoolean(key(c), default)
        is String -> prefs.getString(key(c), default).orEmpty()
        else -> prefs.getInt(key(c), default as Int)
    }
    private fun store(c: Choice, value: Any) = prefs.edit().apply {
        when (value) {
            is Boolean -> putBoolean(key(c), value)
            is String -> putString(key(c), value)
            else -> putInt(key(c), value as Int)
        }
    }.apply()
    private fun choice(key: String) = (streaming + pictureAndSound).first { it.key == key }

    private fun choiceRow(c: Choice) {
        fun current() = c.values.indexOfFirst { it.first == stored(c) }.coerceAtLeast(0)
        // Off and On side by side where the choice is only that (no round switch); other choices are words.
        val isSwitch = c.values.map { it.second } == listOf("On", "Off")
        val words = text("", ROW_SP, bold = true, color = ICE)
        val off = text("Off", ROW_SP, bold = true).apply { setPadding(0, dp(3), 0, dp(3)) }
        val on = text("On", ROW_SP, bold = true).apply { setPadding(0, dp(3), 0, dp(3)) }
        val value = if (isSwitch) LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(off, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(16) })
            addView(on, LinearLayout.LayoutParams(-2, -2))
        } else words
        var focused = false
        fun draw() {
            val index = current()
            words.setTextColor(if (focused) TEXT else ICE)
            words.text = if (focused) "‹  " + c.values[index].second + "  ›" else c.values[index].second
            if (isSwitch) {
                val isOn = c.values[index].first == true
                for ((word, lit) in listOf(off to !isOn, on to isOn)) {
                    word.setTextColor(if (lit) TEXT else TEXT_3)
                    word.background = if (!lit) null else android.graphics.drawable.LayerDrawable(arrayOf(android.graphics.drawable.ColorDrawable(TUNGSTEN))).apply {
                        setLayerGravity(0, Gravity.BOTTOM or Gravity.FILL_HORIZONTAL); setLayerHeight(0, dp(2))
                    }
                }
            }
        }
        fun shift(by: Int) {
            val next = nextSettingIndex(current(), c.values.size, by)
            store(c, c.values[next].first)
            refresh.forEach { it() }
        }
        val row = settingRow(c.label, value, c.hint, c.perProfile, focus = { has -> focused = has; draw() }) { shift(1) }
        choiceRows += row
        row.setOnKeyListener { _, code, event ->
            if (event.action == android.view.KeyEvent.ACTION_DOWN &&
                (code == android.view.KeyEvent.KEYCODE_DPAD_LEFT || code == android.view.KeyEvent.KEYCODE_DPAD_RIGHT)) {
                shift(if (code == android.view.KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1)
                true
            } else false
        }
        refresh += ::draw
        draw()
    }

    /** A language for this profile, picked from a list (too many to step through with Left and Right). */
    private fun languageRow(key: String, label: String, help: String) {
        val codes = listOf("") + Languages.all
        // Audio language was once shared by every profile: that pick stands until this profile makes its own.
        fun current() = prefs.getString(Settings.profileKey(this, key), if (key == "audioLang") prefs.getString(key, "") else "").orEmpty()
        fun name(code: String) = if (code.isEmpty()) "TV language" else Languages.name(code)
        val value = text("", ROW_SP, bold = true, color = ICE)
        fun draw() { value.text = name(current()) + "  ›" }
        settingRow(label, value, help, perProfile = true, focus = { value.setTextColor(if (it) TEXT else ICE) }) {
            choose(label, codes.map(::name), checked = codes.indexOf(current()).coerceAtLeast(0)) { i ->
                prefs.edit().putString(Settings.profileKey(this, key), codes[i]).apply()
                refresh.forEach { it() }
            }
        }
        refresh += ::draw
        draw()
    }

    private fun presetRow() {
        val value = text("", ROW_SP, bold = true, color = ICE)
        fun current() = PRESETS.indexOfFirst { (_, rows) -> rows.all { (key, v) -> stored(choice(key)) == v } }
        var focused = false
        fun draw() {
            value.setTextColor(if (focused) TEXT else ICE)
            value.text = (if (focused) "‹  " else "") + (PRESETS.getOrNull(current())?.first ?: "Custom") + (if (focused) "  ›" else "")
        }
        fun shift(by: Int) {
            val next = nextSettingIndex(current(), PRESETS.size, by)
            PRESETS[next].second.forEach { (key, v) -> store(choice(key), v) }
            refresh.forEach { it() }
        }
        val row = settingRow("Source choice", value,
            "Best picture: full Blu-ray and Dolby Vision first. Balanced: smaller files. Fast start: web releases. Add-on's order: follow the add-on.",
            focus = { has -> focused = has; draw() }) { shift(1) }
        choiceRows += row
        row.setOnKeyListener { _, code, event ->
            if (event.action == android.view.KeyEvent.ACTION_DOWN &&
                (code == android.view.KeyEvent.KEYCODE_DPAD_LEFT || code == android.view.KeyEvent.KEYCODE_DPAD_RIGHT)) {
                shift(if (code == android.view.KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1)
                true
            } else false
        }
        refresh += ::draw
        draw()
    }

    private companion object {
        const val OPEN_GROUP = "group"
        const val GROUP_SP = 17f // a section's name
        const val GROUP_H = 40 // dp
        const val ROW_SP = 15.5f // a setting's name and value
        const val SETTING_H = 52 // dp
        val PRESETS = listOf(
            "Add-on's order" to mapOf("release" to "", "hdrPref" to "", "maxHeight" to 0, "maxSizeGb" to 0),
            "Best picture" to mapOf("release" to "remux", "hdrPref" to "dv", "maxHeight" to 0, "maxSizeGb" to 0),
            "Balanced" to mapOf("release" to "encode", "hdrPref" to "", "maxHeight" to 0, "maxSizeGb" to 20),
            "Fast start" to mapOf("release" to "web", "hdrPref" to "", "maxHeight" to 1080, "maxSizeGb" to 10),
        )
    }
}
