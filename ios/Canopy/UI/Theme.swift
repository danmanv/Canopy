import SwiftUI

/// A colour scheme for the whole app, the TV's 16. [accent] is focus, progress and the open tab; [second] values and the
/// far end of a bar; [lights] the three glows behind Settings.
struct Theme: Identifiable, Equatable {
    static let defaultId = "tangerine"
    let id: String
    let name: String
    let base: UInt32
    let text: UInt32
    let dim: UInt32
    let accent: UInt32
    let second: UInt32
    let lights: [UInt32]

    var baseColor: Color { Color(argb: base) }
    var textColor: Color { Color(argb: text) }
    var text2: Color { Color(argb: text).opacity(0.72) }
    var text3: Color { Color(argb: text).opacity(0.5) }
    var accentColor: Color { Color(argb: accent) }
    var secondColor: Color { Color(argb: second) }
    var glass: Color { Color(argb: mix(base, text, 0.04)) }
    var tile: Color { Color(argb: mix(base, text, 0.07)) }
    var deep: Color { Color(argb: mix(base, 0xFF000000, 0.45)) }
    var lightColors: [Color] { lights.map { Color(argb: $0) } }

    static let all: [Theme] = [
        Theme(id: "aurora", name: "Aurora", base: 0xFF070B16, text: 0xFFF2F5FF, dim: 0xFFD4DCF0, accent: 0xFF7CF2D0, second: 0xFFBFE9FF, lights: [0x387CF2D0, 0x409B8CFF, 0x2E3C6EBE]),
        Theme(id: "ember", name: "Ember", base: 0xFF0C0A10, text: 0xFFFFF6EA, dim: 0xFFB8AD9E, accent: 0xFFFFB547, second: 0xFFFFD9A8, lights: [0x3DFFB547, 0x38FF7850, 0x408C4628]),
        Theme(id: "violet", name: "Violet", base: 0xFF0A0816, text: 0xFFF6F2FF, dim: 0xFFB4ABCC, accent: 0xFFA78BFA, second: 0xFFF0ABFC, lights: [0x52A78BFA, 0x33F0ABFC, 0x4C503CB4]),
        Theme(id: "arctic", name: "Arctic", base: 0xFF070B14, text: 0xFFF2F6FF, dim: 0xFFA7B2C8, accent: 0xFF6FB8FF, second: 0xFFDCEEFF, lights: [0x4C6FB8FF, 0x24C8DCFF, 0x4C2846A0]),
        Theme(id: "crimson", name: "Crimson", base: 0xFF0D0809, text: 0xFFFFF3F4, dim: 0xFFC4ADB0, accent: 0xFFFF4D5E, second: 0xFFFFB3BD, lights: [0x3DFF4D5E, 0x24FF9678, 0x4C781428]),
        Theme(id: "platinum", name: "Platinum", base: 0xFF09090B, text: 0xFFF5F5F7, dim: 0xFFA8A8B0, accent: 0xFFE8D3A6, second: 0xFFFFFFFF, lights: [0x29E8D3A6, 0x12FFFFFF, 0x405A5A6E]),
        Theme(id: "sunset", name: "Sunset", base: 0xFF120A14, text: 0xFFFFF3F1, dim: 0xFFC2AAB4, accent: 0xFFFF7A59, second: 0xFFFF5C8A, lights: [0x42FF7A59, 0x2EFF5C8A, 0x4C6A1F4A]),
        Theme(id: "cobalt", name: "Cobalt", base: 0xFF060818, text: 0xFFF1F3FF, dim: 0xFFA6ACC8, accent: 0xFF3D6BFF, second: 0xFFA9B8FF, lights: [0x423D6BFF, 0x2EA9B8FF, 0x4C1A2A90]),
        Theme(id: "rosegold", name: "Rose Gold", base: 0xFF100C0D, text: 0xFFFFF5F2, dim: 0xFFBFAEAB, accent: 0xFFE8A598, second: 0xFFF6D1C1, lights: [0x42E8A598, 0x2EF6D1C1, 0x4C6A3A35]),
        Theme(id: "magenta", name: "Magenta", base: 0xFF0E0711, text: 0xFFFFF1F8, dim: 0xFFC4A9BC, accent: 0xFFFF3DA5, second: 0xFFFF9FD2, lights: [0x42FF3DA5, 0x2EFF9FD2, 0x4C7A1050]),
        Theme(id: "tangerine", name: "Tangerine", base: 0xFF0B0A0A, text: 0xFFFFF7EF, dim: 0xFFB9ADA2, accent: 0xFFFF8A1F, second: 0xFFFFD08A, lights: [0x42FF8A1F, 0x2EFFD08A, 0x4C7A3A10]),
        Theme(id: "ultraviolet", name: "Ultraviolet", base: 0xFF07061A, text: 0xFFF3F2FF, dim: 0xFFA9A6CC, accent: 0xFF7B5CFF, second: 0xFF5CA8FF, lights: [0x427B5CFF, 0x2E5CA8FF, 0x4C2A1A8A]),
        Theme(id: "goldnoir", name: "Gold Noir", base: 0xFF0A0907, text: 0xFFFBF6EA, dim: 0xFFB5AC98, accent: 0xFFD4AF37, second: 0xFFF3E3B0, lights: [0x42D4AF37, 0x2EF3E3B0, 0x4C5A4A1A]),
        Theme(id: "cherry", name: "Cherry Cola", base: 0xFF14070B, text: 0xFFFFF3F0, dim: 0xFFC4A8AC, accent: 0xFFE2445C, second: 0xFFF7B267, lights: [0x42E2445C, 0x2EF7B267, 0x4C6A1528]),
        Theme(id: "lilac", name: "Lilac Frost", base: 0xFF0D0C14, text: 0xFFF6F4FF, dim: 0xFFADAABD, accent: 0xFFC8B6FF, second: 0xFFFFFFFF, lights: [0x42C8B6FF, 0x2EFFFFFF, 0x4C4A4080]),
        Theme(id: "neon", name: "Neon Pop", base: 0xFF08070F, text: 0xFFF7F3FF, dim: 0xFFABA6C0, accent: 0xFFFF2E88, second: 0xFF4D7CFF, lights: [0x42FF2E88, 0x2E4D7CFF, 0x4C5A1060]),
    ]

