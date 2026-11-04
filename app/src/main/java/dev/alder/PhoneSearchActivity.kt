package dev.alder

import android.app.SearchManager
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.speech.RecognizerIntent
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Future

/**
 * Search on a phone, held upright: a big field with the phone's own keyboard and a mic, All / Shows / Movies, and the
 * results as a grid of posters. Before anything is typed it shows recent searches and what's popular.
 */
class PhoneSearchActivity : PhoneScreen() {
    private enum class Filter(val label: String) { ALL("All"), SHOWS("Shows"), MOVIES("Movies") }

    private lateinit var input: EditText
    private lateinit var results: LinearLayout
    private lateinit var scroll: ScrollView
    private val filters = mutableMapOf<Filter, TextView>()
    private var filter = Filter.ALL
    private var found: List<Meta> = emptyList()
    private var heading = ""
    private var request = 0
    private val tasks = mutableListOf<Future<*>>()
    private val typed = Runnable { search() }
    private val screenW by lazy { resources.displayMetrics.widthPixels }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (authKey == null) return finish()
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        input = EditText(this).apply {
            hint = "Shows and movies"
            setHintTextColor(TEXT_3)
            setTextColor(TEXT)
            textSize = 17f
            typeface = text("", 17f, bold = true).typeface
            background = null
            setPadding(0, 0, 0, 0)
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
            setOnEditorActionListener { _, _, _ -> main.removeCallbacks(typed); search(); hideKeyboard(); true }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    main.removeCallbacks(typed)
                    main.postDelayed(typed, TYPING_PAUSE)
                }
            })
        }
        val field = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(4), 0)
            background = android.graphics.drawable.LayerDrawable(arrayOf(
                rounded(withAlpha(TEXT, 0x14), CORNER),
                GradientDrawable().apply { setColor(TUNGSTEN) },
            )).apply { setLayerGravity(1, Gravity.BOTTOM or Gravity.FILL_HORIZONTAL); setLayerHeight(1, dp(2)) }
            addView(ImageView(context).apply { setImageResource(R.drawable.ic_search); setColorFilter(TEXT_2) }, LinearLayout.LayoutParams(dp(20), dp(20)))
            addView(input, LinearLayout.LayoutParams(0, -1, 1f).apply { leftMargin = dp(10) })
            addView(iconButton(R.drawable.ic_close, "Clear") { input.setText(""); showKeyboard() }, LinearLayout.LayoutParams(dp(40), dp(40)))
            if (Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).resolveActivity(packageManager) != null)
                addView(iconButton(R.drawable.ic_mic, "Voice search") { listen() }, LinearLayout.LayoutParams(dp(40), dp(40)))
        }
        val top = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(10), dp(16), dp(6))
            addView(iconButton(R.drawable.ic_back, "Back") { finish() }, LinearLayout.LayoutParams(dp(TOUCH), dp(TOUCH)))
            addView(field, LinearLayout.LayoutParams(0, dp(TOUCH), 1f))
        }
        val tabs = LinearLayout(this).apply { setPadding(dp(14), 0, dp(14), 0) }
        Filter.entries.forEach { f ->
            filters[f] = text(f.label, BODY_SP, bold = true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(8), 0, dp(8), 0)
                setOnClickListener { filter = f; styleFilters(); draw() }
            }.also { tabs.addView(it, LinearLayout.LayoutParams(-2, dp(44)).apply { rightMargin = dp(12) }) }
        }
        styleFilters()
        results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(12), dp(20), dp(24)) }
        scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            isFocusable = false
            addView(results)
            // A finger on the results puts the keyboard away.
            setOnScrollChangeListener { _, _, y, _, oldY -> if (kotlin.math.abs(y - oldY) > dp(4)) hideKeyboard() }
        }
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(BASE))
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(top, LinearLayout.LayoutParams(-1, -2))
            addView(tabs, LinearLayout.LayoutParams(-1, -2))
            addView(View(context).apply { setBackgroundColor(GLASS_EDGE) }, LinearLayout.LayoutParams(-1, dp(1)))
            addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        })
        if (!take(intent)) { search(); input.requestFocus(); main.postDelayed(::showKeyboard, 200) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        take(intent)
    }

    /** A query from the Assistant or the phone's search, or the mic straight away; false when there's neither. */
    private fun take(intent: Intent): Boolean {
        val query = intent.getStringExtra(SearchManager.QUERY)?.trim()
        if (!query.isNullOrEmpty()) { heard(query); return true }
        if (intent.getBooleanExtra(SearchActivity.LISTEN, false)) { search(); listen(); return true }
        return false
    }

    private fun styleFilters() = filters.forEach { (f, tab) ->
        tab.setTextColor(if (f == filter) TEXT else TEXT_2)
        tab.background = if (f != filter) null else android.graphics.drawable.LayerDrawable(arrayOf(GradientDrawable().apply { setColor(TUNGSTEN) })).apply {
            setLayerGravity(0, Gravity.BOTTOM or Gravity.FILL_HORIZONTAL); setLayerHeight(0, dp(3))
        }
    }

    private fun showKeyboard() {
        input.requestFocus()
        getSystemService(InputMethodManager::class.java)?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(input.windowToken, 0)
    }

    private fun listen() {
        runCatching {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Say a movie or show")
            }, SPEECH)
        }
    }

    @Deprecated("Activity API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != SPEECH || resultCode != RESULT_OK) return
        heard(data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: return)
    }

    private fun heard(words: String) {
        input.setText(words)
        input.setSelection(input.length())
        main.removeCallbacks(typed)
        search()
        hideKeyboard()
    }

    // ---------- searching ----------

    private fun search() {
        val query = input.text.toString().trim()
        val token = ++request
        tasks.forEach { it.cancel(true) }
        tasks.clear()
        val key = authKey.orEmpty()
        if (query.isEmpty()) {
            background({
                val addons = Stremio.savedAddons(key).orEmpty().ifEmpty { Stremio.addons(key) }
                addons.flatMap { it.catalogs }.filter { it.type == "movie" || it.type == "series" }
                    .sortedByDescending { it.name.contains("popular", true) || it.name.contains("trending", true) }
                    .take(4).flatMap { c ->
                        runCatching { c.addon.savedCatalog(c)?.takeIf { it.isNotEmpty() } ?: c.addon.catalog(c) }.getOrDefault(emptyList())
                            .filterNot { isAddonMessage(it.name) }.take(PER_TYPE)
                    }.distinctBy { it.id }
            }) { r ->
                if (token != request) return@background
                found = r.getOrNull().orEmpty()
                heading = "Popular"
                draw()
            }
            return
        }
        remember(query)
        results.removeAllViews()
        results.addView(ring(), LinearLayout.LayoutParams(dp(28), dp(28)))
        background({ Stremio.savedAddons(key) ?: Stremio.addons(key) }) { loaded ->
            if (token != request) return@background
            val addons = loaded.getOrNull() ?: return@background failed()
            val answers = arrayOfNulls<List<Meta>>(addons.size)
            var done = 0
            fun update() {
                if (token != request) return
                // Titles with no picture are mostly noise; among equal matches, newer ones lead (Dexter: Resurrection
                // before a 1990s cartoon).
                found = rankSearchTitles(searchAnswersInAddonOrder(answers)
                    .filter { (it.type == "movie" || it.type == "series") && it.poster != null }.filterNot { isAddonMessage(it.name) }
                    .distinctBy { it.type to it.id }
                    .sortedByDescending { it.year?.take(4)?.toIntOrNull() ?: 0 }, query) { it.name }
                heading = ""
                if (found.isNotEmpty() || done == addons.size) draw()
            }
            if (addons.isEmpty()) return@background update()
            addons.forEachIndexed { i, addon ->
                tasks += pool.submit {
                    val answer = runCatching { addon.search(query) }.getOrNull()
                    main.post {
                        if (isDestroyed || token != request) return@post
                        done++
                        answers[i] = answer
                        update()
                    }
                }
            }
        }
    }

    private fun failed() {
        results.removeAllViews()
        results.addView(text("Search is unavailable right now. Check the phone's connection.", BODY_SP, color = TEXT_2))
    }

    private fun draw() {
        results.removeAllViews()
        if (input.text.isBlank()) recentChips()
        val shown = found.filter { filter == Filter.ALL || it.type == if (filter == Filter.MOVIES) "movie" else "series" }
        if (heading.isNotEmpty() && shown.isNotEmpty()) results.addView(text(heading, 17f, serif = true), LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(10) })
        if (shown.isEmpty()) {
            if (input.text.isNotBlank()) results.addView(text("No matches. Try a shorter title.", BODY_SP, color = TEXT_2))
            return
        }
        val gap = dp(10)
        val w = (screenW - dp(40) - 2 * gap) / 3
        shown.chunked(3).forEach { three ->
            val line = LinearLayout(this)
            three.forEachIndexed { i, m ->
                line.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(scored(ImageView(context).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        background = rounded(TILE, CORNER)
                        clipToOutline = true
                        contentDescription = m.name
                        Images.load(m.poster ?: m.background, this, w)
                    }, m.id, m.type, m.rating), LinearLayout.LayoutParams(w, w * 3 / 2))
                    addView(text(m.name, SMALL_SP, bold = true).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END },
                        LinearLayout.LayoutParams(w, -2).apply { topMargin = dp(6) })
                    setOnClickListener { open(m) }
                }, LinearLayout.LayoutParams(w, -2).apply { if (i > 0) leftMargin = gap })
            }
            results.addView(line, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
        }
    }

    private fun open(m: Meta) {
        hideKeyboard()
        startActivity(Intent(this, PhoneDetailsActivity::class.java)
            .putExtra("type", m.type).putExtra("id", m.id).putExtra("name", m.name)
            .putExtra("poster", m.poster).putExtra("background", m.background))
    }

    // ---------- recent searches ----------

    private fun recentKey() = "recent:${Profiles.active(this)?.id.orEmpty()}"
    private fun recent() = getSharedPreferences("search", MODE_PRIVATE).getString(recentKey(), "").orEmpty().split("\n").filter { it.isNotBlank() }

    private fun remember(query: String) {
        if (query.length < 3) return
        val values = (listOf(query) + recent().filterNot { it.equals(query, true) || query.startsWith(it, true) }).take(RECENT)
        getSharedPreferences("search", MODE_PRIVATE).edit().putString(recentKey(), values.joinToString("\n")).apply()
    }

    private fun recentChips() {
        val values = recent()
        if (values.isEmpty()) return
        results.addView(text("Recent", 17f, serif = true), LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(8) })
        values.forEach { q ->
            results.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(ImageView(context).apply { setImageResource(R.drawable.ic_search); setColorFilter(TEXT_3) }, LinearLayout.LayoutParams(dp(18), dp(18)))
                addView(text(q, BODY_SP), LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(14) })
                setOnClickListener { heard(q) }
                setOnLongClickListener {
                    getSharedPreferences("search", MODE_PRIVATE).edit()
                        .putString(recentKey(), recent().filterNot { it == q }.joinToString("\n")).apply()
                    draw(); true
                }
            }, LinearLayout.LayoutParams(-1, dp(TOUCH)))
        }
        results.addView(View(this), LinearLayout.LayoutParams(1, dp(16)))
    }

    override fun onDestroy() {
        tasks.forEach { it.cancel(true) }
        super.onDestroy()
    }

    private companion object {
        const val TYPING_PAUSE = 500L
        const val PER_TYPE = 12
        const val RECENT = 5
        const val SPEECH = 32
        val pool = Images.newestFirst(8)
    }
}
