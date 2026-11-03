import Foundation
import UIKit

/// What this device plays well, for ranking sources (the TV's Device). VLC decodes HEVC and AV1 everywhere; HDR through
/// VLC on an iPhone or iPad looks washed out, so HDR counts as unsupported and drops down the list.
struct DeviceCaps {
    let height: Int
    let hdr = false
    let dolbyVision = false

    static let current: DeviceCaps = {
        let short = min(UIScreen.main.nativeBounds.width, UIScreen.main.nativeBounds.height)
        return DeviceCaps(height: short >= 2000 ? 2160 : 1080)
    }()

    /// How well a source suits this screen, higher first: nothing above the screen, HDR only on an HDR screen.
    func fit(_ s: Stream) -> Int {
        (s.height > height ? 0 : 2) + (s.isHdr ? (hdr ? 0 : -1) : 0)
    }
}

/// Finds and orders the sources for one video, as the TV's StreamsActivity does.
struct StreamPicker {
    let profile: Profile
    let type: String
    let metaId: String
    let videoId: String
    var maxHeight = 0 // the player asked for less after a failure

    struct Result {
        var ordered: [Stream] = []   // best first; within Settings' limits first
        var eligible: [Stream] = []  // within Settings' limits
        var reasons: [String] = []   // why nothing is playable
        var done = false
    }

    /// The add-ons asked: lenders' shareable stream add-ons first, then the profile's own.
    func addons() async throws -> [Addon] {
        let own = try await Stremio.addons(profile.authKey).filter { $0.serves("stream", type: type, id: videoId) }
        var household = [Addon]()
        for lender in Profiles.streamLenders(profile) {
            let list = (try? await Stremio.anyAddons(lender.authKey)) ?? []
            for a in list where a.shareable && a.serves("stream", type: type, id: videoId) && !household.contains(where: { $0.id == a.id }) {
                household.append(a)
            }
        }
        return household + own.filter { mine in !household.contains { $0.id == mine.id } }
    }

    /// Asks every add-on at once and reports the ordered list each time one answers.
    func gather(_ update: @escaping @MainActor (Result) -> Void) async {
        let addons: [Addon]
        do { addons = try await self.addons() } catch {
            await update(Result(reasons: ["Couldn't load sources. Check your connection and try again."], done: true))
            return
        }
        if addons.isEmpty {
            await update(Result(reasons: ["No stream add-ons are connected. Add one in Stremio to find sources."], done: true))
            return
        }
        var answers = [Int: [Stream]]()
        var failures = [Int: Error]()
        var torrents = [Int: Int]()
        await withTaskGroup(of: (Int, Swift.Result<Addon.Streams, Error>).self) { group in
            for (i, a) in addons.enumerated() {
                group.addTask {
                    do { return (i, .success(try await a.streams(type: type, videoId: videoId))) }
                    catch { return (i, .failure(error)) }
                }
            }
            for await (i, r) in group {
                switch r {
                case .success(let s):
                    answers[i] = s.playable.map { var x = $0; x.addon = addons[i].name; return x }
                    torrents[i] = s.torrents
                case .failure(let e):
                    answers[i] = []
                    failures[i] = e
                }
                let all = (0..<addons.count).flatMap { answers[$0] ?? [] }.filter(\.isVideo)
                let ranked = rank(all)
                let eligible = ranked.filter(wanted)
                var result = Result(ordered: eligible + ranked.filter { !wanted($0) }, eligible: eligible, done: answers.count == addons.count)
                if result.done && result.ordered.isEmpty {
                    result.reasons = emptyReasons(addons.map(\.name), failures, torrents)
                }
                await update(result)
            }
        }
    }

