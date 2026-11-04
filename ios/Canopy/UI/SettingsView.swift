import SwiftUI

/// Settings: the profile, the look, how sources are picked, languages, the player, Home's rows and add-ons.
struct SettingsView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var theme
    @Environment(\.dismiss) private var dismiss
    @State private var refresh = 0
    @State private var renaming = false
    @State private var newName = ""
    @State private var settingPin = false
    @State private var confirmRemove = false
    @State private var email: String?

    var body: some View {
        let _ = refresh
        Form {
            profileSection
            Section("Theme") {
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 64), spacing: 12)], spacing: 12) {
                    ForEach(Theme.all) { t in
                        Button { model.setTheme(t) } label: {
                            VStack(spacing: 6) {
                                ZStack {
                                    RoundedRectangle(cornerRadius: 6).fill(Color(argb: t.base))
                                    Circle().fill(Color(argb: t.accent)).frame(width: 20)
                                        .offset(x: -8)
                                    Circle().fill(Color(argb: t.second)).frame(width: 14).offset(x: 10, y: 6)
                                }
                                .frame(height: 48)
                                .overlay(RoundedRectangle(cornerRadius: 6).stroke(t.id == theme.id ? theme.accentColor : theme.text3.opacity(0.4),
                                                                                  lineWidth: t.id == theme.id ? 2.5 : 1))
                                Text(t.name).font(Look.body(11)).foregroundStyle(theme.text2).lineLimit(1)
                            }
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.vertical, 6)
            }
            streamsSection
            languagesSection
            Section("Player") {
                toggle("Up next and autoplay", Settings.nextEpisode) { Settings.nextEpisode = $0 }
                toggle("Skip intro button", Settings.skipIntro) { Settings.skipIntro = $0 }
                picker("Picture size", Settings.pictureSize, [("fit", "Fit"), ("fill", "Fill screen"), ("stretch", "Stretch")]) { Settings.pictureSize = $0 }
                toggle("Trailers", Settings.trailers) { Settings.trailers = $0 }
            }
            Section("Home") {
                NavigationLink("Rows on Home") { HomeRowsView() }
                Button("Tonight pins: clear") { HomeRows.tonight = []; model.libraryVersion += 1 }
                    .disabled(HomeRows.tonight.isEmpty)
            }
            Section("Add-ons") {
                NavigationLink("Your add-ons (\(model.addons.count))") { AddonsView() }
            }
            Section("About") {
                LabeledContent("Version", value: (Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String) ?? "")
                Text("New versions arrive through SideStore.").font(Look.body(13)).foregroundStyle(theme.text3)
                Link("Canopy on GitHub", destination: URL(string: "https://github.com/danmanv/Canopy")!)
            }
        }
        .scrollContentBackground(.hidden)
        .sky(theme)
        .font(Look.body(16))
        .navigationTitle("Settings")
        .toolbar(.visible, for: .navigationBar)
        .alert("Rename profile", isPresented: $renaming) {
            TextField("Name", text: $newName)
            Button("Save") { if var p = model.active, !newName.isEmpty { p.name = newName; model.update(p) } }
            Button("Cancel", role: .cancel) {}
        }
        .sheet(isPresented: $settingPin) {
            PinPad(title: "Choose a 4-digit PIN") { pin in
                if var p = model.active { p.pin = pin; model.update(p) }
                settingPin = false
                return true
            }
            .presentationDetents([.medium, .large])
        }
        .confirmationDialog("Remove \(model.active?.name ?? "this profile") from this device?", isPresented: $confirmRemove, titleVisibility: .visible) {
            Button("Sign out and remove", role: .destructive) {
                if let p = model.active { Task { await model.remove(p) } }
            }
        } message: {
            Text("Its Stremio account, history and add-ons stay as they are.")
        }
        .task {
            if let key = model.authKey { email = (try? await Stremio.user(key))?.text("email") }
        }
    }

    @ViewBuilder private var profileSection: some View {
        if let p = model.active {
            Section {
                HStack(spacing: 14) {
                    Avatar(profile: p, size: 52)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(p.name).font(Look.title(19))
                        if let email { Text(email).font(Look.body(13)).foregroundStyle(theme.text3) }
                    }
                }
                .padding(.vertical, 4)
                Button("Rename") { newName = p.name; renaming = true }
                HStack {
                    Text("Colour")
                    Spacer()
                    ForEach(Profiles.colors, id: \.self) { c in
                        Button { var q = p; q.color = c; model.update(q) } label: {
                            RoundedRectangle(cornerRadius: 5).fill(Color(argb: c)).frame(width: 26, height: 26)
                                .overlay(RoundedRectangle(cornerRadius: 5).stroke(theme.textColor, lineWidth: p.color == c ? 2 : 0))
                        }
                        .buttonStyle(.plain)
                    }
                }
                if p.pin == nil {
                    Button("Lock with a PIN") { settingPin = true }
                } else {
                    Button("Remove the PIN") { var q = p; q.pin = nil; model.update(q) }
                }
                toggle("Lend my stream add-ons to other profiles", Profiles.sharesStreams(p.id)) { Profiles.setSharesStreams(p.id, $0) }
                Button("Switch profile") { model.leaveProfile() }
                Button("Sign out and remove", role: .destructive) { confirmRemove = true }
            } header: { Text("Profile") }
        }
    }

    private var streamsSection: some View {
        Section {
            picker("Preset", Settings.presetName, [("Add-on's order", "Add-on's order"), ("Best picture", "Best picture"),
                                                  ("Balanced", "Balanced"), ("Fast start", "Fast start"), ("Custom", "Custom")]) { applyPreset($0) }
            picker("Highest quality", "\(Settings.maxHeight)", [("0", "Any"), ("2160", "4K"), ("1080", "1080p"), ("720", "720p")]) { Settings.maxHeight = Int($0) ?? 0 }
            picker("Largest file", "\(Settings.maxSizeGb)", [("0", "Any"), ("5", "5 GB"), ("10", "10 GB"), ("20", "20 GB"), ("40", "40 GB"), ("60", "60 GB")]) { Settings.maxSizeGb = Int($0) ?? 0 }
            picker("HDR", Settings.hdrPref, [("", "No preference"), ("dv", "Prefer Dolby Vision"), ("hdr10", "Prefer HDR10"), ("off", "Never HDR")]) { Settings.hdrPref = $0 }
            picker("Release", Settings.release, [("", "Any"), ("remux", "REMUX first"), ("encode", "Smaller encodes"), ("web", "WEB first")]) { Settings.release = $0 }
            picker("Dubbed audio", Settings.dub, [("", "No preference"), ("original", "Original language"), ("mine", "My language")]) { Settings.dub = $0 }
        } header: { Text("Streams") } footer: {
            Text("Play picks the best match by these. Stream on a title page lists every source.")
        }
    }

    private var languagesSection: some View {
        Section("Languages") {
            picker("Audio language", Languages.code(Settings.audioLanguage) ?? "en", Languages.all.map { ($0, Languages.name($0)) }) { Settings.audioLanguage = $0 }
            picker("Subtitles in", Settings.subtitleLanguage, [("", "Only when I choose")] + Languages.all.map { ($0, Languages.name($0)) }) { Settings.subtitleLanguage = $0 }
            toggle("Subtitles from add-ons", Settings.subtitles) { Settings.subtitles = $0 }
        }
    }

    private func applyPreset(_ name: String) {
        switch name {
        case "Add-on's order": Settings.release = ""; Settings.hdrPref = ""; Settings.maxHeight = 0; Settings.maxSizeGb = 0
        case "Best picture": Settings.release = "remux"; Settings.hdrPref = "dv"; Settings.maxHeight = 0; Settings.maxSizeGb = 0
        case "Balanced": Settings.release = "encode"; Settings.hdrPref = ""; Settings.maxHeight = 0; Settings.maxSizeGb = 20
        case "Fast start": Settings.release = "web"; Settings.hdrPref = ""; Settings.maxHeight = 1080; Settings.maxSizeGb = 10
        default: break
        }
    }

    private func toggle(_ label: String, _ value: Bool, _ set: @escaping (Bool) -> Void) -> some View {
        Toggle(label, isOn: Binding(get: { value }, set: { set($0); refresh += 1 }))
    }

    private func picker(_ label: String, _ value: String, _ options: [(String, String)], _ set: @escaping (String) -> Void) -> some View {
        Picker(label, selection: Binding(get: { value }, set: { set($0); refresh += 1 })) {
            ForEach(options, id: \.0) { Text($0.1).tag($0.0) }
        }
    }
}

