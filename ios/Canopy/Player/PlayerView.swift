import SwiftUI
import MobileVLCKit

/// The player: one starting screen while the best source is found, then the picture with bare-icon controls (a
/// see-through disc behind Play, a round knob on the bar), Audio & subtitles, Picture size, Skip intro and Up next.
/// Progress goes to Stremio as Stremio's own apps send it.
struct PlayerView: View {
    let first: PlayRequest
    var chooseSource: (PlayRequest) -> Void = { _ in }
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var theme
    @Environment(\.dismiss) private var dismiss

    @State private var request: PlayRequest
    @State private var vlc = VLC()
    @State private var sources: [Stream] = []
    @State private var tried = 0
    @State private var status = "Finding the best source"
    @State private var failure: [String] = []
    @State private var showChoose = false
    @State private var startedPlaying = false
    @State private var gatherTask: Task<Void, Never>?

    @State private var controls = true
    @State private var lastTouch = Date()
    @State private var panel: Panel?
    @State private var scrub: Double?
    @State private var screen: CGSize = .zero
    @State private var pictureSize = Settings.pictureSize

    @State private var item: LibraryItem?
    @State private var played: Int64 = 0
    @State private var ticks = 0
    @State private var addonSubs: [Subtitle] = []
    @State private var tracksChosen = false

    @State private var segments: [Segment] = []
    @State private var outroAt: Int64 = 0
    @State private var skipped = Set<Int>()
    @State private var skipShownAt: [Int: Date] = [:]
    @State private var upNext = false
    @State private var upNextDismissed = false
    @State private var countdown = 0
    /// The next episode's sources, found a few minutes before this one ends so Next up starts at once.
    @State private var nextSources: (id: String, list: [Stream])?
    @State private var nextAsked = false
    /// Sideways because Full screen was tapped: Back turns the phone upright again before closing.
    @State private var fullScreenByButton = false

    enum Panel { case tracks, picture }
    struct Segment { let label: String; let start: Int64; let end: Int64; var jump: Int64 = 0 }

    private let clock = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    init(request: PlayRequest, chooseSource: @escaping (PlayRequest) -> Void = { _ in }) {
        first = request
        self.chooseSource = chooseSource
        _request = State(initialValue: request)
    }

    var body: some View {
        GeometryReader { g in
            // Upright: the picture in a 16:9 strip at the top, the controls and the episode under it. Sideways: the
            // whole screen.
            let upright = Orientation.phone && g.size.height > g.size.width
            let videoSize = upright ? CGSize(width: g.size.width, height: (g.size.width * 9 / 16).rounded()) : g.size
            ZStack(alignment: .top) {
                (upright ? theme.baseColor : Color.black).ignoresSafeArea()
                VStack(spacing: 0) {
                    ZStack {
                        Color.black
                        VLCSurface(vlc: vlc)
                            .opacity(startedPlaying ? 1 : 0)
                        if upright { stripGestures } else { gestures }
                        if vlc.buffering && startedPlaying { Spinner(size: upright ? 34 : 44) }
                        skipPill(upright: upright)
                    }
                    .frame(width: upright ? videoSize.width : nil, height: upright ? videoSize.height : nil)
                    .frame(maxWidth: .infinity, maxHeight: upright ? nil : .infinity)
                    .clipped()
                    .ignoresSafeArea(edges: upright ? [] : .all)
                    if upright { uprightPanel }
                }
                // Sideways, the controls stay clear of the camera and the rounded corners.
                if !upright && startedPlaying && controls && panel == nil { controlsLayer.transition(.opacity) }
                if upNext, let next = request.next { upNextCard(next) }
                if !startedPlaying { starting }
                if let panel { panelView(panel) }
            }
            .onAppear { screen = videoSize }
            .onChange(of: videoSize) { _, s in screen = s; vlc.setPictureSize(pictureSize, screen: s) }
        }
        .background { keys }
        .statusBarHidden(true)
        .persistentSystemOverlays(.hidden)
        .onAppear(perform: begin)
        .onDisappear(perform: finish)
        .onReceive(clock) { _ in tick() }
        .onChange(of: vlc.phase) { _, p in phaseChanged(p) }
        .onChange(of: vlc.tracksVersion) { _, _ in chooseTracks() }
        .animation(.easeOut(duration: 0.2), value: controls)
        .animation(.easeOut(duration: 0.2), value: panel)
    }

    // MARK: Starting

