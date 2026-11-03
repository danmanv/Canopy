import Foundation

/// Stremio answers kept as raw JSON, as the Android app does with JSONObject, so fields Canopy doesn't use survive a
/// round trip back to Stremio (library items above all).
typealias JSON = [String: Any]

extension Dictionary where Key == String, Value == Any {
    /// The string at [key], or nil when missing, JSON null or blank. Numbers read as their text ("2026", "7.8").
    func text(_ key: String) -> String? {
        switch self[key] {
        case let s as String: return s.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : s
        case let n as NSNumber: return n.stringValue
        default: return nil
        }
    }

    func string(_ key: String) -> String { text(key) ?? "" }

    func obj(_ key: String) -> JSON? { self[key] as? JSON }

    func objects(_ key: String) -> [JSON] { (self[key] as? [Any])?.compactMap { $0 as? JSON } ?? [] }

    func strings(_ key: String) -> [String] { (self[key] as? [Any])?.compactMap { $0 as? String } ?? [] }

    func items(_ key: String) -> [Any] { self[key] as? [Any] ?? [] }

    func int(_ key: String) -> Int {
        if let n = self[key] as? NSNumber { return n.intValue }
        if let s = self[key] as? String { return Int(s) ?? 0 }
        return 0
    }

    func int64(_ key: String) -> Int64 {
        if let n = self[key] as? NSNumber { return n.int64Value }
        if let s = self[key] as? String { return Int64(s) ?? 0 }
        return 0
    }

    func bool(_ key: String) -> Bool {
        if let n = self[key] as? NSNumber { return n.boolValue }
        if let s = self[key] as? String { return s == "true" }
        return false
    }

    func isNull(_ key: String) -> Bool { self[key] == nil || self[key] is NSNull }
}

enum JSONText {
    static func parse(_ text: String) throws -> JSON {
        guard let data = text.data(using: .utf8),
              let json = try JSONSerialization.jsonObject(with: data) as? JSON else { throw CanopyError.message("Not JSON") }
        return json
    }

    static func string(_ value: Any) -> String {
        guard JSONSerialization.isValidJSONObject(value),
              let data = try? JSONSerialization.data(withJSONObject: value, options: [.withoutEscapingSlashes]) else { return "{}" }
        return String(decoding: data, as: UTF8.self)
    }
}

enum CanopyError: LocalizedError {
    case message(String)
    case http(Int)

    var errorDescription: String? {
        switch self {
        case .message(let m): return m
        case .http(let code): return "HTTP \(code)"
        }
    }
}

/// Regular expressions as the Android app writes them, compiled once.
enum Rx {
    private static var cache = [String: NSRegularExpression]()
    private static let lock = NSLock()

    static func re(_ pattern: String, _ options: NSRegularExpression.Options = []) -> NSRegularExpression {
        lock.lock(); defer { lock.unlock() }
        let key = "\(options.rawValue)/\(pattern)"
        if let r = cache[key] { return r }
        let r = try! NSRegularExpression(pattern: pattern, options: options)
        cache[key] = r
        return r
    }

    static func has(_ pattern: String, _ text: String, _ options: NSRegularExpression.Options = []) -> Bool {
        re(pattern, options).firstMatch(in: text, range: NSRange(text.startIndex..., in: text)) != nil
    }

    /// The capture groups of the first match (0 is the whole match), or nil.
    static func groups(_ pattern: String, _ text: String, _ options: NSRegularExpression.Options = []) -> [String]? {
        guard let m = re(pattern, options).firstMatch(in: text, range: NSRange(text.startIndex..., in: text)) else { return nil }
        return (0..<m.numberOfRanges).map { i in
            Range(m.range(at: i), in: text).map { String(text[$0]) } ?? ""
        }
    }

    static func replace(_ pattern: String, in text: String, with template: String, _ options: NSRegularExpression.Options = []) -> String {
        re(pattern, options).stringByReplacingMatches(in: text, range: NSRange(text.startIndex..., in: text), withTemplate: template)
    }

    static func all(_ pattern: String, _ text: String) -> [String] {
        re(pattern).matches(in: text, range: NSRange(text.startIndex..., in: text)).compactMap { Range($0.range, in: text).map { String(text[$0]) } }
    }
}
