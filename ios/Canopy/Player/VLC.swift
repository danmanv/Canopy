import SwiftUI
import MobileVLCKit

/// VLC behind a small observable face: the state, time and tracks the player screen draws from.
@MainActor
@Observable
final class VLC: NSObject {
    enum Phase: Equatable { case idle, opening, playing, paused, ended, failed }

    let player = VLCMediaPlayer()
    var phase: Phase = .idle
    var time: Int64 = 0      // ms
    var length: Int64 = 0    // ms
    var buffering = false
    var audioTracks: [(id: Int32, name: String)] = []
    var subtitleTracks: [(id: Int32, name: String)] = []
    var audioTrack: Int32 = -1
    var subtitleTrack: Int32 = -1
    /// Bumped whenever VLC adds or drops tracks (an external subtitle arrived).
    var tracksVersion = 0
    private var started = false
    private var keptRatio: UnsafeMutablePointer<CChar>?
    private var keptCrop: UnsafeMutablePointer<CChar>?

    override init() {
        super.init()
        player.delegate = self
    }

    func open(_ s: Stream, startAt ms: Int64) {
        guard let url = URL(string: s.url) else { phase = .failed; return }
        started = false
        let media = VLCMedia(url: url)
        media.addOption(":http-user-agent=\(s.headers["User-Agent"] ?? s.headers["user-agent"] ?? Http.userAgent)")
        if let ref = s.headers["Referer"] ?? s.headers["referer"] { media.addOption(":http-referrer=\(ref)") }
        media.addOption(":network-caching=3000")
        if ms > 5000 { media.addOption(":start-time=\(Double(ms) / 1000)") }
        player.media = media
        phase = .opening
        time = ms
        length = 0
        player.play()
    }

    func toggle() { player.isPlaying ? player.pause() : player.play() }
    func play() { player.play() }
    func pause() { player.pause() }
    func stop() { player.stop() }

    func seek(to ms: Int64) {
        guard length > 0 else { return }
        player.time = VLCTime(int: Int32(clamping: max(0, min(ms, length - 1000))))
        time = ms
    }

    func jump(_ seconds: Int) { seek(to: time + Int64(seconds) * 1000) }

    func setAudio(_ id: Int32) { player.currentAudioTrackIndex = id; audioTrack = id }
    func setSubtitle(_ id: Int32) { player.currentVideoSubTitleIndex = id; subtitleTrack = id }

    func addSubtitle(_ url: String, select: Bool) {
        guard let u = URL(string: url) else { return }
        _ = player.addPlaybackSlave(u, type: .subtitle, enforce: select)
    }

    /// Fit (whole picture), fill (cropped to the screen's shape) or stretch (pulled to it).
    func setPictureSize(_ mode: String, screen: CGSize) {
        free(keptRatio); keptRatio = nil
        free(keptCrop); keptCrop = nil
        let ratio = "\(Int(screen.width)):\(Int(screen.height))"
        switch mode {
        case "fill":
            keptCrop = strdup(ratio)
            player.videoCropGeometry = keptCrop
            player.videoAspectRatio = nil
        case "stretch":
            keptRatio = strdup(ratio)
            player.videoAspectRatio = keptRatio
            player.videoCropGeometry = nil
        default:
            player.videoAspectRatio = nil
            player.videoCropGeometry = nil
        }
        player.scaleFactor = 0
    }

    func readTracks() {
        func pairs(_ ids: [Any]?, _ names: [Any]?) -> [(id: Int32, name: String)] {
            let i = (ids ?? []).compactMap { ($0 as? NSNumber)?.int32Value }
            let n = (names ?? []).map { "\($0)" }
            return zip(i, n).map { (id: $0.0, name: $0.1) }
        }
        audioTracks = pairs(player.audioTrackIndexes, player.audioTrackNames).filter { $0.id >= 0 }
        subtitleTracks = pairs(player.videoSubTitlesIndexes, player.videoSubTitlesNames).filter { $0.id >= 0 }
        audioTrack = player.currentAudioTrackIndex
        subtitleTrack = player.currentVideoSubTitleIndex
        tracksVersion += 1
    }
}

extension VLC: VLCMediaPlayerDelegate {
    nonisolated func mediaPlayerStateChanged(_ aNotification: Notification) {
        Task { @MainActor in
            switch player.state {
            case .opening: phase = started ? phase : .opening
            case .buffering: buffering = true
            case .playing:
                started = true
                buffering = false
                phase = .playing
                readTracks()
            case .paused: phase = .paused
            case .ended: phase = .ended
            case .error: phase = .failed
            case .stopped: if phase == .opening { phase = .failed }
            case .esAdded: readTracks()
            @unknown default: break
            }
        }
    }

    nonisolated func mediaPlayerTimeChanged(_ aNotification: Notification) {
        Task { @MainActor in
            time = Int64(player.time.intValue)
            if let l = player.media?.length.intValue, l > 0 { length = Int64(l) }
            if buffering { buffering = false }
            if phase == .opening { started = true; phase = .playing; readTracks() }
        }
    }
}

/// The view VLC draws into.
struct VLCSurface: UIViewRepresentable {
    let vlc: VLC
    func makeUIView(context: Context) -> UIView {
        let v = UIView()
        v.backgroundColor = .black
        vlc.player.drawable = v
        return v
    }
    func updateUIView(_ uiView: UIView, context: Context) {}
}
