# Canopy for iPhone and iPad

A native SwiftUI app that does what the Android app does, on iOS 17 and later: your Stremio account and add-ons,
Continue Watching and My List (synced with Stremio), profiles with PINs, the Home B carousel, search (with voice),
title pages with episodes, the sources list, and a VLC player that plays MKV, HEVC, AV1, DTS and TrueHD with every
audio and subtitle track.

The logic copies the Android app's (`app/src/main/java/dev/alder`): `Core/` holds the Stremio client, add-on protocol,
library rules (stremio-core's, including the watched bitfield), profiles and settings, and the source ranking. A fix to
one of those in the Android app usually wants the same fix here.

## Installing (no Apple Developer account)

Every push that changes `ios/` builds an unsigned IPA on GitHub's Macs and publishes it as the **ios-nightly**
pre-release, with a SideStore source. SideStore signs it on the phone with a free Apple ID.

1. Set up SideStore once: https://docs.sidestore.io (it needs a computer once, to pair the phone; a Mac works, no
   Xcode needed).
2. In SideStore, Sources › + and add
   `https://github.com/danmanv/Canopy/releases/download/ios-nightly/source.json`, then install Canopy from it.
   (Or download `Canopy.ipa` from the ios-nightly release on the phone and open it with SideStore.)
3. SideStore refreshes the app every 7 days by itself (a free Apple ID's limit) and offers each new build.

The pre-release is never GitHub's "latest" release, so the Android app's updater and the Downloader code keep getting
Canopy.apk.

## Building

On a Mac with Xcode, from `ios/`:

```
brew install xcodegen
xcodegen generate
pod install
open Canopy.xcworkspace
```

The `.xcodeproj` and `Pods/` aren't kept in git; `project.yml` and `Podfile` are the source.
