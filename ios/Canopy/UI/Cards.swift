import SwiftUI

/// A title as pages show it: from a catalog (Meta) or from the library (Continue Watching, My List).
struct Card: Identifiable, Hashable {
    let id: String
    let type: String
    let name: String
    let poster: String?
    var background: String? = nil
    var logo: String? = nil
    var facts: String = ""
    /// Continue Watching: "S1 E3 · 31 min left" and how far in.
    var left: String? = nil
    var progress: Double? = nil
    var videoId: String? = nil
    var description: String? = nil
    /// The add-on's IMDb rating ("8.1"), for the poster's scores.
    var imdb: String? = nil

    static func == (a: Card, b: Card) -> Bool { a.id == b.id && a.progress == b.progress && a.left == b.left }
    func hash(into h: inout Hasher) { h.combine(id) }

    init(id: String, type: String, name: String, poster: String?) {
        self.id = id; self.type = type; self.name = name; self.poster = poster
    }

    init(_ m: Meta) {
        self.init(id: m.id, type: m.type, name: m.name, poster: Art.poster(m.id, m.poster))
        background = m.background
        logo = m.logo
        facts = [m.year, Ratings.line(imdb: m.rating, Ratings.cached(m.id)), m.genres.first].compactMap { $0 }.joined(separator: " · ")
        description = m.description
        imdb = m.rating
    }

    init(_ item: LibraryItem) {
        self.init(id: item.id, type: item.type, name: item.name, poster: Art.poster(item.id, item.poster))
        background = Art.background(for: item.id)
        videoId = item.videoId
        if item.timeOffset > 0 {
            progress = item.progress
            var parts = [String]()
            if let v = item.videoId, item.type == "series", let ep = Card.episodeLabel(v) { parts.append(ep) }
            let left = (item.duration - item.timeOffset) / 60_000
            if item.duration > 0 && item.timeOffset > 1 { parts.append(left > 0 ? "\(left) min left" : "Almost done") }
            else if item.timeOffset == 1 { parts.append("Next episode") }
            self.left = parts.joined(separator: " · ")
        }
    }

    /// "tt123:1:4" → "S1 E4".
    static func episodeLabel(_ videoId: String) -> String? {
        let p = videoId.components(separatedBy: ":")
        guard p.count >= 3, let s = Int(p[p.count - 2]), let e = Int(p[p.count - 1]) else { return nil }
        return "S\(s) E\(e)"
    }

    var ref: TitleRef { TitleRef(id: id, type: type, name: name, poster: poster, background: background, logo: logo) }
}

/// What a title page needs to open at once, before its details arrive.
struct TitleRef: Hashable {
    let id: String
    let type: String
    let name: String
    var poster: String?
    var background: String?
    var logo: String?
}

enum Route: Hashable {
    case title(TitleRef)
    case search
    case settings
    case catalog(key: String, name: String, genre: String?)
    case sources(PlayRequest)
}

/// A poster with its progress bar, soft corners, and its name below when asked.
struct PosterCard: View {
    let card: Card
    var width: CGFloat
    var showName = false
    @Environment(\.theme) private var theme

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            ZStack(alignment: .bottom) {
                RemoteImage(url: card.poster, width: width)
                    .frame(width: width, height: width * 1.5)
                    .overlay(alignment: .bottom) {
                        // Not on a title in progress: its bar sits there.
                        if (card.progress ?? 0) <= 0 { ScoreStrip(id: card.id, type: card.type, imdb: card.imdb, width: width) }
                    }
                    .clipShape(Soft())
                    .overlay(alignment: .center) {
                        if card.poster == nil {
                            Text(card.name).font(Look.title(14)).multilineTextAlignment(.center)
                                .foregroundStyle(theme.text2).padding(8)
                        }
                    }
                if let p = card.progress, p > 0 {
                    GeometryReader { g in
                        ZStack(alignment: .leading) {
                            Rectangle().fill(Color.black.opacity(0.5))
                            Rectangle().fill(theme.accentColor).frame(width: g.size.width * p)
                        }
                    }
                    .frame(height: 3)
                    .clipShape(Soft(radius: 1.5))
                    .padding(.horizontal, 6).padding(.bottom, 6)
                }
            }
            .overlay(Soft().stroke(theme.textColor.opacity(0.08), lineWidth: 1))
            if showName {
                Text(card.name).font(Look.bold(13)).foregroundStyle(theme.textColor).lineLimit(1)
                if let left = card.left { Text(left).font(Look.body(12)).foregroundStyle(theme.text3).lineLimit(1) }
            }
        }
        .frame(width: width, alignment: .leading)
        .contentShape(Rectangle())
    }
}

