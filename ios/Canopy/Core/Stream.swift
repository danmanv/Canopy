import Foundation

/// One playable source from a stream add-on, with what its names say about the release (Android's Stream).
struct Stream: Hashable, Identifiable {
    let title: String
    let description: String
    let url: String
    let headers: [String: String]
    let size: Int64
    let filename: String?
    let bingeGroup: String?
    var addon: String = ""

    var id: String { url }
    static func == (a: Stream, b: Stream) -> Bool { a.url == b.url && a.title == b.title }
    func hash(into h: inout Hasher) { h.combine(url); h.combine(title) }

    private var words: String { "\(filename ?? "") \(title) \(description)".lowercased() }

    /// False for add-on "actions" dressed up as streams (MyTrakt's "Mark Watched"). Unknown size counts as a video.
    var isVideo: Bool { size == 0 || size >= 50 * 1024 * 1024 }

    var height: Int {
        let w = words
        if Rx.has(#"2160|\b4k\b|\buhd\b"#, w) { return 2160 }
        if w.contains("1080") { return 1080 }
        if w.contains("720") { return 720 }
        if w.contains("576") { return 576 }
        if w.contains("480") { return 480 }
        return 0
    }

    var dolbyVision: Bool { Rx.has(#"\b(dv|dovi|dolby.?vision)\b"#, words) }
    var hdr: Bool { Rx.has(#"\bhdr|\bp8\b|hybrid"#, words) }
    var isHdr: Bool { hdr || dolbyVision }
    var dolbyVisionOnly: Bool { dolbyVision && !hdr }
    var hevc: Bool { Rx.has(#"hevc|[xh].?265"#, words) }
    var av1: Bool { Rx.has(#"\bav1\b"#, words) }
    var remux: Bool { Rx.has(#"\bremux\b"#, words) }
    var bluray: Bool { !remux && Rx.has(#"blu.?ray|\bbd.?rip|\bbr.?rip"#, words) }
    var web: Bool { Rx.has(#"web.?(dl|rip)|\bweb\b"#, words) }
    /// Filmed in a cinema or a screener: never picked.
    var cam: Bool { Rx.has(#"\b(cam|camrip|hdcam|ts|hdts|telesync|tc|telecine|scr|screener|dvdscr)\b"#, words) }
    var threeD: Bool { Rx.has(#"\b3d\b"#, words) }
    var hardSubs: Bool { Rx.has(#"\bhc\b|hardcoded|hardsub"#, words) }
    var awkward: Bool { threeD || hardSubs }
    var bigAudio: Bool { Rx.has(#"atmos|truehd|dts.?hd|dts.?x\b"#, words) }
    var multiAudio: Bool { Rx.has(#"\b(multi|dual)\b"#, words) }
    var dubbed: Bool { Rx.has(#"\bdub(bed)?\b"#, words) }
    var audioLanguages: Set<String> { audioLanguagesIn(words) }

    func mentions(_ language: String) -> Bool {
        let name = (Locale(identifier: "en").localizedString(forLanguageCode: language) ?? "").lowercased()
        return name.count > 2 && words.contains(name)
    }

    /// The debrid service it comes through, nil for a direct link.
    var debrid: Debrid? { debridOf(title, url) }

    /// The add-on's name for it on one line: "Torrentio (2160p)" becomes "Torrentio  ·  2160p".
    var label: String {
        let line = title.components(separatedBy: .newlines).map { $0.trimmingCharacters(in: .whitespaces) }.joined(separator: " ")
        let clean = Rx.replace(debridTagPattern, in: line, with: "", .caseInsensitive).trimmingCharacters(in: .whitespaces)
        return Rx.replace(#"\s*\((.+?)\)\s*$"#, in: clean, with: "  ·  $1") + (debrid.map { "  ·  \($0.name)" } ?? "")
    }

    /// Short tags for the source list: "REMUX · DV · Atmos".
    var tags: [String] {
        let w = words
        var t = [String]()
        if remux { t.append("REMUX") }
        if bluray { t.append("BluRay") }
        if web && !remux && !bluray { t.append("WEB") }
        if dolbyVision { t.append("DV") }
        if hdr { t.append("HDR") }
        if w.contains("atmos") { t.append("Atmos") }
        if w.contains("truehd") { t.append("TrueHD") }
        if Rx.has(#"dts.?hd"#, w) { t.append("DTS-HD") }
        if multiAudio { t.append("Multi audio") }
        if dubbed { t.append("Dub") }
        if threeD { t.append("3D") }
        if hardSubs { t.append("Hard subs") }
        return t
    }

    /// "BluRay · HEVC · Atmos": what the release is, in a few words.
    var summary: String {
        let w = words
        let release: String = remux || bluray ? "BluRay" : web ? "WEB" : w.contains("hdtv") ? "HDTV" : w.contains("sdtv") ? "SDTV" : "Source"
        let codec: String? = av1 ? "AV1" : hevc ? "HEVC" : Rx.has(#"avc|[xh].?264"#, w) ? "AVC" : nil
        let audio: String? = w.contains("atmos") ? "Atmos"
            : Rx.has(#"\b(dd|ac3|eac3|e-ac3)[ ._-]?5[ ._-]?1\b"#, w) ? "DD 5.1"
            : Rx.has(#"\b5[ ._-]?1\b"#, w) ? "5.1"
            : Rx.has(#"\b2[ ._-]?0\b|stereo"#, w) ? "2.0" : nil
        return [release, codec, audio].compactMap { $0 }.joined(separator: " · ")
    }

    /// Who found it: the add-on's own provider name when it gives one, else the add-on.
    var provider: String {
        var first = title.components(separatedBy: .newlines).first ?? ""
        if let paren = first.firstIndex(of: "(") { first = String(first[..<paren]) }
        first = Rx.replace(#"^\[[^\]]+\]\s*"#, in: first, with: "").trimmingCharacters(in: .whitespaces)
        if first.isEmpty || Rx.has(#"^\d+p$"#, first) { return addon }
        return first
    }

    var sizeText: String {
        if size <= 0 { return "—" }
        if size >= 1_000_000_000 { return String(format: "%.1f GB", Double(size) / 1_000_000_000) }
        return "\(size / 1_000_000) MB"
    }

    /// The size: the add-on's hint, else the "💾 12.3 GB" Torrentio and others write into the description.
    static func sizeFromText(_ text: String) -> Int64 {
        guard let g = Rx.groups(#"(?i)(?:💾|size[: ]+)\s*([\d.,]+)\s*(tb|gb|mb)"#, text),
              let n = Double(g[1].replacingOccurrences(of: ",", with: ".")) else { return 0 }
        let unit: Double = g[2].lowercased() == "tb" ? 1e12 : g[2].lowercased() == "gb" ? 1e9 : 1e6
        return Int64(n * unit)
    }

    static func from(_ json: JSON) -> Stream? {
        guard let url = json.text("url") else { return nil }
        let hints = json.obj("behaviorHints")
        let request = hints?.obj("proxyHeaders")?.obj("request") ?? [:]
        var headers = [String: String]()
        for (k, v) in request { if let s = v as? String { headers[k] = s } }
        let description = json.text("description") ?? json.text("title") ?? ""
        let name = json.text("name") ?? ""
        var size = hints?.int64("videoSize") ?? 0
        if size == 0 { size = sizeFromText(description + " " + name) }
        return Stream(title: name, description: description, url: url, headers: headers, size: size,
                      filename: hints?.text("filename"), bingeGroup: hints?.text("bingeGroup"))
    }
}

// MARK: Audio languages

/// Audio languages, as two-letter codes, from a stream's names: Torrentio's flags, AIOStreams' "English | Hindi", and
/// file-name tags (ITA, HINDI, TRUEFRENCH). Names only, not the file's real tracks.
func audioLanguagesIn(_ text: String) -> Set<String> {
    var found = Set<String>()
    let letters = text.unicodeScalars.map { Int($0.value) - 0x1F1E6 }
    var i = 0
    while i < letters.count - 1 {
        if (0...25).contains(letters[i]) && (0...25).contains(letters[i + 1]) {
            let a = Character(UnicodeScalar(UInt8(97 + letters[i]))), b = Character(UnicodeScalar(UInt8(97 + letters[i + 1])))
            if let l = flagLanguages["\(a)\(b)"] { found.insert(l) }
            i += 2
        } else { i += 1 }
    }
    for w in Rx.all("[a-z]+", text.lowercased()) { if let l = languageWords[w] { found.insert(l) } }
    return found
}

private let flagLanguages: [String: String] = ["gb": "en", "us": "en", "au": "en", "it": "it", "in": "hi", "es": "es",
    "mx": "es", "fr": "fr", "de": "de", "pt": "pt", "br": "pt", "ru": "ru", "jp": "ja", "kr": "ko",
    "cn": "zh", "tw": "zh", "hk": "zh", "nl": "nl", "pl": "pl", "tr": "tr", "se": "sv", "dk": "da",
    "no": "no", "fi": "fi", "gr": "el", "il": "he", "sa": "ar", "ae": "ar", "eg": "ar", "th": "th",
    "vn": "vi", "id": "id", "ua": "uk", "cz": "cs", "hu": "hu", "ro": "ro", "ir": "fa", "pk": "ur", "bd": "bn"]

private let languageWords: [String: String] = ["english": "en", "eng": "en", "hindi": "hi", "hin": "hi", "italian": "it",
    "ita": "it", "spanish": "es", "spa": "es", "esp": "es", "latino": "es", "castellano": "es",
    "french": "fr", "fre": "fr", "fra": "fr", "vff": "fr", "truefrench": "fr", "german": "de", "ger": "de",
    "deu": "de", "portuguese": "pt", "russian": "ru", "rus": "ru", "japanese": "ja", "jpn": "ja",
    "korean": "ko", "kor": "ko", "chinese": "zh", "mandarin": "zh", "cantonese": "zh", "dutch": "nl",
    "polish": "pl", "turkish": "tr", "swedish": "sv", "danish": "da", "norwegian": "no", "finnish": "fi",
    "greek": "el", "hebrew": "he", "arabic": "ar", "thai": "th", "vietnamese": "vi", "indonesian": "id",
    "ukrainian": "uk", "czech": "cs", "hungarian": "hu", "romanian": "ro", "persian": "fa", "urdu": "ur",
    "bengali": "bn", "tamil": "ta", "tam": "ta", "telugu": "te", "tel": "te", "malayalam": "ml",
    "kannada": "kn", "marathi": "mr"]

// MARK: Debrid

/// A debrid service ([short] as add-ons tag it, "RD"); [cached]: ready to start at once, nil when the add-on doesn't say.
struct Debrid: Hashable {
    let name: String
    let short: String
    let cached: Bool?
    var tag: String { cached == true ? "\(short) ⚡" : short }
}

private let debridServices: [(String, String, [String])] = [
    ("RD", "Real-Debrid", ["real-debrid", "realdebrid", "rdeb.io"]),
    ("AD", "AllDebrid", ["alldebrid", "debrid.it"]),
    ("PM", "Premiumize", ["premiumize", "energycdn"]),
    ("TB", "TorBox", ["torbox", "tb-cdn"]),
    ("DL", "Debrid-Link", ["debrid-link", "debridlink"]),
    ("ED", "EasyDebrid", ["easydebrid"]),
    ("OC", "Offcloud", ["offcloud"]),
    ("PIO", "Put.io", ["put.io", "putio"]),
]
private let debridCodes = debridServices.map(\.0).joined(separator: "|")
let debridTagPattern = #"\[("# + debridCodes + #")\s*(\+|⚡️?|⏳|download)?\]\s*"#
private let debridFlashPattern = #"(⚡️?)\s*("# + debridCodes + #")\b"#

func debridOf(_ name: String, _ url: String) -> Debrid? {
    func service(_ code: String) -> (String, String, [String]) { debridServices.first { $0.0.caseInsensitiveCompare(code) == .orderedSame }! }
    if let m = Rx.groups(debridTagPattern, name, .caseInsensitive) {
        let mark = m[2].lowercased()
        let cached: Bool? = mark == "+" || mark.hasPrefix("⚡") ? true : (mark == "⏳" || mark == "download") ? false : nil
        let s = service(m[1])
        return Debrid(name: s.1, short: s.0, cached: cached)
    }
    if let m = Rx.groups(debridFlashPattern, name) {
        let s = service(m[2])
        return Debrid(name: s.1, short: s.0, cached: true)
    }
    let link = url.lowercased()
    return debridServices.first { $0.2.contains { link.contains($0) } }.map { Debrid(name: $0.1, short: $0.0, cached: nil) }
}

// MARK: Languages

/// Language codes as add-ons and files write them ("eng", "en", "pt-BR", "English") to one form, and their names.
enum Languages {
    static let brazil = "pt-BR"
    private static let aliases = ["alb": "sq", "arm": "hy", "baq": "eu", "bur": "my", "chi": "zh", "cze": "cs", "dut": "nl",
                                  "fre": "fr", "geo": "ka", "ger": "de", "gre": "el", "ice": "is", "mac": "mk", "mao": "mi",
                                  "may": "ms", "per": "fa", "rum": "ro", "slo": "sk", "tib": "bo", "wel": "cy", "iw": "he",
                                  "in": "id", "ji": "yi", "pob": brazil, "pb": brazil, "ptbr": brazil, "spl": "es", "ea": "es"]
    static let common = ["en", "es", "fr", "de", "pt", brazil, "it", "hi", "ar", "ja", "ko", "zh", "ru"]

    private static let byThree: [String: String] = {
        var map = [String: String]()
        for code in Locale.LanguageCode.isoLanguageCodes {
            let two = code.identifier
            guard two.count == 2 else { continue }
            if let three = Locale.LanguageCode(two).identifier(.alpha3) { map[three] = two }
            if let name = Locale(identifier: "en").localizedString(forLanguageCode: two)?.lowercased() { map[name] = two }
        }
        return map
    }()

    /// "eng" → "en", "pt_BR" → "pt-BR", "English" → "en"; nil when it isn't a language.
    static func code(_ raw: String?) -> String? {
        guard var s = raw?.trimmingCharacters(in: .whitespaces).lowercased().replacingOccurrences(of: "_", with: "-"), !s.isEmpty else { return nil }
        if let a = aliases[s] { return a }
        if s == "brazilian" || s.contains("brazil") { return brazil }
        let parts = s.split(separator: "-").map(String.init)
        s = parts[0]
        if let a = aliases[s] { s = a }
        if s.count == 3 || s.count > 3 { s = byThree[s] ?? s }
        guard s.count == 2 else { return byThree[raw!.lowercased()] }
        if s == "pt", parts.count > 1, parts[1] == "br" { return brazil }
        return s
    }

    static func base(_ code: String) -> String { code.components(separatedBy: "-")[0] }
    static func same(_ a: String?, _ b: String?) -> Bool { code(a) != nil && code(a) == code(b) }

    /// 2 for the same language and region, 1 for the same language, 0 otherwise.
    static func match(_ a: String?, _ b: String?) -> Int {
        guard let x = code(a), let y = code(b) else { return 0 }
        if x == y { return 2 }
        return base(x) == base(y) ? 1 : 0
    }

    static func name(_ raw: String) -> String {
        guard let c = code(raw) else { return raw }
        if c == brazil { return "Portuguese (Brazil)" }
        return Locale.current.localizedString(forLanguageCode: c)?.capitalized ?? raw
    }

    static var all: [String] {
        let rest = Locale.LanguageCode.isoLanguageCodes.map(\.identifier).filter { $0.count == 2 && !common.contains($0) }
            .filter { name($0) != $0 }.sorted { name($0) < name($1) }
        return common + rest
    }
}
