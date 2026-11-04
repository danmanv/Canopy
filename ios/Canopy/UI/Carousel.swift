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
    /// True while a finger (or the snap after it) is moving the posters: the glass strip fades out meanwhile.
    @State private var moving = false

    // On iPad the poster is kept to about a third of the screen's height, so the rows below show too (landscape most of all).
    private var cardW: CGFloat {
        // What's left of the screen's height after the top bar, the name/Resume block, one full row of posters and
        // the tab bar, so the first row below always shows whole.
        if size == .regular { return max(120, min(width * 0.34, 320, (UIScreen.main.bounds.height - 510) / 1.5)) }
        return min(width * 0.62, 330)
    }
    private var framed: Card? { cards.first { $0.id == current } ?? cards.first }

    var body: some View {
        VStack(spacing: size == .regular ? 10 : 14) {
            Text(title.uppercased()).font(Look.bold(12)).tracking(1.6).foregroundStyle(theme.text2)
                .frame(maxWidth: .infinity)
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(spacing: 16) {
                    ForEach(cards) { c in
                        NavigationLink(value: Route.title(c.ref)) {
                            RemoteImage(url: c.poster, width: cardW)
                                .frame(width: cardW, height: cardW * 1.5)
                                .clipShape(Soft(radius: 8))
                                .overlay(alignment: .bottom) {
                                    if let p = c.progress, p > 0 {
                                        // Along the poster's very bottom edge.
                                        GeometryReader { g in
                                            Rectangle().fill(theme.accentColor).frame(width: g.size.width * max(0, min(1, p)), height: 3)
                                                .frame(maxHeight: .infinity, alignment: .bottom)
                                        }
                                    }
                                }
                                .overlay {
                                    if c.poster == nil {
                                        Text(c.name).font(Look.title(20)).multilineTextAlignment(.center).padding()
                                            .foregroundStyle(theme.text2)
                                    }
                                }
                                .shadow(color: .black.opacity(0.5), radius: 18, y: 10)
                        }
                        .buttonStyle(.plain)
                        .hoverEffect(.lift)
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
            .modifier(ScrollMoving(moving: $moving))
            .contentMargins(.horizontal, max(0, (width - cardW) / 2), for: .scrollContent)
            .frame(height: cardW * 1.5 + 8)
            .overlay {
                // The frame stays put; the posters move through it.
                Soft(radius: 10).stroke(theme.accentColor.opacity(0.9), lineWidth: 2)
                    .frame(width: cardW + 10, height: cardW * 1.5 + 10)
                    .shadow(color: theme.accentColor.opacity(0.45), radius: 12)
                    .allowsHitTesting(false)
            }
            .overlay {
                // iPad: a frosted strip across the framed poster's foot is the Resume button; it stays in the frame
                // and changes with whichever poster is in it.
                if let c = framed {
                    glassStrip(c)
                        .frame(width: cardW, height: cardW * 1.5, alignment: .bottom)
                        .clipShape(Soft(radius: 8))
                        .opacity(moving ? 0 : 1)
                        .animation(.easeOut(duration: moving ? 0.12 : 0.3), value: moving)
                }
            }
            if size != .regular, let c = framed { nameLine(c) }
        }
        .background(GeometryReader { g in
            Color.clear.onAppear { width = g.size.width }.onChange(of: g.size.width) { _, w in width = w }
        })
        .onChange(of: current) { _, _ in tint(); preload() }
        .onAppear { if current == nil { current = cards.first?.id }; tint(); preload() }
        .onChange(of: cards.first?.id) { _, first in if !cards.contains(where: { $0.id == current }) { current = first } }
    }

    /// Phone: just the framed title's name under it; Resume and the time left are on the glass strip.
    private func nameLine(_ c: Card) -> some View {
        Text(c.name).font(Look.title(20)).foregroundStyle(theme.textColor).lineLimit(1)
            .padding(.horizontal, 20)
            .animation(.easeOut(duration: 0.2), value: c.id)
    }

    /// Design 1, "Glass footer": play circle, "Resume" over the time left, on frosted glass with a hairline top.
    private func glassStrip(_ c: Card) -> some View {
        Button {
            start(c)
        } label: {
            HStack(spacing: 12) {
                Circle().fill(theme.accentColor).frame(width: 38, height: 38)
                    .overlay(Image(systemName: "play.fill").font(.system(size: 15, weight: .bold)).foregroundStyle(.black).offset(x: 1))
                VStack(alignment: .leading, spacing: 2) {
                    Text(c.progress != nil ? "Resume" : "Play").font(Look.bold(15)).foregroundStyle(.white)
                    if let left = c.left {
                        Text(left).font(Look.body(12)).foregroundStyle(.white.opacity(0.85)).lineLimit(1)
                    }
                }
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 14)
            .frame(maxWidth: .infinity)
            .frame(height: 66)
            .background {
                // Frosted glass that picks up the framed poster's own colour.
                ZStack {
                    Rectangle().fill(.ultraThinMaterial)
                    wash.opacity(0.32)
                }
            }
            .overlay(alignment: .top) { Rectangle().fill(.white.opacity(0.18)).frame(height: 1) }
            .padding(.bottom, c.progress != nil ? 3 : 0)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
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

    /// The posters either side of the framed one, decoded before a swipe brings them in.
    private func preload() {
        guard let i = cards.firstIndex(where: { $0.id == framed?.id }) else { return }
        let near = cards[max(0, i - 2)...min(cards.count - 1, i + 3)].compactMap(\.poster)
        ImageStore.shared.prefetch(near, width: cardW)
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

/// iOS 18+: whether the carousel is moving (dragged, or gliding to its stop). Earlier systems leave the strip shown.
private struct ScrollMoving: ViewModifier {
    @Binding var moving: Bool
    func body(content: Content) -> some View {
        if #available(iOS 18.0, *) {
            content.onScrollPhaseChange { _, phase in
                let now = phase.isScrolling
                if now != moving { moving = now }
            }
        } else {
            content
        }
    }
}
