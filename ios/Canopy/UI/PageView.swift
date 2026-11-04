import SwiftUI

private struct OffsetKey: PreferenceKey {
    static var defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) { value = nextValue() }
}

/// One tab's page: the slide-through carousel, then rows of posters (My List: a grid). Draws from last time at once and
/// refreshes behind it, as the TV and the Android phone do.
struct PageView: View {
    let tab: Tab
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var theme
    @Environment(\.horizontalSizeClass) private var size
    @State private var rows: [String: [Card]] = [:]
    @State private var asked = Set<String>()
    @State private var genre: String?
    @State private var scrolled: CGFloat = 0
    @State private var wash: Color = .clear

    private var wide: Bool { size == .regular }
    private var posterW: CGFloat { wide ? 150 : 112 }

    private var catalogs: [Addon.Catalog] {
        let all = model.catalogs()
        switch tab {
        case .home:
            let chosen = HomeRows.arranged(all).filter(\.1).map(\.0)
            return chosen.isEmpty ? Array(all.filter { ["movie", "series"].contains($0.type) }.prefix(6)) : chosen
        case .movies, .shows:
            let ofType = all.filter { $0.type == tab.type }
            guard let genre else { return ofType }
            return ofType.filter { $0.genres.contains(genre) }
        case .list: return []
        }
    }

    private var genres: [String] {
        var seen = Set<String>()
        return model.catalogs(of: tab.type).flatMap(\.genres).filter { seen.insert($0).inserted }
    }

    private func key(_ c: Addon.Catalog) -> String { c.id + "|" + (genre ?? "") }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 22) {
                content
            }
            .background(alignment: .top) {
                GeometryReader { g in
                    Color.clear.preference(key: OffsetKey.self, value: -g.frame(in: .named("page")).minY)
                }
                .frame(height: 0)
            }
            .padding(.top, 50)
            .padding(.bottom, 110)
        }
        .coordinateSpace(name: "page")
        .onPreferenceChange(OffsetKey.self) { y in if #unavailable(iOS 18.0) { scrolled = y + 50 } }
        .modifier(ScrollOffset(offset: $scrolled))
        .refreshable { await model.refresh(); rows = [:]; asked = []; load() }
        .background(alignment: .top) {
            LinearGradient(colors: [wash.opacity(0.55), wash.opacity(0.2), theme.baseColor.opacity(0)], startPoint: .top, endPoint: .bottom)
                .frame(height: 620)
                .offset(y: -min(scrolled, 620))
                .ignoresSafeArea()
                .animation(.easeOut(duration: 0.4), value: wash)
        }
        .overlay(alignment: .top) { TopBar(solid: scrolled > 30) }
        .onAppear(perform: load)
        .onChange(of: model.addons.count) { load() }
        .onChange(of: genre) { load() }
    }

    @ViewBuilder private var content: some View {
        if let problem = model.problem, model.addons.isEmpty {
            Notice(text: problem, action: ("Try again", { Task { await model.refresh() } }))
        }
        switch tab {
        case .list: listPage
        case .home: homePage
        case .movies, .shows: typePage
        }
    }

    // MARK: Pages

    @ViewBuilder private var homePage: some View {
        let cw = model.continueWatching.map(Card.init)
        let first = catalogs.first
        if !cw.isEmpty {
            Carousel(title: "Continue watching", cards: cw, wash: $wash, menu: continueMenu)
        } else if let first, let cards = rows[key(first)], !cards.isEmpty {
            Carousel(title: rowName(first), cards: cards, wash: $wash)
        } else if model.addons.isEmpty && model.problem == nil {
            Spinner().frame(maxWidth: .infinity).padding(.top, 120)
        }
        ForEach(Array(catalogs.enumerated()), id: \.element.id) { i, c in
            if (i > 0 || !cw.isEmpty), let cards = rows[key(c)], !cards.isEmpty {
                PosterRow(title: rowName(c), cards: cards, width: posterW, more: .catalog(key: c.id, name: rowName(c), genre: nil))
            }
        }
        if catalogs.isEmpty && !model.addons.isEmpty {
            Notice(text: "Choose rows for Home in Settings, under Home.")
        }
    }

    @ViewBuilder private var typePage: some View {
        genrePicker
        let list = catalogs
        if let first = list.first, let cards = rows[key(first)], !cards.isEmpty {
            Carousel(title: genre ?? rowName(first), cards: cards, wash: $wash)
        } else if list.isEmpty && !model.addons.isEmpty {
            Notice(text: genre == nil ? "None of your add-ons has \(tab.rawValue.lowercased()). Add one in Stremio." : "No catalog has \(genre!).")
        } else {
            Spinner().frame(maxWidth: .infinity).padding(.top, 120)
        }
        ForEach(Array(list.enumerated()), id: \.element.id) { i, c in
            if i > 0, let cards = rows[key(c)], !cards.isEmpty {
                PosterRow(title: rowName(c), cards: cards, width: posterW, more: .catalog(key: c.id, name: rowName(c), genre: genre))
            }
        }
    }

    private var genrePicker: some View {
        Menu {
            Button("All genres") { genre = nil }
            ForEach(genres, id: \.self) { g in Button(g) { genre = g } }
        } label: {
            HStack(spacing: 6) {
                Text(genre ?? "All genres").font(Look.bold(15))
                Image(systemName: "chevron.down").font(.system(size: 12, weight: .bold))
            }
            .foregroundStyle(genre == nil ? theme.textColor : theme.accentColor)
            .padding(.horizontal, 14).frame(height: 38)
            .glassCard(theme)
        }
        .padding(.horizontal, 20)
        .opacity(genres.isEmpty ? 0 : 1)
    }

    @ViewBuilder private var listPage: some View {
        let items = model.myList.map(Card.init)
        Text("My List").font(Look.title(28)).foregroundStyle(theme.textColor).padding(.horizontal, 20)
        if items.isEmpty {
            if model.libraryLoaded {
                Notice(text: "Titles you add to My List show here, on every device signed in to your Stremio account.")
            } else {
                Spinner().frame(maxWidth: .infinity).padding(.top, 80)
            }
        } else {
            LazyVGrid(columns: [GridItem(.adaptive(minimum: wide ? 140 : 100), spacing: 12)], spacing: 18) {
                ForEach(items) { c in
                    NavigationLink(value: Route.title(c.ref)) {
                        GeometryReader { g in PosterCard(card: c, width: g.size.width, showName: true) }
                            .aspectRatio(0.56, contentMode: .fit)
                    }
                    .buttonStyle(.plain)
                    .contextMenu {
                        Button(role: .destructive) { model.toggleList(id: c.id, type: c.type, name: c.name, poster: c.poster) } label: {
                            Label("Remove from My List", systemImage: "bookmark.slash")
                        }
                    }
                }
            }
            .padding(.horizontal, 20)
        }
    }

    private func continueMenu(_ c: Card) -> AnyView {
        AnyView(Group {
            let pinned = HomeRows.tonight.contains(c.id)
            Button { HomeRows.pin(c.id, !pinned); model.libraryVersion += 1 } label: {
                Label(pinned ? "Unpin from the front" : "Pin to the front", systemImage: pinned ? "pin.slash" : "pin")
            }
            Button(role: .destructive) { model.hideFromContinueWatching(c.id) } label: {
                Label("Remove from Continue Watching", systemImage: "eye.slash")
            }
        })
    }

    // MARK: Loading

    private func load() {
        for c in catalogs.prefix(14) {
            let k = key(c)
            guard !asked.contains(k) else { continue }
            asked.insert(k)
            let genre = self.genre
            if rows[k] == nil, let saved = c.addon.savedCatalog(c, genre: genre), !saved.isEmpty { rows[k] = cards(saved) }
            Task {
                if let fresh = try? await c.addon.catalog(c, genre: genre) {
                    let new = cards(fresh)
                    if new.map(\.id) != rows[k]?.map(\.id) { rows[k] = new }
                }
            }
        }
    }

    private func cards(_ metas: [Meta]) -> [Card] {
        var seen = Set<String>()
        return metas.filter { !isAddonMessage($0.name) && seen.insert($0.id).inserted }.prefix(40).map(Card.init)
    }

    /// A catalog's name on its page: on Home, where films and shows share a page, it says which it holds.
    private func rowName(_ c: Addon.Catalog) -> String {
        var name = c.name.replacingOccurrences(of: "TV Shows: ", with: "").replacingOccurrences(of: "MyTrakt - ", with: "")
        name = name.split(separator: " ").filter { $0.count < 16 }.joined(separator: " ")
        if tab != .home || Rx.has(#"(?i)\b(movies?|films?|shows?|series|tv)\b"#, name) { return name }
        return c.type == "movie" ? "\(name) · Movies" : c.type == "series" ? "\(name) · Shows" : name
    }
}

