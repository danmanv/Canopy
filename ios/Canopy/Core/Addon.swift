import Foundation

/// One installed add-on, spoken to with the Stremio add-on protocol: plain GETs for catalogs, metadata and streams.
/// [descriptor] is the account's entry for it as Stremio stores it, kept whole.
final class Addon: Identifiable, @unchecked Sendable {
    let descriptor: JSON
    private let manifest: JSON
    let id: String
    let name: String
    let transportUrl: String
    let official: Bool
    let locked: Bool
    let providesStreams: Bool
    let personal: Bool
    private let base: String
    private(set) var catalogs: [Catalog] = []

    var shareable: Bool { !official && providesStreams && !personal }
    var logo: String? { manifest.text("logo") }
    var description: String? { manifest.text("description") }
    var version: String? { manifest.text("version") }

    init?(_ descriptor: JSON) {
        guard let manifest = descriptor.obj("manifest"), let transport = descriptor.text("transportUrl") else { return nil }
        self.descriptor = descriptor
        self.manifest = manifest
        let name = manifest.string("name")
        id = manifest.string("id")
        self.name = name
        transportUrl = transport
        official = descriptor.obj("flags")?.bool("official") == true
        locked = descriptor.obj("flags")?.bool("protected") == true
        providesStreams = manifest.items("resources").contains { Addon.resourceName($0) == "stream" }
        personal = ["watchly", "trakt", "simkl", "syncribullet", "aiolists"].contains { name.lowercased().contains($0) }
        base = transport.hasSuffix("/manifest.json") ? String(transport.dropLast("/manifest.json".count)) : transport
        catalogs = manifest.objects("catalogs").compactMap { c in Catalog.from(c, addon: self) }
    }

    private static func resourceName(_ item: Any) -> String? {
        (item as? String) ?? (item as? JSON)?.text("name")
    }

    /// [pages]: takes Stremio's `skip` extra. [required]: extras it can't be asked without, with Stremio's pick.
    struct Catalog: Identifiable {
        let addon: Addon
        let type: String
        let catalogId: String
        let name: String
        let pages: Bool
        let genres: [String]
        let required: [(String, String)]

        var id: String { "\(addon.id)/\(type)/\(catalogId)" }

        static func from(_ c: JSON, addon: Addon) -> Catalog? {
            guard let type = c.text("type"), let id = c.text("id") else { return nil }
            let extra = c.objects("extra")
            let extras = c.strings("extraSupported") + extra.map { $0.string("name") }
            var options = extra.first { $0.string("name") == "genre" }?.strings("options") ?? []
            if options.isEmpty { options = c.strings("genres") }
            var requiredNames = c.strings("extraRequired") + extra.filter { $0.bool("isRequired") }.map { $0.string("name") }
            requiredNames = requiredNames.reduce(into: [String]()) { if !$0.contains($1) { $0.append($1) } }
            var required = [(String, String)]()
            for name in requiredNames {
                let own = extra.first { $0.string("name") == name }
                var choices = own?.strings("options") ?? []
                if choices.isEmpty && name == "genre" { choices = options }
                if let d = own?.text("default"), choices.contains(d) { required.append((name, d)) }
                else if let first = choices.first { required.append((name, first)) }
                else { return nil } // a required extra with no options (a search query): not a browsable catalog
            }
            return Catalog(addon: addon, type: type, catalogId: id, name: c.text("name") ?? id, pages: extras.contains("skip"),
                           genres: options.filter { $0.lowercased() != "none" }, required: required)
        }
    }

    /// Whether this add-on answers [resource] ("meta", "stream", …) for this type and id.
    func serves(_ resource: String, type: String, id: String) -> Bool {
        guard let entry = manifest.items("resources").first(where: { Addon.resourceName($0) == resource }) else { return false }
        let own = entry as? JSON
        let types = (own?["types"] != nil ? own!.strings("types") : manifest.strings("types"))
        let prefixes: [String]? = own?["idPrefixes"] != nil ? own!.strings("idPrefixes")
            : (manifest["idPrefixes"] is [Any] ? manifest.strings("idPrefixes") : nil)
        return types.contains(type) && (prefixes == nil || prefixes!.contains { id.hasPrefix($0) })
    }

