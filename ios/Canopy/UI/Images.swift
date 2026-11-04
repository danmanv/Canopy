import SwiftUI
import UIKit
import ImageIO

/// Pictures from add-ons, decoded at the size they're shown and kept in memory (and in URLCache on disk).
private final class Memory: @unchecked Sendable {
    let cache = NSCache<NSString, UIImage>()
}

/// Poster trouble in the Xcode console; silent in Release builds.
@inline(__always) private func posterLog(_ s: @autoclosure () -> String) {
    #if DEBUG
    print(s())
    #endif
}

final class ImageStore: Sendable {
    static let shared = ImageStore()
    private let box = Memory()
    private var memory: NSCache<NSString, UIImage> { box.cache }

    /// Its own session: many connections per host (poster add-ons serve every picture from one host) and a large
    /// disk cache, so pictures seen once come back instantly on the next launch.
    private let session: URLSession = {
        let c = URLSessionConfiguration.default
        c.httpMaximumConnectionsPerHost = 12
        c.timeoutIntervalForRequest = 30
        c.requestCachePolicy = .returnCacheDataElseLoad
        c.urlCache = URLCache(memoryCapacity: 32 << 20, diskCapacity: 400 << 20,
                              directory: FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first?
                                  .appendingPathComponent("images", isDirectory: true))
        return URLSession(configuration: c)
    }()

    init() { box.cache.totalCostLimit = 150 << 20 }

    func cached(_ url: String, width: CGFloat) -> UIImage? {
        memory.object(forKey: key(url, width) as NSString)
    }

    private func key(_ url: String, _ width: CGFloat) -> String { "\(Int(width))|\(url)" }

    /// Loads in the caller's task, so a poster scrolled off screen stops downloading and frees its connection for
    /// the ones in view. Tries twice; decoding happens off the main thread.
    func image(_ url: String, width: CGFloat, scale: CGFloat = 3) async -> UIImage? {
        let k = key(url, width)
        if let img = memory.object(forKey: k as NSString) { return img }
        guard let u = URL(string: url) ?? URL(string: url.addingPercentEncoding(withAllowedCharacters: .urlFragmentAllowed) ?? "") else {
            posterLog("[poster] bad url \(url)")
            return nil
        }
        var r = URLRequest(url: u)
        r.setValue(Http.userAgent, forHTTPHeaderField: "User-Agent")
        for attempt in 0..<2 {
            if Task.isCancelled { return nil }
            do {
                let (data, resp) = try await session.data(for: r)
                let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
                guard code < 400 else {
                    posterLog("[poster] HTTP \(code) \(url)")
                    if attempt == 0 { try? await Task.sleep(nanoseconds: 600_000_000) }
                    continue
                }
                let img = await Task.detached(priority: .userInitiated) { Self.downsample(data, width: width, scale: scale) }.value
                if img == nil {
                    posterLog("[poster] undecodable \(data.count) bytes \((resp as? HTTPURLResponse)?.mimeType ?? "?") \(url) head=\(String(decoding: data.prefix(40), as: UTF8.self))")
                }
                if let img {
                    memory.setObject(img, forKey: k as NSString, cost: Int(img.size.width * img.size.height * img.scale * img.scale * 4))
                }
                return img
            } catch {
                if (error as? URLError)?.code != .cancelled { posterLog("[poster] \(error.localizedDescription) \(url)") }
            }
            if attempt == 0 { try? await Task.sleep(nanoseconds: 600_000_000) }
        }
        return nil
    }

    /// Fetches and decodes pictures before they're shown, one after another at low priority so the ones on screen
    /// keep the connections. Pictures already in memory are skipped.
    @MainActor func prefetch(_ urls: [String], width: CGFloat) {
        let scale = UIScreen.main.scale
        let todo = urls.filter { cached($0, width: width) == nil }
        guard !todo.isEmpty else { return }
        Task.detached(priority: .low) {
            for u in todo { _ = await self.image(u, width: width, scale: scale) }
        }
    }

    private static func downsample(_ data: Data, width: CGFloat, scale: CGFloat) -> UIImage? {
        guard let src = CGImageSourceCreateWithData(data as CFData, [kCGImageSourceShouldCache: false] as CFDictionary) else { return nil }
        let px = max(64, width * scale)
        let opts: [CFString: Any] = [kCGImageSourceCreateThumbnailFromImageAlways: true, kCGImageSourceShouldCacheImmediately: true,
                                     kCGImageSourceCreateThumbnailWithTransform: true, kCGImageSourceThumbnailMaxPixelSize: px]
        guard let cg = CGImageSourceCreateThumbnailAtIndex(src, 0, opts as CFDictionary) else { return UIImage(data: data) }
        return UIImage(cgImage: cg)
    }
}

