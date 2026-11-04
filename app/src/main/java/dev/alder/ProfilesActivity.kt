package dev.alder

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView

// Profile picker, Stremio sign-in, and setting up Watchly.
// The app starts here; with a single profile it goes straight on to Home.
class ProfilesActivity : Screen() {
    override val backArrow: Boolean get() = false // its screens have their own Back where there's somewhere to go
    // On a phone, held upright and drawn at the phone's own size, with plain taps: the same steps, stacked.
    override val phoneLayout: Boolean get() = touch
    override fun phoneLayoutIn(base: android.content.Context) = Device.touch(base)
    private val upright get() = phoneLayout
    override val screensaver: Boolean get() = false // it would show a profile's titles before its PIN
    // The sign-in code being waited on; anything else stops its polling loop.
    private var waitingCode: String? = null
    private var signingIn = false
    private var draftName = ""
    private var draftColor = Profiles.COLORS[0]
    private var pickerGeneration = 0
    private var signInGeneration = 0
    private var nameInput: EditText? = null
    // Where Back goes from the profile menu's screens (PIN, rename); null elsewhere.
    private var backTo: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        requestedOrientation = if (Device.touch(this)) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        super.onCreate(savedInstanceState)
        val profiles = Profiles.all(this)
        // Home found this profile signed out: straight to its sign-in, the profile and its settings kept.
        val again = intent.getStringExtra("signIn")?.let { id -> profiles.firstOrNull { it.id == id } }
        when {
            again != null -> showSignIn(again.name, again)
            profiles.isEmpty() -> showName()
            profiles.size == 1 && !intent.getBooleanExtra("pick", false) -> enter(profiles[0])
            else -> showPicker()
        }
    }

    override fun onDestroy() {
        waitingCode = null
        super.onDestroy()
    }

    @Deprecated("Android back callback")
    override fun onBackPressed() {
        backTo?.let { return it() }
        if (signingIn) showName() else super.onBackPressed()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val input = nameInput
        if (input != null && !input.hasFocus() && event.action == KeyEvent.ACTION_DOWN) {
            if (event.keyCode == KeyEvent.KEYCODE_DEL) {
                if (input.length() > 0) input.text.delete(input.length() - 1, input.length())
                return true
            }
            val character = event.unicodeChar.takeIf { it in 32..126 }?.toChar()
            if (character != null) { input.text.append(character); return true }
        }
        return super.dispatchKeyEvent(event)
    }

    /** Opens a profile, asking for its PIN first when it has one; a signed-out one signs in again first. */
    private fun enter(p: Profile) = withPin(p) { if (Profiles.signedOut(this, p.id)) showSignIn(p.name, p) else openHome(p) }

    private fun openHome(profile: Profile) {
        Profiles.setActive(this, profile.id)
        val home = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        // Opened from the Google TV home's Continue Watching: Home, with that title's page on top of it.
        // A fresh task, so an app already open in the background doesn't end up with two Homes.
        if (intent.hasExtra("id")) startActivities(arrayOf(home,
            Intent(intent).setClass(this, DetailsActivity::class.java).setFlags(0)))
        else startActivity(home)
        finish()
    }

    /**
     * Puts [content] on screen over [backdrop]. On a phone every control answers a single tap: the TV's controls take
     * focus on the first tap and only act on the second.
     */
    private fun show(content: View, backdrop: View) {
        setWindowed(content, backdrop)
        if (!upright) return
        fun plain(v: View) {
            if (v !is EditText) v.isFocusableInTouchMode = false
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) plain(v.getChildAt(i))
        }
        plain(content)
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
            android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN)
    }

    // ---------- who's watching ----------

    private fun showPicker() {
        // Opened from inside a profile (Switch profile, Manage profiles): Back returns to it instead of leaving Canopy.
        val current = Profiles.active(this)?.takeIf { intent.getBooleanExtra("pick", false) }
        backTo = current?.let { { openHome(it) } }
        nameInput = null
        val generation = ++pickerGeneration
        val profiles = Profiles.all(this)
        val art = onboardingBackdrop(false).apply { alpha = 0.42f }
        var selected = profiles.firstOrNull { it.id == Profiles.active(this)?.id } ?: profiles.first()
        // Top-aligned, so Add (no "last watched" line) sits level with the portraits; padding above for the 10% lift.
        val tiles = LinearLayout(this).apply {
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP; clipChildren = false; clipToPadding = false
            setPadding(0, dp(LIFT_ROOM), 0, 0)
        }
        fun showArt(p: Profile, item: org.json.JSONObject?) {
            if (generation != pickerGeneration || selected.id != p.id) return
            val url = item?.optString("_id")?.let(Images::backgroundFor) ?: return
            Images.load(url, art, resources.displayMetrics.widthPixels, urgent = true) {
                val bitmap = (art.drawable as? BitmapDrawable)?.bitmap ?: return@load
                background({ Images.frost(bitmap, 160) }) { result ->
                    if (generation == pickerGeneration && selected.id == p.id && art.tag == url)
                        result.getOrNull()?.let { art.setImageBitmap(it); art.alpha = 0.5f }
                }
            }
        }
        val recent = mutableMapOf<String, org.json.JSONObject?>()
        fun focus(p: Profile) {
            selected = p
            if (p.id !in recent) { art.tag = null; art.setImageResource(R.drawable.onboarding_scene); art.alpha = 0.42f }
            else showArt(p, recent[p.id])
        }
        profiles.forEach { p ->
            val signedOut = Profiles.signedOut(this, p.id).takeIf { it }
                ?.let { text("Sign in again", SMALL, color = TUNGSTEN).apply { gravity = Gravity.CENTER } }
            tiles.addView(tile(p, signedOut) { focus(p) }.apply {
                setOnClickListener { enter(p) }
                setOnLongClickListener { showMenu(p); true }
            }, LinearLayout.LayoutParams(dp(124), -2).apply { marginStart = dp(10); marginEnd = dp(10) })
            background({
                val items = Stremio.savedLibrary(p.authKey) ?: Stremio.library(p.authKey)
                Library.continueWatching(items).firstOrNull()
                    ?: items.filter { !it.optJSONObject("state")?.optString("lastWatched").isNullOrBlank() }
                        .maxByOrNull { it.optJSONObject("state")?.optString("lastWatched").orEmpty() }
            }) { result ->
                if (generation != pickerGeneration) return@background
                val item = result.getOrNull()
                recent[p.id] = item
                showArt(p, item)
            }
        }
        if (profiles.size < Profiles.MAX) {
            tiles.addView(tile(null, null) {}.apply { setOnClickListener { showName() } },
                LinearLayout.LayoutParams(dp(124), -2).apply { marginStart = dp(10); marginEnd = dp(10) })
        }
        val tileViews = (0 until tiles.childCount).map { tiles.getChildAt(it) }
        val manage = chip("Manage profiles").apply {
            setOnClickListener { choose("Manage profiles", profiles.map { it.name }) { i -> showMenu(profiles[i]) } }
        }
        if (upright) {
            // Two portraits to a row, under the question, with Manage profiles at the foot.
            tiles.removeAllViews()
            val grid = column(Gravity.CENTER_HORIZONTAL)
            tileViews.chunked(2).forEach { pair ->
                grid.addView(LinearLayout(this).apply {
                    gravity = Gravity.CENTER_HORIZONTAL; clipChildren = false
                    pair.forEach { addView(it, LinearLayout.LayoutParams(dp(124), -2).apply { marginStart = dp(12); marginEnd = dp(12) }) }
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })
            }
            val body = column(Gravity.CENTER_HORIZONTAL,
                onboardingHeader(""),
                text("Who's here tonight?", 30f, serif = true).onArt().apply { gravity = Gravity.CENTER }.marginTop(36),
                grid.marginTop(8),
                manage.apply { layoutParams = LinearLayout.LayoutParams(-2, dp(44)).apply { topMargin = dp(36) } },
                text("Press and hold a portrait to rename it, set a PIN, change its colour or remove it.", SMALL, color = TEXT_2)
                    .onArt().apply { gravity = Gravity.CENTER }.marginTop(12),
            ).apply { setPadding(dp(20), 0, dp(20), dp(32)); clipChildren = false }
            show(android.widget.ScrollView(this).apply { isFillViewport = true; addView(body) }, art)
            return
        }
        val content = FrameLayout(this).apply {
            addView(brand(), FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
                leftMargin = dp(PAD); topMargin = dp(31)
            })
            addView(TextClock(this@ProfilesActivity).apply {
                format12Hour = "h:mm a"; format24Hour = "HH:mm"; textSize = SMALL; setTextColor(TEXT_2)
            }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
                rightMargin = dp(PAD); topMargin = dp(39)
            })
            addView(text("Who's here tonight?", 38f, serif = true).onArt().apply { gravity = Gravity.CENTER },
                FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { topMargin = dp(118) })
            addView(tiles, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { topMargin = dp(190 - LIFT_ROOM) })
            addView(manage, FrameLayout.LayoutParams(-2, dp(30), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(50) })
            addView(keyHint("Hold OK", "Rename · PIN · Colour · Remove"),
                FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(20) })
        }
        show(content, art)
        tileViews[profiles.indexOf(selected)].requestFocus()
    }

    private fun tile(profile: Profile?, last: TextView?, focused: () -> Unit): View {
        val add = profile == null
        val face = text(if (add) "+" else profile?.name?.take(1)?.uppercase().orEmpty(), if (add) 42f else 48f,
            serif = !add, color = if (add) TEXT_2 else BASE).apply {
            gravity = Gravity.CENTER
            alpha = 0.6f
            background = GradientDrawable().apply {
                cornerRadius = dp(minOf(PORTRAIT_R, CORNER)).toFloat(); setColor(profile?.color ?: GLASS)
                if (add) setStroke(dp(1), GLASS_EDGE, dp(2).toFloat(), dp(3).toFloat())
            }
        }
        val circle = FrameLayout(this).apply {
            clipChildren = false
            addView(face, FrameLayout.LayoutParams(dp(100), dp(100), Gravity.CENTER))
        }
        // No ring: like the top bar, a line of light under the name marks the remote's portrait.
        val dot = View(this).apply {
            background = GradientDrawable().apply { setColor(TEXT) }
            if (android.os.Build.VERSION.SDK_INT >= 28) { outlineSpotShadowColor = 0xFFFFFFFF.toInt(); elevation = dp(4).toFloat() }
            visibility = View.INVISIBLE
        }
        val name = text(if (add) "Add" else profile?.name.orEmpty().replaceFirstChar { it.titlecase() } + if (profile?.pin != null) "  🔒" else "",
            BODY, color = TEXT_2).apply { gravity = Gravity.CENTER; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        return column(Gravity.CENTER_HORIZONTAL).apply {
            addView(circle, LinearLayout.LayoutParams(dp(112), dp(112)))
            addView(name, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
            if (last != null) addView(last, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })
            addView(dot, LinearLayout.LayoutParams(dp(28), dp(3)).apply { topMargin = dp(8); gravity = Gravity.CENTER_HORIZONTAL })
            isFocusable = true; isFocusableInTouchMode = true
            clipChildren = false
            setOnFocusChangeListener { _, has ->
                dot.visibility = if (has) View.VISIBLE else View.INVISIBLE
                circle.grow(has, 1.1f).start()
                face.alpha = if (has) 1f else 0.6f
                name.setTextColor(if (has) TEXT else TEXT_2)
                if (has) focused()
            }
        }
    }

    // ---------- edit a profile ----------

    private fun showMenu(p: Profile) {
        val items = listOfNotNull(
            "Rename" to { withPin(p) { rename(p) } },
            (if (p.pin == null) "Set a PIN" else "Change PIN") to { withPin(p) { setPin(p) } },
            "Colour" to { withPin(p) {
                choose("${p.name}'s portrait", listOf("Tungsten", "Clay", "Sage", "Lavender", "Honey"),
                    checked = Profiles.COLORS.indexOf(p.color)) { i ->
                    Profiles.update(this, p.id, color = Profiles.COLORS[i]); showPicker()
                }
            } },
            if (p.pin == null) null else "Remove PIN" to {
                withPin(p) { Profiles.update(this, p.id, clearPin = true); showPicker() }
            },
            "Remove profile" to { withPin(p) { showPicker(); confirmRemove(p) } },
        )
        choose(p.name, items.map { it.first }) { i -> items[i].second() }
    }

    /** Runs [then] straight away, or once the profile's PIN is typed, so a locked profile can't be changed around it. */
    private fun withPin(p: Profile, then: () -> Unit) {
        val pin = p.pin ?: return then()
        askPin("Enter ${p.name}'s PIN", ::showPicker) { (it == pin).also { ok -> if (ok) then() } }
    }

    private fun setPin(p: Profile) {
        askPin("Choose a PIN for ${p.name}", ::showPicker) { first ->
            askPin("Enter it again", ::showPicker, "PINs don't match. Try again.") { second ->
                (second == first).also { same ->
                    if (same) { Profiles.update(this, p.id, pin = first); showPicker() }
                }
            }
            true
        }
    }

    private fun rename(p: Profile) {
        ++pickerGeneration
        backTo = ::showPicker
        val previewName = text(p.name, BODY, bold = true).apply { gravity = Gravity.CENTER }
        val avatar = onboardingAvatar(p.name.firstOrNull()?.uppercase().orEmpty(), 100, p.color)
        val error = text("", SMALL, color = TUNGSTEN)
        val entry = nameEntry(p.name) { typed ->
            previewName.text = typed
            avatar.text = typed.firstOrNull()?.uppercase().orEmpty()
            error.text = ""
        }
        val save = onboardingButton("Save")
        val go = {
            entry.input.text.toString().trim().takeIf { it.isNotEmpty() }
                ?.let { Profiles.update(this, p.id, name = it); showPicker() }
                ?: run { error.text = "Enter a name to save." }
        }
        entry.input.setOnEditorActionListener { _, _, _ -> go(); true }
        save.setOnClickListener { go() }
        val copy = column(Gravity.START,
            text("Rename ${p.name}", SERIF_TITLE, serif = true),
            text("History and add-ons stay as they are.", BODY, color = TEXT_2).marginTop(4),
            entry.field.apply { layoutParams = LinearLayout.LayoutParams(fieldW, -2).apply { topMargin = dp(22) } },
            entry.keyboard.apply { layoutParams = LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(14) } },
            error.marginTop(6),
            save.marginTop(10),
        ).apply { gravity = Gravity.CENTER_VERTICAL }
        val preview = column(Gravity.CENTER, avatar, previewName.marginTop(16)).apply { gravity = Gravity.CENTER }
        show(onboardingFrame("", copy, preview, dp(40), 1.1f, 0.9f), onboardingBackdrop())
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        entry.firstKey.requestFocus()
    }

    /**
     * Four digit boxes for a TV remote: Up/Down turn the focused digit, Left/Right move between boxes, number keys
     * type and move on. OK (or the fourth number key) hands the PIN to [check]; false shakes the boxes and clears them.
     */
    private fun askPin(title: String, back: () -> Unit, errorMessage: String = "Incorrect PIN. Try again.", check: (String) -> Boolean) {
        backTo = back
        val digits = IntArray(4) { -1 }
        var at = 0
        val boxes = List(4) {
            text("", 34f, bold = true).apply {
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dp(64), dp(76)).apply { marginStart = dp(8); marginEnd = dp(8) }
            }
        }
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER; isFocusable = true; isFocusableInTouchMode = true; boxes.forEach { addView(it) } }
        val error = text("", BODY, color = TEXT)
        fun draw() = boxes.forEachIndexed { i, b ->
            b.text = when {
                digits[i] < 0 -> ""
                i == at -> "${digits[i]}"
                else -> "\u25AA" // a small square, not a round dot
            }
            b.background = glass(14, i == at)
        }
        fun submit() {
            if (digits.any { it < 0 }) return
            if (check(digits.joinToString("") { "$it" })) return
            digits.fill(-1); at = 0; draw()
            error.text = errorMessage
            row.animate().translationX(dp(16).toFloat()).setDuration(50).withEndAction {
                row.animate().translationX(-dp(16).toFloat()).setDuration(80).withEndAction {
                    row.animate().translationX(0f).setDuration(50).start()
                }.start()
            }.start()
        }
        row.setOnKeyListener { _, code, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            error.text = ""
            when (code) {
                KeyEvent.KEYCODE_DPAD_UP -> digits[at] = (digits[at] + 1).mod(10)
                KeyEvent.KEYCODE_DPAD_DOWN -> digits[at] = if (digits[at] <= 0) 9 else digits[at] - 1
                KeyEvent.KEYCODE_DPAD_LEFT -> at = maxOf(0, at - 1)
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (digits[at] >= 0) at = minOf(3, at + 1)
                in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
                    digits[at] = code - KeyEvent.KEYCODE_0
                    if (at < 3) at++ else { draw(); submit(); return@setOnKeyListener true }
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { submit(); return@setOnKeyListener true }
                else -> return@setOnKeyListener false
            }
            draw()
            true
        }
        draw()
        // A phone has no number keys or arrows: a row of digits to tap, and ⌫.
        fun tapped(digit: Int) {
            error.text = ""
            if (digit < 0) {
                if (digits[at] < 0 && at > 0) at--
                digits[at] = -1
            } else {
                digits[at] = digit
                if (at < 3) at++ else { draw(); submit(); return }
            }
            draw()
        }
        // A phone's number pad: 1 to 9 in threes, then ⌫ and 0.
        val keypad = column(Gravity.CENTER_HORIZONTAL)
        listOf(listOf(1, 2, 3), listOf(4, 5, 6), listOf(7, 8, 9), listOf(-1, 0)).forEach { line ->
            keypad.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER
                line.forEach { d ->
                    addView(text(if (d < 0) "⌫" else "$d", 24f).apply {
                        gravity = Gravity.CENTER
                        background = glass(CORNER)
                        contentDescription = if (d < 0) "Delete" else "$d"
                        setOnClickListener { tapped(d) }
                    }, LinearLayout.LayoutParams(dp(76), dp(56)).apply { marginStart = dp(6); marginEnd = dp(6) })
                }
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        }
        val content = column(Gravity.CENTER_HORIZONTAL,
            text(title, SERIF_TITLE, bold = true, serif = true),
            row.marginTop(36),
            error.marginTop(12),
            if (touch) keypad.marginTop(24)
            else text("▲ ▼ change a digit    ◀ ▶ move    OK to confirm", SMALL, color = TEXT_2).marginTop(28),
        ).apply { gravity = Gravity.CENTER }
        show(content, onboardingBackdrop())
        row.requestFocus()
    }

    private fun confirmRemove(p: Profile) {
        choose("Remove ${p.name}?", listOf("Cancel", "Remove"),
            "This signs ${p.name} out on this TV. Their Stremio account, history and addons stay as they are.") { i ->
            if (i == 0) return@choose
            Profiles.remove(this, p.id)
            background({ Http.forget() }) {}
            // Best effort; the TV forgets the profile either way. A sign-in another profile still uses stays signed in.
            if (Profiles.all(this).none { it.authKey == p.authKey }) background({ Stremio.logout(p.authKey) }) {}
            if (Profiles.all(this).isEmpty()) showName() else showPicker()
        }
    }

    // ---------- add a profile ----------

    private fun showName() {
        ++pickerGeneration
        backTo = if (Profiles.all(this).isEmpty()) null else ::showPicker
        signingIn = false
        waitingCode = null
        val first = Profiles.all(this).isEmpty()
        val previewName = text(draftName, NAME_SP, serif = true).onArt().apply { gravity = Gravity.CENTER; maxLines = 1 }
        val avatar = onboardingAvatar(draftName.firstOrNull()?.uppercase().orEmpty(), PREVIEW, draftColor)
        val error = text("", SMALL, color = TUNGSTEN)
        val entry = nameEntry(draftName) { typed ->
            previewName.text = typed
            avatar.text = typed.firstOrNull()?.uppercase().orEmpty()
            error.text = ""
        }
        val next = text("Continue    →", BODY, bold = true).onArt().apply {
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(6), dp(4), dp(8))
            lineKey(this)
            if (upright) minHeight = dp(48)
        }
        val go = {
            entry.input.text.toString().trim().takeIf { it.isNotEmpty() }?.replaceFirstChar { it.titlecase() }
                ?.let { draftName = it; showSignIn(it) }
                ?: run { error.text = "Enter a name to continue." }
        }
        entry.input.setOnEditorActionListener { _, _, _ -> go(); true }
        next.setOnClickListener { go() }

        // Portrait colour: each colour by its name over a bar of it. The chosen one keeps the accent line under its
        // name, as the open page does in the top menu; the remote's one gets the white line.
        val swatches = LinearLayout(this).apply { gravity = Gravity.CENTER }
        val words = mutableListOf<TextView>()
        fun paint() = words.forEachIndexed { i, word ->
            val chosen = Profiles.COLORS[i] == draftColor
            word.setTextColor(if (word.hasFocus() || chosen) TEXT else TEXT_2)
            word.background = when {
                word.hasFocus() -> lineUnder(TEXT)
                chosen -> lineUnder(TUNGSTEN)
                else -> null
            }
        }
        Profiles.COLORS.forEachIndexed { index, color ->
            val word = text(Profiles.COLOR_NAMES[index], SMALL, color = TEXT_2).onArt().apply {
                gravity = Gravity.CENTER
                isFocusable = true; isFocusableInTouchMode = true
                setPadding(0, dp(2), 0, dp(7))
                setOnFocusChangeListener { _, _ -> paint() }
                setOnClickListener {
                    draftColor = color
                    avatar.background = GradientDrawable().apply { cornerRadius = dp(CORNER).toFloat(); setColor(color) }
                    paint()
                }
            }
            words += word
            swatches.addView(column(Gravity.CENTER,
                word,
                View(this).apply { background = rounded(color, 1) }.also {
                    it.layoutParams = LinearLayout.LayoutParams(-1, dp(4)).apply { topMargin = dp(6) }
                },
            ), LinearLayout.LayoutParams(-2, -2).apply { if (index > 0) marginStart = dp(16) })
        }
        paint()

        val copy = column(Gravity.START,
            text(if (first) "Welcome to Canopy" else "Who's this for?", SERIF_TITLE, serif = true).onArt(),
            text("A name and a portrait colour. You can change both later.", BODY, color = TEXT_2).onArt().marginTop(4),
            entry.field.apply { layoutParams = LinearLayout.LayoutParams(fieldW, -2).apply { topMargin = dp(22) } },
            entry.keyboard.apply { layoutParams = LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(14) } },
            error.marginTop(6),
        ).apply { gravity = Gravity.CENTER_VERTICAL }
        val preview = column(Gravity.CENTER,
            avatar,
            previewName.marginTop(14),
            caps("Portrait colour", Theme.now.second).marginTop(24),
            swatches.marginTop(10),
            next.marginTop(28),
        ).apply { gravity = Gravity.CENTER }
        val content = onboardingFrame(if (first) "step 1 of 2" else "step 1 of 3", copy, preview, dp(40), 1.1f, 0.9f)
        show(content, onboardingBackdrop())
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        entry.firstKey.requestFocus()
    }

    /** The name's line: as wide as the letters on a TV (never wider than its column), the screen's width on a phone. */
    private val fieldW get() = if (upright) -1 else minOf(dp(FIELD_W), nameRoom)

    /**
     * The name steps' left column on a TV, in px: the screen less its margins and the 40dp gap, at the 1.1 : 0.9
     * share both name steps use. A TV with a larger display size or density has fewer dp across than the 960 the
     * letters were drawn for, so the letters shrink to this rather than run off the column's edge.
     */
    private val nameRoom get() = ((resources.displayMetrics.widthPixels - 2 * dp(PAD) - dp(40)) * 1.1f / 2f).toInt()

    /** A line under a word, Light line: white under the remote's word, the accent under a chosen one. Square ends. */
    private fun lineUnder(color: Int, inset: Int = 0) = android.graphics.drawable.LayerDrawable(arrayOf(
        GradientDrawable().apply { setColor(color) })).apply {
        setLayerGravity(0, Gravity.BOTTOM or Gravity.FILL_HORIZONTAL)
        setLayerHeight(0, dp(2))
        setLayerInsetLeft(0, dp(inset))
        setLayerInsetRight(0, dp(inset))
    }

    /** A word or letter with no tile: bright and glowing over a white line while the remote is on it. */
    private fun lineKey(view: TextView, inset: Int = 0, rest: Int = view.currentTextColor) {
        view.isFocusable = true
        view.setOnFocusChangeListener { _, has ->
            view.setTextColor(if (has) TEXT else rest)
            if (has) view.setShadowLayer(dp(10).toFloat(), 0f, 0f, 0x99FFFFFF.toInt()) else view.onArt()
            view.background = if (has) lineUnder(TEXT, inset) else null
        }
    }

    private class NameEntry(val input: EditText, val field: View, val keyboard: View, val firstKey: View)

    /**
     * The name in large type over a glowing accent line (no box), and the remote's keyboard under it: the letters in a
     * grid with no key tiles, then ⇧ (one-shot, on while the name is empty so it starts with a capital), Space and the
     * phone's keyboard as words. [changed] gets the text after every edit.
     */
    private fun nameEntry(initial: String, changed: (String) -> Unit): NameEntry {
        val name = EditText(this).apply {
            hint = "Name"
            textSize = TYPED_SP
            typeface = text("", TYPED_SP, serif = true).typeface
            setTextColor(TEXT)
            setHintTextColor(TEXT_3)
            setPadding(0, 0, dp(5), 0)
            background = null
            setSingleLine()
            showSoftInputOnFocus = touch // a phone types on its own keyboard; a TV uses the remote's below
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
            setText(initial)
            setSelection(initial.length)
            onArt()
        }
        nameInput = name
        // The field's own caret shows only while it has focus, and focus lives on the letters: an accent bar after the
        // last letter stands in for it.
        val cursor = View(this).apply { setBackgroundColor(TUNGSTEN) }
        fun placeCursor() {
            cursor.visibility = if (name.hasFocus()) View.GONE else View.VISIBLE
            cursor.translationX = name.paddingStart + name.paint.measureText(name.text.toString())
        }
        val box = android.widget.FrameLayout(this).apply {
            // At least 52dp, taller when the TV's font size is turned up, so the big letters are never clipped.
            minimumHeight = dp(52)
            addView(name, android.widget.FrameLayout.LayoutParams(-1, -2))
            addView(cursor, android.widget.FrameLayout.LayoutParams(dp(2), dp(34), Gravity.CENTER_VERTICAL))
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }
        val line = View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(TUNGSTEN, TUNGSTEN and 0x00FFFFFF))
        }
        val field = column(Gravity.START, box,
            line.also { it.layoutParams = LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(2) } })
        name.setOnFocusChangeListener { _, _ -> placeCursor() }

        val keyboard = column(Gravity.START)
        val keys = mutableListOf<TextView>()
        // A letter's cell: 42dp, or narrower so all nine of a row fit the column on a TV with fewer dp across.
        val keyW = maxOf(1, minOf(dp(KEY_W), nameRoom / COLUMNS))
        fun key(label: String, row: LinearLayout, sp: Float, width: Int) = text(label, sp, color = TEXT_2).onArt().apply {
            gravity = Gravity.CENTER
            minHeight = dp(KEY_H) // taller, not clipped, when the TV's font size is turned up
            lineKey(this, if (width == -2) 0 else minOf(KEY_INSET, (width / resources.displayMetrics.density / 3).toInt()))
            row.addView(this, LinearLayout.LayoutParams(width, -2).apply {
                if (width == -2 && row.childCount > 0) marginStart = dp(26)
            })
            keys += this
        }
        var upper = false
        val letterKeys = mutableListOf<TextView>()
        fun caps(on: Boolean) {
            upper = on
            letterKeys.forEach { it.text = if (on) it.text.toString().uppercase() else it.text.toString().lowercase() }
        }
        ("abcdefghijklmnopqrstuvwxyz").chunked(COLUMNS).forEach { letters ->
            keyboard.addView(LinearLayout(this).apply {
                letters.forEach { letter -> letterKeys += key(letter.toString(), this, KEY_SP, keyW).apply { setOnClickListener {
                    val typed = if (upper) letter.uppercaseChar() else letter
                    if (upper) caps(false)
                    name.text.append(typed)
                } } }
                // ⌫ ends the last row of letters, where the grid has room for it.
                if (letters.length < COLUMNS) key("⌫", this, KEY_SP, keyW).apply {
                    contentDescription = "Delete"
                    setOnClickListener { if (name.length() > 0) name.text.delete(name.length() - 1, name.length()) }
                }
            })
        }
        keyboard.addView(LinearLayout(this).apply {
            key("⇧  Shift", this, SMALL, -2).setOnClickListener { caps(!upper) }
            key("Space", this, SMALL, -2).setOnClickListener { name.text.append(" ") }
            key("⌨  Phone keyboard", this, SMALL, -2).setOnClickListener {
                name.showSoftInputOnFocus = true
                name.requestFocus()
                name.post { (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                    .showSoftInput(name, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT) }
            }
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })
        caps(initial.isEmpty())
        name.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (s.isNullOrEmpty()) caps(true)
                changed(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: android.text.Editable?) { placeCursor() }
        })
        name.post { placeCursor() }
        // A phone has its own keyboard: the remote's letters stay hidden, and the name box takes the first focus.
        if (touch) keyboard.visibility = View.GONE
        return NameEntry(name, field, keyboard, if (touch) name else keys.first())
    }

    /** Sign-in with a code: for a new profile called [name], or for [again], a profile Stremio signed out. */
    private fun showSignIn(name: String, again: Profile? = null) {
        ++pickerGeneration
        nameInput = null
        backTo = if (again != null) ::showPicker else ::showName
        signingIn = true
        val request = ++signInGeneration
        val qr = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        val status = text("Getting a sign-in code…", SMALL, color = TEXT_2)
        val countdown = ring(1f)
        val codeTiles = (0 until 4).map {
            text("–", 36f, bold = true).apply {
                gravity = Gravity.CENTER
                background = glass(10)
            }
        }
        val codeRow = LinearLayout(this).apply {
            codeTiles.forEach { tile -> addView(tile, LinearLayout.LayoutParams(dp(52), dp(62)).apply {
                if (childCount > 0) marginStart = dp(7)
            }) }
        }
        val back = chip("← Back")
        back.setOnClickListener { waitingCode = null; if (again != null) showPicker() else showName() }
        // On a phone the sign-in page opens right here, in Stremio or the browser; this screen waits for the approval.
        var linkUrl: String? = null
        val openLink = pill("Sign in on this phone").apply {
            visibility = if (touch) View.VISIBLE else View.GONE
            if (upright) { minHeight = dp(48); gravity = Gravity.CENTER; setPadding(dp(20), 0, dp(20), 0) }
            setOnClickListener {
                linkUrl?.let { url -> runCatching { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))) } }
            }
        }
        val left = column(Gravity.CENTER,
            FrameLayout(this).apply {
                setPadding(dp(18), dp(18), dp(18), dp(18))
                background = rounded(TEXT, 12)
                addView(qr, FrameLayout.LayoutParams(-1, -1))
            }.apply { layoutParams = LinearLayout.LayoutParams(dp(236), dp(236)) },
            LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(onboardingAvatar(name.take(1).uppercase(), 26, again?.color ?: draftColor))
                addView(text(name.replaceFirstChar { it.titlecase() }, BODY, bold = true).apply { setPadding(dp(8), 0, 0, 0) })
            }.marginTop(14),
        ).apply {
            gravity = Gravity.CENTER
            setPadding(dp(15), dp(20), dp(15), dp(20))
            background = glass(14)
        }
        val steps = column(Gravity.START,
            text(if (again != null) "Sign in again" else "Connect Stremio", if (upright) 30f else 40f, serif = true).onArt(),
            text(when {
                upright && again != null -> "Stremio signed $name out of Canopy on this phone. Sign in again here; $name's settings and PIN stay."
                upright -> "Tap below: Stremio opens and asks to sign in Canopy on this phone. Or scan the code with another phone."
                again != null -> "Stremio signed $name out of this TV. Scan with $name's phone to connect again. $name's settings and PIN stay."
                else -> "Scan with $name's phone. Stremio opens and asks to sign in this TV."
            }, BODY).onArt().marginTop(10),
            openLink.marginTop(14),
            text("", BODY, color = TEXT_2).onArt().apply {
                val line = "Or go to link.stremio.com and enter"
                val at = line.indexOf("link.stremio.com")
                text = android.text.SpannableString(line).apply {
                    setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), at, at + 16, 0)
                    setSpan(android.text.style.ForegroundColorSpan(TEXT), at, at + 16, 0)
                }
            }.marginTop(22),
            codeRow.marginTop(8),
            LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(countdown, LinearLayout.LayoutParams(dp(20), dp(20)))
                addView(status, wrap().apply { marginStart = dp(9) })
            }.marginTop(17),
            // Two people approving on one phone sign each other out: switching accounts there can end the other's sign-in.
            text("Sharing a phone? Approve each person's code on their own phone or in a private browser window. Signing out of Stremio there can sign this TV out too.",
                SMALL, color = TEXT_2).onArt().marginTop(24),
            text("New to Stremio? Make an account and add your add-ons in the Stremio app or at web.stremio.com. Canopy brings them to the TV.",
                SMALL, color = TEXT_3).onArt().marginTop(10),
        ).apply { gravity = Gravity.CENTER_VERTICAL }
        val first = Profiles.all(this).isEmpty()
        val content = onboardingFrame(if (again != null) "sign in again" else if (first) "step 2 of 2" else "step 2 of 3", left, steps, dp(55), 0f, 1f, dp(300),
            rightFirst = true)
        // A phone has its own Back.
        if (!upright) content.addView(back, FrameLayout.LayoutParams(-2, dp(32), Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = dp(PAD); bottomMargin = dp(40)
        })
        show(content, onboardingBackdrop())
        back.requestFocus()

        waitingCode = null
        background({ Stremio.createLink() }) { result ->
            if (!signingIn || request != signInGeneration) return@background
            result.onSuccess { link ->
                linkUrl = link.url
                qr.setImageBitmap(qrBitmap(link.url, dp(200)))
                codeTiles.forEachIndexed { i, tile -> tile.text = link.code.getOrNull(i)?.toString() ?: "" }
                val started = SystemClock.elapsedRealtime()
                fun tick() {
                    if (!signingIn || request != signInGeneration || waitingCode != link.code) return
                    val leftSeconds = ((5 * 60_000L - (SystemClock.elapsedRealtime() - started)).coerceAtLeast(0) / 1000).toInt()
                    status.text = "Waiting for your phone · new code in ${leftSeconds / 60}:${"%02d".format(leftSeconds % 60)}"
                    countdown.progress = leftSeconds * 1000 / 300
                    main.postDelayed(::tick, 1_000)
                }
                waitingCode = link.code
                tick()
                poll(name, link.code, started, again)
            }.onFailure {
                status.text = "Can't reach Stremio. Trying again…"
                main.postDelayed({ if (signingIn && request == signInGeneration) showSignIn(name, again) }, 5_000)
            }
        }
    }

    private fun onboardingBackdrop(useLastFilm: Boolean = true) = ImageView(this).apply {
        setImageResource(R.drawable.onboarding_scene)
        scaleType = ImageView.ScaleType.CENTER_CROP
        // Dim, as the doc asks: the copy and code sit over it. A darkening filter, not alpha: the window has no
        // background, so a see-through backdrop would show the last frame instead of darkening.
        if (useLastFilm) setColorFilter(android.graphics.Color.argb(166, 14, 12, 10), android.graphics.PorterDuff.Mode.SRC_ATOP)
        if (useLastFilm) Profiles.active(this@ProfilesActivity)?.let { profile ->
            background({ Stremio.savedLibrary(profile.authKey)?.let(Library::continueWatching)?.firstOrNull()?.optString("_id") }) { result ->
                result.getOrNull()?.let(Images::backgroundFor)?.let { url -> Images.load(url, this, resources.displayMetrics.widthPixels) }
            }
        }
    }

    /**
     * A setup step: on a TV, [left] and [right] side by side under the wordmark and the step's marks; on a phone the
     * two stacked in a page that scrolls ([right] first with [rightFirst]).
     */
    private fun onboardingFrame(step: String, left: View, right: View, gap: Int, leftWeight: Float, rightWeight: Float, leftWidth: Int = 0,
                                rightFirst: Boolean = false): FrameLayout {
        if (upright) return FrameLayout(this).apply {
            val body = column(Gravity.CENTER_HORIZONTAL, onboardingHeader(step)).apply { setPadding(dp(20), 0, dp(20), dp(32)) }
            (if (rightFirst) listOf(right, left) else listOf(left, right)).forEach { v ->
                body.addView(v, LinearLayout.LayoutParams(if (v.layoutParams?.width ?: -1 > 0) v.layoutParams.width else -1, -2).apply {
                    topMargin = dp(28); gravity = Gravity.CENTER_HORIZONTAL
                })
            }
            addView(android.widget.ScrollView(this@ProfilesActivity).apply { isFillViewport = true; addView(body) },
                FrameLayout.LayoutParams(-1, -1))
        }
        return tvFrame(step, left, right, gap, leftWeight, rightWeight, leftWidth)
    }

    /** The wordmark, and how far along the setup is ("Welcome · step 1 of 2" and square marks). */
    private fun onboardingHeader(step: String): View = LinearLayout(this).apply {
        layoutParams = LinearLayout.LayoutParams(-1, -2)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(16), 0, 0)
        addView(brand(), LinearLayout.LayoutParams(0, -2, 1f))
        if (step.isEmpty()) return@apply
        val current = step.substringAfter("step ").substringBefore(" of ").toIntOrNull() ?: 1
        val total = step.substringAfter(" of ").toIntOrNull() ?: 2
        addView(text((if (total > 2) "New profile · " else "Welcome · ") + step, SMALL, color = TEXT_2))
        repeat(total) { index -> addView(View(this@ProfilesActivity).apply {
            background = GradientDrawable().apply { setColor(if (index < current) TUNGSTEN else GLASS_EDGE) }
        }, LinearLayout.LayoutParams(dp(5), dp(5)).apply { marginStart = dp(5) }) }
    }

    private fun tvFrame(step: String, left: View, right: View, gap: Int, leftWeight: Float, rightWeight: Float, leftWidth: Int) =
        android.widget.FrameLayout(this).apply {
            val body = LinearLayout(this@ProfilesActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(PAD), dp(68), dp(PAD), dp(35))
                addView(Fit(left, pivotStart = true), if (leftWidth > 0)
                    LinearLayout.LayoutParams(leftWidth, -2).apply { marginEnd = gap }
                else LinearLayout.LayoutParams(0, -1, leftWeight).apply { marginEnd = gap })
                addView(Fit(right, pivotStart = false), LinearLayout.LayoutParams(0, -1, rightWeight))
            }
            addView(body, android.widget.FrameLayout.LayoutParams(-1, -1))
            val current = step.substringAfter("step ").substringBefore(" of ").toIntOrNull() ?: 1
            val total = step.substringAfter(" of ").toIntOrNull() ?: 2
            addView(LinearLayout(this@ProfilesActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(PAD), dp(31), dp(PAD), 0)
                addView(brand(), LinearLayout.LayoutParams(0, -2, 1f))
                if (step.isEmpty()) return@apply // a profile's own screens (rename) aren't part of a setup
                addView(text((if (total > 2) "New profile · " else "Welcome · ") + step, SMALL, color = TEXT_2))
                repeat(total) { index -> addView(View(this@ProfilesActivity).apply {
                    background = GradientDrawable().apply {
                        setColor(if (index < current) TUNGSTEN else GLASS_EDGE) // square marks, no round dots
                    }
                }, LinearLayout.LayoutParams(dp(5), dp(5)).apply { marginStart = dp(5) }) }
            }, android.widget.FrameLayout.LayoutParams(-1, dp(58), Gravity.TOP))
        }

    private fun onboardingButton(label: String) = pill(label)

    private fun onboardingAvatar(initial: String, size: Int, color: Int = draftColor) = text(initial, if (size > 50) size * 0.48f else 19f,
        serif = true, color = BASE).apply {
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            cornerRadius = dp(minOf(if (size > 50) PORTRAIT_R else CORNER, CORNER)).toFloat()
            setColor(color)
        }
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
    }

    private fun poll(name: String, code: String, startedAt: Long, again: Profile?) {
        main.postDelayed({
            if (waitingCode != code) return@postDelayed
            background({ Stremio.readLink(code) }) { result ->
                if (waitingCode != code) return@background
                val key = result.getOrNull()
                when {
                    key != null -> {
                        waitingCode = null
                        signingIn = false
                        approved(name, key, again)
                    }
                    // ponytail: Stremio doesn't say when a code expires (waiting and expired look the same),
                    // so a fresh code every 5 minutes. Read the expiry if their API ever exposes it.
                    SystemClock.elapsedRealtime() - startedAt > 5 * 60_000 -> showSignIn(name, again)
                    else -> poll(name, code, startedAt, again) // not approved yet, or a network blip
                }
            }
        }, 3_000)
    }

    /**
     * A code was approved. When that account is already another profile here (approved on a phone still signed in to
     * the other person), say so before going on. Each sign-in has its own key, so the accounts are compared by user id.
     */
    private fun approved(name: String, key: String, again: Profile?) {
        val others = Profiles.all(this).filter { it.id != again?.id }
        background({
            others.firstOrNull { it.authKey == key } ?: Stremio.userId(key).let { account ->
                others.firstOrNull { runCatching { Stremio.userId(it.authKey) }.getOrNull() == account }
            }
        }) { result ->
            val same = result.getOrNull() ?: return@background signedIn(name, key, again) // unknown: don't hold anyone up
            choose("This is ${same.name}'s Stremio account", listOf("Use another account", "Continue anyway"),
                "Approve the code with $name's own account: on $name's phone, or in a private browser window. " +
                    "Signing out of Stremio on the phone can sign ${same.name} out of this TV.") { i ->
                if (i == 0) showSignIn(name, again) else signedIn(name, key, again)
            }
        }
    }

    private fun signedIn(name: String, key: String, again: Profile?) {
        if (again != null) {
            Profiles.signIn(this, again.id, key)
            return openHome(Profiles.all(this).first { it.id == again.id })
        }
        val others = Profiles.all(this)
        val profile = Profiles.add(this, name, key, draftColor)
        draftName = ""
        draftColor = Profiles.COLORS[0]
        // Each profile brings its own add-ons (stream add-ons are shared in Settings, never copied);
        // the others' lists only tell where Watchly lives, so the new profile can set up its own.
        if (others.isEmpty()) openHome(profile)
        else background({ others.flatMap { runCatching { Stremio.addons(it.authKey) }.getOrDefault(emptyList()) } }) {
            showWatchly(profile, it.getOrDefault(emptyList()))
        }
    }

    // ---------- recommendations ----------

    /** Watchly is per person: the new profile sets up its own, connected to its own Stremio account. */
    private fun showWatchly(profile: Profile, sourceAddons: List<Addon>) {
        backTo = { openHome(profile) }
        val watchly = sourceAddons.firstOrNull { it.name.contains("watchly", ignoreCase = true) }
            ?: return showReady(profile)
        val configure = Uri.parse(watchly.transportUrl).let { "${it.scheme}://${it.authority}/configure" }
        val qr = ImageView(this).apply { setImageBitmap(qrBitmap(configure, dp(220))) }
        val done = onboardingButton("Continue to Canopy").apply { setOnClickListener { openHome(profile) } }
        val steps = column(Gravity.START,
            text("Recommendations for ${profile.name}", SERIF_TITLE, bold = true, serif = true),
            text("1.  Scan with ${profile.name}'s phone to open Watchly\n" +
                "2.  Connect ${profile.name}'s Stremio account there\n" +
                "3.  Install it to ${profile.name}'s Stremio account\n\n" +
                "${profile.name}'s own \"Top Picks\" then show up on Home. You can finish this later.", BODY, color = TEXT_3).marginTop(16),
            done.marginTop(32),
        )
        show(onboardingFrame("step 3 of 3", column(Gravity.CENTER,
            qr.apply { background = rounded(TEXT, 12) },
            text(profile.name, BODY, bold = true).marginTop(12)
        ).apply { gravity = Gravity.CENTER; setPadding(dp(22), dp(22), dp(22), dp(22)); background = glass(14) },
            steps, dp(52), 0.9f, 1.1f), onboardingBackdrop())
        done.requestFocus()
    }

    private fun showReady(profile: Profile) {
        backTo = { openHome(profile) }
        val done = onboardingButton("Continue to Canopy").apply { setOnClickListener { openHome(profile) } }
        show(onboardingFrame("step 3 of 3", column(Gravity.CENTER,
            onboardingAvatar(profile.name.take(1).uppercase(), 100, profile.color),
            text(profile.name, SERIF_TITLE, serif = true).marginTop(12)
        ).apply { gravity = Gravity.CENTER }, column(Gravity.START,
            text("You're all set", 40f, serif = true),
            text("${profile.name}'s profile is ready. You can add more add-ons later in Settings.", BODY, color = TEXT_2).marginTop(12),
            done.marginTop(24)
        ).apply { gravity = Gravity.CENTER_VERTICAL }, dp(55), 0.9f, 1.1f), onboardingBackdrop())
        done.requestFocus()
    }

    /** Black on white with the standard 4-module quiet zone, whole pixels per module so it stays sharp. */
    private fun qrBitmap(text: String, maxPx: Int): Bitmap {
        val m = Qr.encode(text)
        val n = m.size + 8
        val px = maxOf(1, maxPx / n)
        val bmp = Bitmap.createBitmap(n * px, n * px, Bitmap.Config.RGB_565)
        bmp.eraseColor(TEXT)
        val canvas = Canvas(bmp)
        val paint = Paint().apply { color = BASE }
        for (r in m.indices) for (c in m.indices) if (m[r][c]) {
            canvas.drawRect(((c + 4) * px).toFloat(), ((r + 4) * px).toFloat(), ((c + 5) * px).toFloat(), ((r + 5) * px).toFloat(), paint)
        }
        return bmp
    }

    /**
     * Holds a setup column at its own height, centred, and shrinks it (letters and all) when the TV's screen is
     * shorter than it, as on a TV with a larger display or font size, so nothing runs off the top or bottom.
     */
    private class Fit(private val child: View, private val pivotStart: Boolean) : FrameLayout(child.context) {
        init {
            clipChildren = false
            addView(child, LayoutParams(-1, -2))
        }

        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            val wMode = MeasureSpec.getMode(widthSpec)
            child.measure(if (wMode == MeasureSpec.UNSPECIFIED) widthSpec
                else MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthSpec), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            val natural = child.measuredHeight
            val hMode = MeasureSpec.getMode(heightSpec)
            val room = if (hMode == MeasureSpec.UNSPECIFIED) natural else MeasureSpec.getSize(heightSpec)
            val scale = if (natural > room && natural > 0) room.toFloat() / natural else 1f
            child.scaleX = scale
            child.scaleY = scale
            setMeasuredDimension(if (wMode == MeasureSpec.UNSPECIFIED) child.measuredWidth else MeasureSpec.getSize(widthSpec),
                if (hMode == MeasureSpec.EXACTLY) room else minOf(natural, room))
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val top = (b - t - child.measuredHeight) / 2
            child.layout(0, top, child.measuredWidth, top + child.measuredHeight)
            child.pivotX = if (pivotStart) 0f else child.measuredWidth / 2f
            child.pivotY = child.measuredHeight / 2f
        }
    }

    private companion object {
        const val PORTRAIT_R = 6 // dp: portraits are soft squares
        const val LIFT_ROOM = 8 // dp above the portraits for the focused one's 10% lift and ring
        const val KEY_H = 34 // dp: a row of the name step's letters
        const val KEY_W = 42 // dp: a letter's cell, nine to a row
        const val KEY_INSET = 13 // dp: a letter's focus line, about as wide as the letter
        const val KEY_SP = 17f // the letters, as large as Search's
        const val COLUMNS = 9
        const val FIELD_W = 400 // dp: the name's line, as wide as the letters
        const val TYPED_SP = 34f // the name as it's typed
        const val NAME_SP = 19f // the name under the portrait
        const val PREVIEW = 150 // dp: the portrait on the name step
    }
}
