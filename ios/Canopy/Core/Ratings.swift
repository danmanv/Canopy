import Foundation

/// Rotten Tomatoes (and IMDb) scores from MDBList, beside the IMDb rating the add-ons send, as on Android (Ratings.kt).
/// Each title is asked for once and kept for a week, so the free key's 1,000 lookups a day go a long way. Without a key
/// in the build (the MDBLIST_API_KEY build setting, from the GitHub secret in CI) nothing is asked: IMDb only.
enum Ratings {
    struct Scores: Equatable {
        /// The critics' Tomatometer and the audience score, in percent; IMDb as "8.1".
        var tomatoes: Int?
        var popcorn: Int?
        var imdb: String?
        var isEmpty: Bool { tomatoes == nil && popcorn == nil && imdb == nil }
    }

    static let key: String = (Bundle.main.object(forInfoDictionaryKey: "MDBListKey") as? String ?? "")
        .trimmingCharacters(in: .whitespacesAndNewlines)
    static var enabled: Bool { !key.isEmpty && !key.hasPrefix("$(") }

    private static let week: TimeInterval = 7 * 24 * 3600
    // A title MDBList doesn't know (or a failed lookup) is asked again after a day, not on every visit.
    private static let day: TimeInterval = 24 * 3600
    private static let store = UserDefaults(suiteName: "ratings") ?? .standard

    /// What's kept for [id], without the network; nil when nothing fresh is kept.
    static func cached(_ id: String) -> Scores? { entry(id)?.scores }

    /// "🍅 94%", or the audience's "🍿 88%" when critics have no score (common for TV).
    static func tomato(_ s: Scores?) -> String? {
        if let t = s?.tomatoes { return "🍅 \(t)%" }
        if let p = s?.popcorn { return "🍿 \(p)%" }
        return nil
    }

    /// "IMDb 8.1 · 🍅 94%" from the add-on's [imdb] rating (else MDBList's) and [scores].
    static func line(imdb: String?, _ scores: Scores?) -> String? {
        let parts = [(imdb ?? scores?.imdb).map { "IMDb \($0)" }, tomato(scores)].compactMap { $0 }
        return parts.isEmpty ? nil : parts.joined(separator: " · ")
    }

    /// Kept scores for an IMDb id, or MDBList's answer when nothing fresh is kept. Asks made close together (a row of
    /// posters) go as one batch of up to 100 titles, so a screenful costs one lookup, not one per poster.
    @MainActor
    static func fetch(_ id: String, type: String) async -> Scores? {
        if let e = entry(id) { return e.scores }
        if let m = failed[id], Date().timeIntervalSince(m) < day { return nil }
        guard enabled, id.hasPrefix("tt") else { return nil }
        let kind = type == "series" ? "show" : "movie"
        return await withCheckedContinuation { c in
            waiting[id, default: []].append(c)
            if waiting[id]!.count == 1 { queued[kind, default: []].append(id) }
            if !flushing {
                flushing = true
                Task { @MainActor in
                    try? await Task.sleep(nanoseconds: 250_000_000)
                    await flush()
                }
            }
        }
    }

    @MainActor private static var waiting = [String: [CheckedContinuation<Scores?, Never>]]()
    @MainActor private static var queued = [String: [String]]()
    @MainActor private static var flushing = false
    /// Lookups that failed (no network, the day's lookups used up), until the app restarts or a day passes.
    @MainActor private static var failed = [String: Date]()

    @MainActor
    private static func flush() async {
        flushing = false
        let batches = queued.flatMap { kind, ids in stride(from: 0, to: ids.count, by: 100).map { (kind, Array(ids[$0..<min($0 + 100, ids.count)])) } }
        queued = [:]
        await withTaskGroup(of: Void.self) { group in
            for (kind, ids) in batches {
                group.addTask { @MainActor in
                    let found = await lookup(kind, ids)
                    for id in ids {
                        let s = found?[id] ?? nil
                        if found != nil { keep(id, s) } else { failed[id] = Date() }
                        waiting.removeValue(forKey: id)?.forEach { $0.resume(returning: s) }
                    }
                }
            }
        }
    }

    /// Scores by IMDb id, nil when the lookup failed.
    private static func lookup(_ kind: String, _ ids: [String]) async -> [String: Scores?]? {
        let base = "https://api.mdblist.com/imdb/\(kind)/"
        if ids.count == 1 {
            guard let text = try? await Http.get(base + ids[0] + "?apikey=\(Http.encode(key))", timeout: 10),
                  let json = try? JSONText.parse(text), json["error"] == nil else { return nil }
            return [ids[0]: parse(json)]
        }
        guard let body = try? JSONSerialization.data(withJSONObject: ["ids": ids]), let bodyText = String(data: body, encoding: .utf8),
              let text = try? await Http.post(base + "?apikey=\(Http.encode(key))", json: bodyText),
              let data = text.data(using: .utf8), let list = (try? JSONSerialization.jsonObject(with: data)) as? [Any] else { return nil }
        var out = [String: Scores?]()
        for case let item as JSON in list {
            if let imdb = item.obj("ids")?.text("imdb") { out[imdb] = parse(item) }
        }
        return out
    }

    static func parse(_ json: JSON) -> Scores? {
        let ratings = json.objects("ratings")
        func value(_ source: String) -> Double? {
            guard let r = ratings.first(where: { $0.text("source") == source }), let n = r["value"] as? NSNumber else { return nil }
            let v = n.doubleValue
            return v > 0 ? v : nil
        }
        let audience = value("popcorn") ?? value("tomatoesaudience") ?? value("audience")
        let s = Scores(tomatoes: value("tomatoes").map { Int($0) }, popcorn: audience.map { Int($0) },
                       imdb: value("imdb").map { String(format: "%.1f", $0) })
        return s.isEmpty ? nil : s
    }

    private static func entry(_ id: String) -> (at: Date, scores: Scores?)? {
        guard let v = store.string(forKey: id) else { return nil }
        let p = v.components(separatedBy: "|")
        guard p.count >= 4, let t = Double(p[0]) else { return nil }
        let s = Scores(tomatoes: Int(p[1]), popcorn: Int(p[2]), imdb: p[3].isEmpty ? nil : p[3])
        let at = Date(timeIntervalSince1970: t)
        let life = s.isEmpty ? day : week
        return Date().timeIntervalSince(at) < life ? (at, s.isEmpty ? nil : s) : nil
    }

    private static func keep(_ id: String, _ s: Scores?) {
        let parts = [String(Date().timeIntervalSince1970), s?.tomatoes.map(String.init) ?? "", s?.popcorn.map(String.init) ?? "", s?.imdb ?? ""]
        store.set(parts.joined(separator: "|"), forKey: id)
    }
}