    /// The order to try sources in (StreamsActivity.rank): the tallest left when the player asked for less, the file
    /// played last time, the previous episode's release, then the add-on's order with awkward and unsuitable releases
    /// moved down, then Settings' preferences. Stable, so ties keep the add-ons' order.
    func rank(_ streams: [Stream]) -> [Stream] {
        let file = Settings.lastFile(videoId)
        let binge = Settings.binge(metaId)
        let caps = DeviceCaps.current
        let lower = maxHeight > 0
        func key(_ s: Stream) -> [Int] {
            [lower ? -s.height : 0,
             file != nil && s.filename == file ? 0 : 1,
             binge != nil && s.bingeGroup == binge ? 0 : 1,
             s.awkward ? 1 : 0,
             s.dolbyVisionOnly && !caps.dolbyVision ? 1 : 0,
             -caps.fit(s),
             -preference(s)]
        }
        return streams.enumerated().map { ($0.offset, $0.element, key($0.element)) }
            .sorted { a, b in a.2 == b.2 ? a.0 < b.0 : a.2.lexicographicallyPrecedes(b.2) }
            .map(\.1)
    }

    /// Settings' stream preferences as a score, higher first.
    func preference(_ s: Stream) -> Int {
        var score = 0
        switch Settings.release {
        case "remux": score += s.remux ? 3 : s.bluray ? 1 : 0
        case "encode": score += s.remux ? -2 : (s.bluray || s.web) ? 2 : 0
        case "web": score += (s.web && !s.remux) ? 2 : s.remux ? -2 : 0
        default: break
        }
        switch Settings.hdrPref {
        case "dv": score += s.dolbyVision ? 2 : s.hdr ? 1 : 0
        case "hdr10": score += s.hdr ? 2 : 0
        default: break
        }
        switch Settings.dub {
        case "original": score += s.dubbed ? -2 : 0
        case "mine": score += s.mentions(Settings.audioLanguage) ? 2 : s.multiAudio ? 1 : 0
        default: break
        }
        return score
    }

    /// Within Settings' limits (quality, size, HDR), never a cam. Unknown sizes and resolutions pass.
    func wanted(_ s: Stream) -> Bool {
        let maxH = maxHeight > 0 ? maxHeight : Settings.maxHeight
        let maxBytes = Int64(Settings.maxSizeGb) * 1_073_741_824
        return (maxH == 0 || s.height == 0 || s.height <= maxH) && (maxBytes == 0 || s.size == 0 || s.size <= maxBytes)
            && (Settings.allowHdr || !s.isHdr) && !s.cam
    }

    private func emptyReasons(_ names: [String], _ failures: [Int: Error], _ torrents: [Int: Int]) -> [String] {
        var lines = [String]()
        let failed = names.indices.filter { failures[$0] != nil }.map { "\(names[$0]) (\(reason(failures[$0]!)))" }
        if !failed.isEmpty {
            lines.append("Couldn't get sources from \(failed.joined(separator: ", ")). Check the add-on in Stremio, or try again later.")
        }
        let torrentOnly = names.indices.filter { failures[$0] == nil && (torrents[$0] ?? 0) > 0 }.map { names[$0] }
        if !torrentOnly.isEmpty {
            lines.append("\(torrentOnly.joined(separator: ", ")) found only torrents. Canopy plays them through a debrid service: connect one in the add-on's settings.")
        }
        return lines.isEmpty ? ["Your add-ons have no sources for this title."] : lines
    }

    private func reason(_ e: Error) -> String {
        let ns = e as NSError
        if ns.domain == NSURLErrorDomain {
            switch ns.code {
            case NSURLErrorTimedOut: return "timed out"
            case NSURLErrorCannotFindHost, NSURLErrorDNSLookupFailed: return "address not found"
            case NSURLErrorCannotConnectToHost: return "connection refused"
            case NSURLErrorSecureConnectionFailed, NSURLErrorServerCertificateUntrusted: return "secure connection failed"
            default: break
            }
        }
        if e is DecodingError || (e as? CanopyError)?.errorDescription == "Not JSON" { return "not a source list" }
        let m = e.localizedDescription
        return m.count <= 40 ? m : "error"
    }
}
