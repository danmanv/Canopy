import Foundation
import SwiftUI

struct Profile: Codable, Identifiable, Hashable {
    var id: String
    var name: String
    var color: UInt32
    var authKey: String
    var pin: String?
}

/// The profiles on this device, each its own Stremio account, kept on the device as the TV keeps its own.
enum Profiles {
    static let max = 5
    /// Warm and neutral, no greens, the TV's five.
    static let colors: [UInt32] = [0xFFF3D9B1, 0xFFC98E6A, 0xFFA3949E, 0xFFA6A2BD, 0xFFD3A66B]
    private static let d = UserDefaults.standard

    static func all() -> [Profile] {
        guard let data = d.data(forKey: "profiles"), let list = try? JSONDecoder().decode([Profile].self, from: data) else { return [] }
        return list
    }

    static func save(_ list: [Profile]) {
        if let data = try? JSONEncoder().encode(list) { d.set(data, forKey: "profiles") }
    }

    static var activeId: String? {
        get { d.string(forKey: "active") }
        set { d.set(newValue, forKey: "active") }
    }

    static func add(name: String, authKey: String, color: UInt32) -> Profile {
        let list = all()
        let number = (1...max).first { n in !list.contains { $0.id == "\(n)" } } ?? (list.count + 1)
        let p = Profile(id: "\(number)", name: name, color: color, authKey: authKey, pin: nil)
        save(list + [p])
        return p
    }

    static func update(_ p: Profile) { save(all().map { $0.id == p.id ? p : $0 }) }

    static func remove(_ id: String) {
        save(all().filter { $0.id != id })
        if activeId == id { activeId = nil }
        d.removeObject(forKey: "signedOut:\(id)")
        d.removeObject(forKey: "shareStreams:\(id)")
        for key in d.dictionaryRepresentation().keys where key.hasSuffix("@\(id)") { d.removeObject(forKey: key) }
    }

    static func signedOut(_ id: String) -> Bool { d.bool(forKey: "signedOut:\(id)") }
    static func markSignedOut(_ id: String, _ on: Bool = true) { d.set(on, forKey: "signedOut:\(id)") }

    static func sharesStreams(_ id: String) -> Bool { d.bool(forKey: "shareStreams:\(id)") }
    static func setSharesStreams(_ id: String, _ on: Bool) { d.set(on, forKey: "shareStreams:\(id)") }

    /// The other profiles lending their stream add-ons to [current].
    static func streamLenders(_ current: Profile) -> [Profile] {
        all().filter { $0.id != current.id && sharesStreams($0.id) }
    }
}

/// Settings, as the TV's Settings: some per device, some per profile ("key@profileId").
enum Settings {
    private static let d = UserDefaults.standard
    static var profileId = ""

    private static func pk(_ key: String) -> String { "\(key)@\(profileId)" }

    // Stream choices
    static var maxHeight: Int { get { d.integer(forKey: "maxHeight") } set { d.set(newValue, forKey: "maxHeight") } }
    static var maxSizeGb: Int { get { d.integer(forKey: "maxSizeGb") } set { d.set(newValue, forKey: "maxSizeGb") } }
    /// "" no preference, "dv", "hdr10", "off" (never HDR). A phone screen gets HDR wrong through VLC: off by default.
    static var hdrPref: String { get { d.string(forKey: "hdrPref") ?? "" } set { d.set(newValue, forKey: "hdrPref") } }
    static var allowHdr: Bool { hdrPref != "off" }
    static var release: String { get { d.string(forKey: pk("release")) ?? "" } set { d.set(newValue, forKey: pk("release")) } }
    static var dub: String { get { d.string(forKey: pk("dub")) ?? "" } set { d.set(newValue, forKey: pk("dub")) } }
    static var alwaysList: Bool { get { d.bool(forKey: "alwaysList") } set { d.set(newValue, forKey: "alwaysList") } }

    // Languages and subtitles
    static var audioLanguage: String {
        get { d.string(forKey: pk("audioLang")) ?? Locale.current.language.languageCode?.identifier ?? "en" }
        set { d.set(newValue, forKey: pk("audioLang")) }
    }
    static var subtitleLanguage: String { get { d.string(forKey: pk("subLang")) ?? "" } set { d.set(newValue, forKey: pk("subLang")) } }
    static var subtitles: Bool { get { d.object(forKey: "subtitles") as? Bool ?? true } set { d.set(newValue, forKey: "subtitles") } }
    static var subtitleSize: Int { get { d.object(forKey: "subSize") as? Int ?? 100 } set { d.set(newValue, forKey: "subSize") } }