/// Home's rows: every catalog from the add-ons, on or off, in order (drag to reorder).
struct HomeRowsView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var theme
    @State private var rows: [(Addon.Catalog, Bool)] = []

    var body: some View {
        List {
            Section {
                ForEach(Array(rows.enumerated()), id: \.element.0.id) { i, row in
                    Toggle(isOn: Binding(get: { rows[i].1 }, set: { rows[i].1 = $0; HomeRows.saveArranged(rows); model.libraryVersion += 1 })) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(row.0.name).font(Look.bold(15))
                            Text("\(row.0.addon.name) · \(row.0.type == "movie" ? "Movies" : row.0.type == "series" ? "Shows" : row.0.type)")
                                .font(Look.body(12)).foregroundStyle(theme.text3)
                        }
                    }
                }
                .onMove { from, to in rows.move(fromOffsets: from, toOffset: to); HomeRows.saveArranged(rows); model.libraryVersion += 1 }
            } footer: {
                Text("With none on, Home shows your add-ons' first film and show catalogs.")
            }
            Button("Back to the defaults") { HomeRows.reset(); rows = HomeRows.arranged(model.catalogs()); model.libraryVersion += 1 }
        }
        .scrollContentBackground(.hidden)
        .background(theme.baseColor)
        .environment(\.editMode, .constant(.active))
        .navigationTitle("Rows on Home")
        .onAppear { rows = HomeRows.arranged(model.catalogs()) }
    }
}