    private var starting: some View {
        ZStack {
            // Solid, so the upright page under it doesn't show through while the source is found.
            Color.black.ignoresSafeArea()
            RemoteImage(url: request.title.background ?? Art.background(for: request.title.id), width: 900, placeholder: false)
                .opacity(0.35).blur(radius: 6).ignoresSafeArea()
            LinearGradient(colors: [.black.opacity(0.2), .black.opacity(0.85)], startPoint: .top, endPoint: .bottom).ignoresSafeArea()
            VStack(spacing: 18) {
                if failure.isEmpty { Spinner(size: 48) }
                Text(request.title.name).font(Look.title(24)).foregroundStyle(.white).multilineTextAlignment(.center)
                if let e = request.episode { Text(e).font(Look.body(15)).foregroundStyle(.white.opacity(0.75)) }
                if failure.isEmpty {
                    Text(status).font(Look.body(14)).foregroundStyle(.white.opacity(0.6))
                } else {
                    VStack(spacing: 8) {
                        ForEach(failure, id: \.self) { Text($0).font(Look.body(14)).foregroundStyle(.white.opacity(0.8)).multilineTextAlignment(.center) }
                    }
                    .frame(maxWidth: 460)
                    HStack(spacing: 12) {
                        if showChoose {
                            Button("Choose a source") { close(); chooseSource(request) }.buttonStyle(PrimaryButton()).frame(width: 190)
                        }
                        Button("Close") { close() }.buttonStyle(GlassButton())
                    }
                }
            }
            .padding(24)
            VStack {
                HStack { closeButton; Spacer() }
                Spacer()
            }
        }
    }

    private var closeButton: some View {
        Button {
            // Sideways only because Full screen was tapped: back to upright first, then Back closes.
            if fullScreenByButton && Orientation.phone && screen.width > screen.height {
                fullScreenByButton = false
                Orientation.app()
            } else {
                close()
            }
        } label: {
            Image(systemName: "chevron.left").font(.system(size: 20, weight: .semibold)).foregroundStyle(.white)
                .frame(width: 48, height: 48).contentShape(Rectangle())
        }
        .padding(.leading, 8).padding(.top, 4)
    }

    private func begin() {
        UIApplication.shared.isIdleTimerDisabled = true
        item = model.item(request.title.id)?.copy()
            ?? Library.newItem(id: request.title.id, type: request.type, name: request.title.name, poster: request.title.poster)
        fetchSubtitles()
        fetchSegments()
        if request.picked { start(request.sources) } else { gather() }
    }

    private func gather() {
        guard let p = model.active else { return }
        status = "Finding the best source"
        failure = []
        let picker = StreamPicker(profile: p, type: request.type, metaId: request.title.id, videoId: request.videoId, maxHeight: request.maxHeight,
                                   known: model.addons)
        let box = GatherBox()
        gatherTask?.cancel()
        gatherTask = Task {
            await picker.gather { r in
                box.latest = r
                guard sources.isEmpty, !Task.isCancelled else { return }
                if r.done {
                    if !r.eligible.isEmpty { start(Array(r.eligible.prefix(3))) }
                    else if !r.ordered.isEmpty {
                        failure = ["None of the sources fits your stream settings (\(Settings.presetName))."]
                        showChoose = true
                    } else {
                        failure = r.reasons
                        showChoose = false
                    }
                } else if let f = r.eligible.first, picker.knownGood(f) {
                    // The file (or release group) that played last time answered: no need to wait for the rest.
                    start(Array(r.eligible.prefix(3)))
                } else if !r.eligible.isEmpty && !box.waiting {
                    // Keep the add-ons' order when the rest answer promptly; after a moment, start with what's here.
                    box.waiting = true
                    Task { @MainActor in
                        try? await Task.sleep(nanoseconds: 1_500_000_000)
                        if sources.isEmpty && !box.latest.eligible.isEmpty { start(Array(box.latest.eligible.prefix(3))) }
                    }
                }
            }
        }
    }

    private func start(_ list: [Stream]) {
        sources = list
        tried = 0
        open(0)
    }

    private func open(_ i: Int) {
        guard i < sources.count else {
            failure = ["\(sources.count == 1 ? "This source didn't play." : "None of the \(sources.count) sources played.")",
                       "Player: " + vlc.history.joined(separator: " → ") + (Orientation.problem.map { " · \($0)" } ?? "")]
            showChoose = true
            startedPlaying = false
            return
        }
        tried = i
        status = i == 0 ? "Starting" : "That one didn't play. Trying another source…"
        let at = request.startOver ? 0 : Library.resumeAt(item, videoId: request.videoId)
        vlc.open(sources[i], startAt: at)
    }