/// A poster's IMDb and Rotten Tomatoes scores along its foot, in Canopy's own letters (MDBList), as on Android.
/// Add-ons print theirs into the poster too small to read, so Art.poster swaps those pictures for plain ones.
struct ScoreStrip: View {
    let id: String
    let type: String
    let imdb: String?
    var width: CGFloat
    @State private var scores: Ratings.Scores?

    private static let gold = Color(red: 0xF3 / 255, green: 0xC9 / 255, blue: 0x69 / 255)

    var body: some View {
        Group {
            if let label {
                label
                    .font(Look.bold(width >= 130 ? 13 : 12))
                    .foregroundStyle(.white)
                    .lineLimit(1).minimumScaleFactor(0.6)
                    .padding(.horizontal, 4)
                    .frame(width: width, height: 24)
                    .background(LinearGradient(colors: [.black.opacity(0.8), .black.opacity(0.95)], startPoint: .top, endPoint: .bottom))
            }
        }
        .task(id: id) {
            guard Ratings.enabled else { return }
            scores = Ratings.cached(id)
            if scores == nil { scores = await Ratings.fetch(id, type: type) }
        }
    }

    private var label: Text? {
        let i = imdb ?? scores?.imdb
        let t = Ratings.tomato(scores)
        guard Ratings.enabled, i != nil || t != nil else { return nil }
        var out = Text("")
        if let i { out = out + Text("IMDb").foregroundColor(Self.gold) + Text(" \(i)") }
        if let t { out = out + Text(i == nil ? t : "   \(t)") }
        return out
    }
}

/// "IMDb 8.1 · 🍅 94%" in a line of text, filled when MDBList answers (the phone's framed poster).
struct ScoreText: View {
    let id: String
    let type: String
    let imdb: String?
    @State private var scores: Ratings.Scores?

    var body: some View {
        Group {
            if let line = Ratings.line(imdb: imdb, scores) { Text(line).lineLimit(1) }
        }
        .task(id: id) {
            scores = Ratings.cached(id)
            if scores == nil { scores = await Ratings.fetch(id, type: type) }
        }
    }
}

/// A row of posters under a name, swiped sideways.
struct PosterRow: View {
    let title: String
    let cards: [Card]
    var width: CGFloat = 112
    var showNames = false
    var more: Route? = nil
    var menu: ((Card) -> AnyView)? = nil
    @Environment(\.theme) private var theme

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text(title).font(Look.title(18)).foregroundStyle(theme.textColor).lineLimit(1)
                Spacer()
                if let more {
                    NavigationLink(value: more) {
                        Image(systemName: "chevron.right").font(.system(size: 15, weight: .semibold)).foregroundStyle(theme.text2)
                            .frame(width: 44, height: 32)
                    }
                }
            }
            .padding(.horizontal, 20)
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(alignment: .top, spacing: 10) {
                    ForEach(cards) { c in
                        NavigationLink(value: Route.title(c.ref)) {
                            PosterCard(card: c, width: width, showName: showNames)
                        }
                        .buttonStyle(.plain)
                        .hoverEffect(.lift)
                        .contextMenu { if let menu { menu(c) } }
                    }
                }
                .padding(.horizontal, 20)
            }
            // The posters past the screen's edge, ready before a swipe reaches them.
            .task(id: cards.first?.id) { ImageStore.shared.prefetch(cards.prefix(16).compactMap(\.poster), width: width) }
        }
    }
}

/// A short, centred message with an optional action.
struct Notice: View {
    let text: String
    var action: (String, () -> Void)? = nil
    @Environment(\.theme) private var theme

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text(text).font(Look.body(15)).foregroundStyle(theme.text2)
            if let action {
                Button(action.0, action: action.1).buttonStyle(GlassButton())
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(20)
    }
}

/// The spinner: an accent ring that turns.
struct Spinner: View {
    var size: CGFloat = 36
    @Environment(\.theme) private var theme
    @State private var turn = false
    var body: some View {
        Circle().trim(from: 0, to: 0.72)
            .stroke(theme.accentColor, style: StrokeStyle(lineWidth: 3, lineCap: .round))
            .frame(width: size, height: size)
            .rotationEffect(.degrees(turn ? 360 : 0))
            .animation(.linear(duration: 0.9).repeatForever(autoreverses: false), value: turn)
            .onAppear { turn = true }
    }
}
