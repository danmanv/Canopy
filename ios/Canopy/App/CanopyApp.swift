import SwiftUI
import AVFoundation

@main
struct CanopyApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var delegate
    @State private var model = AppModel()

    init() {
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback)
        URLCache.shared = URLCache(memoryCapacity: 64 << 20, diskCapacity: 300 << 20)
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(model)
                .environment(\.theme, model.theme)
                .tint(model.theme.accentColor)
                .preferredColorScheme(.dark)
        }
    }
}

/// Who's watching until a profile is chosen, then the app's tabs.
struct RootView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var theme

    var body: some View {
        ZStack {
            theme.baseColor.ignoresSafeArea()
            if model.active != nil && model.unlocked {
                MainView().id(model.active?.id)
                    .transition(.opacity)
            } else if model.profiles.isEmpty {
                WelcomeView()
            } else {
                ProfilesView()
                    .transition(.opacity)
            }
        }
        .animation(.easeInOut(duration: 0.25), value: model.active?.id)
    }
}
