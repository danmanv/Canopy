package dev.alder

import android.app.SearchManager
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.speech.RecognizerIntent
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextClock
import android.widget.TextView
import java.util.Locale
import java.text.Normalizer
import java.util.concurrent.Future

/** More complete title matches lead; ties retain the installed add-ons' order. */
internal fun <T> rankSearchTitles(items: List<T>, query: String, title: (T) -> String): List<T> {
    fun words(s: String) = Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
    val wanted = words(query)
    if (wanted.isEmpty()) return items
    return items.sortedBy { item ->
        val actual = words(title(item))
        when {
            actual == wanted -> 0
            actual.take(wanted.size) == wanted -> 1
            wanted.all { it in actual } -> 2
            actual.joinToString(" ").contains(wanted.joinToString(" ")) -> 3
            else -> 4
        }
    }
}

internal fun <T> searchAnswersInAddonOrder(answers: Array<List<T>?>): List<T> = answers.filterNotNull().flatten()

/** Search: what you type in large type, the alphabet on one line under it, and poster rows across the screen. */
class SearchActivity : Screen() {
    private lateinit var art: ImageView
    private lateinit var artBack: ImageView
    private lateinit var input: EditText
    private lateinit var clearSearch: View
    private lateinit var resultCount: TextView
    private lateinit var recentBox: LinearLayout
    private lateinit var results: LinearLayout
    private lateinit var resultsScroll: ScrollView
    private lateinit var keyboard: LinearLayout
    private val characterKeys = mutableListOf<TextView>()
    private val cards = mutableListOf<Pair<View, View>>()
    private val topRowCards = mutableSetOf<View>()
    private var firstKey: View? = null
    private var lastKeyboardFocus: View? = null
    private var firstResult: View? = null
    private var focused: Meta? = null
    private var numeric = false
    private var request = 0
    private val searchTasks = mutableListOf<Future<*>>()
    private val typed = Runnable { search(false) }
    override val backdrop get() = art

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val key = authKey ?: return run {
            // Opened by the TV's own search before anyone has signed in: the profiles screen comes first.
            startActivity(Intent(this, ProfilesActivity::class.java))
            finish()
        }
        art = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f }
        artBack = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        val width = resources.displayMetrics.widthPixels / resources.displayMetrics.density
        val height = resources.displayMetrics.heightPixels / resources.displayMetrics.density
        val root = FrameLayout(this).apply { setBackgroundColor(BASE); clipChildren = false }
        root.addView(artBack, FrameLayout.LayoutParams(-1, -1))
        root.addView(art, FrameLayout.LayoutParams(-1, -1))
        root.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(baseAt(0xE6), baseAt(0xBF), baseAt(0x8C), baseAt(0x40)))
        }, FrameLayout.LayoutParams(-1, -1))
        root.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(baseAt(0xF7), baseAt(0xBF), baseAt(0x00)))
        }, FrameLayout.LayoutParams(-1, dp(260), Gravity.BOTTOM))
        root.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(baseAt(0xD9), baseAt(0x8C), baseAt(0x00)))
        }, FrameLayout.LayoutParams(-1, dp(220), Gravity.TOP))
        root.addView(header(), FrameLayout.LayoutParams(-1, dp(40)).apply { topMargin = dp(24) })

        // What you type, large, over a hairline: no field box.
        val field = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        input = EditText(this).apply {
            hint = "Search"
            setHintTextColor(TEXT_3)
            setTextColor(TEXT)
            textSize = QUERY_SP
            typeface = text("", QUERY_SP, bold = true).typeface
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            showSoftInputOnFocus = false
            background = null
            setPadding(0, 0, dp(5), 0)
            setSelectAllOnFocus(false)
            onArt()
        }
        // The field's own caret shows only while it has focus, and focus lives on the letters: a tungsten bar after the
        // last letter stands in for it.
        val inputBox = FrameLayout(this)
        inputBox.addView(input, FrameLayout.LayoutParams(-1, -1))
        cursor = View(this).apply { setBackgroundColor(TUNGSTEN) }
        inputBox.addView(cursor, FrameLayout.LayoutParams(dp(2), dp(26), Gravity.CENTER_VERTICAL))
        field.addView(inputBox, LinearLayout.LayoutParams(0, dp(44), 1f))
        resultCount = text("", SMALL, color = TEXT_3).onArt()
        field.addView(resultCount)
        val clear = ImageView(this).apply {
            setImageResource(R.drawable.ic_close)
            imageTintList = ColorStateList.valueOf(TEXT_2)
            setPadding(dp(5), dp(5), dp(5), dp(5))
            contentDescription = "Clear search"
            visibility = View.GONE
            isFocusable = true
            setOnFocusChangeListener { v, has -> imageTintList = ColorStateList.valueOf(if (has) TEXT else TEXT_2); v.background = if (has) underline(TEXT, 4) else null }
            setOnClickListener {
                input.setText("")
                main.removeCallbacks(typed)
                search(false)
                firstKey?.requestFocus()
            }
        }
        field.addView(clear, LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginStart = dp(8) })
        clearSearch = clear
        input.setOnFocusChangeListener { _, has ->
            placeCursor()
            if (!has) {
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(input.windowToken, 0)
            }
        }
        root.addView(field, FrameLayout.LayoutParams(dp(QUERY_W), dp(44)).apply { leftMargin = dp(48); topMargin = dp(78) })
        root.addView(View(this).apply { setBackgroundColor(0x40FFFFFF) },
            FrameLayout.LayoutParams(dp(QUERY_W), dp(1)).apply { leftMargin = dp(48); topMargin = dp(123) })

        // The alphabet on one line; left and right glide along it.
        keyboard = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(48 - KEY_PAD), 0, dp(48), 0) }
        buildKeyboard()
        root.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            addView(keyboard)
        }, FrameLayout.LayoutParams(-1, dp(KEY_H)).apply { topMargin = dp(132) })

        recentBox = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(48), 0, dp(48), 0) }
        root.addView(recentBox, FrameLayout.LayoutParams(-1, dp(28)).apply { topMargin = dp(172) })
        showRecent()

        results = column(Gravity.START)
        resultsScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(results)
        }
        // Runs to the screen's right edge, as Home's rows do.
        root.addView(resultsScroll, FrameLayout.LayoutParams(-1, dp((height - RESULTS_TOP).toInt())).apply {
            leftMargin = dp(48 - 6); topMargin = dp(RESULTS_TOP)
        })
        window.setBackgroundDrawable(null)
        setContentView(root)

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                placeCursor()
                clear.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
                ++request
                searchTasks.forEach { it.cancel(true) }
                searchTasks.clear()
                main.removeCallbacks(typed)
                main.postDelayed(typed, TYPING_PAUSE)
            }
        })
        input.setOnEditorActionListener { _, _, _ ->
            main.removeCallbacks(typed)
            search(true)
            true
        }
        // Our keyboard first: the field never takes focus on opening, so the Android keyboard stays shut until the keyboard key.
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        firstKey?.post { firstKey?.requestFocus() }
        // Only on first opening: a screen recreated by Android keeps to itself rather than listening again.
        if (savedInstanceState != null || !take(intent)) search(false)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        take(intent)
    }

    /**
     * A search handed over by the TV (the Assistant's "search for … on Canopy", or global search) runs at once; the
     * remote's Search button opens listening. Returns whether it started anything.
     */
    private fun take(intent: Intent): Boolean {
        val query = intent.getStringExtra(SearchManager.QUERY)?.trim()
        if (!query.isNullOrEmpty() && (intent.action == Intent.ACTION_SEARCH || intent.action == ASSISTANT_SEARCH)) {
            heard(query)
            return true
        }
        if (intent.getBooleanExtra(LISTEN, false)) {
            search(false)
            listen()
            return true
        }
        return false
    }

    override fun onSearchRequested(): Boolean {
        listen()
        return true
    }

    private fun listen() {
        runCatching {
            startActivityForResult(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Say a movie or show")
            }, SPEECH)
        }.onFailure { choose("Voice search is unavailable on this TV.", listOf("OK")) {} }
    }

    private fun heard(words: String) {
        input.setText(words)
        input.setSelection(input.length())
        input.requestFocus()
        main.removeCallbacks(typed)
        search(false)
    }

    private lateinit var cursor: View

    private fun placeCursor() {
        cursor.visibility = if (input.hasFocus()) View.GONE else View.VISIBLE
        cursor.translationX = input.paddingStart + input.paint.measureText(input.text.toString())
    }

    private fun header(): View {
        val profile = Profiles.active(this)
        val bar = FrameLayout(this).apply { setPadding(dp(48), 0, dp(48), 0) }
        val avatar = text(profile?.name?.take(1)?.uppercase() ?: "A", BODY, bold = true, color = BASE).apply {
            gravity = Gravity.CENTER
            background = rounded(profile?.color ?: TEXT_2, CORNER)
            isFocusable = true
            setOnFocusChangeListener { v, has -> v.grow(has).start() }
            setOnClickListener {
                choose(profile?.name ?: "Profile", listOf("Switch profile", "Settings")) { picked ->
                    startActivity(Intent(this@SearchActivity,
                        if (picked == 0) ProfilesActivity::class.java else SettingsActivity::class.java)
                        .apply { if (picked == 0) {
                            putExtra("pick", true)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        } })
                }
            }
        }
        bar.addView(avatar, FrameLayout.LayoutParams(dp(30), dp(30), Gravity.START or Gravity.CENTER_VERTICAL))
        val nav = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val searchTab = FrameLayout(this).apply {
            isFocusable = true
            contentDescription = "Search"
            addView(ImageView(this@SearchActivity).apply {
                setImageResource(R.drawable.ic_search)
                imageTintList = ColorStateList.valueOf(TEXT)
            }, FrameLayout.LayoutParams(dp(17), dp(17), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(3) })
            // The open page: a tungsten line under it, white with the remote on it.
            background = underline(TUNGSTEN, 12)
            setOnFocusChangeListener { _, has -> background = underline(if (has) TEXT else TUNGSTEN, 12) }
            setOnClickListener { input.requestFocus() }
        }
        nav.addView(searchTab, LinearLayout.LayoutParams(dp(44), dp(30)))
        listOf("Home", "Shows", "Movies", "My List").forEach { page ->
            nav.addView(tab(page, open = false).apply {
                setOnClickListener {
                    startActivity(Intent(this@SearchActivity, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        .putExtra("page", page))
                    finish()
                }
            }, LinearLayout.LayoutParams(-2, dp(30)))
        }
        bar.addView(nav, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        bar.addView(TextClock(this).apply {
            format12Hour = "h:mm a"
            format24Hour = "HH:mm"
            textSize = SMALL
            setTextColor(TEXT_2)
            typeface = text("", SMALL).typeface
        }, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL))
        return bar
    }

    private fun buildKeyboard() {
        val letters = "abcdefghijklmnopqrstuvwxyz"
        letters.forEachIndexed { index, letter ->
            characterKeys += key(letter.toString()) { append(characterKeys[index].text.toString()) }
        }
        gap()
        key("space") { append(" ") }
        key("⌫") { delete() }.contentDescription = "Delete"
        modeLabel = key("123") {
            numeric = !numeric
            val symbols = "1234567890@#$%&*()!?.,:/+"
            characterKeys.forEachIndexed { index, view ->
                view.text = if (numeric) symbols.getOrNull(index)?.toString() ?: "·" else letters[index].toString()
            }
            modeLabel?.text = if (numeric) "ABC" else "123"
        }
        key("−") { append("-") }
        if (Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).resolveActivity(packageManager) != null) {
            key("") { listen() }.apply {
                contentDescription = "Voice search"
                setCompoundDrawablesRelative(getDrawable(R.drawable.ic_mic)?.mutate()?.apply {
                    setTint(TUNGSTEN); setBounds(0, 0, dp(13), dp(17))
                }, null, null, null)
            }
        }
    }

    private var modeLabel: TextView? = null

    /** A little room between the letters and the other keys. */
    private fun gap() = keyboard.addView(View(this), LinearLayout.LayoutParams(dp(14), 1))

    /** A key: a letter or word with no tile. Bright and glowing over a white line with the remote on it. */
    private fun key(label: String, click: () -> Unit): TextView {
        val view = text(label, if (label.length > 1) SMALL else KEY_SP, color = TEXT_2).onArt().apply {
            gravity = Gravity.CENTER
            isFocusable = true
            setPadding(dp(KEY_PAD), 0, dp(KEY_PAD), 0)
        }
        view.setOnFocusChangeListener { _, has ->
            view.setTextColor(if (has) TEXT else TEXT_2)
            if (has) view.setShadowLayer(dp(10).toFloat(), 0f, 0f, 0x99FFFFFF.toInt()) else view.onArt()
            view.background = if (has) underline(TEXT, KEY_PAD - 2) else null
            if (has) lastKeyboardFocus = view
        }
        view.setOnClickListener { click() }
        keyboard.addView(view, LinearLayout.LayoutParams(-2, -1))
        if (firstKey == null) firstKey = view
        return view
    }

    /** A 2 dp line along a view's foot, [inset] dp in from each side: Canopy's focus and open-page mark. */
    private fun underline(color: Int, inset: Int) = android.graphics.drawable.LayerDrawable(arrayOf(
        GradientDrawable().apply { setColor(color) })).apply {
        setLayerGravity(0, Gravity.BOTTOM or Gravity.FILL_HORIZONTAL)
        setLayerHeight(0, dp(2))
        setLayerInsetLeft(0, dp(inset))
        setLayerInsetRight(0, dp(inset))
    }

    private fun append(value: String) {
        val at = input.selectionStart.coerceIn(0, input.length())
        input.text.insert(at, value)
        input.setSelection(at + value.length)
    }

    private fun delete() {
        val at = input.selectionStart.coerceIn(0, input.length())
        if (at > 0) {
            input.text.delete(at - 1, at)
            input.setSelection(at - 1)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val focus = currentFocus
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && focus === input && clearSearch.visibility == View.VISIBLE && clearSearch.requestFocus()) return true
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT && focus === clearSearch && input.requestFocus()) return true
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN && (focus === input || focus === clearSearch)) {
                if ((lastKeyboardFocus ?: firstKey)?.requestFocus() == true) return true
            }
            // Down from the letters: the recent searches if there are any, else the results. Up from either: the letters.
            val onKey = focus != null && focus.parent === keyboard
            val onRecent = focus != null && focus.parent === recentBox
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN && onKey) {
                (firstRecent() ?: firstResult)?.requestFocus()
                return true
            }
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP && onKey && input.requestFocus()) return true
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN && onRecent) { firstResult?.requestFocus(); return true }
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP && onRecent) { (lastKeyboardFocus ?: firstKey)?.requestFocus(); return true }
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP && focus in topRowCards) {
                val up = firstRecent() ?: lastKeyboardFocus ?: firstKey
                if (up?.requestFocus() == true) return true
            }
            if (event.keyCode == KeyEvent.KEYCODE_DEL) { delete(); return true }
            if (event.keyCode == KeyEvent.KEYCODE_SPACE) { append(" "); return true }
            val char = event.unicodeChar.takeIf { it in 32..126 }?.toChar()
            if (char != null && !event.isCtrlPressed && !event.isAltPressed) {
                append(char.toString())
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    @Deprecated("Android result callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != SPEECH || resultCode != RESULT_OK) return
        heard(data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: return)
    }

    private fun recentKey() = "recent:${Profiles.active(this)?.id.orEmpty()}"

    private fun recent() = getSharedPreferences("search", MODE_PRIVATE).getString(recentKey(), "").orEmpty()
        .split("\n").filter { it.isNotBlank() }

    private fun remember(query: String) {
        val values = (listOf(query) + recent().filterNot { it.equals(query, ignoreCase = true) }).take(RECENT)
        getSharedPreferences("search", MODE_PRIVATE).edit().putString(recentKey(), values.joinToString("\n")).apply()
        showRecent()
    }

    private fun firstRecent(): View? = if (recentBox.visibility != View.VISIBLE) null
        else (0 until recentBox.childCount).map(recentBox::getChildAt).firstOrNull { it.isFocusable }

    private fun showRecent() {
        recentBox.removeAllViews()
        val values = recent()
        recentBox.visibility = if (values.isEmpty()) View.GONE else View.VISIBLE
        if (values.isEmpty()) return
        recentBox.addView(caps("Recent", TEXT_3).onArt(), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
        values.forEach { query ->
            recentBox.addView(chip(query).apply {
                maxWidth = dp(180)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                contentDescription = "$query. Hold OK to remove from recent searches"
                setOnClickListener {
                    input.setText(query)
                    input.setSelection(query.length)
                    main.removeCallbacks(typed)
                    search(true)
                }
                setOnLongClickListener {
                    choose("Remove recent search?", listOf("Remove $query", "Cancel")) { picked ->
                        if (picked == 0) {
                            getSharedPreferences("search", MODE_PRIVATE).edit()
                                .putString(recentKey(), recent().filterNot { it == query }.joinToString("\n")).apply()
                            showRecent()
                        }
                    }
                    true
                }
            }, LinearLayout.LayoutParams(-2, -1))
        }
    }

    private fun search(moveFocus: Boolean) {
        val query = input.text.toString().trim()
        val token = ++request
        searchTasks.forEach { it.cancel(true) }
        searchTasks.clear()
        firstResult = null
        cards.clear()
        focused = null
        topRowCards.clear()
        results.removeAllViews()
        resultsScroll.scrollTo(0, 0)
        resultCount.text = ""
        results.addView(ring(), LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginStart = dp(12) })
        if (query.isEmpty()) {
            background({
                val addons = Stremio.savedAddons(authKey.orEmpty()).orEmpty().ifEmpty { Stremio.addons(authKey.orEmpty()) }
                val catalogs = addons.flatMap { it.catalogs }
                    .filter { it.type == "movie" || it.type == "series" }
                // Popular or trending first, then any other; one that fails or comes back empty gives way to the next.
                catalogs.sortedByDescending { it.name.contains("popular", true) || it.name.contains("trending", true) }
                    .take(4).asSequence()
                    .map { c -> runCatching { c.addon.savedCatalog(c)?.takeIf { it.isNotEmpty() } ?: c.addon.catalog(c) }.getOrDefault(emptyList())
                        .filterNot { isAddonMessage(it.name) }.take(PER_TYPE) }
                    .firstOrNull { it.isNotEmpty() }.orEmpty()
            }) { answer ->
                if (token != request) return@background
                val titles = answer.getOrNull().orEmpty()
                if (titles.isEmpty()) {
                    results.removeAllViews()
                    results.addView(failed("Popular titles are unavailable.") { search(false) })
                } else render(linkedMapOf("Popular" to titles), "", false)
            }
            return
        }
        background({ Stremio.savedAddons(authKey.orEmpty()) ?: Stremio.addons(authKey.orEmpty()) }) { loaded ->
            if (token != request) return@background
            val addons = loaded.getOrElse {
                results.removeAllViews()
                results.addView(failed("Search is unavailable right now.") { search(false) })
                return@background
            }
            val answers = arrayOfNulls<List<Meta>>(addons.size)
            var completed = 0
            var failedCount = 0
            var finished = false
            fun update(final: Boolean = false) {
                if (token != request || finished) return
                val found = searchAnswersInAddonOrder(answers)
                    .filter { it.type == "movie" || it.type == "series" }
                    .filterNot { isAddonMessage(it.name) }
                    .distinctBy { it.type to it.id }
                val ranked = rankSearchTitles(found, query) { it.name }
                val sections = linkedMapOf("Movies" to ranked.filter { it.type == "movie" }.take(PER_TYPE),
                    "Shows" to ranked.filter { it.type == "series" }.take(PER_TYPE)).filterValues { it.isNotEmpty() }
                if (sections.isNotEmpty()) render(sections, query, moveFocus && (final || completed == addons.size))
                else if (final || completed == addons.size) {
                    results.removeAllViews()
                    results.addView(if (failedCount == completed && (completed > 0 || final))
                        failed("Search is unavailable right now.") { search(false) }
                    else text("No matches · Try a shorter title", BODY, color = TEXT_2))
                    resultCount.text = ""
                }
            }
            if (addons.isEmpty()) { update(true); return@background }
            main.postDelayed({ if (token == request) { update(true); finished = true } }, SEARCH_WAIT)
            addons.forEachIndexed { index, addon ->
                searchTasks += searchPool.submit {
                    val answer = runCatching { addon.search(query) }
                    main.post {
                        if (isDestroyed || token != request || finished) return@post
                        completed++
                        answer.fold({ answers[index] = it }) { failedCount++ }
                        update()
                        if (completed == addons.size) finished = true
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        searchTasks.forEach { it.cancel(true) }
        super.onDestroy()
    }

    private fun render(sections: Map<String, List<Meta>>, query: String, moveFocus: Boolean) {
        val selected = (currentFocus?.tag as? String)?.takeIf { resultsScroll.hasFocus() }
        cards.clear()
        topRowCards.clear()
        firstResult = null
        results.removeAllViews()
        val total = sections.values.sumOf { it.size }
        resultCount.text = if (total == 0 || query.isEmpty()) "" else if (total == 1) "1 title" else "$total titles" // not for Popular
        if (total == 0) {
            results.addView(text("No matches · Try a shorter title", BODY, color = TEXT_2))
            return
        }
        sections.forEach { (label, list) ->
            val group = column(Gravity.START)
            group.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(caps(label).onArt())
                addView(text(list.size.toString(), SMALL, color = TEXT_3).onArt(),
                    LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(9) })
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
            val caption = caption("").apply {
                visibility = View.INVISIBLE
                maxWidth = dp(280)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.MIDDLE
            }
            val row = LinearLayout(this).apply {
                clipChildren = false
                clipToPadding = false
                setPadding(dp(6), dp(8), dp(48), dp(8)) // the last card rests at the screen's margin; 8 dp above and below leave room for the focused card to grow
            }
            // Slides like Home's rows (below, on focus) instead of scrolling just far enough, which left cards cut at both edges.
            val scroller = object : HorizontalScrollView(this) {
                override fun computeScrollDeltaToGetChildRectOnScreen(rect: android.graphics.Rect) = 0

                // At either end the remote stays put, as on Home, instead of jumping to the row below.
                override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
                    val key = event.keyCode
                    if (key == android.view.KeyEvent.KEYCODE_DPAD_RIGHT && row.getChildAt(row.childCount - 1)?.isFocused == true) return true
                    return super.dispatchKeyEvent(event)
                }
            }.apply {
                isHorizontalScrollBarEnabled = false
                addView(row)
            }
            val topRow = results.childCount == 0
            list.forEachIndexed { index, meta ->
                row.addView(card(meta, caption, scroller).also { if (topRow) topRowCards += it }, LinearLayout.LayoutParams(dp(88), dp(132)).apply {
                    if (index > 0) marginStart = dp(14)
                })
            }
            group.addView(scroller, LinearLayout.LayoutParams(-1, dp(148)))
            group.addView(caption, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
            results.addView(group, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(28) })
        }
        sections.values.firstOrNull()?.firstOrNull()?.let(::show)
        if (selected != null) (cards.firstOrNull { it.first.tag == selected }?.first ?: firstResult)?.requestFocus()
        else if (moveFocus) firstResult?.requestFocus()
    }

    private fun card(meta: Meta, caption: TextView, scroller: HorizontalScrollView): View {
        val image = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(TILE) }
        val shade = View(this).apply { setBackgroundColor(Color.BLACK); alpha = 0.5f }
        val card = FrameLayout(this).apply {
            tag = "${meta.type}:${meta.id}"
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) =
                    outline.setRect(0, 0, view.width, view.height)
            }
            clipToOutline = true
            addView(image, FrameLayout.LayoutParams(-1, -1))
            addView(shade, FrameLayout.LayoutParams(-1, -1))
            cardFocus(6) { has ->
                // grow() unclamps ancestors; Search must keep each row inside its result pane.
                scroller.clipChildren = true
                scroller.clipToPadding = true
                resultsScroll.clipChildren = true
                resultsScroll.clipToPadding = true
                shade.animate().alpha(if (has) 0f else 0.5f).setDuration(Motion.QUICK).setInterpolator(Motion.EASE).start()
                if (has) {
                    // The focused row snaps to the pane's top, as on Home: scrolling just far enough left Shows cut off.
                    // Rows above it draw nothing: the pane didn't clip them, and they sat over the title's words.
                    (scroller.parent as? View)?.let { group ->
                        resultsScroll.smoothScrollTo(0, group.top)
                        for (i in 0 until results.childCount) results.getChildAt(i).let { it.alpha = if (it.top < group.top) 0f else 1f }
                    }
                    caption.text = meta.name + (meta.year?.let { "  ·  " + it.take(4) } ?: "")
                    caption.visibility = View.VISIBLE
                    // The focused card goes to the row's start; near the end the row stops with the last card at the margin.
                    // Cards that slid past the start hide, so no sliver shows at the pane's edge.
                    val row = parent as ViewGroup
                    val target = (left - row.paddingStart).coerceIn(0, (row.width - scroller.width).coerceAtLeast(0))
                    scroller.smoothScrollTo(target, 0)
                    for (i in 0 until row.childCount) row.getChildAt(i).let { it.animate().alpha(if (it.left < target + row.paddingStart - 1) 0f else 1f).setDuration(Motion.QUICK).setInterpolator(Motion.EASE).start() }
                    caption.post {
                        caption.translationX = (left - target).toFloat()
                            .coerceIn(0f, (scroller.width - dp(48) - caption.width).coerceAtLeast(0).toFloat())
                    }
                    show(meta)
                } else caption.visibility = View.INVISIBLE
            }
            setOnClickListener {
                val query = input.text.toString().trim()
                if (query.isNotEmpty()) remember(query)
                startActivity(Intent(this@SearchActivity, DetailsActivity::class.java)
                    .putExtra("type", meta.type).putExtra("id", meta.id).putExtra("name", meta.name)
                    .putExtra("poster", meta.poster).putExtra("background", meta.background))
            }
        }
        cards += card to shade
        if (firstResult == null) firstResult = card
        Images.load(meta.poster ?: meta.background, image, dp(96))
        return card
    }

    private fun show(meta: Meta) {
        if (focused?.type == meta.type && focused?.id == meta.id) return
        focused = meta
        val url = meta.background ?: meta.poster
        if (url != null && url != art.tag) {
            art.animate().cancel()
            if (art.drawable != null) artBack.setImageDrawable(art.drawable)
            art.alpha = 0f
            Images.load(url, art, resources.displayMetrics.widthPixels, urgent = true, failed = {
                if (focused === meta) {
                    art.setImageDrawable(artBack.drawable)
                    art.alpha = 1f
                    refrost()
                }
            }) {
                if (focused === meta) { art.animate().alpha(1f).setDuration(Motion.STANDARD).setInterpolator(Motion.EASE).start(); refrost() }
            }
        } else if (url == null) {
            art.tag = null
            art.setImageDrawable(null)
            artBack.setImageDrawable(null)
            refrost()
        }
    }

    companion object {
        /** Opens listening straight away, for the remote's Search button. */
        const val LISTEN = "listen"
        private const val ASSISTANT_SEARCH = "com.google.android.gms.actions.SEARCH_ACTION"
        private const val RECENT = 6
        private const val QUERY_SP = 28f
        private const val QUERY_W = 420 // dp: the query's line
        private const val KEY_SP = 17f
        private const val KEY_PAD = 9 // dp either side of a key's letter
        private const val KEY_H = 36 // dp: the alphabet's line
        private const val RESULTS_TOP = 206 // dp
        private const val PER_TYPE = 12
        private const val TYPING_PAUSE = 500L
        private const val SEARCH_WAIT = 6000L
        private const val SPEECH = 31
        private val searchPool = Images.newestFirst(8)
    }
}
