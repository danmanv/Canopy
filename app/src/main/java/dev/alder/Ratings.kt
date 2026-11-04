package dev.alder

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Rotten Tomatoes (and IMDb) scores from MDBList, beside the IMDb rating the add-ons send. Every title is asked for once
 * and kept on the device for a week, so the free key's 1,000 lookups a day go a long way. Without a key in the build
 * (MDBLIST_API_KEY: a GitHub secret in CI, or local.properties) nothing is asked and the apps show IMDb only.
 */
object Ratings {
    /** [tomatoes]: the critics' Tomatometer, [popcorn]: the audience score, both in percent. [imdb]: "8.1". */
    class Scores(val tomatoes: Int?, val popcorn: Int?, val imdb: String?)

    private const val WEEK = 7L * 24 * 60 * 60 * 1000
    // A title MDBList doesn't know (or a failed lookup) is asked again after a day, not on every focus.
    private const val DAY = 24L * 60 * 60 * 1000

    private var prefs: SharedPreferences? = null
    private val memory = ConcurrentHashMap<String, Pair<Long, Scores?>>()
    private val asking = ConcurrentHashMap.newKeySet<String>()
    private val pool = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    val enabled get() = BuildConfig.MDBLIST_KEY.isNotBlank()

    fun init(c: Context) {
        if (prefs == null) prefs = c.applicationContext.getSharedPreferences("ratings", Context.MODE_PRIVATE)
    }

    /** What's kept for [id], without asking the network; null when nothing (or nothing fresh) is kept. */
    fun cached(id: String): Scores? = entry(id)?.second

    /**
     * The scores line's Rotten Tomatoes part from what's kept: "🍅 94%", or the audience's "🍿 88%" when critics have
     * no score (common for TV). Null when neither is known.
     */
    fun tomato(id: String): String? = cached(id)?.let { s ->
        s.tomatoes?.let { "🍅 $it%" } ?: s.popcorn?.let { "🍿 $it%" }
    }

    /** "IMDb 8.1 · 🍅 94%" from [imdb] (the add-on's rating, else MDBList's) and what's kept; null when neither. */
    fun line(id: String, imdb: String?, dot: String): String? =
        listOfNotNull((imdb ?: cached(id)?.imdb)?.let { "IMDb $it" }, tomato(id)).joinToString(dot).ifEmpty { null }

    /**
     * Asks MDBList for [id] (an IMDb id) unless something fresh is kept, and calls [done] on the main thread once new
     * scores have arrived. Nothing happens for other ids, without a key, or while the same title is already asked for.
     */
    fun fetch(id: String, type: String, done: () -> Unit = {}) {
        if (!enabled || !id.startsWith("tt") || entry(id) != null || !asking.add(id)) return
        val kind = if (type == "series") "show" else "movie"
        pool.execute {
            val scores = runCatching {
                parse(JSONObject(Http.get("https://api.mdblist.com/imdb/$kind/$id?apikey=${BuildConfig.MDBLIST_KEY}", timeoutMs = 10_000)))
            }.getOrNull()
            keep(id, scores)
            asking.remove(id)
            if (scores != null) main.post(done)
        }
    }

    internal fun parse(json: JSONObject): Scores? {
        val ratings = json.optJSONArray("ratings") ?: return null
        fun score(source: String): Double? = (0 until ratings.length()).map { ratings.getJSONObject(it) }
            .firstOrNull { it.optString("source") == source }
            ?.let { r -> r.opt("value").takeIf { it is Number }?.let { (it as Number).toDouble() } }
            ?.takeIf { it > 0 }
        val imdb = score("imdb")?.let { "%.1f".format(java.util.Locale.US, it) }
        val audience = score("popcorn") ?: score("tomatoesaudience") ?: score("audience")
        return Scores(score("tomatoes")?.toInt(), audience?.toInt(), imdb)
            .takeIf { it.tomatoes != null || it.popcorn != null || it.imdb != null }
    }

    private fun entry(id: String): Pair<Long, Scores?>? {
        val now = System.currentTimeMillis()
        val e = memory[id] ?: prefs?.getString(id, null)?.let(::decode)?.also { memory[id] = it } ?: return null
        val life = if (e.second == null) DAY else WEEK
        return e.takeIf { now - it.first < life }
    }

    private fun keep(id: String, s: Scores?) {
        val e = System.currentTimeMillis() to s
        memory[id] = e
        prefs?.edit()?.putString(id, listOf(e.first, s?.tomatoes ?: "", s?.popcorn ?: "", s?.imdb ?: "").joinToString("|"))?.apply()
    }

    private fun decode(v: String): Pair<Long, Scores?>? {
        val p = v.split("|")
        if (p.size < 4) return null
        val at = p[0].toLongOrNull() ?: return null
        val s = Scores(p[1].toIntOrNull(), p[2].toIntOrNull(), p[3].ifEmpty { null })
        return at to s.takeIf { it.tomatoes != null || it.popcorn != null || it.imdb != null }
    }
}
