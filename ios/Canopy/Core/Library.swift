import Foundation
import Compression

/// One Stremio library item (a title's watch history), kept as raw JSON so fields Canopy doesn't use survive the trip.
final class LibraryItem: Identifiable, @unchecked Sendable {
    var json: JSON
    init(_ json: JSON) { self.json = json }

    var id: String { json.string("_id") }
    var type: String { json.string("type") }
    var name: String { json.string("name") }
    var poster: String? { json.text("poster") }
    var removed: Bool { json.bool("removed") }
    var temp: Bool { json.bool("temp") }
    var state: JSON {
        get { json.obj("state") ?? [:] }
        set { json["state"] = newValue }
    }
    var timeOffset: Int64 { state.int64("timeOffset") }
    var duration: Int64 { state.int64("duration") }
    var videoId: String? { state.text("video_id") }
    var lastWatched: String { state.string("lastWatched") }
    var progress: Double { duration > 0 ? min(1, Double(timeOffset) / Double(duration)) : 0 }

    func copy() -> LibraryItem { LibraryItem(json) }
}

/// Stremio's library rules (stremio-core's player rules), so progress made here shows on Stremio's other apps.
enum Library {
    static let watchedCoef = 0.7 // watched this much, it counts as seen
    static let creditsCoef = 0.9 // stopped past this, it's finished

    static func continueWatching(_ items: [LibraryItem]) -> [LibraryItem] {
        items.filter { $0.type != "other" && (!$0.removed || $0.temp) && $0.timeOffset > 0 }
            .sorted { $0.lastWatched > $1.lastWatched }
    }

    /// My List: in the library, newest first.
    static func myList(_ items: [LibraryItem]) -> [LibraryItem] {
        items.filter { !$0.removed && ["movie", "series"].contains($0.type) }.sorted { $0.json.string("_ctime") > $1.json.string("_ctime") }
    }

    /// A title not in the library yet: Stremio keeps those as temporary, hidden items.
    static func newItem(id: String, type: String, name: String, poster: String?) -> LibraryItem {
        let now = now()
        return LibraryItem(["_id": id, "name": name, "type": type, "poster": poster ?? "", "posterShape": "poster",
                            "removed": true, "temp": true, "_ctime": now, "_mtime": now, "behaviorHints": [String: Any](),
                            "state": ["lastWatched": now, "timeWatched": 0, "timeOffset": 0, "overallTimeWatched": 0,
                                      "timesWatched": 0, "flaggedWatched": 0, "duration": 0, "video_id": NSNull(),
                                      "watched": NSNull(), "noNotif": false] as JSON])
    }

    static func inList(_ item: LibraryItem?) -> Bool { item != nil && !item!.removed }

    static func setInList(_ item: LibraryItem, _ on: Bool) {
        item.json["removed"] = !on
        item.json["temp"] = false
        item.json["_mtime"] = now()
    }

    static func markTitleWatched(_ item: LibraryItem, videos: [Video] = []) {
        if let id = item.videoId, videos.contains(where: { $0.id == id }) { markWatched(item, videos: videos, videoId: id) }
        var s = item.state
        s["timesWatched"] = max(1, s.int("timesWatched"))
        s["flaggedWatched"] = 1
        s["timeOffset"] = 0
        item.state = s
        item.json["_mtime"] = now()
    }

    /// Off Continue Watching, as Stremio's own dismiss does: the resume point goes, history stays. Returns the offset.
    @discardableResult
    static func hideFromContinueWatching(_ item: LibraryItem) -> Int64 {
        let offset = item.timeOffset
        var s = item.state
        s["timeOffset"] = 0
        item.state = s
        item.json["_mtime"] = now()
        return offset
    }

    /// Where to resume [videoId] in milliseconds, 0 to start from the beginning.
    static func resumeAt(_ item: LibraryItem?, videoId: String) -> Int64 {
        guard let item else { return 0 }
        let offset = item.timeOffset, duration = item.duration
        let unfinished = duration == 0 || Double(offset) < Double(duration) * creditsCoef
        return item.videoId == videoId && offset > 0 && unfinished ? offset : 0
    }

    // MARK: Watched bitfield

    /// Episodes Stremio has ticked as watched, from stremio-core's bitfield (`state.watched`):
    /// `{anchor video id}:{anchor length}:{base64 zlib bits}`, one bit per episode in season-then-episode order.
    static func watched(_ item: LibraryItem?, videos: [Video]) -> Set<String> {
        guard let field = item?.state.text("watched") else { return [] }
        let parts = field.components(separatedBy: ":")
        guard parts.count >= 3, let anchorLength = Int(parts[parts.count - 2]),
              let zipped = Data(base64Encoded: parts.last!), let bits = inflate(zipped) else { return [] }
        let anchor = parts.dropLast(2).joined(separator: ":")
        let ids = ordered(videos).map(\.id)
        guard let anchorAt = ids.firstIndex(of: anchor) else { return [] }
        let offset = anchorLength - anchorAt - 1
        var out = Set<String>()
        for (i, id) in ids.enumerated() {
            let bit = i + offset
            if bit >= 0 && bit < ids.count && bit / 8 < bits.count && (Int(bits[bit / 8]) >> (bit % 8)) & 1 == 1 { out.insert(id) }
        }
        return out
    }

    /// Ticks [videoId] as watched in that bitfield, as stremio-core's `set_video` does.
    static func markWatched(_ item: LibraryItem, videos: [Video], videoId: String) {
        let ids = ordered(videos).map(\.id)
        let seen = watched(item, videos: videos).union([videoId])
        var bits = [UInt8](repeating: 0, count: (ids.count + 7) / 8)
        for (i, id) in ids.enumerated() where seen.contains(id) { bits[i / 8] |= UInt8(1 << (i % 8)) }
        guard let last = ids.lastIndex(where: { seen.contains($0) }), let zipped = deflate(Data(bits)) else { return }
        var s = item.state
        s["watched"] = "\(ids[last]):\(last + 1):\(zipped.base64EncodedString())"
        item.state = s
    }

