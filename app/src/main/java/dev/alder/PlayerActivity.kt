package dev.alder

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Rational
import android.view.OrientationEventListener
import android.os.Handler
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextClock
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.mp3.Mp3Extractor
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.math.abs
import kotlin.math.roundToInt

internal fun lowerSourceHeight(height: Int): Int? = listOf(1080, 720, 480).firstOrNull { it < height }
internal fun upNextWindow(durationMs: Long) = minOf(60_000L, maxOf(30_000L, durationMs / 25))
internal fun plausibleEpisode(durationMs: Long) = durationMs >= 2 * 60_000L

/** An anime episode AniSkip can be asked about: ("kitsu" or "mal", the show's id, the episode), from "kitsu:7442:5". */
internal fun animeEpisode(videoId: String): Triple<String, String, Int>? {
    val parts = videoId.split(":")
    if (parts.size < 3 || parts[0] !in listOf("kitsu", "mal")) return null
    return Triple(parts[0], parts[1], parts.last().toIntOrNull() ?: return null)
}

// ExoPlayer with our own controls (buildControls) and audio/subtitle panel (openOptions). Resumes where Stremio says you stopped and
// writes progress back to the account, so Continue Watching matches on every device.
// It gets a short list of sources: one that fails, or hasn't shown a picture after START_WAIT, gives way to the next
// at the same second, and when the list runs out the sources page opens. Network blips are retried first, twice
// after 1.5 s (NuvioTV's rule), so a dropped connection doesn't cost the source.
class PlayerActivity : Screen() {
    override val clearOfCutout: Boolean get() = false // the film fills a phone's whole screen, camera notch and all
    override val backArrow: Boolean get() = false // its controls have their own
    override val screensaver: Boolean get() = false // the film is the picture; the TV keeps the screen on while it plays
    private lateinit var player: ExoPlayer
    private lateinit var view: PlayerView
    private val type by lazy { intent.getStringExtra("type") ?: "movie" }
    private val metaId by lazy { intent.getStringExtra("id")!! }
    private val videoId by lazy { intent.getStringExtra("videoId")!! }

    // The title's library item. Null when it couldn't be loaded: then nothing is saved, rather than
    // overwriting real history with a blank item.
    private var item: JSONObject? = null
    private var lastPosition = 0L
    private var ticks = 0

    private val sources by lazy { JSONArray(intent.getStringExtra("sources") ?: "[]").objects().map(Stream::read) }
    private var at = 0 // the source playing
    private var retries = 0 // network retries on this source
    private var asHls = false // tried as HLS: live and IPTV links often hide the playlist behind a plain URL
    private var started = false // a picture has shown
    private var startMessage: String? = null
    private var watched = false // any source showed a picture this time: only then is progress saved
    private var startAt = 0L
    private var prepared = false
    private lateinit var status: android.widget.TextView
    private lateinit var loading: FrameLayout
    // Until the first picture: the same starting screen the sources lookup showed, instead of a bar over black.
    private lateinit var starting: Starting
    private lateinit var loadingBar: SeekBar
    private lateinit var loadingStart: TextView
    private lateinit var loadingLeft: TextView
    private lateinit var stallPanel: FrameLayout
    private lateinit var screen: FrameLayout
    private lateinit var pausedShade: View
    // Subtitles from addons (OpenSubtitles): offered in Playback options after the file's own. Picking one reloads the
    // source from the same second with that file attached, then selects it once its track shows up.
    private var addonSubs: List<Subtitle> = emptyList()
    private var chosenSub: Subtitle? = null
    private var selectAddonSub = false
    // The languages to start with: what was picked in Audio & subtitles for this title before (the next episode, a
    // resume), else the profile's Settings. Picks in the menu change them, are kept for the title, and hold when the
    // player moves to another source.
    private var wantAudio = ""
    private var wantSub = ""
    private var subsOn = true
    private var viewerAddonSub = false // the viewer chose the addon file, over the file's own in the same language
    // Timing for an addon subtitle file that runs early or late: the file is fetched, its times moved, and attached
    // from the app's cache instead of its URL. ponytail: SRT and VTT only; ASS and the file's own subtitles can't move.
    private var subDelay = 0L
    private var subFile: java.io.File? = null

