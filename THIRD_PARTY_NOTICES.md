# Third-party notices — Harmony 1.0

Harmony can build and use the native backend from **SpotiFLAC Mobile v4.9.6**. SpotiFLAC Mobile is distributed under the MIT License. The build helper downloads its source from the official upstream repository and compiles only the Go backend used by Harmony; Harmony does not embed the Flutter UI.

Harmony can also download provider-extension packages listed by the **SpotiFLAC Extension** repository. That repository is distributed under the Apache License 2.0. Harmony 1.0 pins package URLs and SHA-256 values and verifies each downloaded package before execution.

No provider credentials, private tokens, or user passwords are committed in this Harmony source archive. Release CI can produce the `gobackend` AAR from the pinned upstream source with `scripts/ci/build-spotiflac-backend.sh`; the local phone-testing package includes the equivalent official arm64 backend in its local Maven repository.

Harmony's Discover screen includes a curated selection of album facts, public album-page links and remote cover-image links from **The Shfl** (theshfl.com). Harmony is not affiliated with, sponsored by, or endorsed by The Shfl. Listening notes are original Harmony copy; SHFL reviews remain on the source website and open in the user's browser through AndroidX Custom Tabs (**androidx.browser**, Apache License 2.0). The app does not fetch or scrape SHFL pages to build a live catalogue or upload the user's library, ratings or listening history. Cover requests go to the image host; all names, trademarks, artwork and source content remain the property of their owners.

Album edition/track metadata and optional song previews use **Deezer** public endpoints. Previews are requested only on an explicit Listen action when a matching local recording is unavailable. The selected public song's artist/title are sent in that lookup; Harmony's stored taste profile is not uploaded. Availability varies by recording and territory. Preview playback is separate from the selected full-track download engine.

Recognize computes Shazam audio fingerprints on the phone with a Kotlin port of the signature generator from **SongRec** (github.com/marin-m/SongRec, GNU General Public License v3.0; its Python version, also reflected in **ShazamIO**, MIT License) and sends only that fingerprint to Shazam's song-lookup service. Harmony is not affiliated with, sponsored by, or endorsed by Shazam or Apple; Shazam is a trademark of Apple Inc. Song names, artwork and links shown after a match come from Shazam.

Upstream license files and copyright notices remain authoritative for the respective upstream projects.


## Mornye downloads on iOS

- Mornye 5.2.5 (MIT), copyright (c) 2026 zarzet.
  Source: https://github.com/mornyeapp/Mornye/tree/9bf4c000a07fb7bf4b6b68cd80a552cdf8e81298
  The Rust runtime is built from source; the Swift FFmpeg command pump is adapted from its iOS host.
- Mornye-Extension provider packages (GNU LGPL v3), pinned source:
  https://github.com/mornyeapp/Mornye-Extension/tree/6b544663fb5b7a7dfbe1959f3c0edab017378f53
- FFmpegKit 8.1.2 audio (GNU LGPL v3), the maintained sk3llo fork used by Mornye:
  https://github.com/sk3llo/ffmpeg_kit_flutter/releases/tag/8.1.2-audio
  Sources and rebuild instructions: https://github.com/sk3llo/ffmpeg_kit_flutter
  Combined native XCFramework ZIPs are downloaded with fixed SHA-256 checksums.

The app includes these license texts under Resources/Licenses. Generated Swift
bindings, native XCFrameworks and extension packages are staged by
scripts/ci/build-mornye-ios.sh and are not committed as binaries.
