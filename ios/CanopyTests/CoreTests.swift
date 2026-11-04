import XCTest
@testable import Canopy

final class CoreTests: XCTestCase {
    func testRatings() throws {
        let json = try JSONText.parse(#"{"ratings":[{"source":"imdb","value":8.13,"score":81},{"source":"tomatoes","value":94,"score":94},{"source":"popcorn","value":null},{"source":"metacritic","value":0}]}"#)
        let s = Ratings.parse(json)
        XCTAssertEqual(s, Ratings.Scores(tomatoes: 94, popcorn: nil, imdb: "8.1"))
        XCTAssertEqual(Ratings.line(imdb: "7.9", s), "IMDb 7.9 · 🍅 94%")
        XCTAssertEqual(Ratings.line(imdb: nil, Ratings.Scores(tomatoes: nil, popcorn: 88, imdb: nil)), "🍿 88%")
        XCTAssertNil(Ratings.parse(try JSONText.parse(#"{"ratings":[]}"#)))
    }

    func testRuntime() {
        XCTAssertEqual(formatRuntime("147 min"), "2h 27m")
        XCTAssertEqual(formatRuntime("2h35min"), "2h 35m")
        XCTAssertEqual(formatRuntime("45 min"), "45 min")
        XCTAssertEqual(formatRuntime("N/A"), "N/A")
    }

    func testDebrid() {
        XCTAssertEqual(debridOf("[RD+] Torrentio\n4k", "https://x")?.tag, "RD ⚡")
        XCTAssertEqual(debridOf("[AD download] Torrentio", "https://x")?.cached, false)
        XCTAssertEqual(debridOf("Comet ⚡TB", "https://x")?.name, "TorBox")
        XCTAssertEqual(debridOf("Torrentio", "https://torrentio/resolve/realdebrid/abc")?.short, "RD")
        XCTAssertNil(debridOf("HDR [RDX]", "https://example.com/a.mkv"))
    }

    func testStreamWords() {
        let s = Stream.from(["name": "Torrentio\n2160p", "title": "Movie.2023.2160p.UHD.BluRay.REMUX.HDR.HEVC.Atmos-GRP\n👤 12 💾 58.3 GB ⚙️ x",
                             "url": "https://a/b.mkv"])!
        XCTAssertEqual(s.height, 2160)
        XCTAssertTrue(s.remux)
        XCTAssertTrue(s.hdr)
        XCTAssertEqual(s.size, 58_300_000_000)
        XCTAssertEqual(s.summary, "BluRay · HEVC · Atmos")
        XCTAssertTrue(audioLanguagesIn("🇮🇹 🇬🇧 multi").isSuperset(of: ["it", "en"]))
    }

    func testWrongEpisodeDemoted() {
        let right = Stream(title: "Torrentio", description: "Nocturne.S01E01.1080p.WEB", url: "https://a/1", headers: [:], size: 0,
                           filename: "Nocturne.S01E01.1080p.mkv", bingeGroup: nil)
        let wrong = Stream(title: "Torrentio", description: "Pack", url: "https://a/2", headers: [:], size: 0,
                           filename: "Other.Show.S01E03.mkv", bingeGroup: nil)
        let plain = Stream(title: "Torrentio", description: "Nocturne 1080p", url: "https://a/3", headers: [:], size: 0,
                           filename: nil, bingeGroup: nil)
        XCTAssertFalse(right.namesOtherEpisode(than: (1, 1)))
        XCTAssertTrue(wrong.namesOtherEpisode(than: (1, 1)))
        XCTAssertFalse(plain.namesOtherEpisode(than: (1, 1)))
        XCTAssertEqual(StreamPicker.episode(of: "tt1:1:1")?.episode, 1)
        XCTAssertNil(StreamPicker.episode(of: "tt1"))
    }

    func testWatchedRoundTrip() {
        let videos = (1...12).map { Video(id: "tt1:1:\($0)", title: "", season: 1, episode: $0, thumbnail: nil, overview: nil, released: nil) }
        let item = Library.newItem(id: "tt1", type: "series", name: "X", poster: nil)
        Library.markWatched(item, videos: videos, videoId: "tt1:1:3")
        Library.markWatched(item, videos: videos, videoId: "tt1:1:10")
        XCTAssertEqual(Library.watched(item, videos: videos), ["tt1:1:3", "tt1:1:10"])
        XCTAssertTrue(item.state.string("watched").hasPrefix("tt1:1:10:10:"))
    }

    func testZlibMatchesStremio() {
        // zlib (as stremio-core and Java write it) of the single byte 1: "eJxjBAAAAgAC"
        let data = Data(base64Encoded: "eJxjBAAAAgAC")!
        XCTAssertEqual(Library.inflate(data), Data([1]))
        XCTAssertEqual(Library.deflate(Data([1])).flatMap(Library.inflate), Data([1]))
    }

    func testHomeRowsArrange() {
        let none = HomeRows.arrange(available: ["a", "b", "c"], defaults: ["b"], saved: nil)
        XCTAssertEqual(none.map(\.0), ["b", "a", "c"])
        XCTAssertEqual(none.map(\.1), [true, false, false])
        let saved = HomeRows.arrange(available: ["a", "b", "c", "d"], defaults: [], saved: [("c", true), ("x", true), ("a", false)])
        XCTAssertEqual(saved.map(\.0), ["c", "a", "b", "d"])
    }

    func testCatalogRequiredGenre() {
        let addon = Addon(["transportUrl": "https://addon.example/manifest.json", "manifest": [
            "id": "x", "name": "X", "resources": ["catalog", "meta"], "types": ["movie"],
            "catalogs": [["type": "movie", "id": "top", "name": "Top", "extra": [["name": "genre", "isRequired": true, "options": ["None", "Drama"]], ["name": "skip"]]],
                         ["type": "movie", "id": "search", "extra": [["name": "search", "isRequired": true]]]],
        ] as JSON])!
        XCTAssertEqual(addon.catalogs.count, 1)
        XCTAssertEqual(addon.catalogs[0].genres, ["Drama"])
        XCTAssertTrue(addon.catalogs[0].pages)
        XCTAssertEqual(addon.catalogs[0].required.first?.1, "None")
    }

    func testLanguages() {
        XCTAssertEqual(Languages.code("eng"), "en")
        XCTAssertEqual(Languages.code("pt_BR"), Languages.brazil)
        XCTAssertEqual(Languages.code("French"), "fr")
        XCTAssertEqual(Languages.match("en", "eng"), 2)
    }
}
