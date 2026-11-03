import SwiftUI

/// Every source for one video, best match first, chosen by hand. Audio-language chips above the list when the sources
/// name more than one.
struct SourcesView: View {
    let request: PlayRequest
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var theme
    @Environment(\.play) private var play
    @Environment(\.dismiss) private var dismiss
    @State private var result = StreamPicker.Result()
    @State private var audio: String?
    @State private var audioOpened = false

    private var languages: [String] {
        let unnamed = result.ordered.contains { $0.audioLanguages.isEmpty && !$0.multiAudio }
        var codes = result.ordered.flatMap(\.audioLanguages) + (unnamed ? ["en"] : [])
        var seen = Set<String>()
        codes = codes.filter { seen.insert($0).inserted }
        let mine = Languages.base(Settings.audioLanguage)
        return codes.sorted { a, b in
            if (a == mine) != (b == mine) { return a == mine }
            if (a == "en") != (b == "en") { return a == "en" }
            return Languages.name(a) < Languages.name(b)
        }
    }

    private var shown: [Stream] {
        guard let audio else { return result.ordered }
        return result.ordered.filter { speaks($0, audio) }
    }

    private func speaks(_ s: Stream, _ language: String) -> Bool {
        s.audioLanguages.contains(language) || s.multiAudio || (language == "en" && s.audioLanguages.isEmpty)
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(request.episode ?? request.title.name).font(Look.title(22)).foregroundStyle(theme.textColor).lineLimit(2)
                    if request.episode != nil {
                        Text(request.title.name).font(Look.body(14)).foregroundStyle(theme.text2)
                    }
                    Text(countLine).font(Look.body(13)).foregroundStyle(theme.text3)
                }
                .padding(.horizontal, 20)
                if languages.count > 1 {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 6) {
                            chip(nil)
                            ForEach(languages, id: \.self) { chip($0) }
                        }
                        .padding(.horizontal, 20)
                    }
                }
                if !result.done && result.ordered.isEmpty {
                    HStack(spacing: 12) { Spinner(size: 22); Text("Finding sources…").font(Look.body(15)).foregroundStyle(theme.text2) }
                        .padding(20)
                }
                if result.done && shown.isEmpty {
                    VStack(alignment: .leading, spacing: 10) {
                        Text("No playable sources found.").font(Look.bold(15)).foregroundStyle(theme.textColor)
                        ForEach(result.reasons, id: \.self) { Text($0).font(Look.body(14)).foregroundStyle(theme.text2) }
                    }
                    .padding(.horizontal, 20)
                }
                LazyVStack(spacing: 2) {
                    let best = result.eligible.first { shown.contains($0) }
                    ForEach(shown) { s in
                        Button {
                            var r = request
                            r.sources = [s]
                            play(r)
                        } label: {
                            SourceRow(stream: s, best: s == best)
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.horizontal, 12)
                Text("Best match follows your stream settings: \(Settings.presetName).")
                    .font(Look.body(12)).foregroundStyle(theme.text3).padding(20)
            }
            .padding(.top, 8)
        }
        .background {
            ZStack {
                theme.baseColor
                RemoteImage(url: request.title.background ?? Art.background(for: request.title.id), width: 600, placeholder: false)
                    .opacity(0.25).blur(radius: 30)
            }
            .ignoresSafeArea()
        }
        .navigationTitle("Sources")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar(.visible, for: .navigationBar)
        .task {
            guard let p = model.active else { return }
            let picker = StreamPicker(profile: p, type: request.type, metaId: request.title.id, videoId: request.videoId)
            await picker.gather { r in
                result = r
                if !audioOpened && r.done {
                    audioOpened = true
                    let mine = Languages.base(Settings.audioLanguage)
                    if Settings.dub == "mine" && languages.contains(mine) { audio = mine }
                }
            }
        }
    }

    private var countLine: String {
        let n = shown.count
        return result.done || n > 0 ? "\(n) \(n == 1 ? "source" : "sources")" + (result.done ? "" : "  ·  still looking") : ""
    }

    private func chip(_ code: String?) -> some View {
        let on = audio == code
        return Button { audio = code } label: {
            Text(code.map(Languages.name) ?? "All languages").font(Look.bold(13))
                .foregroundStyle(on ? Color.black.opacity(0.85) : theme.textColor)
                .padding(.horizontal, 12).frame(height: 36)
                .background(on ? theme.accentColor : theme.tile, in: Soft())
        }
        .buttonStyle(.plain)
    }
}

/// One source: quality, what the release is, the debrid service and tags, who found it, and its size.
struct SourceRow: View {
    let stream: Stream
    let best: Bool
    @Environment(\.theme) private var theme

    var body: some View {
        HStack(alignment: .center, spacing: 12) {
            Text(stream.height > 0 ? "\(stream.height)p" : "Auto").font(Look.heavy(15)).foregroundStyle(theme.textColor)
                .frame(width: 56, alignment: .leading)
            VStack(alignment: .leading, spacing: 5) {
                HStack(spacing: 6) {
                    if best { badge("BEST MATCH", filled: true) }
                    Text(stream.summary).font(Look.bold(14)).foregroundStyle(theme.text2).lineLimit(1)
                }
                HStack(spacing: 5) {
                    ForEach(([stream.debrid?.tag].compactMap { $0 } + stream.tags.filter { !["BluRay", "WEB"].contains($0) }).prefix(4), id: \.self) {
                        badge($0.uppercased(), filled: false)
                    }
                    Text(stream.provider).font(Look.body(12)).foregroundStyle(theme.text3).lineLimit(1)
                }
            }
            Spacer(minLength: 4)
            Text(stream.sizeText).font(Look.bold(14)).foregroundStyle(theme.textColor)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 12)
        .background(best ? theme.accentColor.opacity(0.08) : theme.glass.opacity(0.6), in: Soft())
        .overlay(Soft().stroke(best ? theme.accentColor.opacity(0.5) : .clear, lineWidth: 1))
        .opacity(stream.height > 0 && stream.height < 720 ? 0.55 : 1)
        .contentShape(Rectangle())
    }

    private func badge(_ text: String, filled: Bool) -> some View {
        Text(text).font(Look.bold(10)).tracking(0.5)
            .foregroundStyle(filled ? Color.black.opacity(0.85) : theme.text2)
            .padding(.horizontal, 6).padding(.vertical, 3)
            .background(filled ? theme.accentColor : Color.clear, in: Soft(radius: 3))
            .overlay(Soft(radius: 3).stroke(filled ? .clear : theme.text3, lineWidth: 1))
    }
}