    // A series' episodes (for the next one and Stremio's watched ticks), from the meta addon's saved answer when there is one.
    private var meta: Meta? = null
    private var tickedWatched = false
    // "Up next": offered during credits; the current episode keeps playing until the viewer chooses the next one.
    private lateinit var upNextPanel: FrameLayout
    private var upNextDismissed = false
    // Up next counts down while the episode plays (held while paused), then starts the next one.
    private var countdownLeft = 0L
    private lateinit var countdownText: TextView
    private lateinit var countdownBar: SeekBar
    private val countdownTask = object : Runnable {
        override fun run() {
            if (upNextPanel.visibility != View.VISIBLE || isFinishing) return
            val playing = player.playWhenReady && player.playbackState == Player.STATE_READY
            if (playing) countdownLeft -= COUNTDOWN_STEP
            if (countdownLeft <= 0) return playNext()
            countdownText.text = if (playing) "Starts in ${(countdownLeft + 999) / 1000} s" else "Paused · starts when you play"
            countdownBar.set(UP_NEXT_COUNTDOWN - countdownLeft, 0, UP_NEXT_COUNTDOWN)
            main.postDelayed(this, COUNTDOWN_STEP)
        }
    }
    // The pill's other use: a source that keeps stalling (three times in a minute) is offered a lower quality once.
    private var offeringLower = false
    private val stalls = ArrayDeque<Long>()
    private var seekedAt = 0L
    private val loadingTask = object : Runnable {
        override fun run() {
            if ((loading.visibility != View.VISIBLE && starting.view.visibility != View.VISIBLE) || player.playbackState != Player.STATE_BUFFERING) return
            showLoading(if (started) "Loading" else startMessage ?: "Starting · ${sources[at].label}")
        }
    }
    private val startTimeout = Runnable {
        if (started) return@Runnable
        android.util.Log.w("Canopy", "source ${at + 1}/${sources.size} timed out, state ${player.playbackState}")
        next()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val key = authKey ?: return finish()
        if (sources.isEmpty()) return finish()
        // Audio is decoded to PCM here unless Settings says a receiver takes Dolby and DTS: TVs report passthrough
        // (DTS-HD, TrueHD) they then can't open, and playback dies. A track that fails is swapped (onPlayerError).
        val passthrough = Settings.passthrough(this)
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildVideoRenderers(context: Context, extensionRendererMode: Int, mediaCodecSelector: MediaCodecSelector,
                enableDecoderFallback: Boolean, eventHandler: Handler, eventListener: VideoRendererEventListener,
                allowedVideoJoiningTimeMs: Long, out: ArrayList<Renderer>) {
                super.buildVideoRenderers(context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback, eventHandler,
                    eventListener, allowedVideoJoiningTimeMs, out)
                // Dolby Vision profile 7 the TV can't take plays as HDR10 (see BaseLayerVideoRenderer).
                val i = out.indexOfFirst { it is MediaCodecVideoRenderer }
                if (i >= 0) out[i] = baseLayerRenderer(context) {
                    it.setCodecAdapterFactory(codecAdapterFactory).setMediaCodecSelector(mediaCodecSelector)
                        .setAllowedJoiningTimeMs(allowedVideoJoiningTimeMs).setEnableDecoderFallback(enableDecoderFallback)
                        .setEventHandler(eventHandler).setEventListener(eventListener)
                        .setMaxDroppedFramesToNotify(DefaultRenderersFactory.MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY)
                }
            }

            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
                if (passthrough) DefaultAudioSink.Builder(context).build() else
                object : ForwardingAudioSink(DefaultAudioSink.Builder(context).build()) {
                    override fun getFormatSupport(format: Format): Int =
                        if (format.sampleMimeType == MimeTypes.AUDIO_RAW) super.getFormatSupport(format) else AudioSink.SINK_FORMAT_UNSUPPORTED

                    override fun supportsFormat(format: Format) = getFormatSupport(format) != AudioSink.SINK_FORMAT_UNSUPPORTED
                }
        }.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON) // ffmpeg after the TV's own decoders
        player = ExoPlayer.Builder(this, renderers).build()
        val remembered = Settings.titleChoice(this, metaId)
        wantAudio = remembered?.audio ?: Settings.audioLanguage(this)
        subsOn = remembered?.subtitles?.isNotEmpty() ?: Settings.subtitles(this)
        wantSub = remembered?.subtitles?.ifEmpty { null } ?: Settings.subtitleLanguage(this)
        // Release files carry many dubs; start in the chosen language (the TV's unless Settings says) instead of the first.
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setPreferredAudioLanguage(wantAudio).setPreferredTextLanguage(wantSub).build()
        view = PlayerView(this).apply {
            player = this@PlayerActivity.player
            keepScreenOn = true
            useController = false
            setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
            // Settings' size and background, with a shadow so white text holds up on bright scenes either way.
            subtitleView?.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * Settings.subtitleSize(context) / 100f)
            subtitleView?.setStyle(CaptionStyleCompat(Color.WHITE,
                if (Settings.subtitleBackground(context)) 0xB3000000.toInt() else Color.TRANSPARENT, Color.TRANSPARENT,
                CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW, Color.BLACK, null))
            setBackgroundColor(Color.BLACK)
            resizeMode = fits[fitIndex()].second
        }
        status = text("", BODY).onArt().apply {
            gravity = Gravity.CENTER
            maxWidth = dp(440)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        pausedShade = View(this).apply { setBackgroundColor(0x4D000000); visibility = View.GONE }
        upNextPanel = FrameLayout(this).apply { visibility = View.GONE }
        controls = buildControls()
        loading = buildLoading()
        starting = startingScreen().apply { status.text = "Starting playback" }
        stallPanel = FrameLayout(this).apply { visibility = View.GONE }
        skip = text("", sized(BODY, 18f), bold = true).onArt().apply {
            setPadding(dp(sized(22, 26)), dp(sized(12, 16)), dp(sized(22, 26)), dp(sized(12, 16)))
            background = glass(10)
            visibility = View.GONE
        }
        fetchSegments()
        options = FrameLayout(this).apply { visibility = View.GONE }
        screen = FrameLayout(this).apply {
            addView(view, FrameLayout.LayoutParams(-1, -1))
            addView(pausedShade, FrameLayout.LayoutParams(-1, -1))
            addView(controls, FrameLayout.LayoutParams(-1, -1))
            addView(buildAmbient(), FrameLayout.LayoutParams(-1, -1))
            addView(options, FrameLayout.LayoutParams(-1, -1))
            addView(loading, FrameLayout.LayoutParams(-1, -1))
            addView(starting.view, FrameLayout.LayoutParams(-1, -1))
            addView(stallPanel, FrameLayout.LayoutParams(-1, -1))
            addView(upNextPanel, FrameLayout.LayoutParams(-1, -1))
            addView(skip, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(PAD), dp(PAD + 40)) })
        }
        if (touch) {
            upright = buildUpright()
            screen.addView(upright, FrameLayout.LayoutParams(-1, -1).apply { gravity = Gravity.TOP })
            // Turns with the phone (upright: the picture on top, the title below), unless kept sideways.
            requestedOrientation = if (sideways()) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_USER
            screen.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
                if (r - l != or - ol || b - t != ob - ot) screen.post { applyLayout() }
            }
        }
        if (windowAllowed()) {
            val filter = IntentFilter(WINDOW_ACTION)
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(windowReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(windowReceiver, filter)
            windowReceiverOn = true
        }
        setContentView(screen)
        background({
            Stremio.addons(key).let { it.savedMeta(type, metaId) ?: it.meta(type, metaId) }
        }) { r ->
            meta = r.getOrNull()
            val m = meta ?: return@background
            val episode = m.videos.firstOrNull { it.id == videoId }
            pauseTitle.text = episode?.title?.takeIf { it.isNotBlank() } ?: m.name
            subtitle.text = episode?.let { "S${it.season} E${it.episode}" } ?: m.year.orEmpty()
            Images.load(m.logo ?: Images.logoFor(m.id), playerLogo, dp(300), urgent = true, failed = {
                playerLogo.visibility = View.GONE; topName.visibility = View.VISIBLE
            }) {
                playerLogo.visibility = View.VISIBLE; topName.visibility = View.GONE
            }
            nextCell.visibility = if (nextVideo() == null) View.GONE else View.VISIBLE
            startOverCell.visibility = if (nextVideo() == null && !touch) View.VISIBLE else View.GONE // a phone: Daniel left Start over out
            if (touch) fillUpright()
        }

        player.addListener(object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) {
                // A bad audio track (ffmpeg rejects a DTS-HD packet in The Raid around 5:14) shouldn't end the movie:
                // drop that track and carry on from the same second on the next best one.
                val failed = (e as? ExoPlaybackException)?.rendererFormat
                if (failed?.sampleMimeType?.let(MimeTypes::isAudio) == true && failedAudio.add(failed.id ?: "")) {
                    val next = bestAudio(audioGroups)
                    if (next != null) {
                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                            .setOverrideForType(TrackSelectionOverride(next.mediaTrackGroup, (0 until next.length).toList())).build()
                        player.prepare()
                        android.util.Log.i("Canopy", "Audio track changed after decoder error")
                        return
                    }
                }
                // A link that isn't a file ExoPlayer knows may be an HLS playlist without .m3u8 in its name.
                if (!asHls && e.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED) {
                    asHls = true
                    return load(position())
                }
                if (retryable(e) && retries < RETRIES) {
                    retries++
                    // ExoPlayer's own retries already took about 6 s: each of ours gets a fresh START_WAIT, or the
                    // start timeout would move on to the next source before the second retry ran.
                    main.removeCallbacks(startTimeout)
                    main.postDelayed(startTimeout, RETRY_WAIT + START_WAIT)
                    main.postDelayed({ if (!isFinishing) player.prepare() }, RETRY_WAIT)
                    return
                }
                android.util.Log.w("Canopy", "source ${at + 1}/${sources.size} failed: ${e.errorCodeName}", e)
                next(e.errorCodeName)
            }

            override fun onRenderedFirstFrame() {
                if (started) return
                started = true
                watched = true
                retries = 0
                hideLoading()
                main.removeCallbacks(startTimeout)
                player.videoFormat?.frameRate?.let(::matchFrameRate)
                updateWindow()
                // Next time: the same release for this video, and the same group (release and quality) for the next episode.
                val s = sources[at]
                // A file naming another episode (the Nocturne bug) is never remembered as this one's known-good file.
                val wrong = Stream.episodeOf(videoId)?.let { (season, episode) -> s.namesOtherEpisode(season, episode) } == true
                if (!wrong) getSharedPreferences("sources", MODE_PRIVATE).edit()
                    .putString("file:$videoId", s.filename).putString("binge:$metaId", s.bingeGroup).apply()
            }

            override fun onPlaybackStateChanged(state: Int) {
                // At the real end the next episode starts, unless the viewer chose to watch the credits.
                if (state == Player.STATE_ENDED) {
                    // shortcut: sub-two-minute series streams are treated as addon placeholders; revisit for short-form shows.
                    if (type == "series" && !plausibleEpisode(player.duration)) {
                        started = false // retry from the original resume point, not the placeholder's short ending
                        next("source ended as a short placeholder")
                    } else if (nextVideo() != null && !upNextDismissed && !inWindow) playNext() else finish()
                }
                if (state == Player.STATE_BUFFERING) showLoading(if (started) "Loading" else startMessage ?: "Starting · ${sources[at].label}")
                if (state == Player.STATE_READY && started) hideLoading()
                // A seek buffers too; only stalls in plain playback count.
                if (state == Player.STATE_BUFFERING && started && player.playWhenReady && System.currentTimeMillis() - seekedAt > 2_000) stalled()
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                // Back pauses the player on its way out: nothing on the closing screen changes (Play lit up as it faded).
                if (isFinishing) return
                playPause.foreground = centred(if (playWhenReady) R.drawable.ic_pause else R.drawable.ic_play, 25, BASE)
                buttonLabels[playPause]?.text = if (playWhenReady) "Pause" else "Play"
                bigPlay?.foreground = centred(if (playWhenReady) R.drawable.ic_pause else R.drawable.ic_play, 46, TEXT)
                pausedShade.visibility = if (playWhenReady) View.GONE else View.VISIBLE
                updateWindow()
                if (touch) refreshUpright()
                pauseInfo.visibility = if (playWhenReady || touch) View.GONE else View.VISIBLE // a phone has the name at the top and Play in the middle
                // Paused, the controls stay up until a key or Play: no calm pause screen takes over on its own.
                main.removeCallbacks(ambientTask)
                if (playWhenReady) hideAmbient()
                if (playWhenReady) { if (shown) showControls(null) }
                else if (!stopping && options.visibility != View.VISIBLE && upNextPanel.visibility != View.VISIBLE &&
                    stallPanel.visibility != View.VISIBLE) showControls(playPause)
            }

            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) { updateWindow() }

            override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                    seekedAt = System.currentTimeMillis()
                    skipped.clear() // back into an intro: offer it again
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                if (selectAddonSub) tracks.groups.firstOrNull(::isAddonSub)?.let { g ->
                    selectAddonSub = false
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, 0)).build()
                }
                val audio = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                if (audio.isNotEmpty()) audioGroups = audio
                val best = bestAudio(audio)
                // Releases flag foreign subtitles as forced or default (Lanterns came up with Russian "Forced" under
                // English audio): start with subtitles only in the profile's subtitle language, whatever the sound, else
                // none. The file's own come first, then the addon file attached at the start, then the file's own from
                // another country (Portugal's for Brazil's). Once per video, like audio.
                if (!subsPicked && best != null) {
                    subsPicked = true
                    val texts = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT && it.isTrackSupported(0) }
                    fun inFile(score: Int) = texts.filterNot(::isAddonSub).filter { Languages.match(it.getTrackFormat(0).language, wantSub) == score }
                        .maxByOrNull { it.getTrackFormat(0).selectionFlags and C.SELECTION_FLAG_FORCED == 0 } // full, not forced-only
                    val addon = texts.firstOrNull { isAddonSub(it) && Languages.same(chosenSub?.lang, wantSub) }
                    val pick = if (!subsOn) null
                        else (if (viewerAddonSub) addon else null) ?: inFile(2) ?: addon ?: inFile(1)
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
                        if (pick == null) setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                        else setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setOverrideForType(TrackSelectionOverride(pick.mediaTrackGroup, 0))
                    }.build()
                }
                if (audioPicked || best == null) return
                audioPicked = true // once per video; picks made later in the settings menu stand
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setOverrideForType(TrackSelectionOverride(best.mediaTrackGroup, (0 until best.length).toList())).build()
            }
        })

        // "Start Over" plays from the beginning; a switch to a lower quality carries its own second.
        val known = if (intent.getBooleanExtra("startOver", false)) 0L else intent.getLongExtra("resumeAt", -1L).takeIf { it >= 0 }
        // The addon's subtitles are waited for (SUB_WAIT at most) so a file in the profile's language is attached from
        // the start: attaching one later means loading the source again. The latest resume point asked for wins.
        var subsAnswered = !subsOn
        var waiting: Long? = null
        fun begin(resume: Long) {
            if (prepared) return
            if (!subsAnswered) { waiting = resume; return }
            load(resume)
            lastPosition = resume
            prepared = true
            main.postDelayed(tickTask, TICK)
            main.post(skipTask)
        }
        val stopWaiting = Runnable { subsAnswered = true; waiting?.let(::begin) }
        main.postDelayed(stopWaiting, SUB_WAIT)
        background({
            // Every language: the profile's subtitle language and English first, three files each in the addon's order,
            // then one file for each other language by name, so the list stays short enough for a remote.
            val all = Stremio.addons(key).filter { it.serves("subtitles", type, videoId) }
                .flatMap { a -> runCatching { a.subtitles(type, videoId) }
                    .onFailure { android.util.Log.w("Canopy", "subtitles from ${a.name} failed", it) }.getOrDefault(emptyList()) }
            all.groupBy { Languages.code(it.lang) ?: it.lang }.entries
                .sortedWith(compareBy({ it.key != wantSub }, { it.key != "en" }, { Languages.name(it.key) }))
                .flatMap { (code, files) -> files.take(if (code == wantSub || code == "en") 3 else 1) }
        }) { r ->
            addonSubs = r.getOrDefault(emptyList())
            android.util.Log.i("Canopy", "addon subtitles: ${addonSubs.size}", r.exceptionOrNull())
            if (subsAnswered) return@background // too late: playing already, offered in the list
            main.removeCallbacks(stopWaiting)
            if (chosenSub == null) chosenSub = addonSubs.firstOrNull { Languages.same(it.lang, wantSub) } // attached, picked above
            stopWaiting.run()
        }
        // The stream starts at once from what's known here (the intent, or the library saved on the TV) instead of
        // waiting a round trip to Stremio for the resume point; Stremio's answer corrects it if it moved elsewhere.
        if (known != null) begin(known)
        else background({ Stremio.savedLibrary(key)?.firstOrNull { it.optString("_id") == metaId } }) { r ->
            r.getOrNull()?.let { begin(Library.resumeAt(it, videoId)) }
        }
        background({ Stremio.library(key, listOf(metaId)).firstOrNull() }) { result ->
            item = result.fold(
                { it ?: Library.newItem(metaId, type, intent.getStringExtra("name") ?: "", intent.getStringExtra("poster")) },
                { null },
            )
            val resume = known ?: item?.let { Library.resumeAt(it, videoId) } ?: 0L
            if (!prepared) begin(resume)
            // Watched further on the phone since the TV saved its copy: go there, unless you've already moved on.
            else if (known == null && abs(resume - startAt) > RESUME_SLACK && position() < startAt + RESUME_SLACK) {
                startAt = resume
                player.seekTo(resume)
                lastPosition = resume
            }
        }
    }

    private lateinit var loadingCenter: LinearLayout

    private fun buildLoading() = FrameLayout(this).apply {
        visibility = View.GONE
        addView(View(context).apply { setBackgroundColor(baseAt(0x80)) }, FrameLayout.LayoutParams(-1, -1))
        // On a phone, the ring and its words side by side, lifted above the middle Play while the controls show.
        loadingCenter = if (touch) LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(18), dp(10))
            background = rounded(baseAt(0xCC), CORNER)
            addView(ring(), LinearLayout.LayoutParams(dp(30), dp(30)))
            addView(status, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(12) })
        } else column(Gravity.CENTER_HORIZONTAL).apply {
            addView(ring(), LinearLayout.LayoutParams(dp(40), dp(40)))
            addView(status, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12) })
        }
        if (touch && shown) loadingCenter.translationY = -dp(130).toFloat()
        addView(loadingCenter, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        loadingBar = SeekBar(this@PlayerActivity).apply { isFocusable = false }
        loadingStart = text("", SMALL, color = TEXT_2).onArt()
        loadingLeft = text("", SMALL, color = TEXT_2).onArt()
        // Not on a phone: under the controls' own bar, it read as a second one at the top while a seek loaded.
        if (touch) return@apply
        addView(loadingBar, FrameLayout.LayoutParams(-1, dp(12), Gravity.TOP).apply {
            leftMargin = dp(PAD + 30); rightMargin = dp(PAD + 30); topMargin = dp(32)
        })
        addView(loadingStart, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
            leftMargin = dp(PAD); topMargin = dp(29)
        })
        addView(loadingLeft, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
            rightMargin = dp(PAD); topMargin = dp(29)
        })
    }

    private fun showLoading(message: String) {
        val duration = player.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: 0L
        val position = player.currentPosition.coerceAtLeast(0)
        val buffered = player.bufferedPosition.coerceAtLeast(position)
        loadingBar.set(position, buffered, duration)
        loadingStart.text = if (duration > 0) clock(position) else ""
        loadingLeft.text = if (duration > 0) "−${clock((duration - position).coerceAtLeast(0))}" else ""
        status.text = if (message == "Loading" && player.bufferedPercentage in 1..99)
            "Loading · ${player.bufferedPercentage}%" else message
        // Before any picture, a source starting (or the next one after a failure) stays on the starting screen, with
        // no source names; a dead end ("choose another source") drops to the plain overlay so its panel reads.
        val opening = !watched && (message.startsWith("Starting") || message.endsWith("trying the next one"))
        starting.view.visibility = if (opening) View.VISIBLE else View.GONE
        if (opening) starting.status.text = if (message.endsWith("trying the next one")) "Trying another source" else "Starting playback"
        loading.visibility = if (opening) View.GONE else View.VISIBLE
        main.removeCallbacks(loadingTask)
        if (player.playbackState == Player.STATE_BUFFERING) main.postDelayed(loadingTask, 500)
    }

    private fun hideLoading() {
        main.removeCallbacks(loadingTask)
        loading.visibility = View.GONE
        starting.view.visibility = View.GONE
        status.text = ""
    }

    /** A subtitle pick in the menu: the language ("" for Off) for this title's next episode and resume, and for the
     *  next source if this one fails. A track with no language turns subtitles on without naming one. */
    private fun remember(language: String?, addonFile: Boolean) {
        subsOn = language != ""
        viewerAddonSub = addonFile
        val code = if (language == "") "" else Languages.code(language) ?: return
        if (code.isNotEmpty()) wantSub = code
        Settings.pickedInPlayer(this, metaId, subtitles = code)
    }

    /** Plays the current source from [position], with its own request headers (debrid proxies need them). */
    private fun load(position: Long, message: String? = null) {
        val s = sources[at]
        startAt = position
        started = false
        toldUnseekable = false
        startMessage = message ?: "Starting · ${s.label}"
        showLoading(startMessage!!)
        val http = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true).setDefaultRequestProperties(s.headers)
        val item = MediaItem.Builder().setUri(s.url).apply { if (asHls) setMimeType(MimeTypes.APPLICATION_M3U8) }
            .setSubtitleConfigurations(listOfNotNull(chosenSub?.let {
                MediaItem.SubtitleConfiguration.Builder(subFile?.let(android.net.Uri::fromFile) ?: android.net.Uri.parse(it.url)).setMimeType(it.mimeType)
                    .setLanguage(it.lang).setLabel(it.addon).setId(ADDON_SUB).build()
            })).build()
        // Files with no index (.ts, raw audio, some .avi) seek by their bitrate instead of not at all: ExoPlayer turns
        // every seek in a file it can't seek into a jump to 0:00, so scrubbing restarted the movie.
        val extractors = DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true).setConstantBitrateSeekingAlwaysEnabled(true)
            .setMp3ExtractorFlags(Mp3Extractor.FLAG_ENABLE_INDEX_SEEKING)
        player.setMediaSource(DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http), extractors).createMediaSource(item), position) // files too: shifted subtitles
        player.prepare()
        player.playWhenReady = true
        main.removeCallbacks(startTimeout)
        main.postDelayed(startTimeout, START_WAIT)
    }

    private fun position() = if (started) player.currentPosition else startAt

    /** This source failed: the next one from the same second or, with none left, the list to choose from. */
    private fun next(reason: String = "didn't start") {
        main.removeCallbacks(startTimeout)
        val position = position()
        if (at + 1 < sources.size) {
            at++
            stalls.clear()
            retries = 0
            asHls = false
            audioPicked = false
            subsPicked = false
            failedAudio.clear()
            android.util.Log.w("Canopy", "Source failed: $reason; trying another")
            return load(position, "That source failed · trying the next one")
        }
        if (intent.getBooleanExtra("picked", false)) {
            showSourceError()
            player.stop()
            showLoading("That source failed · choose another source")
            return
        }
        startActivity(Intent(this, StreamsActivity::class.java).putExtras(intent).putExtra("pick", true)
            .putExtra("message", "${sources.size} sources didn't play"))
        finish()
    }

    /** Worth trying the same link again: a network or server hiccup, not a link that's gone (4xx) or a bad file. */
    private fun retryable(e: PlaybackException): Boolean {
        if (e.errorCode !in 2000..2999) return false
        val code = (e.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode ?: return true
        return code !in 400..499 || code == 408 || code == 429
    }

    private var audioPicked = false
    private var subsPicked = false
    private var audioGroups: List<Tracks.Group> = emptyList()
    private val failedAudio = mutableSetOf<String>() // Format ids of audio tracks that failed to decode

    /**
     * The start track: never a commentary (releases only say so in the track name, which ExoPlayer ignores), then the
     * TV's language, then the release's default track, then the most channels. ExoPlayer alone would favour whatever
     * the TV decodes in hardware, which on The Raid was a stereo commentary.
     */
    private fun bestAudio(groups: List<Tracks.Group>): Tracks.Group? {
        val language = wantAudio
        return groups
            .filter { it.isTrackSupported(0) && (it.getTrackFormat(0).id ?: "") !in failedAudio }
            .filter { it.getTrackFormat(0).label?.contains("commentary", ignoreCase = true) != true }
            .maxWithOrNull(compareBy<Tracks.Group>(
                { Languages.match(it.getTrackFormat(0).language, language) },
                { it.getTrackFormat(0).selectionFlags and C.SELECTION_FLAG_DEFAULT != 0 },
                { it.getTrackFormat(0).channelCount },
            ))
    }

    private val tickTask = Runnable { tick() }

    // Progress every 10 s, pushed to Stremio every minute (Stremio's own apps push every 3 minutes).
    private fun tick() {
        record()
        if (touch) refreshUpright()
        if (++ticks % 6 == 0) push()
        main.postDelayed(tickTask, TICK)
    }

    override fun onStart() {
        super.onStart()
        if (prepared) {
            lastPosition = player.currentPosition
            main.postDelayed(tickTask, TICK)
            main.post(skipTask)
            if (upNextPanel.visibility == View.VISIBLE) main.postDelayed(countdownTask, COUNTDOWN_STEP)
            if ((loading.visibility == View.VISIBLE || starting.view.visibility == View.VISIBLE) && player.playbackState == Player.STATE_BUFFERING)
                main.post(loadingTask)
        }
    }

    private fun record() {
        // Nothing is saved until a picture shows: a source that never starts would otherwise reset the resume point.
        if (!started || player.isCurrentMediaItemLive) return // live TV has no progress to keep
        if (type == "series" && !plausibleEpisode(player.duration)) return
        val item = item ?: return
        val position = player.currentPosition
        val duration = player.duration.takeIf { it > 0 } ?: return
        // Only real viewing counts as watched time: a seek moves the position by more than a tick allows.
        val played = (position - lastPosition).takeIf { it in 1..TICK * 2 } ?: 0
        lastPosition = position
        Library.record(item, videoId, position, duration, played)
        val videos = meta?.videos
        if (!tickedWatched && videos != null && item.optJSONObject("state")?.optInt("flaggedWatched") == 1) {
            tickedWatched = true
            Library.markWatched(item, videos, videoId)
        }
        // Keep the current episode full screen; offer the next one only near its actual end.
        if (!upNextDismissed && !inWindow && upNextPanel.visibility != View.VISIBLE && duration - position < upNextWindow(duration)) showUpNext()
        if (duration - position < PREFETCH_BEFORE_END) prefetchNext()
    }

    private var nextAsked = false

    /** Finds the next episode's sources a few minutes before this one ends, so Next up starts at once (NextSources). */
    private fun prefetchNext() {
        if (nextAsked) return
        val next = nextVideo() ?: return
        val key = authKey ?: return
        nextAsked = true
        val lenders = Profiles.streamLenders(this)
        val kind = type
        background({
            val addons = streamAddons(key, lenders, kind, next.id)
            val pool = java.util.concurrent.Executors.newFixedThreadPool(addons.size.coerceIn(1, 6))
            try {
                addons.map { a -> a.id to pool.submit<Addon.Streams?> { runCatching { a.streams(kind, next.id) }.getOrNull() } }
                    .mapNotNull { (id, f) -> runCatching { f.get(20, java.util.concurrent.TimeUnit.SECONDS) }.getOrNull()?.let { id to it } }
                    .toMap()
            } finally { pool.shutdownNow() }
        }) { found -> found.getOrNull()?.takeIf { it.isNotEmpty() }?.let { NextSources.put(next.id, it) } }
    }

    /** The episode after this one, or null for a film, the last episode, or before the episode list is in. */
    private fun nextVideo(): Video? {
        val videos = meta?.videos ?: return null
        val at = videos.indexOfFirst { it.id == videoId }.takeIf { it >= 0 } ?: return null
        return videos.getOrNull(at + 1)?.takeIf { it.season != 0 || videos[at].season == 0 } // not from the finale into specials
    }

    private fun stalled() {
        val now = System.currentTimeMillis()
        stalls.addLast(now)
        while (stalls.first() < now - 60_000) stalls.removeFirst()
        val height = sources[at].height
        if (stalls.size != 3 || offeringLower || upNextPanel.visibility == View.VISIBLE || lowerSourceHeight(height) == null) return
        showStall(height)
    }

    private fun showStall(height: Int) {
        val lower = lowerSourceHeight(height) ?: return
        offeringLower = true
        stallPanel.removeAllViews()
        val card = column(Gravity.START).apply {
            setPadding(dp(16), dp(15), dp(16), dp(14))
            background = glass(14)
            addView(caps("Keeps buffering", TUNGSTEN))
            addView(text("Switch to ${lower}p?", SERIF_TITLE, serif = true),
                wrap().apply { topMargin = dp(4) })
            addView(text("This ${height}p source stopped 3 times in a minute. A smaller file may play more smoothly from ${clock(position())}.",
                sized(SMALL, 15f), color = TEXT_2), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })
            val actions = LinearLayout(this@PlayerActivity).apply { gravity = Gravity.CENTER_VERTICAL }
            val lowerButton = pill("Switch to ${lower}p").thumb().apply { setOnClickListener { lowerQuality() } }
            actions.addView(lowerButton, wrap())
            actions.addView(chip("Keep ${height}p").thumb().apply { setOnClickListener { dismissStall() } },
                wrap().apply { marginStart = dp(6) })
            actions.addView(chip("Other sources").thumb().apply { setOnClickListener { changeStream() } },
                wrap().apply { marginStart = dp(6) })
            addView(actions, wrap().apply { topMargin = dp(12) })
            lowerButton.post { lowerButton.requestFocus() }
        }
        stallPanel.addView(card, FrameLayout.LayoutParams(cardWidth(sized(460, 560)), -2, Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = dp(PAD); bottomMargin = dp(48)
        })
        stallPanel.visibility = View.VISIBLE
    }

    private fun dismissStall() {
        offeringLower = false
        stallPanel.visibility = View.GONE
        stallPanel.removeAllViews()
    }

    private fun showSourceError() {
        stallPanel.removeAllViews()
        val card = column(Gravity.START,
            caps("Source failed", TUNGSTEN),
            text("Try another source", SERIF_TITLE, serif = true).marginTop(6),
            pill("Other sources").thumb().apply { setOnClickListener { changeStream() } }.marginTop(12)
        ).apply {
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = glass(14)
        }
        stallPanel.addView(card, FrameLayout.LayoutParams(cardWidth(sized(310, 360)), -2, Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = dp(PAD); bottomMargin = dp(48)
        })
        stallPanel.visibility = View.VISIBLE
        card.getChildAt(2).requestFocus()
    }

    /** Continue at the same second with one quality step lower. */
    private fun lowerQuality() {
        val height = sources[at].height
        val lower = lowerSourceHeight(height) ?: return
        startActivity(Intent(this, StreamsActivity::class.java).putExtras(intent).apply {
            removeExtra("picked"); removeExtra("pick")
        }.putExtra("maxHeight", lower).putExtra("resumeAt", player.currentPosition))
        finish()
    }

    private fun showUpNext() {
        if (type == "series" && !plausibleEpisode(player.duration)) return
        val next = nextVideo() ?: return
        if (offeringLower) dismissStall()
        if (shown) hideControls(force = true)
        options.visibility = View.GONE
        hideLoading()
        upNextPanel.removeAllViews()
        val actions = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val playNow = pill("Play now", R.drawable.ic_play).thumb().apply { setOnClickListener { playNext() } }
        actions.addView(playNow, wrap())
        actions.addView(chip("Watch the credits").thumb().apply { setOnClickListener { dismissUpNext() } },
            wrap().apply { marginStart = dp(8) })
        val content = column(Gravity.START).apply {
            setPadding(dp(18), dp(14), dp(18), dp(14))
            // Video can't be frosted: a dark base under the glass keeps it readable over white end cards.
            background = android.graphics.drawable.LayerDrawable(arrayOf(rounded(BASE and 0xD9FFFFFF.toInt(), 14), glass(14)))
            addView(caps("Up next", TUNGSTEN).onArt())
            addView(text(next.title.ifBlank { "Episode ${next.episode}" }, SERIF_HERO, serif = true).onArt(),
                wrap().apply { topMargin = dp(6) })
            addView(text("S${next.season} E${next.episode}", sized(SMALL, 15f), color = TEXT_2).onArt(), wrap().apply { topMargin = dp(5) })
            countdownText = text("", sized(SMALL, 15f), color = TEXT_2).onArt()
            addView(countdownText, wrap().apply { topMargin = dp(12) })
            countdownBar = SeekBar(this@PlayerActivity).apply { isFocusable = false }
            addView(countdownBar, LinearLayout.LayoutParams(-1, dp(12)).apply { topMargin = dp(4) })
            addView(actions, wrap().apply { topMargin = dp(12) })
        }
        upNextPanel.addView(content, FrameLayout.LayoutParams(cardWidth(sized(500, 560)), -2, Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = dp(if (portrait) 16 else PAD); bottomMargin = dp(if (portrait) 24 else 68)
        })
        upNextPanel.visibility = View.VISIBLE
        playNow.requestFocus()
        countdownLeft = UP_NEXT_COUNTDOWN + COUNTDOWN_STEP
        main.removeCallbacks(countdownTask)
        countdownTask.run()
    }

    private fun dismissUpNext() {
        upNextDismissed = true
        main.removeCallbacks(countdownTask)
        upNextPanel.visibility = View.GONE
    }

    /** Hands the next episode to the sources page, which starts it (preferring this release's group) and finishes here. */
    private fun playNext() = nextVideo()?.let(::playEpisode) ?: finish()

    /** Hands [next] (the next episode, or one picked from the upright list) to the sources page, as [playNext] does. */
    private fun playEpisode(next: Video) {
        val videos = meta!!.videos
        val after = videos.getOrNull(videos.indexOf(next) + 1)?.takeIf { it.season != 0 }
        val name = intent.getStringExtra("name") ?: ""
        startActivity(Intent(this, StreamsActivity::class.java)
            .putExtra("type", type).putExtra("id", metaId).putExtra("name", name)
            .putExtra("poster", intent.getStringExtra("poster")).putExtra("background", intent.getStringExtra("background"))
            .putExtra("videoId", next.id).putExtra("nextVideoId", after?.id)
            .putExtra("label", "$name  ·  S${next.season} E${next.episode}")
            .putExtra("logo", meta?.logo).putExtra("still", next.thumbnail).putExtra("runtime", meta?.runtime)
            .putExtra("episode", "S${next.season} E${next.episode}  ·  ${next.title}"))
        finish()
    }

    /**
     * Switches the TV to a refresh rate the film divides into (24 Hz for 24 fps, 50 Hz for 25), as Just Player does, so
     * pans don't judder. Same resolution only; the window gives the mode back when the player closes.
     */
    private fun matchFrameRate(fps: Float) {
        if (fps <= 0f || !Settings.matchFrameRate(this)) return
        val display = window.decorView.display ?: return
        val now = display.mode
        fun fits(m: Display.Mode) = (m.refreshRate / fps).let { r -> r.roundToInt() >= 1 && abs(r - r.roundToInt()) < 0.0005f }
        if (fits(now)) return
        val best = display.supportedModes
            .filter { it.physicalWidth == now.physicalWidth && it.physicalHeight == now.physicalHeight && fits(it) }
            .minByOrNull { it.refreshRate } ?: return
        window.attributes = window.attributes.apply { preferredDisplayModeId = best.modeId }
    }

    private fun push() {
        if (!watched || player.isCurrentMediaItemLive) return
        val key = authKey ?: return
        val snapshot = item?.let { JSONObject(it.toString()) } ?: return
        background({ Stremio.saveLibrary(key, listOf(snapshot)) }) {}
    }

    // Search mid-film would leave the film: the button does nothing here.
    override fun onSearchRequested() = false

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // On the pause screen, a key only wakes it.
        if (::ambient.isInitialized && ambient.visibility == View.VISIBLE && event.action == KeyEvent.ACTION_DOWN) {
            hideAmbient()
            if (event.keyCode !in listOf(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY)) {
                showControls(playPause)
                wakeKey = event.keyCode
                return true
            }
        }
        if (upNextPanel.visibility == View.VISIBLE) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    wakeKey = KeyEvent.KEYCODE_BACK
                    dismissUpNext()
                }
                return true
            }
            return super.dispatchKeyEvent(event)
        }
        if (stallPanel.visibility == View.VISIBLE) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) dismissStall()
                return true
            }
            return super.dispatchKeyEvent(event)
        }
        val down = event.action == KeyEvent.ACTION_DOWN
        when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { if (down) { if (player.playWhenReady) player.pause() else player.play() }; return true }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { if (down) player.play(); return true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { if (down) player.pause(); return true }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { if (down) { showControls(bar); scrub(SKIP) }; return true }
            KeyEvent.KEYCODE_MEDIA_REWIND -> { if (down) { showControls(bar); scrub(-SKIP) }; return true }
        }
        if (options.visibility == View.VISIBLE) {
            if (event.keyCode != KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event)
            if (!down) closeOptions()
            return true
        }
        // The OK that woke the controls: its repeats and release would otherwise press the button it focused.
        // Skip intro: OK jumps past it while the pill shows over the picture (the controls down).
        if (skip.visibility == View.VISIBLE && !shown && down && event.keyCode in listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER)) {
            skipSegment()
            wakeKey = event.keyCode
            return true
        }
        if (wakeKey != 0 && event.keyCode == wakeKey) {
            if (!down) wakeKey = 0
            return true
        }
        if (!shown && down) when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> { showControls(bar); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                wakeKey = event.keyCode
                if (player.playWhenReady) player.pause() else player.play()
                showControls(playPause)
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> { showControls(); return true }
        }
        if (shown) showControls(null) // any key keeps them up a while longer
        return super.dispatchKeyEvent(event)
    }

    /** Jumps past the intro (or recap) the Skip pill offers. */
    private fun skipSegment() {
        (skip.tag as? Segment)?.takeIf { canSeek() }?.let { s ->
            if (s.jump == 0L) player.seekTo(s.end)
            else { segments = segments - s; player.seekTo(player.currentPosition + s.jump) } // the guess is used once
        }
        skip.visibility = View.GONE
    }

    // ---------- touch, on a phone ----------
    // A tap on the picture shows or hides the controls; two quick taps on its left or right third go back or ahead
    // 10 s (more taps, further); a tap on the Skip pill skips; dragging along the bar seeks.

    private var lastTap = 0L
    private var lastTapSide = 0
    private val singleTap = Runnable { if (shown) hideControls(force = true) else showControls(null) }

    override fun onBareTap(event: android.view.MotionEvent) {
        if (::ambient.isInitialized && ambient.visibility == View.VISIBLE) { hideAmbient(); showControls(playPause); return }
        if (options.visibility == View.VISIBLE) { closeOptions(); return }
        if (upNextPanel.visibility == View.VISIBLE || stallPanel.visibility == View.VISIBLE) return
        if (portrait && !under(view, event)) return // the title and episodes below the picture
        if (skip.visibility == View.VISIBLE && !shown && under(skip, event)) { skipSegment(); return }
        val side = when {
            event.rawX < screen.width / 3f -> -1
            event.rawX > screen.width * 2 / 3f -> 1
            else -> 0
        }
        val now = android.os.SystemClock.uptimeMillis()
        main.removeCallbacks(singleTap)
        if (side != 0 && side == lastTapSide && now - lastTap < DOUBLE_TAP) {
            lastTap = now
            showControls(bar)
            scrub(side * SKIP)
            return
        }
        lastTap = now
        lastTapSide = side
        // A side tap waits a moment to see if a second follows; a tap in the middle shows or hides at once.
        if (side == 0) singleTap.run() else main.postDelayed(singleTap, DOUBLE_TAP)
    }

    // A finger on the controls keeps them up a while longer, as any key does.
    override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
        if (shown && event.actionMasked == android.view.MotionEvent.ACTION_DOWN && player.playWhenReady) {
            main.removeCallbacks(hideTask)
            main.postDelayed(hideTask, hideAfter)
        }
        return super.dispatchTouchEvent(event)
    }

    private fun under(view: View, event: android.view.MotionEvent): Boolean {
        val at = IntArray(2).also { view.getLocationOnScreen(it) }
        return event.rawX >= at[0] && event.rawX < at[0] + view.width && event.rawY >= at[1] && event.rawY < at[1] + view.height
    }

    /** The finger on the bar at [fraction] of its length (-1: the drag was taken away); [done] when it lifts. */
    private fun dragTo(fraction: Float, done: Boolean) {
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return
        if (player.isCurrentMediaItemLive) return
        main.removeCallbacks(hideTask)
        main.removeCallbacks(seekTask)
        if (fraction < 0) { scrubTo = -1; endScrub(); return }
        if (scrubTo < 0) {
            if (!canSeek()) return
            scrubFrom = player.currentPosition
        }
        scrubTo = (fraction * duration).toLong().coerceIn(0, maxOf(0, duration - 1_000))
        updateProgress()
        showScrub()
        if (done) {
            seekTask.run()
            if (player.playWhenReady) main.postDelayed(hideTask, hideAfter)
        }
    }

    // ---------- skip intro, as NuvioTV does it ----------
    // IntroDB (api.introdb.app, community timings by IMDb id, season and episode; no key, nothing personal sent) says where
    // an episode's intro, recap and credits are; AniSkip (api.aniskip.com, by MyAnimeList id, Kitsu's ids mapped through
    // Kitsu) says the same for anime. Inside an intro or recap a "Skip intro" pill shows for 10 s, and OK jumps to its
    // end. The credits start, when known, is when Up next appears, rather than the last 4% guess. An episode neither
    // knows gets a plain "Skip 85 s" for its first minutes instead, used once. ponytail: films' credits aren't asked for.
    private class Segment(val label: String, val start: Long, val end: Long, val jump: Long = 0) // jump: ms forward, not to the end
    private class SkipTimes(val intro: Pair<Long, Long>?, val recap: Pair<Long, Long>?, val outro: Long?)
    private lateinit var skip: TextView
    private var segments = emptyList<Segment>()
    private var outroAt = 0L
    private var skipShownAt = 0L
    private val skipped = mutableSetOf<Segment>() // shown once and left alone, or skipped: not offered again this time
    private val skipTask = Runnable { checkSegments() }

    private fun fetchSegments() {
        if (type != "series") return
        val parts = videoId.split(":")
        val anime = animeEpisode(videoId)
        background({
            when {
                anime != null -> aniSkip(anime.first, anime.second, anime.third)
                metaId.startsWith("tt") && parts.size >= 3 -> introDb(parts[0], parts[1], parts[2])
                else -> null
            }
        }) { r ->
            r.exceptionOrNull()?.let { android.util.Log.w("Canopy", "skip times failed", it) }
            val times = r.getOrNull()
            segments = listOfNotNull(times?.recap?.let { Segment("Skip recap", it.first, it.second) },
                times?.intro?.let { Segment("Skip intro", it.first, it.second) })
                .ifEmpty { listOf(Segment("Skip ${GUESS_JUMP / 1000} s", 0, GUESS_WINDOW, jump = GUESS_JUMP)) }
            outroAt = times?.outro ?: 0L
        }
    }

    private fun introDb(imdb: String, season: String, episode: String): SkipTimes {
        val json = JSONObject(Http.get("https://api.introdb.app/segments?imdb_id=$imdb&season=$season&episode=$episode"))
        fun part(name: String) = json.optJSONObject(name)?.let { it.optDouble("start_sec", -1.0) to it.optDouble("end_sec", -1.0) }
            ?.takeIf { (a, b) -> a >= 0 && b > a }?.let { (a, b) -> (a * 1000).toLong() to (b * 1000).toLong() }
        return SkipTimes(part("intro"), part("recap"), part("outro")?.first)
    }

    private fun aniSkip(site: String, id: String, episode: Int): SkipTimes? {
        val mal = if (site == "mal") id else JSONObject(Http.get("https://kitsu.app/api/edge/anime/$id/mappings" +
            "?filter%5BexternalSite%5D=myanimelist%2Fanime", keep = true)).optJSONArray("data").objects()
            .map { it.optJSONObject("attributes") }.firstOrNull { it?.optString("externalSite") == "myanimelist/anime" }
            ?.optString("externalId")?.ifBlank { null } ?: return null
        val json = JSONObject(Http.get("https://api.aniskip.com/v2/skip-times/$mal/$episode" +
            "?types%5B%5D=op&types%5B%5D=mixed-op&types%5B%5D=recap&types%5B%5D=ed&types%5B%5D=mixed-ed&episodeLength=0"))
        val found = json.optJSONArray("results").objects().mapNotNull { r ->
            val i = r.optJSONObject("interval") ?: return@mapNotNull null
            val (a, b) = i.optDouble("startTime", -1.0) to i.optDouble("endTime", -1.0)
            if (a < 0 || b <= a) null else r.optString("skipType") to ((a * 1000).toLong() to (b * 1000).toLong())
        }.toMap()
        return SkipTimes(found["op"] ?: found["mixed-op"], found["recap"], (found["ed"] ?: found["mixed-ed"])?.first)
    }

    private fun checkSegments() {
        main.removeCallbacks(skipTask)
        if (!started || player.isCurrentMediaItemLive) return run { main.postDelayed(skipTask, 500) }
        val position = player.currentPosition
        val now = segments.firstOrNull { position >= it.start && position < it.end - 1_000 && it !in skipped }
        if (now == null || shown) skip.visibility = View.GONE
        else if (skip.tag !== now || skip.visibility != View.VISIBLE) {
            if (skip.tag !== now) skipShownAt = System.currentTimeMillis()
            skip.tag = now
            skip.text = "${now.label}   OK"
            skip.visibility = View.VISIBLE
        }
        if (now != null && now.jump == 0L && skip.tag === now && System.currentTimeMillis() - skipShownAt > SKIP_SHOWN) { skipped += now; skip.visibility = View.GONE }
        if (outroAt > 0 && position >= outroAt && player.duration - position < 60_000 &&
            !upNextDismissed && upNextPanel.visibility != View.VISIBLE && player.duration > 0) showUpNext()
        main.postDelayed(skipTask, 500)
    }

    // ---------- the pause screen ----------
    // Paused a while with no key pressed: the controls fade, the picture dims, and a calm card says what this is,
    // what happens in it, and when it would end. Any key brings the controls straight back.
    private lateinit var ambient: FrameLayout
    private lateinit var ambientKicker: TextView
    private lateinit var ambientTitle: TextView
    private lateinit var ambientAbout: TextView
    private lateinit var ambientEnds: TextView
    private val ambientTask = Runnable { showAmbient() }

    private fun buildAmbient(): FrameLayout {
        ambientKicker = caps("Paused", TUNGSTEN).onArt()
        ambientTitle = text("", SERIF_HERO, serif = true).onArt().apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        ambientAbout = text("", BODY, color = TEXT_2).onArt().apply { maxLines = 3; ellipsize = TextUtils.TruncateAt.END }
        ambientEnds = text("", sized(SMALL, 16f), color = TEXT).onArt()
        ambient = FrameLayout(this).apply {
            visibility = View.GONE
            alpha = 0f
            addView(View(context).apply {
                background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(baseAt(0xF2), baseAt(0x99), baseAt(0x73)))
            }, FrameLayout.LayoutParams(-1, -1))
            addView(TextClock(this@PlayerActivity).apply {
                format12Hour = "h:mm a"; format24Hour = "HH:mm"
                typeface = text("", SERIF_TITLE, serif = true).typeface
                textSize = SERIF_TITLE
                setTextColor(TEXT)
            }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { topMargin = dp(30); rightMargin = dp(PAD) })
            addView(column(Gravity.START).apply {
                addView(ambientKicker)
                addView(ambientTitle, LinearLayout.LayoutParams(dp(620), -2).apply { topMargin = dp(8) })
                addView(ambientAbout, LinearLayout.LayoutParams(dp(560), -2).apply { topMargin = dp(10) })
                addView(ambientEnds, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(16) })
            }, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { leftMargin = dp(PAD); bottomMargin = dp(PAD + 16) })
        }
        return ambient
    }

    private fun showAmbient() {
        if (isFinishing || player.playWhenReady || !started) return
        if (options.visibility == View.VISIBLE || upNextPanel.visibility == View.VISIBLE || stallPanel.visibility == View.VISIBLE) return
        val episode = meta?.videos?.firstOrNull { it.id == videoId }
        ambientKicker.text = listOfNotNull("PAUSED", subtitle.text.toString().takeIf { it.isNotBlank() }?.uppercase()).joinToString(DOT)
        ambientTitle.text = pauseTitle.text
        val about = episode?.overview?.takeIf { it.isNotBlank() } ?: meta?.description
        ambientAbout.text = about.orEmpty()
        ambientAbout.visibility = if (about.isNullOrBlank()) View.GONE else View.VISIBLE
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
        val remaining = (duration - player.currentPosition).coerceAtLeast(0)
        ambientEnds.text = if (duration <= 0 || player.isCurrentMediaItemLive) ""
            else "Ends ${SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(System.currentTimeMillis() + remaining))} if you carry on"
        hideControls(force = true)
        pausedShade.visibility = View.GONE
        ambient.visibility = View.VISIBLE
        ambient.animate().cancel()
        ambient.animate().alpha(1f).setDuration(Motion.SLOW).setInterpolator(Motion.EASE).start()
    }

    private fun hideAmbient() {
        if (!::ambient.isInitialized || ambient.visibility != View.VISIBLE) return
        if (!player.playWhenReady) pausedShade.visibility = View.VISIBLE
        ambient.animate().cancel()
        ambient.animate().alpha(0f).setDuration(Motion.QUICK).setInterpolator(Motion.EASE).withEndAction { ambient.visibility = View.GONE }.start()
    }

    // ---------- player controls ----------
    /** On a phone (held at arm's length, tapped with a thumb) the controls' words and buttons are larger than the TV's. */
    private fun sized(tv: Float, phone: Float) = if (touch) phone else tv
    private fun sized(tv: Int, phone: Int) = if (touch) phone else tv
    private val hideAfter get() = if (touch) HIDE_TOUCH else HIDE
    /** On a phone, a pill or chip a thumb can hit, with words read at arm's length. */
    private fun <T : TextView> T.thumb() = apply {
        if (!touch) return@apply
        textSize = 16f; minHeight = dp(48)
        setPaddingRelative(paddingStart + dp(6), paddingTop, paddingEnd + dp(6), paddingBottom)
    }

    // ---------- picture size: Fit, Fill (crop to the screen) or Stretch; kept for next time ----------
    private val fits = listOf("Fit" to AspectRatioFrameLayout.RESIZE_MODE_FIT, "Fill screen" to AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
        "Stretch" to AspectRatioFrameLayout.RESIZE_MODE_FILL)
    private fun fitIndex() = getSharedPreferences("player", MODE_PRIVATE).getInt(Settings.profileKey(this, "fit"), 0).coerceIn(0, fits.lastIndex)
    private fun setFit(index: Int, say: Boolean = true) {
        getSharedPreferences("player", MODE_PRIVATE).edit().putInt(Settings.profileKey(this, "fit"), index).apply()
        view.resizeMode = fits[index].second
        if (say) flash(fits[index].first)
    }

    /** A word in the middle at the top, for a moment: the notice's corner is the clock's on a phone. */
    private fun flash(word: String) {
        screen.findViewWithTag<View>("flash")?.let { it.animate().cancel(); screen.removeView(it) }
        val label = text(word, sized(BODY, 18f), bold = true).apply {
            tag = "flash"
            setPadding(dp(18), dp(10), dp(18), dp(10))
            background = rounded(baseAt(0xE6), CORNER)
            elevation = dp(12).toFloat()
        }
        screen.addView(label, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(24) })
        label.animate().alpha(0f).setStartDelay(1_200).setDuration(Motion.STANDARD)
            .withEndAction { screen.removeView(label) }.start()
    }

    private lateinit var controls: FrameLayout
    private lateinit var bar: SeekBar
    private lateinit var elapsed: TextView
    private lateinit var length: TextView
    private lateinit var playPause: TextView
    private var bigPlay: TextView? = null // a phone's Play in the middle of the picture, with back and ahead 10 s beside it
    private lateinit var audioButton: TextView
    private lateinit var endsAt: TextView // "  ·  Ends 11:20 AM" beside the clock, top right
    private lateinit var nextCell: FrameLayout
    private lateinit var startOverCell: FrameLayout
    private lateinit var playerLogo: ImageView
    private lateinit var topName: TextView
    private lateinit var pauseTitle: TextView
    private lateinit var pauseInfo: LinearLayout
    private lateinit var pauseFacts: TextView
    private lateinit var subtitle: TextView // the year or S5 E2
    private val buttonLabels = mutableMapOf<View, TextView>()
    private var shown = false
    private var wakeKey = 0
    private var scrubTo = -1L
    private val hideTask = Runnable { hideControls() }
    private val progressTask = Runnable { updateProgress() }
    private val seekTask = Runnable { if (scrubTo >= 0) { player.seekTo(scrubTo); scrubTo = -1 }; endScrub() }
    /** Where the current scrub started (-1: not scrubbing), and the time readout that floats over the bar meanwhile. */
    private var scrubFrom = -1L
    private lateinit var scrubBubble: LinearLayout
    private lateinit var scrubTime: TextView
    private lateinit var scrubJump: TextView

    private fun buildControls(): FrameLayout {
        val name = intent.getStringExtra("name").orEmpty()
        playerLogo = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_START; visibility = View.GONE }
        topName = text(name, SERIF_TITLE, serif = true).onArt().apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        pauseTitle = text(intent.getStringExtra("episode")?.substringAfter("  ·  ", "")?.takeIf { it.isNotBlank() }
            ?: name, SERIF_HERO, serif = true).onArt().apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        subtitle = text(intent.getStringExtra("episode")?.substringBefore("  ·  ").orEmpty(), sized(SMALL, 15f), color = TEXT_2).onArt()
        pauseFacts = text("", sized(SMALL, 15f), color = TEXT_2).onArt()
        pauseInfo = column(Gravity.START).apply {
            addView(caps("Paused", TUNGSTEN).onArt())
            addView(pauseTitle, LinearLayout.LayoutParams(dp(820), -2).apply { topMargin = dp(6) }) // most of the width: a long name was cut at half
            addView(pauseFacts, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })
        }

        endsAt = text("", sized(SMALL, 17f), color = TEXT).onArt().apply { maxLines = 1 }
        elapsed = text("", sized(SMALL, 16f), bold = touch).onArt()
        length = text("", sized(SMALL, 16f), bold = touch).onArt()
        bar = SeekBar(this).apply {
            id = View.generateViewId()
            isFocusable = true; isFocusableInTouchMode = true
            contentDescription = "Seek"
            setOnFocusChangeListener { _, _ -> invalidate() }
            setOnClickListener { if (player.playWhenReady) player.pause() else player.play() }
            if (touch) onDrag = ::dragTo
            setOnKeyListener { _, code, event ->
                val by = when (code) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> -1
                    KeyEvent.KEYCODE_DPAD_RIGHT -> 1
                    else -> return@setOnKeyListener false
                }
                if (event.action == KeyEvent.ACTION_DOWN)
                    scrub(by * when { event.repeatCount < 10 -> SKIP; event.repeatCount < 30 -> 30_000L; else -> 60_000L })
                true
            }
        }

        audioButton = button("Audio & subtitles", R.drawable.ic_subtitles) { openOptions() }
        val streamButton = button("Change stream", R.drawable.ic_layers) { changeStream() }
        playPause = button("Pause", R.drawable.ic_pause, primary = true) {
            if (player.playWhenReady) player.pause() else player.play()
        }
        val nextButton = button("Next episode", R.drawable.ic_next_episode) { playNext() }
        val infoButton = button("Info", R.drawable.ic_info) {
            startActivity(Intent(this, DetailsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("type", type).putExtra("id", metaId)
                .putExtra("name", intent.getStringExtra("name"))
                .putExtra("poster", intent.getStringExtra("poster"))
                .putExtra("background", intent.getStringExtra("background")))
            finish()
        }
        // Two a side, so Play sits in the middle: Next episode when there is one, else Start over in its place.
        val startOverButton = button("Start over", R.drawable.ic_restart) { if (canSeek()) { player.seekTo(0); player.play() } }
        val fitButton = button("Picture size", R.drawable.ic_aspect) { setFit((fitIndex() + 1) % fits.size) }
        nextCell = buttonCell(nextButton).apply { visibility = View.GONE }
        startOverCell = buttonCell(startOverButton).apply { if (touch) visibility = View.GONE }
        val windowButton = button("Window", R.drawable.ic_pip) { enterWindow() }
        lockButton = button("Keep sideways", R.drawable.ic_lock_rotation) { setSideways(!sideways()) }
        val buttons = LinearLayout(this).apply {
            clipChildren = false // a focused button's label is wider than its cell
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
            addView(buttonCell(audioButton))
            addView(buttonCell(streamButton))
            addView(View(context).apply { setBackgroundColor(GLASS_EDGE) },
                LinearLayout.LayoutParams(dp(1), dp(22)).apply { topMargin = dp(sized(14, 22)); marginEnd = dp(10) })
            // On a phone Play is in the middle of the picture instead, big, for a thumb.
            if (!touch) addView(buttonCell(playPause, true))
            if (!touch) addView(View(context).apply { setBackgroundColor(GLASS_EDGE) },
                LinearLayout.LayoutParams(dp(1), dp(22)).apply { topMargin = dp(14); marginStart = dp(10) })
            addView(nextCell)
            addView(startOverCell)
            // A phone leaves Info out: its Back arrow goes to the same title page.
            if (!touch) addView(buttonCell(infoButton))
            if (touch) addView(buttonCell(fitButton))
            if (windowAllowed()) addView(buttonCell(windowButton))
            if (touch) addView(buttonCell(lockButton))
        }
        dockButtons = buttons
        if (touch) paintLock()
        for (button in listOf(audioButton, streamButton, playPause, nextButton, startOverButton, infoButton, fitButton, windowButton, lockButton))
            button.nextFocusUpId = bar.id
        playPause.id = View.generateViewId()
        bar.nextFocusDownId = playPause.id

        return FrameLayout(this).apply {
            visibility = View.GONE
            alpha = 0f
            clipChildren = false // the scrub bubble rides above the dock's top
            addView(View(context).apply {
                background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(baseAt(0x99), baseAt(0x00)))
            }, FrameLayout.LayoutParams(-1, dp(120), Gravity.TOP))
            bottomShade = View(context).apply {
                background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                    intArrayOf(baseAt(0xF2), baseAt(0xB3), baseAt(0x00)))
            }
            addView(bottomShade, FrameLayout.LayoutParams(-1, dp(280), Gravity.BOTTOM))
            // A phone has no remote's Back: a Back button top left, the title moved along beside it.
            val nameAt = if (touch) PAD + 70 else PAD
            if (touch) addView(button("Back", R.drawable.ic_back) { press(KeyEvent.KEYCODE_BACK) }.apply {
                buttonLabels.remove(this) // no word under it: the arrow says it
                foreground = centred(R.drawable.ic_back, 32, TEXT)
                background = null
            }, FrameLayout.LayoutParams(dp(62), dp(62)).apply { leftMargin = dp(PAD - 10); topMargin = dp(16) })
            addView(playerLogo, FrameLayout.LayoutParams(dp(sized(150, 280)), dp(sized(50, 70))).apply {
                leftMargin = dp(nameAt); topMargin = dp(sized(26, 18))
            })
            addView(topName, FrameLayout.LayoutParams(dp(250), -2).apply {
                leftMargin = dp(nameAt); topMargin = dp(31)
            })
            // A phone: top right, Full screen while upright, Upright while sideways.
            if (touch) turnButton = button("Full screen", R.drawable.ic_fullscreen) { turn() }.apply {
                buttonLabels.remove(this)
                foreground = centred(R.drawable.ic_fullscreen, 32, TEXT)
                background = null
            }.also { addView(it, FrameLayout.LayoutParams(dp(62), dp(62), Gravity.TOP or Gravity.END).apply { rightMargin = dp(PAD - 10); topMargin = dp(16) }) }
            // The time now, then when this finishes if it played straight through from here.
            clockRow = LinearLayout(context)
            addView(clockRow.apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(TextClock(this@PlayerActivity).apply {
                    format12Hour = "h:mm a"; format24Hour = "HH:mm"
                    textSize = sized(SMALL, 17f); setTextColor(if (touch) TEXT else TEXT_2); typeface = text("", SMALL).typeface
                    if (touch) setShadowLayer(dp(4).toFloat(), 0f, dp(1).toFloat(), 0xCC000000.toInt()) // reads over a bright sky
                })
                addView(endsAt)
            }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
                topMargin = dp(sized(34, 36)); rightMargin = dp(if (touch) PAD + 62 else PAD)
            })
            // Straight on the picture, no panel: what's playing at the lower left, a full-width timeline with the times
            // under its ends, then the buttons in the middle.
            dock = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                clipChildren = false
                clipToPadding = false
                addView(pauseInfo, LinearLayout.LayoutParams(-1, -2))
                // The bar, with the scrub time floating above its head while a seek is being lined up.
                scrubTime = text("", sized(BODY, 22f), bold = true).onArt().apply { gravity = Gravity.CENTER; maxLines = 1 }
                scrubJump = text("", sized(SMALL, 15f), color = ICE).onArt().apply { gravity = Gravity.CENTER; maxLines = 1 }
                scrubBubble = column(Gravity.CENTER_HORIZONTAL, scrubTime, scrubJump).apply {
                    setPadding(dp(sized(12, 18)), dp(sized(5, 8)), dp(sized(12, 18)), dp(sized(6, 9)))
                    background = if (touch) GradientDrawable().apply {
                        setColor(baseAt(0xF5)); setStroke(dp(2), TUNGSTEN); cornerRadius = dp(CORNER).toFloat()
                    } else android.graphics.drawable.LayerDrawable(arrayOf(rounded(baseAt(0xD9), 2), glass(2)))
                    elevation = if (touch) dp(12).toFloat() else 0f
                    alpha = 0f
                }
                addView(FrameLayout(context).apply {
                    clipChildren = false
                    clipToPadding = false
                    addView(bar, FrameLayout.LayoutParams(-1, -1))
                    addView(scrubBubble, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START))
                }, LinearLayout.LayoutParams(-1, dp(sized(26, 44))).apply { topMargin = dp(sized(14, 8)) })
                addView(LinearLayout(context).apply {
                    addView(elapsed)
                    addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
                    addView(length)
                }, LinearLayout.LayoutParams(-1, -2))
                addView(buttons, LinearLayout.LayoutParams(-1, dp(sized(76, 84))).apply { topMargin = dp(sized(2, 4)) })
            }
            if (touch) addView(LinearLayout(context).apply {
                bigRow = this
                gravity = Gravity.CENTER_VERTICAL
                fun big(control: TextView, icon: Int, size: Int, iconSize: Int, tint: Int) = control.apply {
                    buttonLabels.remove(this)
                    foreground = centred(icon, iconSize, if (tint == BASE) TEXT else tint)
                    // Bare icons over the picture (Daniel's pick): Play on a faint dark disc that the picture shows through.
                    background = if (tint == BASE) GradientDrawable().apply {
                        shape = GradientDrawable.OVAL; setColor(baseAt(0x80)); setStroke(dp(1), withAlpha(TEXT, 0x59))
                    } else null
                    elevation = 0f
                    setOnFocusChangeListener(null) // no solid face on focus: a finger needs no focus mark
                }.also { addView(it, LinearLayout.LayoutParams(dp(size), dp(size)).apply { marginStart = dp(28); marginEnd = dp(28) }) }
                big(button("Back 10 seconds", R.drawable.ic_replay10) { if (canSeek()) { showControls(null); scrub(-SKIP) } }, R.drawable.ic_replay10, 66, 44, TEXT)
                bigPlay = big(button("Pause", R.drawable.ic_pause, primary = true) {
                    if (player.playWhenReady) player.pause() else player.play()
                }, R.drawable.ic_pause, 84, 46, BASE)
                big(button("Ahead 10 seconds", R.drawable.ic_forward10) { if (canSeek()) { showControls(null); scrub(SKIP) } }, R.drawable.ic_forward10, 66, 44, TEXT)
            }, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER).apply { bottomMargin = dp(50) }) // centred in the picture above the dock
            addView(dock, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
                leftMargin = dp(sized(DOCK_INSET, PAD)); rightMargin = dp(sized(DOCK_INSET, PAD)); bottomMargin = dp(sized(DOCK_INSET - 4, 10))
            })
        }
    }

    /** An icon of [size] dp in the middle of whatever it's drawn over (a button's circle). */
    private fun centred(res: Int, size: Int, tint: Int) = getDrawable(res)?.mutate()?.let { d ->
        d.setTint(tint)
        android.graphics.drawable.LayerDrawable(arrayOf(d)).apply {
            setLayerSize(0, dp(size), dp(size)); setLayerGravity(0, Gravity.CENTER)
        }
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility") // the touch listener only dims; clicks go on as usual
    private fun button(label: String, res: Int, primary: Boolean = false, onClick: () -> Unit): TextView {
        // On a phone every button says its name: there's no focus to show it.
        val words = text(label, sized(SMALL, 17f), color = TEXT).onArt().apply {
            gravity = Gravity.CENTER; visibility = if (touch) View.VISIBLE else View.INVISIBLE
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        val radius = CORNER // square-cornered: no round buttons
        val control = text("", BODY).apply {
            gravity = Gravity.CENTER
            contentDescription = label
            isFocusable = true; isFocusableInTouchMode = true
            foreground = centred(res, if (primary) 25 else sized(20, 30), if (primary) BASE else TEXT)
            if (primary) {
                // White at rest, mint and glowing with the remote on it.
                fun face(focused: Boolean) = GradientDrawable().apply {
                    setColor(if (focused) TUNGSTEN else TEXT); cornerRadius = dp(minOf(radius, CORNER)).toFloat()
                }
                background = face(false)
                if (android.os.Build.VERSION.SDK_INT >= 28) outlineSpotShadowColor = TUNGSTEN_GLOW
                setOnFocusChangeListener { view, focused ->
                    view.background = face(focused)
                    view.elevation = if (focused) dp(10).toFloat() else 0f // a mint glow under the focused Play
                    view.grow(focused).start()
                    words.visibility = if (focused || touch) View.VISIBLE else View.INVISIBLE
                }
            } else {
                // Light line: a quiet glass square, outlined in white with the remote on it. A phone: the bare icon
                // over the picture (Daniel's pick), dimming under the finger.
                background = if (touch) null else rounded(0x1FFFFFFF, radius)
                if (touch) setOnTouchListener { view, event ->
                    when (event.actionMasked) {
                        android.view.MotionEvent.ACTION_DOWN -> view.alpha = 0.5f
                        android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> view.alpha = 1f
                    }
                    false
                }
                setOnFocusChangeListener { view, focused ->
                    if (!touch) view.background = if (focused) outline(true) else rounded(0x1FFFFFFF, radius)
                    if (!touch) view.grow(focused).start()
                    words.visibility = if (focused || touch) View.VISIBLE else View.INVISIBLE
                }
            }
            setOnClickListener { onClick() }
        }
        buttonLabels[control] = words
        return control
    }

    private fun buttonCell(control: TextView, primary: Boolean = false) = FrameLayout(this).apply {
        clipChildren = false
        clipToPadding = false
        // The small buttons sit 5dp lower, so every button's middle is on one line with the bigger Play's; the labels
        // all hang from the same line under them.
        val size = if (primary) 50 else sized(40, 62)
        addView(control, FrameLayout.LayoutParams(dp(size), dp(size),
            Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(if (primary) 0 else sized(5, 2)) })
        addView(buttonLabels.getValue(control), FrameLayout.LayoutParams(dp(sized(160, 150)), dp(sized(18, 28)),
            Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(sized(53, 52)) })
        layoutParams = LinearLayout.LayoutParams(dp(if (primary) 70 else sized(62, 150)), dp(sized(76, 82)))
    }

    /** Brings the controls up with [focus] focused (null: leaves focus where it is) and restarts the time to hide. */
    private fun showControls(focus: View? = playPause) {
        if (inWindow) return // the window has Android's own controls
        main.removeCallbacks(hideTask)
        if (!shown) {
            shown = true
            view.subtitleView?.setBottomPaddingFraction(0.36f) // keep captions above the dock
            controls.visibility = View.VISIBLE
            controls.animate().alpha(1f).setDuration(Motion.QUICK).setInterpolator(Motion.EASE).start()
            updateProgress()
            // Its mark follows the player, whatever started it (a resume plays before the listener hears of it).
            bigPlay?.foreground = centred(if (player.playWhenReady) R.drawable.ic_pause else R.drawable.ic_play, 46, TEXT)
            if (touch && !portrait) loadingCenter.translationY = -dp(130).toFloat()
            (focus ?: bigPlay ?: playPause).requestFocus()
        } else focus?.requestFocus()
        if (player.playWhenReady) main.postDelayed(hideTask, hideAfter)
    }

    private fun hideControls(force: Boolean = false) {
        if (!force && !player.playWhenReady) return
        shown = false
        if (touch) loadingCenter.translationY = 0f
        main.removeCallbacks(hideTask)
        main.removeCallbacks(progressTask)
        controls.animate().alpha(0f).setDuration(Motion.STANDARD).setInterpolator(Motion.EASE).withEndAction {
            if (!shown) {
                controls.visibility = View.GONE
                view.subtitleView?.setBottomPaddingFraction(0.08f)
            }
        }.start()
    }

    private fun updateProgress() {
        main.removeCallbacks(progressTask)
        if (!shown && options.visibility != View.VISIBLE) return
        val live = player.isCurrentMediaItemLive
        val duration = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L
        val position = if (scrubTo >= 0) scrubTo else player.currentPosition
        if (shown) {
            bar.set(position, player.bufferedPosition, if (live) 0L else duration)
            elapsed.text = if (live) "Live" else clock(position)
            length.text = if (live || duration <= 0) "" else "−${clock((duration - position).coerceAtLeast(0))}"
        }
        if (options.visibility == View.VISIBLE) {
            optionsBar.set(position, player.bufferedPosition, if (live) 0L else duration)
            optionsElapsed.text = if (live) "Live" else clock(position)
            optionsLeft.text = if (live || duration <= 0) "" else "−${clock((duration - position).coerceAtLeast(0))}"
        }
        val remaining = (duration - position).coerceAtLeast(0)
        val end = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(System.currentTimeMillis() + remaining))
        endsAt.text = if (live || duration <= 0) "" else "${DOT}Ends $end"
        pauseFacts.text = listOfNotNull(subtitle.text.toString().takeIf { it.isNotBlank() },
            if (live || duration <= 0) null else runtime("${(remaining / 60_000).coerceAtLeast(1)} min") + " left").joinToString(DOT)
        main.postDelayed(progressTask, 500)
    }

    /** The time a scrub will land on, above the bar's head, with how far it jumps under it; the start stays marked. */
    private fun showScrub() {
        if (scrubTo < 0 || scrubFrom < 0) return
        bar.from = scrubFrom
        scrubTime.text = clock(scrubTo)
        val jump = scrubTo - scrubFrom
        scrubJump.text = (if (jump < 0) "\u2212" else "+") + clock(kotlin.math.abs(jump))
        scrubBubble.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val w = scrubBubble.measuredWidth
        scrubBubble.translationX = (bar.xOf(scrubTo) - w / 2f).coerceIn(0f, maxOf(0f, bar.width - w.toFloat()))
        scrubBubble.translationY = -(scrubBubble.measuredHeight + dp(sized(8, 4))).toFloat()
        // A finger moves it many times a frame: restarting a fade each time kept it faint, so a phone shows it at once.
        if (touch) { scrubBubble.animate().cancel(); scrubBubble.alpha = 1f }
        else if (scrubBubble.alpha < 1f) scrubBubble.animate().alpha(1f).setDuration(Motion.QUICK).start()
    }

    /** The seek landed (or was dropped): the readout and the start mark go. */
    private fun endScrub() {
        scrubFrom = -1
        bar.from = -1
        scrubBubble.animate().alpha(0f).setDuration(Motion.STANDARD).start()
    }

    private fun clock(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    /** Moves the playing point by [by] ms; the seek itself waits until the presses stop (SEEK_SETTLE). */
    private fun scrub(by: Long) {
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return
        if (player.isCurrentMediaItemLive || !canSeek()) return
        if (scrubTo < 0) scrubFrom = player.currentPosition
        scrubTo = ((if (scrubTo >= 0) scrubTo else player.currentPosition) + by).coerceIn(0, maxOf(0, duration - 1_000))
        updateProgress()
        showScrub()
        main.removeCallbacks(seekTask)
        main.postDelayed(seekTask, SEEK_SETTLE)
    }

    /** Whether this file can jump to another second. A seek in one that can't (no index, even by bitrate) lands on
     *  0:00, so it's refused with a word instead; once per source. */
    private fun canSeek(): Boolean {
        if (player.isCurrentMediaItemSeekable) return true
        if (!toldUnseekable) {
            toldUnseekable = true
            notice("Can't skip ahead in this source", "Change stream to pick one that can.")
        }
        return false
    }
    private var toldUnseekable = false

    /** The source list for this video, from this second; the player closes, and a pick starts a new one. */
    private fun changeStream() {
        startActivity(Intent(this, if (touch) PhoneStreamsActivity::class.java else StreamsActivity::class.java).putExtras(intent).apply {
            removeExtra("picked"); removeExtra("maxHeight")
            putExtra("pick", true)
            if (!player.isCurrentMediaItemLive) putExtra("resumeAt", player.currentPosition)
        })
        finish()
    }

    // ---------- playback options, after design/player-options-cinematic-minimal-glass.png ----------
    // A panel down the left over a darkening: the audio tracks, the subtitles (Off first), and Change stream with the
    // source playing. The chosen track has a tick; OK on another switches to it there and then. Back closes it.
    private lateinit var options: FrameLayout
    private lateinit var optionsBar: SeekBar
    private lateinit var optionsElapsed: TextView
    private lateinit var optionsLeft: TextView

    private fun openOptions() {
        hideControls(force = true)
        controls.visibility = View.GONE
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        list.addView(text("Audio & subtitles", SERIF_TITLE, serif = true), wrap())
        list.addView(text(listOfNotNull(pauseTitle.text.toString().takeIf { it.isNotBlank() },
            subtitle.text.toString().takeIf { it.isNotBlank() }).joinToString(DOT),
            sized(SMALL, 15f), color = TEXT_2).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, wrap().apply { topMargin = dp(5) })
        // Picture size first on a phone, where the screen is wider than the film.
        if (touch) section(list, "Picture size", fits.map { it.first }, fitIndex()) { i -> setFit(i, say = false) }

        val audio = audioGroups.filter { it.isTrackSupported(0) }
        if (audio.isNotEmpty()) section(list, "Audio", audio.map { trackName(it.getTrackFormat(0), true) }, audio.indexOfFirst { it.isSelected }) { i ->
            audioPicked = true
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setOverrideForType(TrackSelectionOverride(audio[i].mediaTrackGroup, (0 until audio[i].length).toList())).build()
            Languages.code(audio[i].getTrackFormat(0).language)?.let { wantAudio = it; Settings.pickedInPlayer(this, metaId, audio = it) }
        }
        val texts = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT && it.isTrackSupported(0) }
        val subs = texts.filterNot(::isAddonSub)
        val loaded = texts.firstOrNull(::isAddonSub) // the addon file attached now, if any
        val chosen = when {
            C.TRACK_TYPE_TEXT in player.trackSelectionParameters.disabledTrackTypes -> 0
            loaded?.isSelected == true -> 1 + subs.size + addonSubs.indexOf(chosenSub)
            else -> subs.indexOfFirst { it.isSelected } + 1
        }
        val subsFirst = section(list, "Subtitles", listOf("Off") + subs.map { trackName(it.getTrackFormat(0), false) + "  ·  in the file" } +
            addonSubs.map { "${it.language}  ·  ${it.addon}" }, chosen) { i ->
            val p = player.trackSelectionParameters.buildUpon()
            val sub = addonSubs.getOrNull(i - 1 - subs.size)
            remember(if (i == 0) "" else sub?.lang ?: subs[i - 1].getTrackFormat(0).language, addonFile = sub != null)
            player.trackSelectionParameters = when {
                i == 0 -> p.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
                sub == null -> p.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .setOverrideForType(TrackSelectionOverride(subs[i - 1].mediaTrackGroup, 0)).build()
                sub == chosenSub && loaded != null -> p.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .setOverrideForType(TrackSelectionOverride(loaded.mediaTrackGroup, 0)).build()
                else -> { chosenSub = sub; subDelay = 0; subFile = null; selectAddonSub = true; load(position()); return@section }
            }
        }
        val shiftable = chosenSub?.takeIf { loaded?.isSelected == true && it.mimeType != MimeTypes.TEXT_SSA }
        if (shiftable != null) {
            val steps = listOf(-2_000L, -1_000L, -500L, 0L, 500L, 1_000L, 2_000L)
            section(list, "Subtitle timing", steps.map { if (it == 0L) "On time" else "%+.1f s".format(it / 1000f) + if (it < 0) "  earlier" else "  later" },
                steps.indexOf(subDelay)) { i -> shiftSubtitles(shiftable, steps[i]) }
        }
        list.addView(View(this).apply { setBackgroundColor(GLASS_EDGE) }, LinearLayout.LayoutParams(-1, dp(1)).apply { setMargins(0, dp(18), 0, dp(10)) })
        list.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val head = text("Change stream  →", sized(BODY, 17f), bold = true).apply { setPadding(dp(8), dp(5), dp(8), dp(5)) }
            addView(head, wrap())
            // "2160p · Torrentio" as the mock reads it: what you're watching first, where it comes from last.
            val label = sources[at].label.split(DOT.trim()).map { it.trim() }.filter { it.isNotEmpty() }
            addView(text((label.drop(1) + label.take(1)).joinToString(DOT), sized(SMALL, 14f), color = TEXT_3).apply { setPadding(dp(8), 0, 0, 0) }, wrap())
            setPadding(0, 0, 0, dp(6))
            rowFocus(8)
            setOnClickListener { changeStream() }
        }, LinearLayout.LayoutParams(-1, -2))

        options.removeAllViews()
        options.addView(ScrollView(this).apply {
            // A phone: the rows below the edge fade out, so the list reads as one that scrolls.
            isVerticalScrollBarEnabled = touch
            if (touch) { isVerticalFadingEdgeEnabled = true; setFadingEdgeLength(dp(48)) }
            addView(list)
            setPadding(dp(22), dp(20), dp(22), dp(22))
            clipToPadding = false
            background = android.graphics.drawable.LayerDrawable(arrayOf(rounded(baseAt(0xF0), 14), glass(14)))
        }, FrameLayout.LayoutParams(cardWidth(sized(310, 470)), screen.height.takeIf { it > 0 }.let { it ?: resources.displayMetrics.heightPixels } - dp(sized(54, 32))).apply {
            leftMargin = dp(if (portrait) 16 else sized(27, PAD)); topMargin = dp(sized(27, 16))
        })
        optionsBar = SeekBar(this).apply { isFocusable = false }
        optionsElapsed = text("", sized(SMALL, 15f), color = TEXT_2).onArt()
        optionsLeft = text("", sized(SMALL, 15f), color = TEXT_2).onArt()
        options.addView(optionsBar, FrameLayout.LayoutParams(dp(280), dp(12), Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = dp(PAD); bottomMargin = dp(sized(48, 52))
        })
        options.addView(optionsElapsed, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = dp(PAD + sized(246, 236)); bottomMargin = dp(27)
        })
        options.addView(optionsLeft, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = dp(PAD); bottomMargin = dp(27)
        })
        options.visibility = View.VISIBLE
        updateProgress()
        subsFirst.requestFocus()
    }

    /** Rows stay on clear glass; a tungsten rim and tick carry focus and selection. */
    private fun section(list: LinearLayout, heading: String, labels: List<String>, chosen: Int, pick: (Int) -> Unit): View {
        // The heading names the choice in use ("SUBTITLES · SPANISH"), so it reads before any scrolling.
        fun headingFor(index: Int) = (listOf(heading) + listOfNotNull(labels.getOrNull(index)?.substringBefore("  ·  "))).joinToString(" · ")
        val title = caps(headingFor(chosen), TUNGSTEN).apply { if (touch) textSize = 16f }
        list.addView(title, wrap().apply {
            topMargin = dp(18); bottomMargin = dp(5)
        })
        val checks = mutableListOf<View>()
        val names = mutableListOf<TextView>()
        val rows = labels.mapIndexed { index, label ->
            val name = text(label.substringBefore("  ·  "), sized(BODY, 20f), color = TEXT_2).apply {
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            }
            names += name
            val tag = text(label.substringAfter("  ·  ", ""), sized(SMALL, 16f), color = TEXT_3).apply {
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            }
            // The choice in use: the tungsten dot before its name, as on chips and filters, and its name at full strength.
            val check = View(this).apply {
                background = GradientDrawable().apply { setColor(TUNGSTEN) } // a small square, not a round dot
                visibility = if (index == chosen) View.VISIBLE else View.INVISIBLE
            }
            checks += check
            LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(9), 0, dp(9), 0)
                addView(check, LinearLayout.LayoutParams(dp(6), dp(6)).apply { marginEnd = dp(8) })
                addView(name)
                if (tag.text.isNotEmpty()) addView(tag, LinearLayout.LayoutParams(0, -2, 1f).apply {
                    marginStart = dp(7)
                })
                else addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
                rowFocus(8) { has ->
                    name.setTextColor(if (has || check.visibility == View.VISIBLE) TEXT else TEXT_2)
                }
                setOnClickListener {
                    pick(index)
                    checks.forEachIndexed { i, icon -> icon.visibility = if (i == index) View.VISIBLE else View.INVISIBLE }
                    title.text = headingFor(index).uppercase()
                    names.forEachIndexed { i, word -> word.setTextColor(if (i == index) TEXT else TEXT_2) }
                }
            }.also { list.addView(it, LinearLayout.LayoutParams(-1, dp(sized(32, 62)))) }
        }
        return rows[chosen.coerceIn(0, rows.lastIndex)]
    }

    private fun closeOptions() {
        options.visibility = View.GONE
        showControls(audioButton)
    }

    /** "English · Dolby Digital 5.1": the language, then the release's own name for the track or, for audio, its format. */
    /** Re-attaches [sub] with every time moved by [delay] ms (later when positive), from the same second. */
    private fun shiftSubtitles(sub: Subtitle, delay: Long) {
        subDelay = delay
        background({
            if (delay == 0L) return@background null
            val times = Regex("""(\d{2}):(\d{2}):(\d{2})([,.])(\d{3})""")
            val moved = times.replace(Http.get(sub.url)) { m ->
                val (h, mi, se, sep, ms) = m.destructured
                val t = maxOf(0L, ((h.toLong() * 60 + mi.toLong()) * 60 + se.toLong()) * 1000 + ms.toLong() + delay)
                "%02d:%02d:%02d%s%03d".format(t / 3_600_000, t / 60_000 % 60, t / 1000 % 60, sep, t % 1000)
            }
            java.io.File(cacheDir, "subtitle." + if (sub.mimeType == MimeTypes.TEXT_VTT) "vtt" else "srt").apply { writeText(moved) }
        }) { r ->
            subFile = r.getOrNull()
            selectAddonSub = true
            load(position())
        }
    }

    private fun isAddonSub(g: Tracks.Group) = g.type == C.TRACK_TYPE_TEXT && g.getTrackFormat(0).id?.contains(ADDON_SUB) == true

    private fun trackName(f: Format, audio: Boolean): String {
        val language = f.language?.takeIf { it != "und" }?.let { Locale.forLanguageTag(it).getDisplayLanguage(Locale.getDefault()) }?.ifBlank { null }
        val format = if (!audio) null else listOfNotNull(when (f.sampleMimeType) {
            MimeTypes.AUDIO_AC3 -> "Dolby Digital"
            MimeTypes.AUDIO_E_AC3 -> "Dolby Digital Plus"
            MimeTypes.AUDIO_E_AC3_JOC -> "Dolby Atmos"
            MimeTypes.AUDIO_TRUEHD -> "Dolby TrueHD"
            MimeTypes.AUDIO_DTS -> "DTS"
            MimeTypes.AUDIO_DTS_HD -> "DTS-HD"
            MimeTypes.AUDIO_AAC -> "AAC"
            MimeTypes.AUDIO_OPUS -> "Opus"
            MimeTypes.AUDIO_FLAC -> "FLAC"
            else -> null
        }, when (f.channelCount) { 1 -> "Mono"; 2 -> "Stereo"; 6 -> "5.1"; 8 -> "7.1"; Format.NO_VALUE -> null; else -> "${f.channelCount} ch" })
            .joinToString(" ").ifEmpty { null }
        val label = f.label?.takeIf { it.isNotBlank() && !it.equals(language, ignoreCase = true) }
        return listOfNotNull(language, label ?: format).joinToString("  ·  ").ifEmpty { "Track ${f.id ?: ""}".trim() }
    }

    private var stopping = false // onStop's own pause: not the viewer's, so the controls stay as they are

    override fun onStop() {
        super.onStop()
        if (!::status.isInitialized) return
        main.removeCallbacks(tickTask)
        main.removeCallbacks(progressTask)
        main.removeCallbacks(skipTask)
        main.removeCallbacks(loadingTask)
        main.removeCallbacks(countdownTask)
        stopping = true
        player.pause()
        stopping = false
        // The window was closed (swiped away or its ✕): nothing is left to come back to.
        if (inWindow) finish()
        if (!watched || player.isCurrentMediaItemLive || type == "series" && !plausibleEpisode(player.duration)) return
        record()
        // The player's own episode list first: Home's Resume passes no next episode when the title's details weren't in
        // yet, and a finished episode with no next one drops the title out of Continue Watching.
        item?.let { Library.stopped(it, nextVideo()?.id ?: intent.getStringExtra("nextVideoId")) }
        push()
    }

    override fun onDestroy() {
        turnWatcher?.disable()
        if (windowReceiverOn) unregisterReceiver(windowReceiver)
        main.removeCallbacks(startTimeout)
        main.removeCallbacks(loadingTask)
        if (::status.isInitialized) player.release()
        super.onDestroy()
    }

    // ---------- upright, and in a window, on a phone ----------
    // Upright, the picture runs the width of the screen at the top with its controls on it, and the title, what's left,
    // the episodes and the story sit below, as on YouTube. Full screen (top right) turns it sideways; Keep sideways in
    // the dock holds it there. Window (or Home while it plays) shrinks the film into Android's picture-in-picture
    // window, which plays on over other apps with back 10 s, Play and ahead 10 s.

    private lateinit var upright: ScrollView
    private lateinit var uprightTitle: TextView
    private lateinit var uprightSub: TextView
    private lateinit var uprightFacts: TextView
    private lateinit var uprightAbout: TextView
    private lateinit var uprightEpisodes: LinearLayout
    private lateinit var dockButtons: LinearLayout
    private lateinit var clockRow: LinearLayout
    private lateinit var dock: LinearLayout
    private lateinit var bottomShade: View
    private lateinit var lockButton: TextView
    private var bigRow: LinearLayout? = null
    private var turnButton: TextView? = null
    /** Upright on a phone: the picture at the top, the title below. */
    private var portrait = false
    /** In Android's picture-in-picture window. */
    private var inWindow = false
    private var windowReceiverOn = false
    /** Full screen or Upright turned the screen; once the phone itself is turned that way, it follows the phone again. */
    private var turnWatcher: OrientationEventListener? = null

    private fun sideways() = getSharedPreferences("player", MODE_PRIVATE).getBoolean("sideways", false)

    private fun setSideways(on: Boolean) {
        getSharedPreferences("player", MODE_PRIVATE).edit().putBoolean("sideways", on).apply()
        turnWatcher?.disable()
        requestedOrientation = if (on) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_USER
        paintLock()
        flash(if (on) "Kept sideways" else "Turns with the phone")
    }

    private fun paintLock() {
        val on = sideways()
        lockButton.foreground = centred(R.drawable.ic_lock_rotation, sized(20, 30), if (on) TUNGSTEN else TEXT)
        buttonLabels[lockButton]?.text = if (on) "Kept sideways" else "Keep sideways"
    }

    /** Full screen while upright, Upright while sideways: turns the screen now, then follows the phone once it's turned. */
    private fun turn() {
        val goSideways = portrait
        if (!goSideways && sideways()) {
            getSharedPreferences("player", MODE_PRIVATE).edit().putBoolean("sideways", false).apply()
            paintLock()
        }
        val want = if (goSideways) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        requestedOrientation = want
        // With auto-rotate off the phone never follows the sensor, so the turn stays until the button is pressed again.
        if (android.provider.Settings.System.getInt(contentResolver, android.provider.Settings.System.ACCELEROMETER_ROTATION, 0) != 1) return
        turnWatcher?.disable()
        turnWatcher = object : OrientationEventListener(this) {
            override fun onOrientationChanged(degrees: Int) {
                if (degrees == ORIENTATION_UNKNOWN) return
                val held = if (want == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE) degrees in 60..120 || degrees in 240..300
                    else degrees < 30 || degrees > 330
                if (!held) return
                disable()
                if (!sideways()) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER
            }
        }.also { it.enable() }
    }

    /** A card's width: [tv] dp, or the screen less a margin each side when upright on a phone. */
    private fun cardWidth(tv: Int) = if (portrait) screen.width - dp(32) else dp(tv)

    /** Lays the screen out for the way the phone is held (or for the window): runs after every change of size. */
    private fun applyLayout() {
        if (!touch) return
        val w = screen.width
        val h = screen.height
        if (w == 0 || h == 0) return
        portrait = !inWindow && h > w
        val top = if (portrait) window.decorView.rootWindowInsets?.displayCutout?.safeInsetTop ?: 0 else 0
        val box = if (portrait) w * 9 / 16 else -1
        for (v in listOf(view, pausedShade, controls, loading)) v.layoutParams = (v.layoutParams as FrameLayout.LayoutParams).apply {
            height = box; topMargin = top; gravity = Gravity.TOP
        }
        // Upright, the picture's controls are only what fits on it: Back, Full screen, back/Play/ahead and the bar.
        val sideOnly = if (portrait || inWindow) View.GONE else View.VISIBLE
        dockButtons.visibility = sideOnly
        clockRow.visibility = sideOnly
        if (portrait) { playerLogo.visibility = View.GONE; topName.visibility = View.GONE }
        else if (playerLogo.drawable == null) topName.visibility = View.VISIBLE else playerLogo.visibility = View.VISIBLE
        bigRow?.layoutParams = (bigRow?.layoutParams as? FrameLayout.LayoutParams)?.apply { bottomMargin = dp(if (portrait) 0 else 50) }
        bottomShade.layoutParams = bottomShade.layoutParams.apply { height = if (portrait) box else dp(280) }
        turnButton?.let { b ->
            b.foreground = centred(if (portrait) R.drawable.ic_fullscreen else R.drawable.ic_fullscreen_exit, 32, TEXT)
            b.contentDescription = if (portrait) "Full screen" else "Upright"
        }
        if (portrait) loadingCenter.translationY = 0f else if (shown) loadingCenter.translationY = -dp(130).toFloat()
        // The Skip pill stays on the picture.
        skip.layoutParams = (skip.layoutParams as FrameLayout.LayoutParams).apply {
            if (portrait) { gravity = Gravity.TOP or Gravity.END; topMargin = top + box - dp(130); setMargins(0, topMargin, dp(16), 0) }
            else { gravity = Gravity.BOTTOM or Gravity.END; setMargins(0, 0, dp(PAD), dp(PAD + 40)) }
        }
        // The starting screen's title block keeps inside a narrow screen.
        (starting.view.getChildAt(starting.view.childCount - 1) as? LinearLayout)?.let { block ->
            block.layoutParams = (block.layoutParams as FrameLayout.LayoutParams).apply { leftMargin = dp(if (portrait) 20 else PAD) }
            block.getChildAt(0)?.let { it.layoutParams = it.layoutParams.apply { width = minOf(dp(HERO_W), w - dp(40)) } }
        }
        upright.visibility = if (portrait) View.VISIBLE else View.GONE
        upright.layoutParams = (upright.layoutParams as FrameLayout.LayoutParams).apply { topMargin = top + box }
        if (portrait) refreshUpright()
        screen.requestLayout()
    }

    private fun buildUpright(): ScrollView {
        uprightTitle = text(intent.getStringExtra("name").orEmpty(), 24f, serif = true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        uprightSub = text(intent.getStringExtra("episode").orEmpty(), 16f, bold = true, color = TEXT_2).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        uprightFacts = text("", 15f, color = TEXT_2)
        uprightAbout = text("", 15f, color = TEXT_2).apply { setLineSpacing(0f, 1.2f) }
        uprightEpisodes = column(Gravity.START)
        fun action(label: String, icon: Int, onClick: () -> Unit) = column(Gravity.CENTER_HORIZONTAL).apply {
            addView(ImageView(context).apply { setImageResource(icon); setColorFilter(TEXT) }, LinearLayout.LayoutParams(dp(28), dp(28)))
            addView(text(label, 13f, color = TEXT_2).apply { gravity = Gravity.CENTER; maxLines = 2 }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            setPadding(dp(4), dp(10), dp(4), dp(10))
            rowFocus(8)
            setOnClickListener { onClick() }
        }
        val actions = LinearLayout(this).apply {
            fun add(v: View) = addView(v, LinearLayout.LayoutParams(0, -2, 1f))
            add(action("Audio & subtitles", R.drawable.ic_subtitles) { openOptions() })
            add(action("Change stream", R.drawable.ic_layers) { changeStream() })
            if (windowAllowed()) add(action("Window", R.drawable.ic_pip) { enterWindow() })
            add(action("Full screen", R.drawable.ic_fullscreen) { turn() })
        }
        val list = column(Gravity.START).apply {
            setPadding(dp(20), dp(18), dp(20), dp(40))
            addView(uprightTitle, LinearLayout.LayoutParams(-1, -2))
            addView(uprightSub, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
            addView(uprightFacts, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
            addView(View(context).apply { setBackgroundColor(GLASS_EDGE) }, LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(12) })
            addView(uprightEpisodes, LinearLayout.LayoutParams(-1, -2))
            addView(uprightAbout, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })
        }
        return ScrollView(this).apply {
            setBackgroundColor(BASE)
            isVerticalScrollBarEnabled = false
            visibility = View.GONE
            addView(list)
        }
    }

    /** The title page's facts for the panel below the picture, once the title's details have come in. */
    private fun fillUpright() {
        val m = meta ?: return
        val episode = m.videos.firstOrNull { it.id == videoId }
        uprightTitle.text = m.name
        uprightSub.text = episode?.let { "S${it.season} E${it.episode}" + if (it.title.isNotBlank()) "$DOT${it.title}" else "" } ?: m.year.orEmpty()
        uprightAbout.text = episode?.overview?.takeIf { it.isNotBlank() } ?: m.description.orEmpty()
        uprightEpisodes.removeAllViews()
        val season = episode?.season ?: return
        val inSeason = m.videos.filter { it.season == season }
        if (inSeason.size < 2) return
        uprightEpisodes.addView(caps("Season $season", TUNGSTEN), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(18); bottomMargin = dp(6) })
        for (v in inSeason) {
            val playing = v.id == videoId
            uprightEpisodes.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(12), dp(8), dp(12))
                addView(text("${v.episode}", 16f, bold = true, color = if (playing) TUNGSTEN else TEXT_3), LinearLayout.LayoutParams(dp(36), -2))
                addView(text(v.title.ifBlank { "Episode ${v.episode}" }, 16f, color = if (playing) TEXT else TEXT_2).apply {
                    maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(0, -2, 1f))
                if (playing) addView(text("Playing", 13f, bold = true, color = TUNGSTEN), wrap())
                else {
                    rowFocus(8)
                    setOnClickListener { playEpisode(v) }
                }
            }, LinearLayout.LayoutParams(-1, -2))
        }
    }

    /** What's left and when it ends, under the title below the picture. */
    private fun refreshUpright() {
        if (!portrait || !::uprightFacts.isInitialized) return
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return
        if (player.isCurrentMediaItemLive) { uprightFacts.text = "Live"; return }
        val remaining = (duration - player.currentPosition).coerceAtLeast(0)
        val end = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(System.currentTimeMillis() + remaining))
        uprightFacts.text = "${(remaining / 60_000).coerceAtLeast(1)} min left${DOT}Ends $end" + if (player.playWhenReady) "" else "${DOT}Paused"
    }

    private fun windowAllowed() = touch && packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    private val windowReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra("do", 0)) {
                WINDOW_PLAY -> if (player.playWhenReady) player.pause() else player.play()
                WINDOW_BACK -> if (canSeek()) player.seekTo((player.currentPosition - SKIP).coerceAtLeast(0))
                WINDOW_AHEAD -> if (canSeek()) player.seekTo(player.currentPosition + SKIP)
            }
        }
    }

    /** The window's shape (the film's), its three buttons, and on Android 12 and later whether Home sends it there. */
    private fun windowParams(): PictureInPictureParams {
        val size = player.videoSize
        val ratio = if (size.width > 0 && size.height > 0) Rational((size.width * size.pixelWidthHeightRatio).roundToInt(), size.height) else Rational(16, 9)
        // Android refuses shapes beyond 2.39:1 either way.
        val fitted = when {
            ratio.toFloat() > 2.39f -> Rational(239, 100)
            ratio.toFloat() < 1 / 2.39f -> Rational(100, 239)
            else -> ratio
        }
        fun action(icon: Int, title: String, code: Int) = RemoteAction(Icon.createWithResource(this, icon), title, title,
            PendingIntent.getBroadcast(this, code, Intent(WINDOW_ACTION).setPackage(packageName).putExtra("do", code),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        val playing = player.playWhenReady
        val actions = listOf(
            action(R.drawable.ic_replay10, "Back 10 seconds", WINDOW_BACK),
            action(if (playing) R.drawable.ic_pause else R.drawable.ic_play, if (playing) "Pause" else "Play", WINDOW_PLAY),
            action(R.drawable.ic_forward10, "Ahead 10 seconds", WINDOW_AHEAD),
        )
        val hint = android.graphics.Rect().also { view.getGlobalVisibleRect(it) }
        return PictureInPictureParams.Builder().setAspectRatio(fitted).setActions(actions).setSourceRectHint(hint).apply {
            if (Build.VERSION.SDK_INT >= 31) {
                setAutoEnterEnabled(started && playing && !isFinishing)
                setSeamlessResizeEnabled(false)
            }
        }.build()
    }

    private fun updateWindow() {
        if (!windowAllowed() || isFinishing) return
        runCatching { setPictureInPictureParams(windowParams()) }.onFailure { android.util.Log.w("Canopy", "window params: $it") }
    }

    private fun enterWindow() {
        if (!windowAllowed() || !started) return
        hideControls(force = true)
        runCatching { enterPictureInPictureMode(windowParams()) }.onFailure { android.util.Log.w("Canopy", "no window: $it") }
    }

    // Before Android 12 Home doesn't send the film to the window by itself.
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < 31 && started && player.playWhenReady && !isFinishing) enterWindow()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inWindow = isInPictureInPictureMode
        if (inWindow) {
            hideControls(force = true)
            options.visibility = View.GONE
            skip.visibility = View.GONE
            if (upNextPanel.visibility == View.VISIBLE) dismissUpNext()
        }
        applyLayout()
    }


    private companion object {
        const val TICK = 10_000L
        const val RESUME_SLACK = 10_000L // how far apart the saved and fresh resume points may be before it jumps
        const val SKIP = 10_000L // ms a Left/Right press on the bar moves
        const val DOCK_INSET = 28 // dp the glass dock sits in from the screen's sides and foot
        const val SEEK_SETTLE = 600L // ms after the last skip before seeking
        const val SKIP_SHOWN = 10_000L // ms the Skip intro pill stays, as in NuvioTV
        const val GUESS_JUMP = 85_000L // ms "Skip 85 s" moves when no one has timed the intro: a typical opening's length
        const val GUESS_WINDOW = 180_000L // ms from the start that "Skip 85 s" is offered
        const val PREFETCH_BEFORE_END = 4 * 60_000L // ms before the end the next episode's sources are found
        const val UP_NEXT_COUNTDOWN = 15_000L // ms Up next counts down before the next episode starts
        const val COUNTDOWN_STEP = 250L
        const val HIDE = 5_000L // ms the controls stay up while playing
        const val HIDE_TOUCH = 8_000L // on a phone: long enough to reach for the bar
        const val DOUBLE_TAP = 300L // ms within which a second tap on the picture's side makes a double tap
        const val START_WAIT = 12_000L // ms for a source to show a picture before the next is tried (4K DV from debrid took 6 s)
        const val ADDON_SUB = "addon-subtitle" // the id an addon subtitle file is attached under
        const val SUB_WAIT = 2_000L // ms the start waits for the addon's subtitles
        const val RETRIES = 2
        const val RETRY_WAIT = 1_500L
        const val WINDOW_ACTION = "dev.canopy.WINDOW"
        const val WINDOW_PLAY = 1
        const val WINDOW_BACK = 2
        const val WINDOW_AHEAD = 3
    }
}