    private static func ordered(_ videos: [Video]) -> [Video] {
        videos.sorted { $0.season != $1.season ? $0.season < $1.season : $0.episode < $1.episode }
    }

    // MARK: Playback

    /// Playback progress, every few seconds. [playedMs] is time actually watched since the last call.
    static func record(_ item: LibraryItem, videoId: String, positionMs: Int64, durationMs: Int64, playedMs: Int64) {
        var s = item.state
        let now = now()
        s["lastWatched"] = now
        if s.text("video_id") != videoId {
            s["video_id"] = videoId
            s["overallTimeWatched"] = s.int64("overallTimeWatched") + s.int64("timeWatched")
            s["timeWatched"] = 0
            s["flaggedWatched"] = 0
        }
        s["timeWatched"] = s.int64("timeWatched") + playedMs
        s["overallTimeWatched"] = s.int64("overallTimeWatched") + playedMs
        s["timeOffset"] = positionMs
        if durationMs > 0 { s["duration"] = durationMs }
        let duration = s.int64("duration")
        if s.int("flaggedWatched") == 0 && duration > 0 && Double(s.int64("timeWatched")) > Double(duration) * watchedCoef {
            s["flaggedWatched"] = 1
            s["timesWatched"] = s.int("timesWatched") + 1
        }
        item.state = s
        if item.temp && s.int("timesWatched") == 0 { item.json["removed"] = true }
        if item.removed { item.json["temp"] = true }
        item.json["_mtime"] = now
    }

    /// When playback stops: past the credits it's finished, and a series moves on to [nextVideoId].
    static func stopped(_ item: LibraryItem, nextVideoId: String?, videos: [Video] = []) {
        var s = item.state
        let duration = s.int64("duration")
        if duration == 0 || Double(s.int64("timeOffset")) <= Double(duration) * creditsCoef { return }
        if let current = s.text("video_id"), !videos.isEmpty {
            item.state = s
            markWatched(item, videos: videos, videoId: current)
            s = item.state
        }
        s["timeOffset"] = 0
        if let next = nextVideoId {
            s["video_id"] = next
            s["overallTimeWatched"] = s.int64("overallTimeWatched") + s.int64("timeWatched")
            s["timeWatched"] = 0
            s["flaggedWatched"] = 0
            s["timeOffset"] = 1 // stremio-core's marker for "start the next episode"
        }
        item.state = s
        item.json["_mtime"] = now()
    }

    /// "Mark as watched" on a show's Continue Watching poster: ticks the episode it's on and moves the show to the
    /// next one, as finishing it would; after the last episode the show leaves Continue Watching.
    static func finishEpisode(_ item: LibraryItem, videos: [Video]) {
        guard let current = item.videoId else { markTitleWatched(item, videos: videos); return }
        if !videos.isEmpty { markWatched(item, videos: videos, videoId: current) }
        let list = ordered(videos).filter { $0.season > 0 }
        var s = item.state
        if let i = list.firstIndex(where: { $0.id == current }), i + 1 < list.count {
            s["video_id"] = list[i + 1].id
            s["overallTimeWatched"] = s.int64("overallTimeWatched") + s.int64("timeWatched")
            s["timeWatched"] = 0
            s["flaggedWatched"] = 0
            s["timeOffset"] = 1 // stremio-core's marker for "start the next episode"
        } else {
            s["timeOffset"] = 0
            s["flaggedWatched"] = 1
        }
        item.state = s
        item.json["_mtime"] = now()
    }

    static func now() -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = TimeZone(identifier: "UTC")
        f.dateFormat = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"
        return f.string(from: Date())
    }

    // MARK: zlib (RFC 1950) around Apple's raw deflate

    static func inflate(_ data: Data) -> Data? {
        guard data.count > 6 else { return nil }
        let raw = data.subdata(in: 2..<(data.count - 4)) // drop the zlib header and the Adler-32 trailer
        var size = max(64 * 1024, raw.count * 16)
        for _ in 0..<4 {
            var out = Data(count: size)
            let n = out.withUnsafeMutableBytes { dst in
                raw.withUnsafeBytes { src in
                    compression_decode_buffer(dst.bindMemory(to: UInt8.self).baseAddress!, size,
                                              src.bindMemory(to: UInt8.self).baseAddress!, raw.count, nil, COMPRESSION_ZLIB)
                }
            }
            if n == 0 { return nil }
            if n < size { return out.prefix(n) }
            size *= 4
        }
        return nil
    }

    static func deflate(_ data: Data) -> Data? {
        let size = data.count + 1024
        var out = Data(count: size)
        let n = out.withUnsafeMutableBytes { dst in
            data.withUnsafeBytes { src in
                compression_encode_buffer(dst.bindMemory(to: UInt8.self).baseAddress!, size,
                                          src.bindMemory(to: UInt8.self).baseAddress!, data.count, nil, COMPRESSION_ZLIB)
            }
        }
        guard n > 0 else { return nil }
        var a: UInt32 = 1, b: UInt32 = 0
        for byte in data { a = (a + UInt32(byte)) % 65521; b = (b + a) % 65521 }
        let adler = (b << 16) | a
        var result = Data([0x78, 0x9C])
        result.append(out.prefix(n))
        result.append(contentsOf: [UInt8(adler >> 24), UInt8((adler >> 16) & 0xFF), UInt8((adler >> 8) & 0xFF), UInt8(adler & 0xFF)])
        return result
    }
}
