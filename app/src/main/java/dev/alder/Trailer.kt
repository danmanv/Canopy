package dev.alder

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout

/** The YouTube id in [raw]: a bare id, as Cinemeta sends it, or a watch, youtu.be, embed or shorts link. */
internal fun youTubeId(raw: String?): String? {
    val s = raw?.trim()?.ifEmpty { null } ?: return null
    val id = Regex("""(?:[?&]v=|youtu\.be/|/embed/|/shorts/)([A-Za-z0-9_-]{11})""").find(s)?.groupValues?.get(1) ?: s
    return id.takeIf { Regex("[A-Za-z0-9_-]{11}").matches(it) }
}

/**
 * A title's trailer, muted, in [box]: YouTube's own player in a WebView, as trailers are YouTube videos and ExoPlayer
 * can't play YouTube. One WebView, made the first time a trailer plays (a moment's work, while the remote rests), and
 * emptied when the trailer stops so the TV's video decoder is free for the next one.
 *
 * The player says what it's doing through the page's title, not a JavaScript bridge: a bridge would be open to
 * YouTube's frame too.
 */
class Trailer(private val context: Context, private val box: FrameLayout) {
    private val main = Handler(Looper.getMainLooper())
    private var web: WebView? = null
    private var broken = false // no WebView on this TV: trailers stay off
    private var playing: (() -> Unit)? = null
    private var done: (() -> Unit)? = null
    private val giveUp = Runnable { finish() }
    // Emptied a moment after it stops, so the art fades in over the last frames rather than over black.
    private val blank = Runnable { web?.loadUrl("about:blank") }

    /** Starts [id] out of sight: [onPlaying] once its picture moves, [onDone] when it ends, fails or [stop] is called. */
    fun play(id: String, onPlaying: () -> Unit, onDone: () -> Unit) {
        stop()
        val view = web ?: make() ?: return onDone()
        main.removeCallbacks(blank)
        playing = onPlaying
        done = onDone
        main.postDelayed(giveUp, START_WAIT)
        view.loadDataWithBaseURL(ORIGIN, page(id), "text/html", "utf-8", null)
    }

    /** Stops the trailer, if one is on, and empties the page. */
    fun stop() {
        main.removeCallbacks(giveUp)
        val wasOn = done != null
        playing = null
        done?.let { done = null; it() }
        if (wasOn) main.postDelayed(blank, BLANK_AFTER)
    }

    fun destroy() {
        stop()
        main.removeCallbacks(blank)
        web?.let { box.removeView(it); it.destroy() }
        web = null
    }

    private fun finish() = stop()

    @SuppressLint("SetJavaScriptEnabled")
    private fun make(): WebView? {
        if (broken) return null
        // A TV without a WebView (or with a broken one) throws here: trailers simply don't play.
        val view = runCatching { WebView(context) }.getOrElse { broken = true; return null }
        view.apply {
            setBackgroundColor(Color.TRANSPARENT)
            isFocusable = false
            isFocusableInTouchMode = false
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            settings.javaScriptEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.domStorageEnabled = true
            webChromeClient = object : WebChromeClient() {
                override fun onReceivedTitle(view: WebView, title: String?) {
                    when (title?.substringBefore(':')) {
                        "playing" -> playing?.let {
                            playing = null
                            main.removeCallbacks(giveUp)
                            // A beat after the picture starts: the first frames are often black.
                            main.postDelayed({ if (done != null) it() }, REVEAL_DELAY)
                        }
                        "ended", "error" -> finish()
                    }
                }
                // YouTube's spinner and its poster frame: neither is wanted behind the hero.
                override fun getDefaultVideoPoster() = android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888)
            }
        }
        box.addView(view, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        box.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        box.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        web = view
        return view
    }

    // The player is a little larger than the screen, so YouTube's title and logo at its edges are cut off. [id] is
    // checked by youTubeId: letters, digits, - and _ only.
    private fun page(id: String) = """
        <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
        <style>html,body{margin:0;width:100%;height:100%;overflow:hidden;background:transparent}
        #p{position:absolute;left:-8%;top:-8%;width:116%;height:116%;border:0;pointer-events:none}</style></head>
        <body><div id="p"></div><script>
        var n=0;function say(s){document.title=s+':'+(++n)}
        function onYouTubeIframeAPIReady(){new YT.Player('p',{host:'https://www.youtube-nocookie.com',videoId:'$id',
          playerVars:{autoplay:1,mute:1,controls:0,disablekb:1,fs:0,iv_load_policy:3,modestbranding:1,playsinline:1,rel:0,
            cc_load_policy:0,origin:location.origin},
          events:{onReady:function(e){e.target.mute();e.target.playVideo()},
            onStateChange:function(e){if(e.data==1)say('playing');else if(e.data==0)say('ended')},
            onError:function(){say('error')}}})}
        </script><script src="https://www.youtube.com/iframe_api" onerror="say('error')"></script></body></html>
    """.trimIndent()

    private companion object {
        // YouTube's embedded player refuses pages with no origin (error 153), so the page is given Canopy's.
        const val ORIGIN = "https://danmanv.github.io/Canopy/"
        const val START_WAIT = 12_000L // ms a trailer may take to start before it's given up
        const val REVEAL_DELAY = 700L
        const val BLANK_AFTER = 600L // ms: past the art's fade back in
    }
}
