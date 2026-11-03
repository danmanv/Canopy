import SwiftUI

/// A title's page: its art and logo, where you are, a big Play or Resume, the actions under it, the story, then the
/// episodes by season (a show) or More like this (a film). On an iPad the episodes sit beside the rest.
struct TitleView: View {
    let ref: TitleRef
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var theme
    @Environment(\.play) private var play
    @Environment(\.navigate) private var navigate
    @Environment(\.horizontalSizeClass) private var size
    @Environment(\.dismiss) private var dismiss
    @State private var meta: Meta?
    @State private var failed = false
    @State private var season = -1
    @State private var similar: [Card] = []
    @State private var trailer: String?
    @State private var logoFailed = false

    private var wide: Bool { size == .regular }
    private var item: LibraryItem? { _ = model.libraryVersion; return model.item(ref.id) }
    private var videos: [Video] { meta?.videos ?? [] }
    private var watched: Set<String> { Library.watched(item, videos: videos) }

    var body: some View {
        Group {
            if wide {
                HStack(alignment: .top, spacing: 0) {
                    ScrollView { header.padding(.bottom, 40) }
                        .frame(maxWidth: .infinity)
                    ScrollView { below.padding(.vertical, 24) }
                        .frame(width: 440)
                        .background(theme.glass.opacity(0.5))
                }
            } else {
                ScrollView {
                    VStack(alignment: .leading, spacing: 24) {
                        header
                        below
                    }
                    .padding(.bottom, 40)
                }
            }
        }
        .background(theme.baseColor)
        .ignoresSafeArea(edges: .top)
        .toolbar(.hidden, for: .navigationBar)
        .overlay(alignment: .topLeading) { backButton }
        .sheet(item: Binding(get: { trailer.map(TrailerId.init) }, set: { trailer = $0?.id })) { t in
            TrailerSheet(youTubeId: t.id)
        }
        .task { await load() }
    }

    private var backButton: some View {
        Button { dismiss() } label: {
            Image(systemName: "chevron.left").font(.system(size: 18, weight: .semibold)).foregroundStyle(.white)
                .frame(width: 44, height: 44)
                .background(Color.black.opacity(0.35), in: Soft(radius: 8))
        }
        .padding(.leading, 12)
        .padding(.top, 4)
    }

    // MARK: Header

    private var header: some View {
        VStack(alignment: .leading, spacing: 14) {
            ZStack(alignment: .bottomLeading) {
                RemoteImage(url: meta?.background ?? ref.background ?? Art.background(for: ref.id), width: wide ? 900 : 500,
                            fallback: ref.poster)
                    .frame(height: wide ? 460 : 400)
                    .frame(maxWidth: .infinity)
                    .clipped()
                    .overlay(LinearGradient(colors: [theme.baseColor.opacity(0.25), .clear, theme.baseColor],
                                            startPoint: .top, endPoint: .bottom))
                brand.padding(.horizontal, 20)
            }
            VStack(alignment: .leading, spacing: 14) {
                if let meta, !meta.info.isEmpty {
                    Text(meta.info + (meta.rating.map { "   ★ \($0)" } ?? "")).font(Look.body(14)).foregroundStyle(theme.text2)
                }
                if let w = whereLine {
                    Text(w).font(Look.bold(14)).foregroundStyle(theme.accentColor).lineLimit(1)
                }
                if let item, item.timeOffset > 1, item.duration > 0, target?.0 == item.videoId {
                    ProgressLine(value: item.progress)
                }
                Button { start(startOver: false) } label: {
                    Label(resumable ? "Resume" : "Play", systemImage: "play.fill")
                }
                .buttonStyle(PrimaryButton())
                .disabled(target == nil)
                .opacity(target == nil ? 0.5 : 1)
                actions
                if let d = meta?.description {
                    Text(d).font(Look.body(15)).foregroundStyle(theme.text2).lineSpacing(3)
                }
                if let meta, !meta.cast.isEmpty {
                    Text("Starring ").font(Look.bold(13)).foregroundColor(theme.text3)
                        + Text(meta.cast.prefix(5).joined(separator: ", ")).font(Look.body(13)).foregroundColor(theme.text2)
                }
                if let meta, !meta.directors.isEmpty {
                    Text("Directed by ").font(Look.bold(13)).foregroundColor(theme.text3)
                        + Text(meta.directors.joined(separator: ", ")).font(Look.body(13)).foregroundColor(theme.text2)
                }
                if failed && meta == nil {
                    Notice(text: "Couldn't load this title's details.", action: ("Try again", { Task { await load() } }))
                }
            }
            .padding(.horizontal, 20)
        }
    }

