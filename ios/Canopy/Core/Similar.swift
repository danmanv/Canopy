import Foundation

/// "More like this" picked for each title, as on Android (Similar.kt): MDBList's recommendations (TMDB's "people who
/// liked this also liked"), with the same key as the Rotten Tomatoes scores. One lookup per title, kept for a week, so it
/// barely touches the free key's 1,000 lookups a day. Without a key, or for a title MDBList doesn't know, the caller
/// falls back to add-ons.
enum Similar {
    private static let week: TimeInterval = 7 * 24 * 3600
    // An empty answer (a title too new or obscure for recommendations) is asked again after a day.
    private static let day: TimeInterval = 24 * 3600
    private static let store = UserDefaults(suiteName: "similar") ?? .standard
    /// Failed lookups (no network, the day's lookups used up): not asked again until the app restarts.
    @MainActor private static var failed = Set<String>()

    /// Titles MDBList recommends for [id] (an IMDb id), movies and shows alike; nil when it can't say.
    @MainActor
    static func of(type: String, id: String) async -> [Meta]? {
        guard Ratings.enabled, id.hasPrefix("tt"), !failed.contains(id) else { return nil }
        let now = Date().timeIntervalSince1970
        if let v = store.string(forKey: id), let bar = v.firstIndex(of: "|"), let at = Double(v[..<bar]),
           let data = v[v.index(after: bar)...].data(using: .utf8),
           let kept = (try? JSONSerialization.jsonObject(with: data)) as? [Any] {
            if now - at < (kept.isEmpty ? day : week) { return metas(kept) }
        }
        let kind = type == "series" ? "show" : "movie"
        let url = "https://api.mdblist.com/imdb/\(kind)/\(id)?append_to_response=recommendations&apikey=\(Http.encode(Ratings.key))"
        guard let text = try? await Http.get(url, timeout: 10), let json = try? JSONText.parse(text) else {
            failed.insert(id)
            return nil
        }
        // A title MDBList doesn't know answers with an error: kept as "none" for a day, like an empty list.
        let recs = json.items("recommendations")
        if let data = try? JSONSerialization.data(withJSONObject: recs), let body = String(data: data, encoding: .utf8) {
            store.set("\(now)|\(body)", forKey: id)
        }
        return metas(recs)
    }

    /// MDBList's items as add-on style titles (Cinemeta ids), so rows, posters and scores treat them as any other.
    static func metas(_ items: [Any]) -> [Meta] {
        items.compactMap { $0 as? JSON }.compactMap { r in
            guard let imdb = r.text("imdb_id") ?? r.obj("ids")?.text("imdb"), imdb.hasPrefix("tt") else { return nil }
            var j: JSON = ["id": imdb, "type": r.text("mediatype") == "show" ? "series" : "movie", "name": r.string("title")]
            // MDBList sends TMDB's 200 px poster: a card wants the 500 px one.
            if let p = r.text("poster") { j["poster"] = p.replacingOccurrences(of: "/t/p/w200/", with: "/t/p/w500/") }
            if let y = r.text("release_year"), y != "0" { j["releaseInfo"] = y }
            return Meta(j)
        }
    }
}