    // Player and look
    /// "fit", "fill" or "stretch", per profile.
    static var pictureSize: String { get { d.string(forKey: pk("pictureSize")) ?? "fit" } set { d.set(newValue, forKey: pk("pictureSize")) } }
    static var trailers: Bool { get { d.object(forKey: "trailers") as? Bool ?? true } set { d.set(newValue, forKey: "trailers") } }
    static var nextEpisode: Bool { get { d.object(forKey: "nextEpisode") as? Bool ?? true } set { d.set(newValue, forKey: "nextEpisode") } }
    static var skipIntro: Bool { get { d.object(forKey: "skipIntro") as? Bool ?? true } set { d.set(newValue, forKey: "skipIntro") } }
    static var theme: String { get { d.string(forKey: "theme") ?? Theme.defaultId } set { d.set(newValue, forKey: "theme") } }

    /// The audio and subtitle tracks picked in the player for a title, so the next episode starts with them.
    static func titleChoice(_ metaId: String) -> (audio: String?, subtitles: String?) {
        let j = d.dictionary(forKey: pk("title:\(metaId)")) ?? [:]
        return (j["audio"] as? String, j["subtitles"] as? String)
    }

    static func pickedInPlayer(_ metaId: String, audio: String? = nil, subtitles: String? = nil) {
        var j = d.dictionary(forKey: pk("title:\(metaId)")) ?? [:]
        if let audio { j["audio"] = audio }
        if let subtitles { j["subtitles"] = subtitles }
        d.set(j, forKey: pk("title:\(metaId)"))
    }

    // Recent searches, per profile
    static var recentSearches: [String] {
        get { d.stringArray(forKey: pk("recent")) ?? [] }
        set { d.set(Array(newValue.prefix(8)), forKey: pk("recent")) }
    }

    // The file and release played last, so the next pick prefers the same (debrid links change, the release doesn't).
    static func lastFile(_ videoId: String) -> String? { d.string(forKey: "file:\(videoId)") }
    static func setLastFile(_ videoId: String, _ file: String?) { d.set(file, forKey: "file:\(videoId)") }
    static func binge(_ metaId: String) -> String? { d.string(forKey: "binge:\(metaId)") }
    static func setBinge(_ metaId: String, _ group: String?) { d.set(group, forKey: "binge:\(metaId)") }

    static var presetName: String {
        switch (release, hdrPref, maxHeight, maxSizeGb) {
        case ("", "", 0, 0): return "Add-on's order"
        case ("remux", "dv", 0, 0): return "Best picture"
        case ("encode", "", 0, 20): return "Balanced"
        case ("web", "", 1080, 10): return "Fast start"
        default: return "Custom"
        }
    }
}

/// Home's rows per profile: which catalogs show and in what order, and the titles pinned to Tonight.
enum HomeRows {
    private static let d = UserDefaults.standard
    private static var key: String { "homeRows@\(Settings.profileId)" }

    /// Watchly's "Because you watched" rows: Home's rows until the viewer picks their own.
    static func onByDefault(_ c: Addon.Catalog) -> Bool { c.addon.id.contains("watchly") && c.catalogId.hasPrefix("watchly.item") }

    static func saved() -> [(String, Bool)]? {
        guard let a = d.array(forKey: key) as? [[String: Any]] else { return nil }
        return a.map { ($0["key"] as? String ?? "", $0["on"] as? Bool ?? false) }
    }

    static func save(_ rows: [(String, Bool)]) {
        d.set(rows.map { ["key": $0.0, "on": $0.1] as [String: Any] }, forKey: key)
    }

    static func reset() { d.removeObject(forKey: key) }

    static func arranged(_ catalogs: [Addon.Catalog]) -> [(Addon.Catalog, Bool)] {
        var byKey = [String: Addon.Catalog]()
        var order = [String]()
        for c in catalogs where byKey[c.id] == nil { byKey[c.id] = c; order.append(c.id) }
        let defaults = Set(catalogs.filter(onByDefault).map(\.id))
        return arrange(available: order, defaults: defaults, saved: saved()).compactMap { k, on in byKey[k].map { ($0, on) } }
    }

    static func saveArranged(_ rows: [(Addon.Catalog, Bool)]) {
        let keys = Set(rows.map { $0.0.id })
        save(rows.map { ($0.0.id, $0.1) } + (saved() ?? []).filter { !keys.contains($0.0) })
    }

    static func arrange(available: [String], defaults: Set<String>, saved: [(String, Bool)]?) -> [(String, Bool)] {
        guard let saved else {
            return available.filter { defaults.contains($0) }.map { ($0, true) } + available.filter { !defaults.contains($0) }.map { ($0, false) }
        }
        var seen = Set<String>()
        let known = saved.filter { available.contains($0.0) && seen.insert($0.0).inserted }
        return known + available.filter { !seen.contains($0) }.map { ($0, defaults.contains($0)) }
    }

    static var tonight: [String] {
        get { d.stringArray(forKey: "tonight@\(Settings.profileId)") ?? [] }
        set {
            var seen = Set<String>()
            d.set(newValue.filter { seen.insert($0).inserted }, forKey: "tonight@\(Settings.profileId)")
        }
    }

    static func pin(_ id: String, _ on: Bool) { tonight = on ? tonight + [id] : tonight.filter { $0 != id } }
}