    @ViewBuilder private var brand: some View {
        let logo = meta?.logo ?? ref.logo ?? Art.logo(for: ref.id)
        if let logo, !logoFailed {
            LogoImage(url: logo, failed: $logoFailed)
                .frame(maxWidth: 260, maxHeight: 100, alignment: .bottomLeading)
        } else {
            Text(meta?.name ?? ref.name).font(Look.title(30)).foregroundStyle(theme.textColor).lineLimit(3)
        }
    }

    private var actions: some View {
        HStack(spacing: 0) {
            action("arrow.counterclockwise", "Restart") { start(startOver: true) }
            action(Library.inList(item) ? "checkmark" : "plus", "My List") {
                model.toggleList(id: ref.id, type: ref.type, name: meta?.name ?? ref.name, poster: meta?.poster ?? ref.poster)
            }
            if let t = meta?.trailer, Settings.trailers {
                action("film", "Trailer") { trailer = t }
            }
            action("square.stack.3d.up", "Stream") { choose(target?.0, label: target?.1) }
        }
    }

    private func action(_ icon: String, _ label: String, _ run: @escaping () -> Void) -> some View {
        Button(action: run) {
            VStack(spacing: 6) {
                Image(systemName: icon).font(.system(size: 20, weight: .medium))
                Text(label).font(Look.bold(12))
            }
            .foregroundStyle(theme.textColor)
            .frame(maxWidth: .infinity, minHeight: 64)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    // MARK: Below: episodes or More like this

    @ViewBuilder private var below: some View {
        if ref.type == "series" || !videos.isEmpty && videos.count > 1 {
            episodes
        } else if !similar.isEmpty {
            PosterRow(title: "More like this", cards: similar, width: wide ? 130 : 112)
        }
    }

    private var seasons: [Int] { meta?.seasons ?? [] }

    @ViewBuilder private var episodes: some View {
        VStack(alignment: .leading, spacing: 14) {
            if seasons.count > 1 {
                ScrollViewReader { proxy in
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) {
                            ForEach(seasons, id: \.self) { s in
                                Button { season = s } label: {
                                    Text(s == 0 ? "Specials" : "Season \(s)").font(Look.bold(14))
                                        .foregroundStyle(s == season ? Color.black.opacity(0.85) : theme.textColor)
                                        .padding(.horizontal, 14).frame(height: 38)
                                        .background(s == season ? theme.accentColor : theme.tile, in: Soft())
                                }
                                .buttonStyle(.plain)
                                .id(s)
                            }
                        }
                        .padding(.horizontal, 20)
                    }
                    .onAppear { proxy.scrollTo(season, anchor: .center) }
                }
            } else if let s = seasons.first {
                Text(s == 0 ? "Specials" : "Season \(s)").font(Look.title(18)).foregroundStyle(theme.textColor).padding(.horizontal, 20)
            }
            if meta == nil {
                Spinner().frame(maxWidth: .infinity).padding(.top, 30)
            }
            LazyVStack(alignment: .leading, spacing: 18) {
                ForEach(videos.filter { $0.season == season }) { v in
                    EpisodeRow(video: v, watched: watched.contains(v.id), progress: progress(v), current: v.id == target?.0)
                        .contentShape(Rectangle())
                        .onTapGesture { playEpisode(v) }
                        .contextMenu {
                            Button { playEpisode(v) } label: { Label("Play", systemImage: "play.fill") }
                            Button { choose(v.id, label: episodeLabel(v)) } label: { Label("Choose a source", systemImage: "square.stack.3d.up") }
                            Button { markWatched(v) } label: { Label("Mark as watched", systemImage: "checkmark.circle") }
                        }
                }
            }
            .padding(.horizontal, 20)
        }
    }

    private func progress(_ v: Video) -> Double? {
        guard let item, item.videoId == v.id, item.timeOffset > 1, item.duration > 0 else { return nil }
        return item.progress
    }

    // MARK: What Play plays

    /// The video Play starts: the one in progress, else the first not watched, else the first.
    private var target: (String, String?)? {
        if ref.type == "movie" || (videos.isEmpty && meta != nil) { return (ref.id, nil) }
        guard !videos.isEmpty else { return nil }
        if let v = item?.videoId, let video = videos.first(where: { $0.id == v }) { return (v, episodeLabel(video)) }
        let regular = videos.filter { $0.season > 0 }
        let next = regular.first { !watched.contains($0.id) } ?? regular.first ?? videos[0]
        return (next.id, episodeLabel(next))
    }

    private var resumable: Bool {
        guard let t = target, let item else { return false }
        return Library.resumeAt(item, videoId: t.0) > 0
    }

    private var whereLine: String? {
        guard ref.type == "series", let t = target, let label = t.1 else { return nil }
        if let item, item.videoId == t.0, item.timeOffset > 1, item.duration > 0 {
            let left = (item.duration - item.timeOffset) / 60_000
            return "\(label) · \(left) min left"
        }
        return label
    }