/// Every title in one catalog, in a grid that loads more as it scrolls.
struct CatalogView: View {
    let key: String
    let name: String
    let genre: String?
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var theme
    @Environment(\.horizontalSizeClass) private var size
    @State private var cards: [Card] = []
    @State private var loading = false
    @State private var end = false

    private var catalog: Addon.Catalog? { model.catalogs().first { $0.id == key } }

    var body: some View {
        ScrollView {
            LazyVGrid(columns: [GridItem(.adaptive(minimum: size == .regular ? 140 : 100), spacing: 12)], spacing: 18) {
                ForEach(cards) { c in
                    NavigationLink(value: Route.title(c.ref)) {
                        GeometryReader { g in PosterCard(card: c, width: g.size.width, showName: true) }
                            .aspectRatio(0.56, contentMode: .fit)
                    }
                    .buttonStyle(.plain)
                    .onAppear { if c.id == cards.last?.id { more() } }
                }
            }
            .padding(20)
            if loading { Spinner().padding() }
        }
        .background(theme.baseColor)
        .navigationTitle(genre.map { "\(name) · \($0)" } ?? name)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar(.visible, for: .navigationBar)
        .onAppear { if cards.isEmpty { more() } }
    }

    private func more() {
        guard !loading, !end, let c = catalog else { return }
        if !cards.isEmpty && !c.pages { end = true; return }
        loading = true
        Task {
            let metas = (try? await c.addon.catalog(c, genre: genre, skip: cards.count)) ?? []
            var seen = Set(cards.map(\.id))
            let new = metas.filter { !isAddonMessage($0.name) && seen.insert($0.id).inserted }.map(Card.init)
            if new.isEmpty { end = true }
            cards += new
            loading = false
        }
    }
}

/// On iOS 18 and later the scroll view reports its own offset, which is reliable where the GeometryReader trick
/// (kept for iOS 17) can stop updating, leaving the top bar see-through over the page.
private struct ScrollOffset: ViewModifier {
    @Binding var offset: CGFloat
    func body(content: Content) -> some View {
        if #available(iOS 18.0, *) {
            content.onScrollGeometryChange(for: CGFloat.self) { $0.contentOffset.y + $0.contentInsets.top } action: { _, y in offset = y }
        } else {
            content
        }
    }
}