    private func phaseChanged(_ p: VLC.Phase) {
        switch p {
        case .playing:
            if !startedPlaying {
                startedPlaying = true
                lastTouch = Date()
                vlc.setPictureSize(pictureSize, screen: screen)
                let s = sources[tried]
                Settings.setLastFile(request.videoId, s.filename)
                Settings.setBinge(request.title.id, s.bingeGroup)
            }
        case .failed:
            if !startedPlaying { open(tried + 1) }
            else { failure = ["The video stopped: the source failed."]; showChoose = true; startedPlaying = false }
        case .ended:
            record()
            if let next = request.next, Settings.nextEpisode { playNext(next) } else { close() }
        default: break
        }
    }

    // MARK: Tracks

    private func fetchSubtitles() {
        let type = request.type, videoId = request.videoId
        let addons = model.addons.filter { $0.serves("subtitles", type: type, id: videoId) }
        Task {
            var all = [Subtitle]()
            await withTaskGroup(of: [Subtitle].self) { group in
                for a in addons { group.addTask { (try? await a.subtitles(type: type, videoId: videoId)) ?? [] } }
                for await s in group { all += s }
            }
            addonSubs = all
            if tracksChosen { autoAddonSubtitle() }
        }
    }

    /// The language a track's name gives: "English - [eng]", "Track 2 - [Français]".
    private func language(of name: String) -> String? {
        if let g = Rx.groups(#"\[([^\]]+)\]"#, name), let c = Languages.code(g[1]) { return c }
        for word in name.components(separatedBy: CharacterSet.letters.inverted) where word.count > 3 {
            if let c = Languages.code(word) { return c }
        }
        return nil
    }

    private func chooseTracks() {
        guard !tracksChosen, startedPlaying || vlc.phase == .playing, !vlc.audioTracks.isEmpty else { return }
        tracksChosen = true
        let saved = Settings.titleChoice(request.title.id)
        if let a = saved.audio, let t = vlc.audioTracks.first(where: { $0.name == a }) {
            vlc.setAudio(t.id)
        } else if Settings.dub != "original", vlc.audioTracks.count > 1,
                  let t = vlc.audioTracks.first(where: { Languages.match(language(of: $0.name), Settings.audioLanguage) > 0 }) {
            vlc.setAudio(t.id)
        }
        if let s = saved.subtitles {
            if s == "off" { vlc.setSubtitle(-1) }
            else if let t = vlc.subtitleTracks.first(where: { $0.name == s }) { vlc.setSubtitle(t.id) }
            return
        }
        guard Settings.subtitles, !Settings.subtitleLanguage.isEmpty else { return }
        let audioLang = vlc.audioTracks.first { $0.id == vlc.audioTrack }.flatMap { language(of: $0.name) }
        if Languages.match(audioLang, Settings.subtitleLanguage) > 0 { vlc.setSubtitle(-1); return }
        if let t = vlc.subtitleTracks.first(where: { Languages.match(language(of: $0.name), Settings.subtitleLanguage) > 0 }) {
            vlc.setSubtitle(t.id)
        } else {
            autoAddonSubtitle()
        }
    }

    private func autoAddonSubtitle() {
        guard Settings.subtitles, !Settings.subtitleLanguage.isEmpty, vlc.subtitleTrack < 0,
              Settings.titleChoice(request.title.id).subtitles == nil,
              let s = addonSubs.first(where: { Languages.match($0.lang, Settings.subtitleLanguage) > 0 }) else { return }
        vlc.addSubtitle(s.url, select: true)
    }

    // MARK: Progress

    private func tick() {
        if startedPlaying && vlc.phase == .playing {
            played += 1000
            ticks += 1
            if ticks % 10 == 0 { record(upload: ticks % 30 == 0) }
            checkSegments()
            prefetchNext()
        }
        if upNext && countdown > 0 && vlc.phase != .playing {
            countdown -= 1
            if countdown == 0, let next = request.next { playNext(next) }
        }
        if controls && panel == nil && vlc.phase == .playing && scrub == nil && Date().timeIntervalSince(lastTouch) > 5 { controls = false }
    }

    private func record(upload: Bool = false) {
        guard let item, startedPlaying, vlc.length > 0 else { return }
        Library.record(item, videoId: request.videoId, positionMs: vlc.time, durationMs: vlc.length, playedMs: played)
        played = 0
        model.save(item, upload: upload)
    }

    private func finish() {
        gatherTask?.cancel()
        record()
        if let item, startedPlaying {
            Library.stopped(item, nextVideoId: request.next?.id, videos: request.videos)
            model.save(item, upload: true)
        }
        vlc.stop()
        UIApplication.shared.isIdleTimerDisabled = false
        Orientation.app()
    }

    private func close() {
        dismiss()
    }

    private func prefetchNext() {
        guard !nextAsked, let next = request.next, Settings.nextEpisode, vlc.length > 0,
              vlc.length - vlc.time < 4 * 60_000, let p = model.active else { return }
        nextAsked = true
        let picker = StreamPicker(profile: p, type: request.type, metaId: request.title.id, videoId: next.id, known: model.addons)
        Task {
            await picker.gather { r in
                if r.done, !r.eligible.isEmpty { nextSources = (next.id, Array(r.eligible.prefix(3))) }
            }
        }
    }

    private func playNext(_ next: Video) {
        record()
        if let item {
            Library.stopped(item, nextVideoId: next.id, videos: request.videos)
            model.save(item, upload: true)
        }
        vlc.stop()
        var r = PlayRequest(title: request.title, videoId: next.id, episode: request.episodeLine(next), videos: request.videos)
        r.startOver = true
        request = r
        sources = []
        startedPlaying = false
        tracksChosen = false
        upNext = false
        upNextDismissed = false
        countdown = 0
        segments = []
        skipped = []
        outroAt = 0
        failure = []
        item = model.item(r.title.id)?.copy() ?? item
        fetchSubtitles()
        fetchSegments()
        let ready = nextSources?.id == next.id ? nextSources?.list : nil
        nextSources = nil
        nextAsked = false
        if let ready { start(ready) } else { gather() }
    }

    // MARK: Skip intro and Up next (IntroDB, as the TV)

    private func fetchSegments() {
        guard request.type == "series" else { return }
        let p = request.videoId.components(separatedBy: ":")
        guard request.title.id.hasPrefix("tt"), p.count >= 3 else { return }
        Task {
            var found = [Segment]()
            var outro: Int64 = 0
            if let text = try? await Http.get("https://api.introdb.app/segments?imdb_id=\(p[0])&season=\(p[1])&episode=\(p[2])", timeout: 8),
               let json = try? JSONText.parse(text) {
                func part(_ name: String) -> (Int64, Int64)? {
                    guard let o = json.obj(name), let a = (o["start_sec"] as? NSNumber)?.doubleValue,
                          let b = (o["end_sec"] as? NSNumber)?.doubleValue, a >= 0, b > a else { return nil }
                    return (Int64(a * 1000), Int64(b * 1000))
                }
                if let r = part("recap") { found.append(Segment(label: "Skip recap", start: r.0, end: r.1)) }
                if let i = part("intro") { found.append(Segment(label: "Skip intro", start: i.0, end: i.1)) }
                outro = part("outro")?.0 ?? 0
            }
            if found.isEmpty { found = [Segment(label: "Skip 85 s", start: 0, end: 300_000, jump: 85_000)] }
            segments = found
            outroAt = outro
        }
    }

    private var currentSegment: (Int, Segment)? {
        guard Settings.skipIntro, startedPlaying else { return nil }
        let t = vlc.time
        return segments.enumerated().first { i, s in t >= s.start && t < s.end - 1000 && !skipped.contains(i) }.map { ($0.offset, $0.element) }
    }

    private func checkSegments() {
        if case let (i, s)? = currentSegment {
            if skipShownAt[i] == nil { skipShownAt[i] = Date() }
            if s.jump == 0, Date().timeIntervalSince(skipShownAt[i]!) > 10 { skipped.insert(i) }
        }
        guard let _ = request.next, Settings.nextEpisode, !upNextDismissed, !upNext, vlc.length > 0 else { return }
        let left = vlc.length - vlc.time
        if (outroAt > 0 && vlc.time >= outroAt && left < 180_000) || left < max(30_000, vlc.length / 25) {
            upNext = true
            countdown = 10
        }
    }

    @ViewBuilder private func skipPill(upright: Bool) -> some View {
        if case let (i, s)? = currentSegment {
            VStack {
                Spacer()
                HStack {
                    Spacer()
                    Button {
                        skipped.insert(i)
                        vlc.seek(to: s.jump > 0 ? vlc.time + s.jump : s.end)
                    } label: {
                        Text(s.label).font(Look.bold(15)).foregroundStyle(.black.opacity(0.85))
                            .padding(.horizontal, 18).frame(height: 44)
                            .background(theme.accentColor, in: Soft())
                    }
                }
                .padding(.trailing, upright ? 12 : 28).padding(.bottom, upright ? 12 : controls ? 120 : 40)
            }
        }
    }

    private func upNextCard(_ next: Video) -> some View {
        VStack {
            Spacer()
            HStack {
                Spacer()
                VStack(alignment: .leading, spacing: 10) {
                    Text("UP NEXT").font(Look.bold(11)).tracking(1.4).foregroundStyle(.white.opacity(0.6))
                    HStack(spacing: 12) {
                        RemoteImage(url: next.thumbnail, width: 120).frame(width: 120, height: 68).clipShape(Soft())
                        VStack(alignment: .leading, spacing: 4) {
                            Text(next.label).font(Look.bold(13)).foregroundStyle(theme.accentColor)
                            Text(next.title).font(Look.bold(15)).foregroundStyle(.white).lineLimit(2)
                        }
                    }
                    HStack(spacing: 10) {
                        Button(countdown > 0 && vlc.phase != .playing ? "Play in \(countdown)" : "Play now") { playNext(next) }
                            .buttonStyle(PrimaryButton())
                        Button("Hide") { upNext = false; upNextDismissed = true }.buttonStyle(GlassButton())
                    }
                }
                .padding(16)
                .frame(width: 330)
                .background(.ultraThinMaterial, in: Soft(radius: 10))
            }
            .padding(.trailing, 24).padding(.bottom, controls ? 120 : 30)
        }
    }

    // MARK: Controls

    private var gestures: some View {
        HStack(spacing: 0) {
            Color.clear.contentShape(Rectangle())
                .onTapGesture(count: 2) { vlc.jump(-10); touched() }
                .onTapGesture { toggleControls() }
            Color.clear.contentShape(Rectangle())
                .onTapGesture(count: 2) { vlc.jump(10); touched() }
                .onTapGesture { toggleControls() }
        }
    }

    /// A keyboard on the iPad (or a Bluetooth remote that sends keys): space plays and pauses, the arrows skip 10 s
    /// (with Shift, 30 s), N is the next episode, Escape closes.
    private var keys: some View {
        ZStack {
            key(.space) { vlc.toggle(); touched() }
            key(.leftArrow) { vlc.jump(-10); touched() }
            key(.rightArrow) { vlc.jump(10); touched() }
            key(.leftArrow, .shift) { vlc.jump(-30); touched() }
            key(.rightArrow, .shift) { vlc.jump(30); touched() }
            key(.upArrow) { touched() }
            key("n") { if let next = request.next { playNext(next) } }
            key(.escape) { if panel != nil { panel = nil } else { close() } }
        }
        .opacity(0)
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }

    private func key(_ k: KeyEquivalent, _ m: EventModifiers = [], _ run: @escaping () -> Void) -> some View {
        Button("", action: run).keyboardShortcut(k, modifiers: m)
    }

    private func toggleControls() {
        if panel != nil { panel = nil; return }
        controls.toggle()
        lastTouch = Date()
    }

    private func touched() { lastTouch = Date(); if !controls { controls = true } }

    private var controlsLayer: some View {
        ZStack {
            LinearGradient(colors: [.black.opacity(0.65), .clear, .clear, .black.opacity(0.75)], startPoint: .top, endPoint: .bottom)
                .ignoresSafeArea().allowsHitTesting(false)
            VStack(spacing: 0) {
                HStack(alignment: .center, spacing: 6) {
                    closeButton
                    VStack(alignment: .leading, spacing: 2) {
                        Text(request.title.name).font(Look.title(17)).foregroundStyle(.white).lineLimit(1)
                        if let e = request.episode { Text(e).font(Look.body(13)).foregroundStyle(.white.opacity(0.75)).lineLimit(1) }
                    }
                    Spacer()
                    Text(Date(), style: .time).font(Look.bold(14)).foregroundStyle(.white.opacity(0.85))
                        .padding(.trailing, Orientation.phone ? 4 : 20)
                    if Orientation.phone {
                        bare("arrow.down.right.and.arrow.up.left", size: 20) { fullScreenByButton = false; Orientation.app() }.padding(.trailing, 8)
                    }
                }
                Spacer()
                HStack(spacing: 56) {
                    bare("gobackward.10", size: 30) { vlc.jump(-10); touched() }
                    Button { vlc.toggle(); touched() } label: {
                        Image(systemName: vlc.phase == .playing ? "pause.fill" : "play.fill").font(.system(size: 36, weight: .semibold))
                            .foregroundStyle(.white).frame(width: 86, height: 86)
                            .background(Circle().fill(Color.black.opacity(0.38)))
                            .overlay(Circle().stroke(Color.white.opacity(0.18), lineWidth: 1))
                    }
                    bare("goforward.10", size: 30) { vlc.jump(10); touched() }
                }
                Spacer()
                bar.padding(.horizontal, 24)
                HStack(spacing: 30) {
                    labelled("captions.bubble", "Audio & subtitles") { panel = .tracks }
                    labelled("aspectratio", "Picture size") { panel = .picture }
                    if sources.count > 0 || !request.picked {
                        labelled("square.stack.3d.up", "Sources") { close(); chooseSource(request) }
                    }
                    if let next = request.next { labelled("forward.end", "Next episode") { playNext(next) } }
                }
                .padding(.top, 10).padding(.bottom, 14)
            }
        }
    }

    // MARK: Upright (phone held upright, as the Android phone)

    /// On the strip: a tap in the middle plays or pauses, a double tap on either side skips 10 s. No controls on it.
    private var stripGestures: some View {
        HStack(spacing: 0) {
            Color.clear.contentShape(Rectangle())
                .onTapGesture(count: 2) { vlc.jump(-10) }
            Color.clear.contentShape(Rectangle())
                .onTapGesture { vlc.toggle() }
            Color.clear.contentShape(Rectangle())
                .onTapGesture(count: 2) { vlc.jump(10) }
        }
    }

    /// Under the strip, on graphite: the bar, back 10 / Play / ahead 10, a hairline, the name, episode and story, then
    /// a row of buttons along the foot.
    private var uprightPanel: some View {
        let current = request.videos.first { $0.id == request.videoId }
        return VStack(spacing: 0) {
            bar.padding(.horizontal, 20).padding(.top, 14)
            HStack(spacing: 36) {
                bare("gobackward.10", size: 30) { vlc.jump(-10) }.frame(width: 64, height: 64)
                Button { vlc.toggle() } label: {
                    Image(systemName: vlc.phase == .playing ? "pause.fill" : "play.fill").font(.system(size: 32, weight: .semibold))
                        .foregroundStyle(.white).frame(width: 80, height: 80)
                        .background(Circle().fill(Color.white.opacity(0.1)))
                        .overlay(Circle().stroke(Color.white.opacity(0.18), lineWidth: 1))
                }
                bare("goforward.10", size: 30) { vlc.jump(10) }.frame(width: 64, height: 64)
            }
            .padding(.vertical, 8)
            Rectangle().fill(Color.white.opacity(0.1)).frame(height: 1)
            ScrollView {
                VStack(alignment: .leading, spacing: 8) {
                    HStack(alignment: .top, spacing: 4) {
                        Text(request.title.name).font(Look.title(25)).foregroundStyle(theme.textColor).lineLimit(2)
                        Spacer(minLength: 0)
                    }
                    if let e = request.episode {
                        Text(e).font(Look.bold(15)).foregroundStyle(theme.accentColor).lineLimit(2)
                    }
                    if let o = current?.overview, !o.isEmpty {
                        Text(o).font(Look.body(15)).foregroundStyle(theme.text2).padding(.top, 4)
                    }
                }
                .padding(.horizontal, 20).padding(.vertical, 16)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .scrollIndicators(.hidden)
            HStack(spacing: 0) {
                cell("chevron.left", "Back") { close() }
                cell("captions.bubble", "Audio & subtitles") { panel = .tracks }
                if sources.count > 0 || !request.picked {
                    cell("square.stack.3d.up", "Change stream") { close(); chooseSource(request) }
                }
                if let next = request.next { cell("forward.end", "Next episode") { playNext(next) } }
                cell("arrow.up.left.and.arrow.down.right", "Full screen") { fullScreenByButton = true; Orientation.player() }
            }
            .padding(.top, 6).padding(.bottom, 4)
            .overlay(alignment: .top) { Rectangle().fill(Color.white.opacity(0.1)).frame(height: 1) }
        }
    }

    /// One of the equal cells along the foot: icon over a small label.
    private func cell(_ icon: String, _ label: String, _ run: @escaping () -> Void) -> some View {
        Button(action: run) {
            VStack(spacing: 5) {
                Image(systemName: icon).font(.system(size: 20, weight: .medium))
                Text(label).font(Look.bold(11)).lineLimit(1).minimumScaleFactor(0.8)
            }
            .foregroundStyle(.white)
            .frame(maxWidth: .infinity, minHeight: 56)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private func bare(_ icon: String, size: CGFloat, _ run: @escaping () -> Void) -> some View {
        Button(action: run) {
            Image(systemName: icon).font(.system(size: size, weight: .medium)).foregroundStyle(.white).frame(width: 60, height: 60)
        }
    }

    private func labelled(_ icon: String, _ label: String, _ run: @escaping () -> Void) -> some View {
        Button(action: run) {
            HStack(spacing: 7) {
                Image(systemName: icon).font(.system(size: 18, weight: .medium))
                Text(label).font(Look.bold(13))
            }
            .foregroundStyle(.white).frame(minHeight: 44).contentShape(Rectangle())
        }
    }

    private var bar: some View {
        let length = max(vlc.length, 1)
        let shown = scrub.map { Int64($0 * Double(length)) } ?? vlc.time
        return VStack(spacing: 6) {
            GeometryReader { g in
                let x = g.size.width * CGFloat(Double(shown) / Double(length))
                ZStack(alignment: .leading) {
                    Rectangle().fill(.white.opacity(0.25)).frame(height: 4)
                    Rectangle().fill(theme.accentColor).frame(width: max(0, x), height: 4)
                    Circle().fill(.white).frame(width: scrub == nil ? 16 : 24, height: scrub == nil ? 16 : 24)
                        .shadow(color: .black.opacity(0.4), radius: 4)
                        .offset(x: x - (scrub == nil ? 8 : 12))
                    if scrub != nil {
                        Text(format(shown)).font(Look.bold(14)).foregroundStyle(.white)
                            .padding(.horizontal, 8).padding(.vertical, 4)
                            .background(Color.black.opacity(0.7), in: Soft())
                            .offset(x: min(max(0, x - 30), g.size.width - 64), y: -34)
                    }
                }
                .frame(height: 30)
                .contentShape(Rectangle())
                .gesture(DragGesture(minimumDistance: 0)
                    .onChanged { v in scrub = Double(max(0, min(1, v.location.x / g.size.width))); lastTouch = Date() }
                    .onEnded { _ in
                        if let s = scrub { vlc.seek(to: Int64(s * Double(length))) }
                        scrub = nil
                        lastTouch = Date()
                    })
            }
            .frame(height: 30)
            HStack {
                Text(format(shown)).font(Look.bold(16)).foregroundStyle(.white)
                Spacer()
                Text("-" + format(max(0, vlc.length - shown))).font(Look.bold(16)).foregroundStyle(.white.opacity(0.8))
            }
        }
    }

    private func format(_ ms: Int64) -> String {
        let s = ms / 1000
        return s >= 3600 ? String(format: "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60) : String(format: "%d:%02d", s / 60, s % 60)
    }

    // MARK: Panels

    @ViewBuilder private func panelView(_ p: Panel) -> some View {
        ZStack(alignment: .trailing) {
            Color.black.opacity(0.35).ignoresSafeArea().onTapGesture { panel = nil }
            Group {
                switch p {
                case .tracks: TracksPanel(vlc: vlc, addonSubs: addonSubs, metaId: request.title.id) { panel = nil }
                case .picture: picturePanel
                }
            }
            .frame(width: min(screen.width * 0.9, p == .tracks ? 620 : 320))
            .frame(maxHeight: .infinity)
            .background(.ultraThinMaterial)
            .background(Color.black.opacity(0.4))
        }
        .transition(.move(edge: .trailing))
    }

    private var picturePanel: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("Picture size").font(Look.title(20)).foregroundStyle(.white).padding(.bottom, 8)
            ForEach([("fit", "Fit", "The whole picture, with bars"), ("fill", "Fill screen", "No bars, the edges cropped"),
                     ("stretch", "Stretch", "No bars, the picture pulled to the screen")], id: \.0) { mode, name, hint in
                Button {
                    pictureSize = mode
                    Settings.pictureSize = mode
                    vlc.setPictureSize(mode, screen: screen)
                } label: {
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(name).font(Look.bold(16)).foregroundStyle(.white)
                            Text(hint).font(Look.body(13)).foregroundStyle(.white.opacity(0.6))
                        }
                        Spacer()
                        if pictureSize == mode { Image(systemName: "checkmark").foregroundStyle(theme.accentColor) }
                    }
                    .padding(.vertical, 10).contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
            Spacer()
        }
        .padding(24)
    }
}

@MainActor
private final class GatherBox {
    var latest = StreamPicker.Result()
    var waiting = false
}

/// Audio on the left, subtitles on the right: the file's own tracks, then subtitles from add-ons.
struct TracksPanel: View {
    let vlc: VLC
    let addonSubs: [Subtitle]
    let metaId: String
    let done: () -> Void
    @Environment(\.theme) private var theme
    @State private var addedUrl: String?

    private var sortedAddonSubs: [Subtitle] {
        let mine = Settings.subtitleLanguage.isEmpty ? Settings.audioLanguage : Settings.subtitleLanguage
        var seen = Set<String>()
        return addonSubs.filter { seen.insert($0.url).inserted }
            .sorted { a, b in
                let ma = Languages.match(a.lang, mine), mb = Languages.match(b.lang, mine)
                return ma != mb ? ma > mb : a.language < b.language
            }
    }

    var body: some View {
        HStack(alignment: .top, spacing: 18) {
            column("Audio") {
                ForEach(vlc.audioTracks, id: \.id) { t in
                    row(t.name, on: vlc.audioTrack == t.id) {
                        vlc.setAudio(t.id)
                        Settings.pickedInPlayer(metaId, audio: t.name)
                    }
                }
                if vlc.audioTracks.isEmpty { Text("One track").font(Look.body(14)).foregroundStyle(.white.opacity(0.6)) }
            }
            column("Subtitles") {
                row("Off", on: vlc.subtitleTrack < 0) {
                    vlc.setSubtitle(-1)
                    Settings.pickedInPlayer(metaId, subtitles: "off")
                }
                ForEach(vlc.subtitleTracks, id: \.id) { t in
                    row(t.name, on: vlc.subtitleTrack == t.id) {
                        vlc.setSubtitle(t.id)
                        Settings.pickedInPlayer(metaId, subtitles: t.name)
                    }
                }
                if !sortedAddonSubs.isEmpty {
                    Text("FROM ADD-ONS").font(Look.bold(11)).tracking(1.2).foregroundStyle(.white.opacity(0.5)).padding(.top, 10)
                    ForEach(sortedAddonSubs.prefix(40), id: \.url) { s in
                        row("\(s.language) · \(s.addon)", on: addedUrl == s.url && vlc.subtitleTrack >= 0) {
                            addedUrl = s.url
                            vlc.addSubtitle(s.url, select: true)
                        }
                    }
                }
            }
        }
        .padding(22)
    }

    private func column<C: View>(_ title: String, @ViewBuilder _ content: () -> C) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title).font(Look.title(20)).foregroundStyle(.white).padding(.bottom, 8)
            ScrollView { VStack(alignment: .leading, spacing: 2) { content() } }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func row(_ name: String, on: Bool, _ run: @escaping () -> Void) -> some View {
        Button(action: run) {
            HStack(spacing: 10) {
                Image(systemName: "checkmark").opacity(on ? 1 : 0).foregroundStyle(theme.accentColor)
                Text(name).font(on ? Look.bold(15) : Look.body(15)).foregroundStyle(.white).lineLimit(2)
                Spacer(minLength: 0)
            }
            .padding(.vertical, 9).contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

/// iPhone pages stay upright; the player turns sideways (and back) by itself. An iPad turns freely.
@MainActor
enum Orientation {
    /// Why the last turn was refused, if it was.
    static var problem: String?
    // SwiftUI's presented screens keep the orientations they started with, so the app allows every way up all the time
    // and turns the phone itself: sideways for the player, upright again after.
    static var mask: UIInterfaceOrientationMask = UIDevice.current.userInterfaceIdiom == .pad ? .all : .allButUpsideDown

    static var phone: Bool { UIDevice.current.userInterfaceIdiom == .phone }

    /// Full screen: the phone turns sideways (the player follows the phone's own turning too).
    static func player() {
        guard phone else { return }
        problem = nil
        request(.landscapeRight)
        // Once more after the player's screen is up, so its controller is in the chain that turns.
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { problem = nil; request(.landscapeRight) }
    }

    static func app() {
        guard UIDevice.current.userInterfaceIdiom == .phone else { return }
        request(.portrait)
    }

    private static func request(_ m: UIInterfaceOrientationMask) {
        guard let scene = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first else { return }
        // Every controller in the chain re-reads the allowed orientations first, then the scene turns.
        var vc = scene.keyWindow?.rootViewController ?? scene.windows.first?.rootViewController
        while let c = vc {
            c.setNeedsUpdateOfSupportedInterfaceOrientations()
            vc = c.presentedViewController
        }
        scene.requestGeometryUpdate(.iOS(interfaceOrientations: m)) { error in
            Task { @MainActor in problem = error.localizedDescription }
        }
    }
}

final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication, supportedInterfaceOrientationsFor window: UIWindow?) -> UIInterfaceOrientationMask {
        Orientation.mask
    }
}