    private func episodeLabel(_ v: Video) -> String { v.title.isEmpty ? v.label : "\(v.label) · \(v.title)" }

    private func start(startOver: Bool) {
        guard let t = target else { return }
        play(request(t.0, label: t.1, startOver: startOver))
    }

    private func playEpisode(_ v: Video) { play(request(v.id, label: episodeLabel(v), startOver: false)) }

    private func choose(_ videoId: String?, label: String?) {
        guard let videoId else { return }
        navigate(.sources(request(videoId, label: label, startOver: false)))
    }

    private func request(_ videoId: String, label: String?, startOver: Bool) -> PlayRequest {
        let r = TitleRef(id: ref.id, type: ref.type, name: meta?.name ?? ref.name, poster: meta?.poster ?? ref.poster,
                         background: meta?.background ?? ref.background, logo: meta?.logo ?? ref.logo)
        return PlayRequest(title: r, videoId: videoId, episode: label, startOver: startOver, videos: videos)
    }

    private func markWatched(_ v: Video) {
        let it = item?.copy() ?? Library.newItem(id: ref.id, type: ref.type, name: meta?.name ?? ref.name, poster: meta?.poster ?? ref.poster)
        Library.markWatched(it, videos: videos, videoId: v.id)
        it.json["_mtime"] = Library.now()
        model.save(it)
    }

    // MARK: Loading

    private func load() async {
        failed = false
        if meta == nil, let saved = model.addons.savedMeta(type: ref.type, id: ref.id) { apply(saved) }
        var addons = model.addons
        if addons.isEmpty, let key = model.authKey { addons = (try? await Stremio.anyAddons(key)) ?? [] }
        if let fresh = await addons.meta(type: ref.type, id: ref.id, together: true) { apply(fresh) } else if meta == nil { failed = true }
        if ref.type == "movie", similar.isEmpty {
            for a in addons {
                if let s = try? await a.similar(type: ref.type, id: ref.id), !s.isEmpty {
                    similar = s.filter { $0.id != ref.id }.prefix(20).map(Card.init)
                    break
                }
            }
        }
    }

    private func apply(_ m: Meta) {
        meta = m
        if season == -1 || !m.seasons.contains(season) {
            let current = item?.videoId.flatMap { v in m.videos.first { $0.id == v } }?.season
            season = current ?? m.seasons.first { $0 > 0 } ?? m.seasons.first ?? 1
        }
    }
}

private struct TrailerId: Identifiable { let id: String }

/// The title's logo, or nothing (the name shows instead) when it doesn't load.
private struct LogoImage: View {
    let url: String
    @Binding var failed: Bool
    @State private var image: UIImage?
    var body: some View {
        Group {
            if let image { Image(uiImage: image).resizable().aspectRatio(contentMode: .fit) } else { Color.clear.frame(height: 80) }
        }
        .task(id: url) {
            image = await ImageStore.shared.image(url, width: 300)
            if image == nil { failed = true }
        }
    }
}

/// One episode: its still with progress, number and name, runtime and date, two lines of story; the accent outline
/// marks the one Play starts.
struct EpisodeRow: View {
    let video: Video
    let watched: Bool
    let progress: Double?
    let current: Bool
    @Environment(\.theme) private var theme

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            ZStack(alignment: .bottom) {
                RemoteImage(url: video.thumbnail, width: 150)
                    .frame(width: 150, height: 84)
                    .clipShape(Soft())
                if let progress {
                    ProgressLine(value: progress).padding(.horizontal, 6).padding(.bottom, 6)
                }
                if watched {
                    Image(systemName: "checkmark.circle.fill").font(.system(size: 18)).foregroundStyle(theme.accentColor)
                        .background(Circle().fill(Color.black.opacity(0.5)))
                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topTrailing).padding(5)
                }
            }
            .frame(width: 150, height: 84)
            .overlay(Soft().stroke(current ? theme.accentColor : .clear, lineWidth: 2))
            VStack(alignment: .leading, spacing: 4) {
                Text("\(video.episode). \(video.title.isEmpty ? "Episode \(video.episode)" : video.title)")
                    .font(Look.bold(15)).foregroundStyle(theme.textColor).lineLimit(2)
                if let date = video.releasedDate {
                    Text(date.formatted(date: .abbreviated, time: .omitted) + (date > Date() ? " · Coming" : ""))
                        .font(Look.body(12)).foregroundStyle(theme.text3)
                }
                if let o = video.overview {
                    Text(o).font(Look.body(13)).foregroundStyle(theme.text2).lineLimit(2)
                }
            }
            Spacer(minLength: 0)
        }
        .opacity(video.releasedDate.map { $0 > Date() } == true ? 0.5 : 1)
    }
}
