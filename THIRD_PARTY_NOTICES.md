# Third-party notices — Harmony 1.0.0

Harmony can build and use the native backend from **SpotiFLAC Mobile v4.9.5**. SpotiFLAC Mobile is distributed under the MIT License. The build helper downloads its source from the official upstream repository and compiles only the Go backend used by Harmony; Harmony does not embed the Flutter UI.

Harmony can also download provider-extension packages listed by the **SpotiFLAC Extension** repository. That repository is distributed under the Apache License 2.0. Harmony 1.0.0 pins package URLs and SHA-256 values and verifies each downloaded package before execution.

No provider credentials, private tokens, or user passwords are committed in this Harmony source archive. Release CI can produce the `gobackend` AAR from the pinned upstream source with `scripts/ci/build-spotiflac-backend.sh`; the local phone-testing package includes the equivalent official arm64 backend in its local Maven repository.

Harmony's Discover screen includes a curated selection of album facts, public album-page links and remote cover-image links from **The Shfl** (theshfl.com). Harmony is not affiliated with, sponsored by, or endorsed by The Shfl. Listening notes are original Harmony copy; SHFL reviews remain on the source website and open in the user's browser through AndroidX Custom Tabs (**androidx.browser**, Apache License 2.0). The app does not fetch or scrape SHFL pages to build a live catalogue or upload the user's library, ratings or listening history. Cover requests go to the image host; all names, trademarks, artwork and source content remain the property of their owners.

Album edition/track metadata and optional song previews use **Deezer** public endpoints. Previews are requested only on an explicit Listen action when a matching local recording is unavailable. The selected public song's artist/title are sent in that lookup; Harmony's stored taste profile is not uploaded. Availability varies by recording and territory. Preview playback is separate from the selected full-track download engine.

Upstream license files and copyright notices remain authoritative for the respective upstream projects.