    // MARK: Catalogs

    func catalog(_ c: Catalog, genre: String? = nil, skip: Int = 0, timeout: TimeInterval = 20) async throws -> [Meta] {
        let json = try JSONText.parse(try await Http.get(catalogUrl(c, genre: genre, skip: skip), keep: skip == 0, timeout: timeout))
        return json.objects("metas").compactMap(Meta.init)
    }

    func savedCatalog(_ c: Catalog, genre: String? = nil) -> [Meta]? {
        guard let text = Http.savedAnswer(catalogUrl(c, genre: genre, skip: 0)), let json = try? JSONText.parse(text) else { return nil }
        return json.objects("metas").compactMap(Meta.init)
    }

    /// /catalog/type/id/genre=Drama&skip=20.json
    private func catalogUrl(_ c: Catalog, genre: String?, skip: Int) -> String {
        var extras = c.required.filter { !(genre != nil && $0.0 == "genre") }
        if let genre { extras.append(("genre", genre)) }
        if skip > 0 { extras.append(("skip", "\(skip)")) }
        let path = url(["catalog", c.type, c.catalogId])
        if extras.isEmpty { return path }
        return String(path.dropLast(5)) + "/" + extras.map { "\($0.0)=\(Http.encode($0.1))" }.joined(separator: "&") + ".json"
    }

    /// Every movie and series search catalog the add-on advertises.
    func search(_ query: String) async throws -> [Meta] {
        let catalogs = manifest.objects("catalogs").filter { c in
            let type = c.string("type")
            let names = c.strings("extraRequired") + c.strings("extraSupported") + c.objects("extra").map { $0.string("name") }
            return ["movie", "series"].contains(type) && names.contains("search")
                && c.strings("extraRequired").allSatisfy { $0 == "search" }
                && !c.objects("extra").contains { $0.bool("isRequired") && $0.string("name") != "search" }
        }
        var seen = Set<String>()
        let unique = catalogs.filter { seen.insert($0.string("type") + "/" + $0.string("id")).inserted }
        if unique.isEmpty { return [] }
        let results = await withTaskGroup(of: (Int, [Meta]?).self) { group -> [(Int, [Meta]?)] in
            for (i, c) in unique.enumerated() {
                group.addTask {
                    let path = String(self.url(["catalog", c.string("type"), c.string("id")]).dropLast(5))
                    let answer = try? JSONText.parse(try await Http.get("\(path)/search=\(Http.encode(query)).json", timeout: 6))
                    return (i, answer.map { $0.objects("metas").compactMap(Meta.init) })
                }
            }
            var all = [(Int, [Meta]?)]()
            for await r in group { all.append(r) }
            return all
        }
        if !results.contains(where: { $0.1 != nil }) { throw CanopyError.message("Search unavailable") }
        return results.sorted { $0.0 < $1.0 }.flatMap { $0.1 ?? [] }
    }

    // MARK: Details, streams, subtitles

    func meta(type: String, id: String) async throws -> Meta? {
        let json = try await get(keep: true, ["meta", type, id])
        return json.obj("meta").flatMap { Meta(withId: $0, id: id) }
    }

    func savedMeta(type: String, id: String) -> Meta? {
        guard let text = Http.savedAnswer(url(["meta", type, id])), let json = try? JSONText.parse(text) else { return nil }
        return json.obj("meta").flatMap { Meta(withId: $0, id: id) }
    }

