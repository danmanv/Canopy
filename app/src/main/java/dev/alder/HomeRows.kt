package dev.alder

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * The viewer's Home, per profile on this TV: which catalogs show as rows and in what order, and the titles pinned to
 * Tonight (a row above Continue Watching for what they mean to finish). A catalog is named "addonId/type/catalogId",
 * so a row keeps its place when the add-on's URL changes.
 */
object HomeRows {
    private fun prefs(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun key(c: Addon.Catalog) = "${c.addon.id}/${c.type}/${c.id}"

    /** Watchly's "Because you watched" rows: Home's rows until the viewer picks their own. */
    fun onByDefault(c: Addon.Catalog) = c.addon.id.contains("watchly") && c.id.startsWith("watchly.item")

    /** Every catalog in Home's order, on or off; null when the viewer never changed it. */
    fun saved(c: Context): List<Pair<String, Boolean>>? = prefs(c).getString(Settings.profileKey(c, "homeRows"), null)
        ?.let { runCatching { JSONArray(it) }.getOrNull() }
        ?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map { it.optString("key") to it.optBoolean("on") } }

    fun save(c: Context, rows: List<Pair<String, Boolean>>) = prefs(c).edit().putString(Settings.profileKey(c, "homeRows"),
        JSONArray(rows.map { (key, on) -> JSONObject().put("key", key).put("on", on) }).toString()).apply()

    fun reset(c: Context) = prefs(c).edit().remove(Settings.profileKey(c, "homeRows")).apply()

    /** These catalogs in Home's order, each with whether it shows. */
    fun arranged(c: Context, catalogs: List<Addon.Catalog>): List<Pair<Addon.Catalog, Boolean>> {
        val byKey = catalogs.associateBy(::key)
        return arrangeHomeRows(byKey.keys.toList(), catalogs.filter(::onByDefault).map(::key).toSet(), saved(c))
            .mapNotNull { (key, on) -> byKey[key]?.let { it to on } }
    }

    /** Saves [rows] as Home's order, keeping the place of catalogs from add-ons that aren't loaded right now. */
    fun saveArranged(c: Context, rows: List<Pair<Addon.Catalog, Boolean>>) {
        val keys = rows.map { key(it.first) }.toSet()
        save(c, rows.map { (catalog, on) -> key(catalog) to on } + saved(c).orEmpty().filter { it.first !in keys })
    }

    /** Ids pinned to Tonight, in the order they were pinned. */
    fun tonight(c: Context): List<String> = prefs(c).getString(Settings.profileKey(c, "tonight"), null)
        ?.let { runCatching { JSONArray(it) }.getOrNull() }
        ?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()

    fun setTonight(c: Context, ids: List<String>) =
        prefs(c).edit().putString(Settings.profileKey(c, "tonight"), JSONArray(ids.distinct()).toString()).apply()

    fun pinned(c: Context, id: String) = id in tonight(c)

    fun pin(c: Context, id: String, on: Boolean) = setTonight(c, if (on) tonight(c) + id else tonight(c) - id)
}

/**
 * Home's rows in order: the [saved] order first (catalogs no longer [available] dropped), then catalogs it doesn't
 * know yet. Without a saved order, the [defaults] lead, in the add-ons' order, and only they show.
 */
internal fun arrangeHomeRows(available: List<String>, defaults: Set<String>, saved: List<Pair<String, Boolean>>?): List<Pair<String, Boolean>> {
    if (saved == null) return available.filter { it in defaults }.map { it to true } + available.filter { it !in defaults }.map { it to false }
    val known = saved.filter { it.first in available }.distinctBy { it.first }
    val names = known.map { it.first }.toSet()
    return known + available.filter { it !in names }.map { it to (it in defaults) }
}

/** Continue Watching split into Tonight (the [pins] still in progress, in pin order) and the rest, in its own order. */
internal fun <T> splitTonight(items: List<T>, id: (T) -> String, pins: List<String>): Pair<List<T>, List<T>> {
    val byId = items.associateBy(id)
    val tonight = pins.distinct().mapNotNull { byId[it] }
    return tonight to items.filter { id(it) !in pins }
}

/** Moves the item at [from] by [by] places, clamped to the list's ends. */
internal fun <T> moved(list: List<T>, from: Int, by: Int): List<T> {
    val to = (from + by).coerceIn(0, list.lastIndex)
    return list.toMutableList().apply { add(to, removeAt(from)) }
}
