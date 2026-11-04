import Foundation

/// Thin client for Stremio's own services (request shapes from stremio-core), as the Android app's Stremio.kt.
enum Stremio {
    private static let api = "https://api.strem.io/api/"
    private static let link = "https://link.stremio.com/api/v2/"

    struct Link { let code: String; let url: String }

    struct ApiError: LocalizedError {
        let code: Int
        let message: String
        var errorDescription: String? { message.isEmpty ? "Stremio error \(code)" : message }
    }

    /// Error code for a session that no longer exists (signed out elsewhere).
    static let noSession = 1

    private static let store = Store()

    /// Memory of add-on lists (per auth key) and library items saved here that Stremio may not have yet.
    private final class Store: @unchecked Sendable {
        private let lock = NSLock()
        private var addons = [String: [Addon]]()
        private var saving = [String: JSON]()

        func addons(_ key: String) -> [Addon]? { lock.lock(); defer { lock.unlock() }; return addons[key] }
        func setAddons(_ key: String, _ list: [Addon]?) { lock.lock(); addons[key] = list; lock.unlock() }
        func putIfAbsent(_ key: String, _ list: [Addon]) { lock.lock(); if addons[key] == nil { addons[key] = list }; lock.unlock() }
        func saving(_ key: String) -> JSON? { lock.lock(); defer { lock.unlock() }; return saving[key] }
        func setSaving(_ key: String, _ item: JSON?) { lock.lock(); saving[key] = item; lock.unlock() }
    }

    // MARK: Sign-in

    /// Email and password, as Stremio's own apps sign in. Accounts made with Google or Facebook have no password: they
    /// use [createLink] instead.
    static func login(email: String, password: String) async throws -> String {
        let body: JSON = ["type": "Login", "email": email, "password": password, "facebook": false]
        return try result(try await Http.post(api + "login", json: JSONText.string(body))).string("authKey")
    }

    /// The code flow: open [Link.url] (signed in to Stremio there), approve it, and [readLink] returns the auth key.
    static func createLink() async throws -> Link {
        let r = try result(try await Http.get(link + "create?type=Create"))
        return Link(code: r.string("code"), url: r.string("link"))
    }

    /// Auth key once the code is approved, nil until then (Stremio answers 101 for both waiting and expired).
    static func readLink(_ code: String) async throws -> String? {
        do {
            return try result(try await Http.get(link + "read?type=Read&code=" + Http.encode(code))).text("authKey")
        } catch let e as ApiError where e.code == 101 {
            return nil
        }
    }

    static func userId(_ authKey: String) async throws -> String {
        try result(try await post("getUser", ["type": "GetUser", "authKey": authKey])).string("_id")
    }

    /// The account's email, shown in Settings.
    static func user(_ authKey: String) async throws -> JSON {
        try result(try await post("getUser", ["type": "GetUser", "authKey": authKey]))
    }

    static func logout(_ authKey: String) async {
        store.setAddons(authKey, nil)
        _ = try? await post("logout", ["type": "Logout", "authKey": authKey])
    }

    // MARK: Add-ons

    static func addons(_ authKey: String, refresh: Bool = false) async throws -> [Addon] {
        if !refresh, let cached = store.addons(authKey) { return cached }
        let body = addonsBody(authKey)
        let answer = try await Http.post(api + "addonCollectionGet", json: body)
        let list = try result(answer).objects("addons").compactMap(Addon.init)
        store.setAddons(authKey, list)
        Http.keep(api + "addonCollectionGet", json: body, body: answer)
        return list
    }

    /// The add-on list as it was last time, from disk (nil if never fetched).
    static func savedAddons(_ authKey: String) -> [Addon]? {
        guard let text = Http.savedAnswer(api + "addonCollectionGet", json: addonsBody(authKey)),
              let list = try? result(text).objects("addons").compactMap(Addon.init) else { return nil }
        store.putIfAbsent(authKey, list)
        return list
    }

    /// Whatever is quickest: memory, then disk, then the network.
    static func anyAddons(_ authKey: String) async throws -> [Addon] {
        if let a = store.addons(authKey) { return a }
        if let a = savedAddons(authKey) { return a }
        return try await addons(authKey)
    }

    private static func addonsBody(_ authKey: String) -> String {
        JSONText.string(["type": "AddonCollectionGet", "authKey": authKey, "update": true] as JSON)
    }

    static func setAddons(_ authKey: String, _ addons: [Addon]) async throws {
        _ = try result(try await post("addonCollectionSet",
            ["type": "AddonCollectionSet", "authKey": authKey, "addons": addons.map(\.descriptor)]))
        store.setAddons(authKey, addons)
    }

    // MARK: Library

    /// Library items (watch history included): the given ids, or every item when [ids] is empty.
    static func library(_ authKey: String, ids: [String] = []) async throws -> [LibraryItem] {
        let body = libraryBody(authKey, ids)
        let answer = try await Http.post(api + "datastoreGet", json: body)
        let items = try libraryItems(answer)
        if ids.isEmpty { Http.keep(api + "datastoreGet", json: body, body: answer) }
        return newest(authKey, items)
    }

    static func savedLibrary(_ authKey: String) -> [LibraryItem]? {
        guard let text = Http.savedAnswer(api + "datastoreGet", json: libraryBody(authKey, [])),
              let items = try? libraryItems(text) else { return nil }
        return newest(authKey, items)
    }

    static func saveLibrary(_ authKey: String, _ items: [LibraryItem]) async throws {
        let raws = items.map(\.json)
        raws.forEach { store.setSaving("\(authKey)/\($0.string("_id"))", $0) }
        do {
            _ = try result(try await post("datastorePut", ["authKey": authKey, "collection": "libraryItem", "changes": raws]))
        } catch {
            raws.forEach { store.setSaving("\(authKey)/\($0.string("_id"))", nil) }
            throw error
        }
    }

    // Items saved here whose save may not have reached Stremio yet: the player saves as it closes while the page behind
    // asks Stremio at the same moment.
    private static func newest(_ authKey: String, _ items: [JSON]) -> [LibraryItem] {
        items.map { item in
            if let mine = store.saving("\(authKey)/\(item.string("_id"))"), mine.string("_mtime") > item.string("_mtime") {
                return LibraryItem(mine)
            }
            return LibraryItem(item)
        }
    }

    private static func libraryBody(_ authKey: String, _ ids: [String]) -> String {
        JSONText.string(["authKey": authKey, "collection": "libraryItem", "ids": ids, "all": ids.isEmpty] as JSON)
    }

    private static func libraryItems(_ answer: String) throws -> [JSON] {
        let json = try JSONText.parse(answer)
        if let e = json.obj("error") { throw ApiError(code: e.int("code"), message: e.string("message")) }
        return json.objects("result")
    }

    // Stremio wraps every answer as {"result": …} or {"error": {"code", "message"}}.
    private static func result(_ body: String) throws -> JSON {
        let json = try JSONText.parse(body)
        if let e = json.obj("error") { throw ApiError(code: e.int("code"), message: e.string("message")) }
        return json.obj("result") ?? [:]
    }

    private static func post(_ method: String, _ body: JSON) async throws -> String {
        try await Http.post(api + method, json: JSONText.string(body))
    }
}