    /// A title-specific catalog, when an add-on advertises the protocol's required `similar` extra.
    func similar(type: String, id: String) async throws -> [Meta]? {
        guard let c = manifest.objects("catalogs").first(where: { c in
            c.string("type") == type && c.objects("extra").contains { $0.string("name") == "similar" && $0.bool("isRequired") }
                && (c.strings("idPrefixes").isEmpty || c.strings("idPrefixes").contains { id.hasPrefix($0) })
        }) else { return nil }
        let path = String(url(["catalog", type, c.string("id")]).dropLast(5))
        return try JSONText.parse(try await Http.get("\(path)/similar=\(Http.encode(id, allow: ":")).json", keep: true))
            .objects("metas").compactMap(Meta.init)
    }

    struct Streams { let playable: [Stream]; let torrents: Int }

    /// Playable streams only: torrent-only results (no url) need a streaming server, which Canopy doesn't have.
    func streams(type: String, videoId: String) async throws -> Streams {
        let all = try await get(keep: false, ["stream", type, videoId]).objects("streams")
        return Streams(playable: all.compactMap(Stream.from),
                       torrents: all.filter { $0.text("url") == nil && $0.text("infoHash") != nil }.count)
    }

    func subtitles(type: String, videoId: String) async throws -> [Subtitle] {
        try await get(keep: false, ["subtitles", type, videoId]).objects("subtitles").compactMap { s in
            guard let url = s.text("url") else { return nil }
            return Subtitle(url: url, lang: s.text("lang") ?? "und", addon: name)
        }
    }

    // Ids such as "tt0903747:1:2" keep their colons, like Stremio's own requests.
    private func url(_ path: [String]) -> String {
        base + "/" + path.map { Http.encode($0, allow: ":=") }.joined(separator: "/") + ".json"
    }

    private func get(keep: Bool, _ path: [String]) async throws -> JSON {
        try JSONText.parse(try await Http.get(url(path), keep: keep))
    }
}

extension Array where Element == Addon {
    /// A title's details from the first add-on, in the account's order, that has them. [together] asks them all at once.
    func meta(type: String, id: String, together: Bool = false) async -> Meta? {
        let serving = filter { $0.serves("meta", type: type, id: id) }
        if !together || serving.count < 2 {
            for a in serving { if let m = try? await a.meta(type: type, id: id) { return m } }
            return nil
        }
        return await withTaskGroup(of: (Int, Meta?).self) { group in
            for (i, a) in serving.enumerated() { group.addTask { (i, try? await a.meta(type: type, id: id)) } }
            var answers = [Int: Meta?]()
            var next = 0
            for await (i, m) in group {
                answers[i] = m
                // The first in the account's order that has them wins, as soon as every add-on before it has answered.
                while let a = answers[next] {
                    if let a { group.cancelAll(); return a }
                    next += 1
                }
            }
            return nil
        }
    }

    func savedMeta(type: String, id: String) -> Meta? {
        for a in self where a.serves("meta", type: type, id: id) { if let m = a.savedMeta(type: type, id: id) { return m } }
        return nil
    }
}

struct Meta: Identifiable, Hashable {
    static func == (a: Meta, b: Meta) -> Bool { a.id == b.id && a.type == b.type }
    func hash(into h: inout Hasher) { h.combine(id); h.combine(type) }

    let json: JSON
    let id: String
    let type: String
    let name: String
    let poster: String?
    let background: String?
    let logo: String?
    let description: String?
    let year: String?
    let runtime: String?
    let rating: String?
    let genres: [String]
    let cast: [String]
    let directors: [String]
    let trailer: String?
    let videos: [Video]

    var info: String { [year, runtime, genres.isEmpty ? nil : genres.joined(separator: " · ")].compactMap { $0 }.joined(separator: "   ") }

