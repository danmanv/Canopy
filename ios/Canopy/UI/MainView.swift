import SwiftUI

enum Tab: String, CaseIterable, Identifiable {
    case home = "Home", movies = "Movies", shows = "Shows", list = "My List"
    var id: String { rawValue }
    var icon: String {
        switch self {
        case .home: return "house"
        case .movies: return "film"
        case .shows: return "tv"
        case .list: return "bookmark"
        }
    }
    var type: String? { self == .movies ? "movie" : self == .shows ? "series" : nil }
}

/// The app once a profile is chosen: four pages on tabs along the foot, a slim bar on top, titles pushed over them,
/// and the player over everything.
struct MainView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var theme
    @State private var tab: Tab = .home
    @State private var path = NavigationPath()
    @State private var player: PlayRequest?

    var body: some View {
        NavigationStack(path: $path) {
            ZStack(alignment: .bottom) {
                ZStack {
                    ForEach(Tab.allCases) { t in
                        PageView(tab: t)
                            .opacity(t == tab ? 1 : 0)
                            .allowsHitTesting(t == tab)
                    }
                }
                TabBar(tab: $tab)
            }
            .ignoresSafeArea(.keyboard)
            .toolbar(.hidden, for: .navigationBar)
            .navigationDestination(for: Route.self) { route in
                switch route {
                case .title(let ref): TitleView(ref: ref)
                case .search: SearchView()
                case .settings: SettingsView()
                case .catalog(let key, let name, let genre): CatalogView(key: key, name: name, genre: genre)
                case .sources(let req): SourcesView(request: req)
                }
            }
        }
        .environment(\.play, PlayAction { player = $0 })
        .environment(\.navigate, NavigateAction { path.append($0) })
        .fullScreenCover(item: $player) { req in
            PlayerView(request: req) { chosen in
                Task { @MainActor in
                    try? await Task.sleep(nanoseconds: 400_000_000)
                    path.append(Route.sources(chosen))
                }
            }
            .environment(model).environment(\.theme, theme)
        }
    }
}

/// Starts the player from anywhere below MainView.
struct PlayAction {
    let run: (PlayRequest) -> Void
    func callAsFunction(_ r: PlayRequest) { run(r) }
}
struct NavigateAction {
    let run: (Route) -> Void
    func callAsFunction(_ r: Route) { run(r) }
}
private struct PlayKey: EnvironmentKey { static let defaultValue = PlayAction { _ in } }
private struct NavigateKey: EnvironmentKey { static let defaultValue = NavigateAction { _ in } }
extension EnvironmentValues {
    var play: PlayAction { get { self[PlayKey.self] } set { self[PlayKey.self] = newValue } }
    var navigate: NavigateAction { get { self[NavigateKey.self] } set { self[NavigateKey.self] = newValue } }
}

/// The tabs: soft, the bar fades up out of the page, the open tab glows in the accent.
struct TabBar: View {
    @Binding var tab: Tab
    @Environment(\.theme) private var theme

    var body: some View {
        HStack(spacing: 0) {
            ForEach(Tab.allCases) { t in
                Button {
                    tab = t
                } label: {
                    VStack(spacing: 4) {
                        Image(systemName: t == tab ? t.icon + ".fill" : t.icon).font(.system(size: 20, weight: .medium))
                        Text(t.rawValue).font(Look.bold(11))
                    }
                    .foregroundStyle(t == tab ? theme.accentColor : theme.text2)
                    .shadow(color: t == tab ? theme.accentColor.opacity(0.6) : .clear, radius: 8)
                    .frame(maxWidth: .infinity, minHeight: 52)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.top, 18)
        .frame(maxWidth: 560)
        .frame(maxWidth: .infinity)
        .background(
            LinearGradient(colors: [theme.baseColor.opacity(0), theme.baseColor.opacity(0.92), theme.baseColor],
                           startPoint: .top, endPoint: .bottom)
                .ignoresSafeArea(edges: .bottom)
        )
    }
}

/// The slim bar on top: the canopy mark and wordmark, then search and the profile.
struct TopBar: View {
    var solid: Bool
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var theme

    var body: some View {
        HStack(spacing: 8) {
            Image("Logo").resizable().frame(width: 28, height: 28)
            Text("canopy").font(Look.wordmark(24)).foregroundStyle(theme.textColor)
            Spacer()
            NavigationLink(value: Route.search) {
                Image(systemName: "magnifyingglass").font(.system(size: 19, weight: .semibold))
                    .foregroundStyle(theme.textColor).frame(width: 44, height: 44)
            }
            NavigationLink(value: Route.settings) {
                Avatar(profile: model.active, size: 30)
                    .frame(width: 44, height: 44)
            }
        }
        .padding(.horizontal, 16)
        .padding(.bottom, 6)
        .background(
            (solid ? theme.baseColor.opacity(0.94) : Color.clear)
                .background(solid ? AnyShapeStyle(.ultraThinMaterial) : AnyShapeStyle(Color.clear))
                .ignoresSafeArea(edges: .top)
                .animation(.easeOut(duration: 0.2), value: solid)
        )
    }
}

/// A profile's square: its colour fading darker, its initial in the middle.
struct Avatar: View {
    let profile: Profile?
    var size: CGFloat
    var body: some View {
        let c = Color(argb: profile?.color ?? Profiles.colors[0])
        RoundedRectangle(cornerRadius: size * 0.2, style: .continuous)
            .fill(LinearGradient(colors: [c, c.opacity(0.75)], startPoint: .topLeading, endPoint: .bottomTrailing))
            .frame(width: size, height: size)
            .overlay(Text(String(profile?.name.prefix(1) ?? "?")).font(Look.heavy(size * 0.48)).foregroundStyle(Color.black.opacity(0.7)))
    }
}