/// A picture from the web that fades in; holds its place with the theme's tile until then.
struct RemoteImage: View {
    let url: String?
    var width: CGFloat = 300
    var contentMode: ContentMode = .fill
    var placeholder = true
    var fallback: String? = nil
    var onLoad: ((UIImage) -> Void)? = nil
    @Environment(\.theme) private var theme
    @Environment(\.displayScale) private var scale
    @State private var image: UIImage?
    @State private var loadedFor: String?

    var body: some View {
        // The picture never sizes the view: it fills whatever space it's given and is clipped to it, so a wide
        // backdrop can't push a page wider than the screen.
        (placeholder && image == nil ? theme.tile : Color.clear)
            .overlay {
                if let image {
                    Image(uiImage: image).resizable().aspectRatio(contentMode: contentMode)
                        .transition(.opacity)
                }
            }
            .clipped()
        .task(id: url) {
            guard let url, !url.isEmpty else { image = nil; return }
            if loadedFor == url, image != nil { return }
            if let c = ImageStore.shared.cached(url, width: width) { image = c; loadedFor = url; onLoad?(c); return }
            var img = await ImageStore.shared.image(url, width: width, scale: scale)
            if img == nil, let fallback { img = await ImageStore.shared.image(fallback, width: width, scale: scale) }
            guard !Task.isCancelled else { return }
            withAnimation(.easeOut(duration: 0.25)) { image = img }
            loadedFor = url
            if let img { onLoad?(img) }
        }
    }
}

enum Art {
    /// Cinemeta's logo for an IMDb id, when the add-on sent none.
    static func logo(for id: String) -> String? {
        id.hasPrefix("tt") ? "https://images.metahub.space/logo/medium/\(id.components(separatedBy: ":")[0])/img" : nil
    }

    static func background(for id: String) -> String? {
        id.hasPrefix("tt") ? "https://images.metahub.space/background/medium/\(id.components(separatedBy: ":")[0])/img" : nil
    }

    /// The poster to show: with MDBList scores on, Cinemeta's plain picture for an IMDb title (Canopy draws its own
    /// readable scores on it; add-ons often print theirs too small to read). Else [url], then Cinemeta's.
    static func poster(_ id: String, _ url: String?) -> String? {
        (Ratings.enabled ? poster(for: id) : nil) ?? url ?? poster(for: id)
    }

    static func poster(for id: String) -> String? {
        id.hasPrefix("tt") ? "https://images.metahub.space/poster/medium/\(id.components(separatedBy: ":")[0])/img" : nil
    }

    /// [base] moved [amount] of the way to [tint]: a page colour that keeps the theme's darkness.
    static func blend(_ base: Color, with tint: Color, amount: CGFloat) -> Color {
        var r1: CGFloat = 0, g1: CGFloat = 0, b1: CGFloat = 0, a1: CGFloat = 0
        var r2: CGFloat = 0, g2: CGFloat = 0, b2: CGFloat = 0, a2: CGFloat = 0
        UIColor(base).getRed(&r1, green: &g1, blue: &b1, alpha: &a1)
        UIColor(tint).getRed(&r2, green: &g2, blue: &b2, alpha: &a2)
        // Darken the tint first so a pale poster can't wash the page out.
        let k: CGFloat = 0.55
        return Color(red: r1 + (r2 * k - r1) * amount, green: g1 + (g2 * k - g1) * amount, blue: b1 + (b2 * k - b1) * amount)
    }

    /// The average colour of an image's lower half, for the page's wash behind the carousel.
    static func tint(_ image: UIImage) -> Color? {
        guard let cg = image.cgImage else { return nil }
        var px = [UInt8](repeating: 0, count: 4)
        let drawn: Bool = px.withUnsafeMutableBytes { buf in
            guard let ctx = CGContext(data: buf.baseAddress, width: 1, height: 1, bitsPerComponent: 8, bytesPerRow: 4,
                                      space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return false }
            ctx.interpolationQuality = .medium
            ctx.draw(cg, in: CGRect(x: 0, y: 0, width: 1, height: 1))
            return true
        }
        guard drawn else { return nil }
        return Color(red: Double(px[0]) / 255, green: Double(px[1]) / 255, blue: Double(px[2]) / 255)
    }
}