    static func named(_ id: String) -> Theme { all.first { $0.id == id } ?? all.first { $0.id == defaultId }! }
}

private func mix(_ a: UInt32, _ b: UInt32, _ t: Double) -> UInt32 {
    func ch(_ shift: UInt32) -> UInt32 {
        let x = Double((a >> shift) & 0xFF), y = Double((b >> shift) & 0xFF)
        return UInt32(x * (1 - t) + y * t) << shift
    }
    return 0xFF000000 | ch(16) | ch(8) | ch(0)
}

extension Color {
    init(argb: UInt32) {
        self.init(.sRGB, red: Double((argb >> 16) & 0xFF) / 255, green: Double((argb >> 8) & 0xFF) / 255,
                  blue: Double(argb & 0xFF) / 255, opacity: Double((argb >> 24) & 0xFF) / 255)
    }
}

/// Canopy's shapes and type: small soft corners (never pills), Sora for titles, Manrope for words.
enum Look {
    static let corner: CGFloat = 6
    static let touch: CGFloat = 48

    static func title(_ size: CGFloat) -> Font { .custom("Sora-SemiBold", size: size, relativeTo: .title) }
    static func heavy(_ size: CGFloat) -> Font { .custom("Sora-Bold", size: size, relativeTo: .title) }
    static func body(_ size: CGFloat = 15) -> Font { .custom("Manrope-Medium", size: size, relativeTo: .body) }
    static func bold(_ size: CGFloat = 15) -> Font { .custom("Manrope-Bold", size: size, relativeTo: .body) }
    static func wordmark(_ size: CGFloat) -> Font { .custom("Outfit-Light", size: size) }
}

/// The scheme in use, for views to read from the environment.
private struct ThemeKey: EnvironmentKey { static let defaultValue = Theme.named(Theme.defaultId) }
extension EnvironmentValues {
    var theme: Theme {
        get { self[ThemeKey.self] }
        set { self[ThemeKey.self] = newValue }
    }
}

struct Soft: Shape {
    var radius: CGFloat = Look.corner
    func path(in rect: CGRect) -> Path { Path(roundedRect: rect, cornerRadius: radius, style: .continuous) }
}

/// The primary button: filled with the accent, dark text, small soft corners.
struct PrimaryButton: ButtonStyle {
    @Environment(\.theme) private var theme
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(Look.bold(16))
            .foregroundStyle(Color.black.opacity(0.88))
            .frame(maxWidth: .infinity, minHeight: Look.touch)
            .background(theme.accentColor.opacity(configuration.isPressed ? 0.8 : 1), in: Soft())
            .scaleEffect(configuration.isPressed ? 0.98 : 1)
    }
}

/// A quiet button: glass with a hairline edge.
struct GlassButton: ButtonStyle {
    @Environment(\.theme) private var theme
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(Look.bold(15))
            .foregroundStyle(theme.textColor)
            .frame(minHeight: Look.touch)
            .padding(.horizontal, 16)
            .background(.ultraThinMaterial.opacity(configuration.isPressed ? 0.6 : 1), in: Soft())
            .overlay(Soft().stroke(theme.textColor.opacity(0.12), lineWidth: 1))
    }
}

/// A row of glass behind text on art.
extension View {
    func glassCard(_ theme: Theme) -> some View {
        self.background(.ultraThinMaterial, in: Soft()).overlay(Soft().stroke(theme.textColor.opacity(0.1), lineWidth: 1))
    }

    /// The theme's sky: base colour with its three soft glows, behind Settings and the profile picker.
    func sky(_ theme: Theme) -> some View {
        self.background {
            ZStack {
                theme.baseColor
                GeometryReader { g in
                    ForEach(Array(theme.lightColors.enumerated()), id: \.offset) { i, c in
                        Circle().fill(c).frame(width: g.size.width * 1.1).blur(radius: 90)
                            .position(x: g.size.width * [0.1, 0.95, 0.5][i % 3], y: g.size.height * [0.05, 0.25, 0.95][i % 3])
                    }
                }
            }.ignoresSafeArea()
        }
    }
}
