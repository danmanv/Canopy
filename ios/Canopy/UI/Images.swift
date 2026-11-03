import SwiftUI
import UIKit
import ImageIO

/// Pictures from add-ons, decoded at the size they're shown and kept in memory (and in URLCache on disk).
private final class Memory: @unchecked Sendable {
    let cache = NSCache<NSString, UIImage>()
}

actor ImageStore {
    static let shared = ImageStore()
    private nonisolated let box = Memory()
    private nonisolated var memory: NSCache<NSString, UIImage> { box.cache }
    private var running = [String: Task<UIImage?, Never>]()

    init() { box.cache.totalCostLimit = 120 << 20 }

    nonisolated func cached(_ url: String, width: CGFloat) -> UIImage? {
        memory.object(forKey: key(url, width) as NSString)
    }

    nonisolated private func key(_ url: String, _ width: CGFloat) -> String { "\(Int(width))|\(url)" }

    func image(_ url: String, width: CGFloat) async -> UIImage? {
        let k = key(url, width)
        if let img = memory.object(forKey: k as NSString) { return img }
        if let t = running[k] { return await t.value }
        let t = Task<UIImage?, Never> {
            guard let u = URL(string: url) else { return nil }
            var r = URLRequest(url: u, cachePolicy: .returnCacheDataElseLoad, timeoutInterval: 20)
            r.setValue(Http.userAgent, forHTTPHeaderField: "User-Agent")
            guard let (data, _) = try? await URLSession.shared.data(for: r) else { return nil }
            return Self.downsample(data, width: width)
        }
        running[k] = t
        let img = await t.value
        running[k] = nil
        if let img { memory.setObject(img, forKey: k as NSString, cost: Int(img.size.width * img.size.height * img.scale * img.scale * 4)) }
        return img
    }

    private static func downsample(_ data: Data, width: CGFloat) -> UIImage? {
        guard let src = CGImageSourceCreateWithData(data as CFData, [kCGImageSourceShouldCache: false] as CFDictionary) else { return nil }
        let px = max(64, width * 3)
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
    @State private var image: UIImage?
    @State private var loadedFor: String?

    var body: some View {
        ZStack {
            if placeholder && image == nil { theme.tile }
            if let image {
                Image(uiImage: image).resizable().aspectRatio(contentMode: contentMode)
                    .transition(.opacity)
            }
        }
        .task(id: url) {
            guard let url, !url.isEmpty else { image = nil; return }
            if loadedFor == url, image != nil { return }
            if let c = ImageStore.shared.cached(url, width: width) { image = c; loadedFor = url; onLoad?(c); return }
            var img = await ImageStore.shared.image(url, width: width)
            if img == nil, let fallback { img = await ImageStore.shared.image(fallback, width: width) }
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

    static func poster(for id: String) -> String? {
        id.hasPrefix("tt") ? "https://images.metahub.space/poster/medium/\(id.components(separatedBy: ":")[0])/img" : nil
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
