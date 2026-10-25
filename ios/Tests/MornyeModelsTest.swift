import Foundation

@main
enum MornyeModelsTest {
    static func expect(_ condition: @autoclosure () -> Bool, _ message: String) {
        precondition(condition(), message)
    }
    static func main() throws {
        let rows = #"""
        [
          {"provider_id":"tidal-web","id":123,"name":"Țara mea","artists":["Artist A","Artist B"],"album_name":"Album","duration_ms":198431,"isrc":"ROABC1234567","cover_url":"https://example.com/cover.jpg"},
          {"provider_id":"tidal-web","id":123,"name":"Duplicate"},
          {"provider_id":"qobuz-web","id":"456","name":"Another song","duration_ms":-1},
          {"provider_id":"tidal-web","id":"album1","name":"Album","item_type":"album"},
          {"provider_id":"unknown","id":"x","name":"Not a loaded provider"}
        ]
        """#
        let tracks = try MornyeTrack.parseSearch(rows)
        expect(tracks.count == 2, "Track search must deduplicate by provider/id and ignore other types")
        let track = tracks[0]
        expect(track.trackID == "123", "Numeric provider ids must survive")
        expect(track.artist == "Artist A, Artist B", "Artist arrays must be kept")
        expect(track.durationMs == 198431 && tracks[1].durationMs == 0, "Duration stays in milliseconds")
        let wrapper = try MornyeTrack.parseSearch(#"{"extension_id":"deezer","track":{"id":"9","name":"Linked song","artists":"A"}}"#)
        expect(wrapper.count == 1 && wrapper[0].providerID == "deezer", "Provider links carry their extension identity")
        let request = try MornyeJSON.object(track.request(provider: "qobuz-web", quality: "HI_RES_LOSSLESS",
                                                         itemID: "job-1", directory: URL(fileURLWithPath: "/tmp/test")))
        expect(request["service"] as? String == "qobuz-web", "Fallback route must be explicit")
        expect(request["source"] as? String == "tidal-web", "Original catalog identity must be kept")
        expect(request["provider_track_id"] as? String == "", "Never send one provider's id to another provider")
        expect(request["tidal_id"] as? String == "123", "Keep the source id for matching")
        expect(request["duration_ms"] as? Int == 198431, "Do not divide milliseconds twice")
        expect(request["use_extensions"] as? Bool == true && request["use_fallback"] as? Bool == false,
               "Only Harmony may move to another isolated provider")
        expect(request["allow_quality_variant"] as? Bool == false, "Lossless must not silently become spatial/lossy")

        let provider = MornyeProvider(id: "test", name: "Test", qualities: [
            .init(id: "best", kind: "spatial"), .init(id: "LOSSLESS", kind: "lossy"), .init(id: "flac", kind: "")
        ])
        let qualityID = try provider.qualityID(for: .hiRes)
        expect(qualityID == "flac", "Never select a spatial/lossy quality by its name")
        let callback = MornyeAuthCallback.parse(URL(string: "mornye://session-grant?state=nonce&grant=a%2Bb%3D")!)
        expect(callback?.credential == "a+b=" && callback?.sessionGrant == true, "Callback credentials must decode exactly once")
        expect(MornyeAuthCallback.parse(URL(string: "https://session-grant?state=x&grant=y")!) == nil, "Wrong scheme")
        expect(MornyeAuthCallback.parse(URL(string: "mornye://callback?code=x")!) == nil, "State is required")
        expect(MornyeAuthCallback.parse(URL(string: "mornye://unrelated?state=x&code=y")!) == nil, "Unknown callback route")
        expect(MornyeTrack.isProviderURL("https://open.qobuz.com/track/123"), "Recognize provider links")
        expect(!MornyeTrack.isProviderURL("https://qobuz.com.evil.test/track/123"), "Domain matching must be bounded")
        expect(!MornyeTrack.isProviderURL("https://user:password@tidal.com/track/1"), "Reject URL credentials")

        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let allowed = directory.appendingPathComponent("attempt", isDirectory: true)
        try FileManager.default.createDirectory(at: allowed, withIntermediateDirectories: true)
        let file = allowed.appendingPathComponent("track.flac")
        try Data("fLaC-test".utf8).write(to: file)
        let staged = try MornyeJSON.stagedFile(file.path, directory: allowed)
        expect(staged == file.standardizedFileURL.resolvingSymlinksInPath(), "Keep a regular staged output")
        let outside = directory.appendingPathComponent("outside.flac")
        try Data("outside".utf8).write(to: outside)
        let symlink = allowed.appendingPathComponent("escape.flac")
        try FileManager.default.createSymbolicLink(at: symlink, withDestinationURL: outside)
        do {
            _ = try MornyeJSON.stagedFile(symlink.path, directory: allowed)
            fatalError("A symlink outside staging must be rejected")
        } catch is MornyeError {}
        let redacted = MornyeJSON.redact("failed https://provider.test/callback?token=SECRET grant=HIDDEN")
        expect(!redacted.contains("SECRET") && !redacted.contains("HIDDEN"), "Provider credentials must not reach the UI")
        print("Mornye wire-contract, callback, quality and staging tests passed")
    }
}
