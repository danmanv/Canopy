import SwiftUI
import Observation

/// What the whole app shares: the profiles, the one in use, its add-ons and library, and the colour scheme.
@MainActor
@Observable
final class AppModel {
    var profiles: [Profile] = Profiles.all()
    var active: Profile?
    var unlocked = false
    var theme: Theme = Theme.named(Settings.theme)

    var addons: [Addon] = []
    var library: [LibraryItem] = []
    var libraryLoaded = false
    var problem: String?
    /// Bumped when the library changes here, so pages redraw Continue Watching and My List.
    var libraryVersion = 0

    init() {
        Http.trim()
        if let id = Profiles.activeId, let p = profiles.first(where: { $0.id == id }), p.pin == nil, !Profiles.signedOut(p.id) {
            choose(p)
        } else if profiles.count == 1, let p = profiles.first, p.pin == nil, !Profiles.signedOut(p.id) {
            choose(p)
        }
    }

    var authKey: String? { active?.authKey }

    func setTheme(_ t: Theme) {
        Settings.theme = t.id
        theme = t
    }

    // MARK: Profiles

    func choose(_ p: Profile) {
        active = p
        Profiles.activeId = p.id
        Settings.profileId = p.id
        unlocked = true
        addons = Stremio.savedAddons(p.authKey) ?? []
        library = Stremio.savedLibrary(p.authKey) ?? []
        libraryLoaded = !library.isEmpty
        libraryVersion += 1
        Task { await refresh() }
    }

    func leaveProfile() {
        active = nil
        unlocked = false
        addons = []
        library = []
        libraryLoaded = false
    }

    func addProfile(name: String, authKey: String, color: UInt32) {
        let p = Profiles.add(name: name, authKey: authKey, color: color)
        profiles = Profiles.all()
        choose(p)
    }

    func update(_ p: Profile) {
        Profiles.update(p)
        profiles = Profiles.all()
        if active?.id == p.id { active = p }
    }

    func signedInAgain(_ p: Profile, authKey: String) {
        var q = p
        q.authKey = authKey
        Profiles.markSignedOut(p.id, false)
        update(q)
        choose(q)
    }

    func remove(_ p: Profile) async {
        await Stremio.logout(p.authKey)
        Profiles.remove(p.id)
        Http.forget()
        profiles = Profiles.all()
        if active?.id == p.id { leaveProfile() }
    }

    // MARK: Data

    func refresh() async {
        guard let p = active else { return }
        async let a = Stremio.addons(p.authKey, refresh: true)
        async let l = Stremio.library(p.authKey)
        do {
            let (addons, library) = try await (a, l)
            guard active?.id == p.id else { return }
            self.addons = addons
            self.library = library
            libraryLoaded = true
            libraryVersion += 1
            problem = nil
        } catch let e as Stremio.ApiError where e.code == Stremio.noSession {
            Profiles.markSignedOut(p.id)
            profiles = Profiles.all()
            leaveProfile()
        } catch {
            if addons.isEmpty { problem = "Couldn't reach Stremio. Check the connection." }
        }
    }

    func refreshLibrary() async {
        guard let p = active, let l = try? await Stremio.library(p.authKey), active?.id == p.id else { return }
        library = l
        libraryLoaded = true
        libraryVersion += 1
    }

    func item(_ id: String) -> LibraryItem? { library.first { $0.id == id } }

    var continueWatching: [LibraryItem] {
        let cw = Library.continueWatching(library)
        let pins = HomeRows.tonight
        let pinned = pins.compactMap { id in cw.first { $0.id == id } }
        return pinned + cw.filter { !pins.contains($0.id) }
    }

    var myList: [LibraryItem] { Library.myList(library) }

    /// Saves a changed library item here at once and to Stremio behind it.
    func save(_ item: LibraryItem, upload: Bool = true) {
        if let i = library.firstIndex(where: { $0.id == item.id }) { library[i] = item.copy() } else { library.append(item.copy()) }
        libraryVersion += 1
        guard upload, let key = authKey else { return }
        let item = item.copy()
        Task { try? await Stremio.saveLibrary(key, [item]) }
    }

    func toggleList(id: String, type: String, name: String, poster: String?) {
        let item = self.item(id)?.copy() ?? Library.newItem(id: id, type: type, name: name, poster: poster)
        Library.setInList(item, !Library.inList(item))
        save(item)
    }

    func hideFromContinueWatching(_ id: String) {
        guard let item = self.item(id)?.copy() else { return }
        Library.hideFromContinueWatching(item)
        HomeRows.pin(id, false)
        save(item)
    }

    func catalogs(of type: String? = nil) -> [Addon.Catalog] {
        addons.flatMap(\.catalogs).filter { type == nil || $0.type == type }
    }
}
