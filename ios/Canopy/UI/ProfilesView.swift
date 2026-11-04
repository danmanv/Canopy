import SwiftUI

/// The first start: the canopy mark, a line, and sign-in.
struct WelcomeView: View {
    @Environment(\.theme) private var theme
    @State private var signingIn = false

    var body: some View {
        VStack(spacing: 22) {
            Spacer()
            Image("Logo").resizable().frame(width: 96, height: 96)
            Text("canopy").font(Look.wordmark(46)).foregroundStyle(theme.textColor)
            Text("Your Stremio, on iPhone and iPad.").font(Look.body(16)).foregroundStyle(theme.text2)
            Spacer()
            Button("Sign in with Stremio") { signingIn = true }.buttonStyle(PrimaryButton()).frame(maxWidth: 380)
            Text("Canopy uses your Stremio account: its add-ons, Continue Watching and My List.")
                .font(Look.body(13)).foregroundStyle(theme.text3).multilineTextAlignment(.center).frame(maxWidth: 380)
                .padding(.bottom, 30)
        }
        .padding(.horizontal, 24)
        .sky(theme)
        .sheet(isPresented: $signingIn) { SignInView(profile: nil) }
    }
}

/// Who's watching: each profile's square and name; a PIN when it has one; Add profile.
struct ProfilesView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var theme
    @State private var adding = false
    @State private var pinFor: Profile?
    @State private var signInAgain: Profile?

    var body: some View {
        VStack(spacing: 30) {
            Spacer()
            Text("Who's watching?").font(Look.title(28)).foregroundStyle(theme.textColor)
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 110, maximum: 150), spacing: 22)], spacing: 26) {
                ForEach(model.profiles) { p in
                    Button { pick(p) } label: {
                        VStack(spacing: 10) {
                            Avatar(profile: p, size: 96)
                                .overlay(alignment: .bottomTrailing) {
                                    if p.pin != nil {
                                        Image(systemName: "lock.fill").font(.system(size: 12)).foregroundStyle(theme.textColor)
                                            .padding(5).background(theme.baseColor.opacity(0.8), in: Soft(radius: 4)).padding(4)
                                    }
                                }
                            Text(p.name).font(Look.bold(15)).foregroundStyle(theme.textColor).lineLimit(1)
                            if Profiles.signedOut(p.id) {
                                Text("Sign in again").font(Look.body(12)).foregroundStyle(theme.accentColor)
                            }
                        }
                    }
                    .buttonStyle(.plain)
                }
                if model.profiles.count < Profiles.max {
                    Button { adding = true } label: {
                        VStack(spacing: 10) {
                            RoundedRectangle(cornerRadius: 19, style: .continuous).stroke(theme.text3, style: StrokeStyle(lineWidth: 1.5, dash: [5]))
                                .frame(width: 96, height: 96)
                                .overlay(Image(systemName: "plus").font(.system(size: 30, weight: .light)).foregroundStyle(theme.text2))
                            Text("Add profile").font(Look.bold(15)).foregroundStyle(theme.text2)
                        }
                    }
                    .buttonStyle(.plain)
                }
            }
            .frame(maxWidth: 560)
            Spacer()
            Spacer()
        }
        .padding(.horizontal, 24)
        .sky(theme)
        .sheet(isPresented: $adding) { SignInView(profile: nil) }
        .sheet(item: $signInAgain) { p in SignInView(profile: p) }
        .sheet(item: $pinFor) { p in
            PinPad(title: "\(p.name)'s PIN") { entered in
                if entered == p.pin { pinFor = nil; open(p); return true }
                return false
            }
            .presentationDetents([.medium, .large])
        }
    }

    private func pick(_ p: Profile) {
        if p.pin != nil { pinFor = p } else { open(p) }
    }

    private func open(_ p: Profile) {
        if Profiles.signedOut(p.id) { signInAgain = p } else { model.choose(p) }
    }
}

/// Four digits on a keypad; [check] says whether they're right.
struct PinPad: View {
    let title: String
    let check: (String) -> Bool
    @Environment(\.theme) private var theme
    @State private var digits = ""
    @State private var wrong = false