/// The account's add-ons, in its order: drag to reorder, swipe to remove (Stremio's own can't be removed).
struct AddonsView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var theme
    @State private var list: [Addon] = []
    @State private var error: String?

    var body: some View {
        List {
            if let error { Text(error).foregroundStyle(Color(argb: 0xFFFF6B6B)) }
            ForEach(list) { a in
                HStack(spacing: 12) {
                    RemoteImage(url: a.logo, width: 40).frame(width: 36, height: 36).clipShape(Soft())
                    VStack(alignment: .leading, spacing: 2) {
                        Text(a.name).font(Look.bold(15))
                        Text([a.version.map { "v\($0)" }, a.providesStreams ? "Streams" : nil, a.catalogs.isEmpty ? nil : "Catalogs"]
                            .compactMap { $0 }.joined(separator: " · ")).font(Look.body(12)).foregroundStyle(theme.text3)
                    }
                }
                .deleteDisabled(a.locked)
            }
            .onMove { from, to in list.move(fromOffsets: from, toOffset: to); save() }
            .onDelete { idx in list.remove(atOffsets: idx); save() }
            Section {
                Text("Add new add-ons in Stremio (web.stremio.com or the Stremio app); they show here on the next start.")
                    .font(Look.body(13)).foregroundStyle(theme.text3)
            }
        }
        .scrollContentBackground(.hidden)
        .background(theme.baseColor)
        .toolbar { EditButton() }
        .navigationTitle("Add-ons")
        .onAppear { list = model.addons }
    }

    private func save() {
        guard let key = model.authKey else { return }
        let new = list
        Task {
            do {
                try await Stremio.setAddons(key, new)
                model.addons = new
                error = nil
            } catch { self.error = "Couldn't save the order to Stremio." }
        }
    }
}
