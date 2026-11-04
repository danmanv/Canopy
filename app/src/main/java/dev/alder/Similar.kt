package dev.alder

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * "More like this" picked for each title: MDBList's recommendations (TMDB's "people who liked this also liked"), the
 * same key as the Rotten Tomatoes scores. One lookup per title, kept on the device for a week, so it barely touches the
 * free key's 1,000 lookups a day. Without a key, or for a title MDBList doesn't know, the caller falls back to add-ons.
 */
object Similar {
    private const val WEEK = 7L * 24 * 60 * 60 * 1000
    // An empty answer (a title too new or obscure for recommendations) is asked again after a day.
    private const val DAY = 24L * 60 * 60 * 1000

    private var prefs: SharedPreferences? = null
    // Failed lookups (no network, the day's lookups used up): not asked again until the app restarts.
    private val failed = java.util.Collections.synchronizedSet(HashSet<String>())

    /**
     * Titles MDBList recommends for [id] (an IMDb id), movies and shows alike; null when it can't say (no key, not an
     * IMDb id, a failed lookup). Blocks on the network: call it off the main thread.
     */
    fun of(c: Context, type: String, id: String): List<Meta>? {
        if (!Ratings.enabled || !id.startsWith("tt") || id in failed) return null
        val p = prefs ?: c.applicationContext.getSharedPreferences("similar", Context.MODE_PRIVATE).also { prefs = it }
        val now = System.currentTimeMillis()
        p.getString(id, null)?.let { v ->
            val at = v.substringBefore('|').toLongOrNull()
            val kept = runCatching { JSONArray(v.substringAfter('|')) }.getOrNull()
            if (at != null && kept != null && now - at < if (kept.length() == 0) DAY else WEEK) return metas(kept)
        }
        val kind = if (type == "series") "show" else "movie"
        val url = "https://api.mdblist.com/imdb/$kind/$id?append_to_response=recommendations&apikey=${BuildConfig.MDBLIST_KEY}"
        val json = runCatching { JSONObject(Http.get(url, timeoutMs = 10_000)) }.getOrNull()
        if (json == null) { failed += id; return null }
        // A title MDBList doesn't know answers with an error: kept as "none" for a day, like an empty list.
        val recs = json.optJSONArray("recommendations") ?: JSONArray()
        p.edit().putString(id, "$now|$recs").apply()
        return metas(recs)
    }

    /** MDBList's items as add-on style titles (Cinemeta ids), so the rows, posters and scores treat them as any other. */
    internal fun metas(a: JSONArray): List<Meta> = a.objects().mapNotNull { r ->
        val imdb = r.text("imdb_id") ?: r.optJSONObject("ids")?.text("imdb") ?: return@mapNotNull null
        if (!imdb.startsWith("tt")) return@mapNotNull null
        Meta(JSONObject()
            .put("id", imdb)
            .put("type", if (r.optString("mediatype") == "show") "series" else "movie")
            .put("name", r.optString("title"))
            // MDBList sends TMDB's 200 px poster: a card wants the 500 px one.
            .put("poster", r.text("poster")?.replace("/t/p/w200/", "/t/p/w500/"))
            .put("releaseInfo", r.text("release_year")?.takeIf { it != "0" }))
    }
}