    var body: some View {
        VStack(spacing: 22) {
            Text(title).font(Look.title(20)).foregroundStyle(theme.textColor).padding(.top, 24)
            HStack(spacing: 14) {
                ForEach(0..<4, id: \.self) { i in
                    RoundedRectangle(cornerRadius: 4).fill(i < digits.count ? theme.accentColor : theme.tile).frame(width: 18, height: 18)
                }
            }
            .modifier(Shake(amount: wrong ? 1 : 0))
            LazyVGrid(columns: Array(repeating: GridItem(.fixed(84), spacing: 14), count: 3), spacing: 14) {
                ForEach(["1", "2", "3", "4", "5", "6", "7", "8", "9", "", "0", "⌫"], id: \.self) { k in
                    if k.isEmpty { Color.clear.frame(height: 60) } else {
                        Button { press(k) } label: {
                            Text(k).font(Look.title(24)).foregroundStyle(theme.textColor)
                                .frame(width: 84, height: 60).background(theme.tile, in: Soft())
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
            Spacer()
        }
        .frame(maxWidth: .infinity)
        .background(theme.baseColor)
    }

    private func press(_ k: String) {
        wrong = false
        if k == "⌫" { digits = String(digits.dropLast()); return }
        guard digits.count < 4 else { return }
        digits += k
        if digits.count == 4 {
            if !check(digits) {
                withAnimation(.default) { wrong = true }
                digits = ""
            }
        }
    }
}

private struct Shake: GeometryEffect {
    var amount: CGFloat
    var animatableData: CGFloat { get { amount } set { amount = newValue } }
    func effectValue(size: CGSize) -> ProjectionTransform {
        ProjectionTransform(CGAffineTransform(translationX: 10 * sin(amount * .pi * 4), y: 0))
    }
}

/// Signing in to Stremio: email and password, or (accounts made with Google or Facebook) a code approved on a page
/// where Stremio is signed in. Then the profile's name and colour.
struct SignInView: View {
    /// Signing an existing profile in again, or nil for a new profile.
    let profile: Profile?
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var theme
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL
    @State private var email = ""
    @State private var password = ""
    @State private var busy = false
    @State private var error: String?
    @State private var link: Stremio.Link?
    @State private var authKey: String?
    @State private var name = ""
    @State private var color = Profiles.colors[0]
    @State private var polling: Task<Void, Never>?

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    if authKey == nil { signIn } else { nameStep }
                    if let error { Text(error).font(Look.body(14)).foregroundStyle(Color(argb: 0xFFFF6B6B)) }
                }
                .padding(24)
                .frame(maxWidth: 480)
                .frame(maxWidth: .infinity)
            }
            .background(theme.baseColor)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } } }
            .navigationTitle(profile == nil ? "Add a profile" : "Sign in again")
            .navigationBarTitleDisplayMode(.inline)
        }
        .onDisappear { polling?.cancel() }
        .onAppear {
            let used = Set(model.profiles.map(\.color))
            color = Profiles.colors.first { !used.contains($0) } ?? Profiles.colors[0]
        }
    }

    @ViewBuilder private var signIn: some View {
        Text("Sign in with your Stremio account").font(Look.title(22)).foregroundStyle(theme.textColor)
        field("Email", text: $email).textContentType(.username).keyboardType(.emailAddress).textInputAutocapitalization(.never)
        SecureField("", text: $password, prompt: Text("Password").foregroundColor(theme.text3))
            .textContentType(.password)
            .font(Look.body(17)).foregroundStyle(theme.textColor)
            .padding(.horizontal, 14).frame(height: 50).background(theme.tile, in: Soft())
        Button { Task { await login() } } label: {
            if busy && link == nil { ProgressView().tint(.black) } else { Text("Sign in") }
        }
        .buttonStyle(PrimaryButton())
        .disabled(email.isEmpty || password.isEmpty || busy)

        Divider().overlay(theme.text3).padding(.vertical, 8)
        Text("Signed up with Google or Facebook?").font(Look.bold(15)).foregroundStyle(theme.textColor)
        if let link {
            Text("Open the page below where you're signed in to Stremio (here, or on a computer) and approve code \(link.code). Canopy carries on by itself.")
                .font(Look.body(14)).foregroundStyle(theme.text2)
            Text(link.url).font(Look.bold(14)).foregroundStyle(theme.accentColor).textSelection(.enabled)
            HStack {
                Button("Open the page") { if let u = URL(string: link.url) { openURL(u) } }.buttonStyle(GlassButton())
                ShareLink(item: link.url) { Label("Share", systemImage: "square.and.arrow.up") }.buttonStyle(GlassButton())
            }
            HStack(spacing: 10) { Spinner(size: 18); Text("Waiting for approval").font(Look.body(13)).foregroundStyle(theme.text3) }
        } else {
            Button("Sign in with a code instead") { Task { await startLink() } }.buttonStyle(GlassButton())
        }
    }

    @ViewBuilder private var nameStep: some View {
        Text(profile == nil ? "Name this profile" : "Signed in").font(Look.title(22)).foregroundStyle(theme.textColor)
        if profile == nil {
            field("Name", text: $name).textInputAutocapitalization(.words)
            HStack(spacing: 14) {
                ForEach(Profiles.colors, id: \.self) { c in
                    Button { color = c } label: {
                        RoundedRectangle(cornerRadius: 8).fill(Color(argb: c)).frame(width: 46, height: 46)
                            .overlay(RoundedRectangle(cornerRadius: 8).stroke(theme.textColor, lineWidth: color == c ? 3 : 0))
                    }
                }
            }
            Button("Start watching") {
                model.addProfile(name: name.trimmingCharacters(in: .whitespaces).isEmpty ? "Me" : name, authKey: authKey!, color: color)
                dismiss()
            }
            .buttonStyle(PrimaryButton())
        }
    }

    private func field(_ prompt: String, text: Binding<String>) -> some View {
        TextField("", text: text, prompt: Text(prompt).foregroundColor(theme.text3))
            .font(Look.body(17)).foregroundStyle(theme.textColor).autocorrectionDisabled()
            .padding(.horizontal, 14).frame(height: 50).background(theme.tile, in: Soft())
    }

    private func login() async {
        busy = true
        error = nil
        do { got(try await Stremio.login(email: email.trimmingCharacters(in: .whitespaces), password: password)) }
        catch { self.error = (error as? Stremio.ApiError)?.message.nilIfEmpty ?? "Couldn't sign in. Check the email and password." }
        busy = false
    }

    private func startLink() async {
        error = nil
        do {
            let l = try await Stremio.createLink()
            link = l
            polling = Task {
                while !Task.isCancelled {
                    try? await Task.sleep(nanoseconds: 3_000_000_000)
                    if let key = try? await Stremio.readLink(l.code) { got(key); return }
                }
            }
        } catch { self.error = "Couldn't reach Stremio. Check the connection." }
    }

    private func got(_ key: String) {
        polling?.cancel()
        if let profile {
            model.signedInAgain(profile, authKey: key)
            dismiss()
        } else {
            authKey = key
            if name.isEmpty { name = email.components(separatedBy: "@").first?.capitalized ?? "" }
        }
    }
}

extension String {
    var nilIfEmpty: String? { isEmpty ? nil : self }
}
