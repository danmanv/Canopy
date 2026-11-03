import Foundation

/// What to play: a title's video, from where, and (when chosen by hand) which sources.
struct PlayRequest: Hashable, Identifiable {
    let id = UUID()
    let title: TitleRef
    let videoId: String
    var episode: String? = nil
    var startOver = false
    var sources: [Stream] = []
    var videos: [Video] = []
    var maxHeight = 0

    static func == (a: PlayRequest, b: PlayRequest) -> Bool { a.id == b.id }
    func hash(into h: inout Hasher) { h.combine(id) }

    var type: String { title.type }
    var picked: Bool { !sources.isEmpty }

    /// The episode after this one, for Next up.
    var next: Video? {
        guard let i = videos.firstIndex(where: { $0.id == videoId }), i + 1 < videos.count else { return nil }
        let n = videos[i + 1]
        return n.season == 0 ? nil : n
    }

    func episodeLine(_ v: Video) -> String { v.title.isEmpty ? v.label : "\(v.label) · \(v.title)" }
}