/**
 * Playback progress over a light track, chunky and square-ended, with a slim upright line of light as its head on
 * focus (Light line, no round knob). While scrubbing, [from] marks where the seek started.
 */
private class SeekBar(context: Context) : View(context) {
    private var position = 0L
    private var buffered = 0L
    private var duration = 0L
    /** Where a scrub started, drawn as a faint upright mark; -1 when not scrubbing. */
    var from = -1L
        set(value) { field = value; invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val d = context.resources.displayMetrics.density

    init { setLayerType(LAYER_TYPE_SOFTWARE, null) }

    /** On a phone, a finger on the bar: where along it (0 to 1, or -1 when the drag is taken away), and whether it lifted. */
    var onDrag: ((Float, Boolean) -> Unit)? = null

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        val drag = onDrag ?: return super.onTouchEvent(event)
        val edge = 12 * d
        val at = ((event.x - edge) / (width - 2 * edge)).coerceIn(0f, 1f)
        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> { parent?.requestDisallowInterceptTouchEvent(true); drag(at, false) }
            android.view.MotionEvent.ACTION_MOVE -> drag(at, false)
            android.view.MotionEvent.ACTION_UP -> drag(at, true)
            android.view.MotionEvent.ACTION_CANCEL -> drag(-1f, true)
        }
        return true
    }

    fun set(position: Long, buffered: Long, duration: Long) {
        this.position = position
        this.buffered = buffered
        this.duration = duration
        invalidate()
    }

    /** The x of [t] along the bar, for anything that rides above it (the scrub time). */
    fun xOf(t: Long): Float {
        val edge = (if (onDrag != null) 12 else 2) * d // a phone's knob stays whole at the ends
        return edge + (width - 2 * edge) * (if (duration > 0) t.coerceIn(0, duration) / duration.toFloat() else 0f)
    }

    override fun onDraw(canvas: Canvas) {
        val edge = (if (onDrag != null) 12 else 2) * d
        val thumb = onDrag != null // a phone: a thicker line with a knob for the thumb
        val line = (if (thumb) 10 else if (isFocused) 10 else 8) * d // chunky, Daybreak's bar
        val y = height / 2f
        fun stretch(to: Float, color: Int) {
            paint.color = color
            canvas.drawRect(edge, y - line / 2, to, y + line / 2, paint)
        }
        // A light track: the dark one vanished over a dark picture.
        stretch(width - edge, 0x29FFFFFF)
        stretch(xOf(buffered), 0x40FFFFFF)
        // Accent into its lighter partner, glowing.
        paint.shader = LinearGradient(edge, 0f, maxOf(xOf(position), edge + 1), 0f, Screen.TUNGSTEN, Screen.ICE, Shader.TileMode.CLAMP)
        paint.setShadowLayer(8 * d, 0f, 0f, Screen.TUNGSTEN_GLOW)
        stretch(xOf(position), Screen.TUNGSTEN)
        paint.clearShadowLayer()
        paint.shader = null
        if (duration <= 0) return
        if (from >= 0) {
            paint.color = 0x73FFFFFF
            val x = xOf(from)
            canvas.drawRect(x - d, y - line / 2 - 4 * d, x + d, y + line / 2 + 4 * d, paint)
        }
        if (thumb) {
            // A round knob to take hold of, glowing in the accent (as in the bare-icons mockup Daniel picked).
            val x = xOf(position)
            val half = 13 * d // about 24 dp of the phone's own
            paint.color = Screen.TEXT
            paint.setShadowLayer(8 * d, 0f, 0f, Screen.TUNGSTEN_GLOW)
            canvas.drawCircle(x, y, half, paint)
            paint.clearShadowLayer()
        } else if (isFocused) {
            // The head: a slim upright line of light, standing past the bar's edges.
            paint.color = Screen.TEXT
            paint.setShadowLayer(6 * d, 0f, 0f, 0x99FFFFFF.toInt())
            val x = xOf(position)
            canvas.drawRect(x - 1.5f * d, y - line / 2 - 7 * d, x + 1.5f * d, y + line / 2 + 7 * d, paint)
            paint.clearShadowLayer()
        }
    }
}