    init?(_ json: JSON) {
        guard let id = json.text("id") else { return nil }
        self.json = json
        self.id = id
        type = json.string("type")
        name = json.string("name")
        poster = json.text("poster")
        background = json.text("background")
        logo = json.text("logo")
        description = json.text("description")
        year = json.text("releaseInfo").map { $0.trimmingCharacters(in: CharacterSet(charactersIn: "–- ")) }
        runtime = json.text("runtime").map(formatRuntime)
        rating = json.text("imdbRating")
        genres = Array(json.strings("genres").prefix(3))
        let links = json.objects("links")
        func linked(_ category: String) -> [String] { links.filter { $0.string("category") == category }.compactMap { $0.text("name") } }
        cast = json.strings("cast").isEmpty ? linked("Cast") : json.strings("cast")
        directors = json.strings("director").isEmpty ? linked("Directors") : json.strings("director")
        let trailers = json.objects("trailerStreams").map { $0.text("ytId") }
            + json.objects("trailers").sorted { ($0.text("type") ?? "Trailer") == "Trailer" && ($1.text("type") ?? "Trailer") != "Trailer" }.map { $0.text("source") }
        trailer = trailers.compactMap { $0.flatMap(youTubeId) }.first
        videos = json.objects("videos").compactMap { v in
            guard let vid = v.text("id") else { return nil }
            return Video(id: vid, title: v.text("title") ?? v.text("name") ?? "", season: v.int("season"), episode: v.int("episode"),
                         thumbnail: v.text("thumbnail"), overview: v.text("overview") ?? v.text("description"),
                         released: v.text("released") ?? v.text("available"))
        }.sorted { a, b in
            let sa = a.season == 0 ? Int.max : a.season, sb = b.season == 0 ? Int.max : b.season
            return sa != sb ? sa < sb : a.episode < b.episode
        }
    }

    /// Some add-ons leave the title's own id out of its details: it's the one asked for.
    init?(withId json: JSON, id: String) {
        var j = json
        if j.text("id") == nil { j["id"] = id }
        self.init(j)
    }

    var seasons: [Int] {
        var s = [Int]()
        for v in videos where !s.contains(v.season) { s.append(v.season) }
        return s
    }
}

struct Video: Identifiable, Hashable {
    let id: String
    let title: String
    let season: Int
    let episode: Int
    let thumbnail: String?
    let overview: String?
    let released: String?

    var releasedDate: Date? {
        guard let released else { return nil }
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let d = f.date(from: released) { return d }
        f.formatOptions = [.withInternetDateTime]
        return f.date(from: released)
    }

    var label: String { season > 0 ? "S\(season) E\(episode)" : "Special \(episode)" }
}

struct Subtitle: Hashable {
    let url: String
    let lang: String
    let addon: String
    var language: String { Languages.name(lang) }
}

/// "147 min" reads "2h 27m"; under an hour, and anything not in minutes, stays as the add-on wrote it.
func formatRuntime(_ text: String) -> String {
    let t = text.trimmingCharacters(in: .whitespaces)
    var minutes: Int?
    if let hm = Rx.groups(#"^(\d+)\s*h\s*(\d+)\s*m(in)?$"#, t) { minutes = Int(hm[1])! * 60 + Int(hm[2])! }
    else if let m = Rx.groups(#"^(\d+)\s*min"#, t) { minutes = Int(m[1]) }
    guard let minutes else { return text }
    return minutes < 60 ? "\(minutes) min" : "\(minutes / 60)h " + String(format: "%02dm", minutes % 60)
}

func youTubeId(_ text: String) -> String? {
    let t = text.trimmingCharacters(in: .whitespaces)
    if Rx.has(#"^[A-Za-z0-9_-]{11}$"#, t) { return t }
    return Rx.groups(#"(?:v=|youtu\.be/|embed/|shorts/)([A-Za-z0-9_-]{11})"#, t)?[1]
}

/// An add-on's "sign in first" card dressed up as a title.
func isAddonMessage(_ name: String) -> Bool {
    name.range(of: "Authentication Required", options: .caseInsensitive) != nil
}
