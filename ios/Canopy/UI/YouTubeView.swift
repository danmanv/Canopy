import SwiftUI
import WebKit

/// YouTube's embedded player, playing inline and at once.
struct YouTubeView: UIViewRepresentable {
    let id: String
    var muted = false

    func makeUIView(context: Context) -> WKWebView {
        let c = WKWebViewConfiguration()
        c.allowsInlineMediaPlayback = true
        c.mediaTypesRequiringUserActionForPlayback = []
        let v = WKWebView(frame: .zero, configuration: c)
        v.isOpaque = false
        v.backgroundColor = .black
        v.scrollView.isScrollEnabled = false
        let html = """
        <html><head><meta name="viewport" content="width=device-width,initial-scale=1"><style>html,body{margin:0;background:#000;height:100%}iframe{border:0;width:100%;height:100%}</style></head>
        <body><iframe src="https://www.youtube-nocookie.com/embed/\(id)?autoplay=1&playsinline=1&rel=0&modestbranding=1\(muted ? "&mute=1&controls=0" : "")" allow="autoplay; encrypted-media; picture-in-picture" allowfullscreen></iframe></body></html>
        """
        v.loadHTMLString(html, baseURL: URL(string: "https://www.youtube-nocookie.com"))
        return v
    }

    func updateUIView(_ uiView: WKWebView, context: Context) {}
}
