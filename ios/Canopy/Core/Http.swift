import Foundation
import CryptoKit

/// Plain HTTP for Stremio's API, add-ons and images. Answers can be kept on disk (title details, catalogs, the library,
/// the add-on list), so a screen draws from last time at once and refreshes behind it.
enum Http {
    static var userAgent: String = {
        let v = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0"
        return "Canopy/\(v) (iOS)"
    }()

    static let session: URLSession = {
        let c = URLSessionConfiguration.default
        c.requestCachePolicy = .reloadIgnoringLocalCacheData
        c.httpMaximumConnectionsPerHost = 8
        c.timeoutIntervalForRequest = 20
        return URLSession(configuration: c)
    }()

    static let saved: URL = {
        let dir = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("answers")
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }()

    static func get(_ url: String, keep: Bool = false, timeout: TimeInterval = 20) async throws -> String {
        try await text(url, json: nil, keep: keep, timeout: timeout)
    }

    static func post(_ url: String, json: String, keep: Bool = false) async throws -> String {
        try await text(url, json: json, keep: keep, timeout: 20)
    }

    private static func text(_ url: String, json: String?, keep: Bool, timeout: TimeInterval) async throws -> String {
        guard let u = URL(string: url) else { throw CanopyError.message("Bad address") }
        var r = URLRequest(url: u, timeoutInterval: timeout)
        r.setValue(userAgent, forHTTPHeaderField: "User-Agent")
        if let json {
            r.httpMethod = "POST"
            r.setValue("application/json", forHTTPHeaderField: "Content-Type")
            r.httpBody = Data(json.utf8)
        }
        let (data, response) = try await session.data(for: r)
        let code = (response as? HTTPURLResponse)?.statusCode ?? 200
        let text = String(decoding: data, as: UTF8.self)
        // Stremio's API answers errors as JSON (read by the caller); anything else failed is an error page.
        if code >= 400 && !text.trimmingCharacters(in: .whitespaces).hasPrefix("{") { throw CanopyError.http(code) }
        if keep && code < 400 { self.keep(url, json: json, body: text) }
        return text
    }

    /// The last answer kept for this request, or nil. Never touches the network.
    static func savedAnswer(_ url: String, json: String? = nil) -> String? {
        try? String(contentsOf: file(url, json), encoding: .utf8)
    }

    static func keep(_ url: String, json: String?, body: String) {
        let f = file(url, json)
        let tmp = f.appendingPathExtension("tmp")
        do {
            try body.write(to: tmp, atomically: false, encoding: .utf8)
            _ = try? FileManager.default.removeItem(at: f)
            try FileManager.default.moveItem(at: tmp, to: f)
        } catch {}
    }

    /// Forgets every kept answer (a profile was removed: its answers aren't told apart by name).
    static func forget() {
        let files = (try? FileManager.default.contentsOfDirectory(at: saved, includingPropertiesForKeys: nil)) ?? []
        files.forEach { try? FileManager.default.removeItem(at: $0) }
    }

    /// Oldest first, down to 60 MB.
    static func trim() {
        let keys: [URLResourceKey] = [.contentModificationDateKey, .fileSizeKey]
        let files = (try? FileManager.default.contentsOfDirectory(at: saved, includingPropertiesForKeys: keys)) ?? []
        var entries = files.map { f -> (URL, Date, Int) in
            let v = try? f.resourceValues(forKeys: Set(keys))
            return (f, v?.contentModificationDate ?? .distantPast, v?.fileSize ?? 0)
        }
        entries.sort { $0.1 < $1.1 }
        var total = entries.reduce(0) { $0 + $1.2 }
        for e in entries where total > 60 * 1024 * 1024 {
            total -= e.2
            try? FileManager.default.removeItem(at: e.0)
        }
    }

    // Named by a hash of the request: urls carry add-on settings and the body an auth key, so neither is a file name.
    private static func file(_ url: String, _ json: String?) -> URL {
        let digest = Insecure.SHA1.hash(data: Data((url + "\n" + (json ?? "")).utf8))
        return saved.appendingPathComponent(digest.map { String(format: "%02x", $0) }.joined())
    }

    /// Percent-encodes a path piece as Android's Uri.encode(text, allow) does: letters, digits, `_-!.~'()*` and [allow].
    static func encode(_ text: String, allow: String = "") -> String {
        var set = CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-!.~'()*")
        set.insert(charactersIn: allow)
        return text.addingPercentEncoding(withAllowedCharacters: set) ?? text
    }
}
