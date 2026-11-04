import SwiftUI

/// Home B: one big poster in a fixed frame in the middle; the posters either side slide through the frame under the
/// finger and stop with one in it. The framed title's name, facts and Resume (or Play) sit under it.
struct Carousel: View {
    let title: String
    let cards: [Card]
    @Binding var wash: Color
    var menu: ((Card) -> AnyView)? = nil
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var theme
    @Environment(\.play) private var play
    @Environment(\.navigate) private var navigate
    @Environment(\.horizontalSizeClass) private var size
    @State private var current: String?
    @State private var width: CGFloat = UIScreen.main.bounds.width

    // On iPad the poster is kept to about a third of the screen's height, so the rows below show too (landscape most of all).
    private var cardW: CGFloat {
        // What's left of the screen's height after the top bar, the name/Resume block, one full row of posters and
        // the tab bar, so the first row below always shows whole.
        if size == .regular { return max(120, min(width * 0.3, 260, (UIScreen.main.bounds.height - 640) / 1.5)) }
        return min(width * 0.62, 330)
    }
    private var framed: Card? { cards.first { $0.id == current } ?? cards.first }

    var body: some View {
        VStack(spacing: 14) {
            Text(title.uppercased()).font(Look.bold(12)).tracking(1.6).foregroundStyle(theme.text2)
                .frame(maxWidth: .infinity)
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(spacing: 16) {
                    ForEach(cards) { c in
                        NavigationLink(value: Route.title(c.ref)) {
                            RemoteImage(url: c.poster, width: cardW)
                                .frame(width: cardW, height: cardW * 1.5)
                                .clipShape(Soft(radius: 8))
                                .overlay {
                                    if c.poster == nil {
                                        Text(c.name).font(Look.title(20)).multilineTextAlignment(.center).padding()
                                            .foregroundStyle(theme.text2)
                                    }
                                }
                                .shadow(color: .black.opacity(0.5), radius: 18, y: 10)
                        }
                        .buttonStyle(.plain)
                        .contextMenu { if let menu { menu(c) } }
                        .scrollTransition(.interactive, axis: .horizontal) { content, phase in
                            content.scaleEffect(phase.isIdentity ? 1 : 0.84).opacity(phase.isIdentity ? 1 : 0.5)
                        }
                        .id(c.id)
                    }
                }
                .scrollTargetLayout()
            }
            .scrollTargetBehavior(.viewAligned)
            .scrollPosition(id: $current)
            .contentMargins(.horizontal, max(0, (width - cardW) / 2), for: .scrollContent)
            .frame(height: cardW * 1.5 + 8)
            .overlay {
                // The frame stays put; the posters move through it.
                Soft(radius: 10).stroke(theme.accentColor.opacity(0.9), lineWidth: 2)
                    .frame(width: cardW + 10, height: cardW * 1.5 + 10)
                    .shadow(color: theme.accentColor.opacity(0.45), radius: 12)
                    .allowsHitTesting(false)
            }
            if let c = framed { below(c) }
        }
        .background(GeometryReader { g in
            Color.clear.onAppear { width = g.size.width }.onChange(of: g.size.width) { _, w in width = w }
        })
        .onChange(of: current) { _, _ in tint() }
        .onAppear { if current == nil { current = cards.first?.id }; tint() }
        .onChange(of: cards.first?.id) { _, first in if !cards.contains(where: { $0.id == current }) { current = first } }
    }

    private func below(_ c: Card) -> some View {
        VStack(spacing: size == .regular ? 7 : 10) {
            Text(c.name).font(Look.title(size == .regular ? 19 : 22)).foregroundStyle(theme.textColor).lineLimit(2).multilineTextAlignment(.center)
            if let left = c.left {
                Text(left).font(Look.bold(14)).foregroundStyle(theme.accentColor)
            } else if !c.facts.isEmpty {
                Text(c.facts).font(Look.body(14)).foregroundStyle(theme.text2)
            }
            if let p = c.progress, p > 0 {
                ProgressLine(value: p).frame(width: min(cardW, 260))
            }
            Button {
                start(c)
            } label: {
                Label(c.progress != nil ? "Resume" : "Play", systemImage: "play.fill")
            }
            .buttonStyle(PrimaryButton())
            .frame(width: min(cardW + 10, 300))
        }
        .padding(.horizontal, 20)
        .animation(.easeOut(duration: 0.2), value: c.id)
    }

    private func start(_ c: Card) {
        if c.type == "movie" {
            play(PlayRequest(title: c.ref, videoId: c.id))
        } else if let v = c.videoId {
            play(PlayRequest(title: c.ref, videoId: v, episode: Card.episodeLabel(v)))
        } else {
            navigate(.title(c.ref))
        }
    }

    private func tint() {
        guard let c = framed, let url = c.poster else { return }
        Task {
            if let img = await ImageStore.shared.image(url, width: cardW), let color = Art.tint(img) { wash = color }
        }
    }
}

/// A thin progress bar in the accent.
struct ProgressLine: View {
    let value: Double
    @Environment(\.theme) private var theme
    var body: some View {
        GeometryReader { g in
            ZStack(alignment: .leading) {
                Capsule().fill(theme.textColor.opacity(0.18))
                Capsule().fill(theme.accentColor).frame(width: g.size.width * max(0, min(1, value)))
            }
        }
        .frame(height: 3)
    }
}
