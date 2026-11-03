import SwiftUI
import Speech
import AVFoundation

/// Search every add-on that searches: films and shows in two rows, recent searches before typing, and a microphone.
struct SearchView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var theme
    @Environment(\.horizontalSizeClass) private var size
    @State private var query = ""
    @State private var movies: [Card] = []
    @State private var shows: [Card] = []
    @State private var searching = false
    @State private var failed = false
    @State private var searched = ""
    @State private var listener = Listener()
    @FocusState private var focused: Bool

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 10) {
                Image(systemName: "magnifyingglass").foregroundStyle(theme.text3)
                TextField("", text: $query, prompt: Text("Films and shows").foregroundColor(theme.text3))
                    .font(Look.body(17)).foregroundStyle(theme.textColor)
                    .focused($focused)
                    .submitLabel(.search)
                    .autocorrectionDisabled()
                    .onSubmit { run(query) }
                if !query.isEmpty {
                    Button { query = ""; movies = []; shows = [] } label: {
                        Image(systemName: "xmark.circle.fill").foregroundStyle(theme.text3)
                    }
                }
                Button { listen() } label: {
                    Image(systemName: listener.listening ? "waveform" : "mic.fill")
                        .foregroundStyle(listener.listening ? theme.accentColor : theme.textColor)
                        .symbolEffect(.pulse, isActive: listener.listening)
                        .frame(width: 40, height: 40)
                }
            }
            .padding(.horizontal, 14)
            .frame(height: 50)
            .glassCard(theme)
            .padding(.horizontal, 16)
            .padding(.top, 8)

            ScrollView {
                VStack(alignment: .leading, spacing: 26) {
                    if query.isEmpty && movies.isEmpty && shows.isEmpty { recent }
                    if searching { Spinner().frame(maxWidth: .infinity).padding(.top, 40) }
                    if !movies.isEmpty { PosterRow(title: "Movies", cards: movies, width: size == .regular ? 140 : 112, showNames: true) }
                    if !shows.isEmpty { PosterRow(title: "Shows", cards: shows, width: size == .regular ? 140 : 112, showNames: true) }
                    if !searching && !searched.isEmpty && movies.isEmpty && shows.isEmpty {
                        Notice(text: failed ? "Search isn't answering. Try again in a moment." : "Nothing found for “\(searched)”.")
                    }
                }
                .padding(.vertical, 20)
            }
            .scrollDismissesKeyboard(.immediately)
        }
        .background(theme.baseColor)
        .navigationTitle("Search")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar(.visible, for: .navigationBar)
        .onAppear { focused = true }
        .onChange(of: query) { _, q in
            // Search as you type, after a short pause.
            let typed = q
            Task {
                try? await Task.sleep(nanoseconds: 450_000_000)
                if typed == query && typed.count >= 2 { run(typed, remember: false) }
            }
        }
        .onChange(of: listener.text) { _, t in if !t.isEmpty { query = t } }
        .onChange(of: listener.listening) { _, on in if !on && !query.isEmpty { run(query) } }
    }

    @ViewBuilder private var recent: some View {
        let list = Settings.recentSearches
        if !list.isEmpty {
            VStack(alignment: .leading, spacing: 4) {
                Text("RECENT").font(Look.bold(12)).tracking(1.4).foregroundStyle(theme.text3).padding(.bottom, 6)
                ForEach(list, id: \.self) { r in
                    Button { query = r; run(r) } label: {
                        HStack {
                            Image(systemName: "clock.arrow.circlepath").foregroundStyle(theme.text3)
                            Text(r).font(Look.body(16)).foregroundStyle(theme.textColor)
                            Spacer()
                        }
                        .frame(height: 44).contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 20)
        }
    }

    private func run(_ q: String, remember: Bool = true) {
        let text = q.trimmingCharacters(in: .whitespaces)
        guard !text.isEmpty else { return }
        if remember { Settings.recentSearches = [text] + Settings.recentSearches.filter { $0.lowercased() != text.lowercased() } }
        guard text != searched || failed else { return }
        searched = text
        searching = true
        failed = false
        let addons = model.addons
        Task {
            var all = [Meta]()
            var anyAnswered = false
            await withTaskGroup(of: (Int, [Meta]?).self) { group in
                for (i, a) in addons.enumerated() { group.addTask { (i, try? await a.search(text)) } }
                var answers = [(Int, [Meta])]()
                for await (i, r) in group { if let r { anyAnswered = true; answers.append((i, r)) } }
                all = answers.sorted { $0.0 < $1.0 }.flatMap(\.1)
            }
            guard text == searched else { return }
            var seen = Set<String>()
            let unique = all.filter { !isAddonMessage($0.name) && seen.insert($0.type + $0.id).inserted }
            movies = unique.filter { $0.type == "movie" }.map(Card.init)
            shows = unique.filter { $0.type == "series" }.map(Card.init)
            failed = !anyAnswered && !addons.isEmpty
            searching = false
        }
    }

    private func listen() {
        focused = false
        if listener.listening { listener.stop() } else { listener.start() }
    }
}

/// Speech to text for the microphone in Search.
@MainActor
@Observable
final class Listener {
    var text = ""
    var listening = false
    private let recognizer = SFSpeechRecognizer()
    private let engine = AVAudioEngine()
    private var request: SFSpeechAudioBufferRecognitionRequest?
    private var task: SFSpeechRecognitionTask?

    func start() {
        SFSpeechRecognizer.requestAuthorization { status in
            Task { @MainActor in
                guard status == .authorized else { return }
                AVAudioApplication.requestRecordPermission { ok in
                    Task { @MainActor in if ok { self.begin() } }
                }
            }
        }
    }

    private func begin() {
        guard let recognizer, recognizer.isAvailable else { return }
        let session = AVAudioSession.sharedInstance()
        try? session.setCategory(.record, mode: .measurement, options: .duckOthers)
        try? session.setActive(true, options: .notifyOthersOnDeactivation)
        let request = SFSpeechAudioBufferRecognitionRequest()
        request.shouldReportPartialResults = true
        self.request = request
        let input = engine.inputNode
        input.removeTap(onBus: 0)
        input.installTap(onBus: 0, bufferSize: 1024, format: input.outputFormat(forBus: 0)) { buffer, _ in request.append(buffer) }
        engine.prepare()
        do { try engine.start() } catch { return }
        text = ""
        listening = true
        task = recognizer.recognitionTask(with: request) { result, error in
            Task { @MainActor in
                if let result { self.text = result.bestTranscription.formattedString }
                if error != nil || result?.isFinal == true { self.stop() }
            }
        }
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 6_000_000_000)
            if self.listening { self.stop() }
        }
    }

    func stop() {
        guard listening else { return }
        engine.stop()
        engine.inputNode.removeTap(onBus: 0)
        request?.endAudio()
        task?.cancel()
        listening = false
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback)
    }
}

/// A title's trailer from YouTube, in a sheet.
struct TrailerSheet: View {
    let youTubeId: String
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        ZStack(alignment: .topTrailing) {
            Color.black.ignoresSafeArea()
            YouTubeView(id: youTubeId).aspectRatio(16 / 9, contentMode: .fit).frame(maxHeight: .infinity)
            Button { dismiss() } label: {
                Image(systemName: "xmark").font(.system(size: 17, weight: .bold)).foregroundStyle(.white)
                    .frame(width: 44, height: 44)
            }
            .padding(8)
        }
        .presentationDetents([.medium, .large])
        .presentationBackground(.black)
    }
}
