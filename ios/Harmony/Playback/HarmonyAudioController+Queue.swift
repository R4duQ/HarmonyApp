import Foundation

/**
 * The play queue, with the same rules as the Android app.
 *
 * **One order, played as shown.** RANDOM reorders the queue itself; Smart
 * Shuffle appends the brain's picks; the player always plays the queue in
 * the order the queue screen shows.
 *
 * **The queue is stable.** Next, Previous and a tap on a queued song only
 * move within it. A fast skip (under 20%) teaches the brain, which steers
 * the songs it adds later at the end; the songs already queued stay where
 * they are. When fewer than [refillBelow] songs are left ahead, a few picks
 * are appended, up to [queueTarget].
 *
 * Only a new list (an album, a playlist, a song tapped in the library)
 * starts over: in Smart Shuffle the session anchors on the chosen song.
 * Turning shuffle off puts the original running order back; songs queued
 * with Play next or Add to queue are never replaced.
 */
extension HarmonyAudioController {
    static let queueTarget = 12
    static let refillBelow = 10

    // MARK: - Starting a list

    /// Plays [tracks] from [startAt] (a new list).
    func play(tracks: [LibraryTrack], startAt: Int = 0) {
        guard tracks.indices.contains(startAt) else { return }
        if let current = currentEntry, isPlaying { reportOutcome(of: current, fraction: playedFraction, natural: false) }
        queueGeneration += 1
        originalOrder = tracks
        decisions = [:]
        let chosen = tracks[startAt]
        switch shuffleMode {
        case .off:
            queue = tracks.map { makeEntry($0) }
            load(index: startAt, autoplay: true)
        case .random:
            var others = tracks
            others.remove(at: startAt)
            others.shuffle()
            queue = ([chosen] + others).map { makeEntry($0) }
            load(index: 0, autoplay: true)
        case .smart:
            // The session anchors on the chosen song; the brain picks what follows.
            queue = [makeEntry(chosen)]
            load(index: 0, autoplay: true)
            Task {
                let event = await brain.manualSelect(chosen.id)
                lastEvent = event
                topUpIfNeeded()
            }
        }
        saveQueue()
    }

    /// Smart Shuffle over the whole library, starting from [seed] (or a random song).
    func startSmartMix(from seed: LibraryTrack? = nil) {
        let all = store.allTracks()
        guard let first = seed ?? all.randomElement() else { return }
        if shuffleMode != .smart {
            shuffleMode = .smart
            saveModes()
        }
        play(tracks: [first])
    }

    /// Plays [track] right after the current song (after earlier Play next songs).
    func playNext(_ track: LibraryTrack) {
        guard currentEntry != nil else {
            play(tracks: [track])
            return
        }
        var insertAt = currentIndex + 1
        while insertAt < queue.count, queue[insertAt].userQueued { insertAt += 1 }
        queue.insert(makeEntry(track, userQueued: true), at: insertAt)
        if shuffleMode == .smart {
            Task { lastEvent = await brain.manualSelect(track.id) }
        }
        updateNextSong()
        saveQueue()
    }

    /// Adds [track] at the end of the queue.
    func addToQueue(_ track: LibraryTrack) {
        guard currentEntry != nil else {
            play(tracks: [track])
            return
        }
        queue.append(makeEntry(track, userQueued: true))
        updateNextSong()
        saveQueue()
    }

    // MARK: - Moving around (never a queue change)

    /// Next: the song after this one, as queued.
    func skipToNext() {
        guard let current = currentEntry else { return }
        reportOutcome(of: current, fraction: playedFraction, natural: false)
        if currentIndex + 1 < queue.count {
            load(index: currentIndex + 1, autoplay: true)
        } else if repeatMode == .all, !queue.isEmpty {
            load(index: 0, autoplay: true)
        } else {
            pause()
            seek(to: 0)
        }
    }

    /// Previous: back to the start of the song after 3 seconds, else the song before.
    func skipToPrevious() {
        guard currentEntry != nil else { return }
        if position > 3 || currentIndex == 0 {
            seek(to: 0)
            return
        }
        load(index: currentIndex - 1, autoplay: isPlaying)
    }

    /// A tap on a queued song: a seek to it. A song the brain did not pick anchors the vibe on it.
    func playQueueItem(at index: Int) {
        guard queue.indices.contains(index) else { return }
        guard index != currentIndex else {
            seek(to: 0)
            return
        }
        if index > currentIndex, let current = currentEntry {
            reportOutcome(of: current, fraction: playedFraction, natural: false)
        }
        let target = queue[index]
        load(index: index, autoplay: true)
        if shuffleMode == .smart, !target.generated {
            Task { lastEvent = await brain.manualSelect(target.track.id) }
        }
    }

    // MARK: - Editing the queue (the listener's own changes)

    func removeFromQueue(at index: Int) {
        guard queue.indices.contains(index), index != currentIndex else { return }
        queue.remove(at: index)
        if index < currentIndex { currentIndex -= 1 }
        updateNextSong()
        saveQueue()
    }

    /// Moves an upcoming song (absolute indices, both after the current song).
    func moveInQueue(from source: Int, to destination: Int) {
        guard source > currentIndex, destination > currentIndex,
              queue.indices.contains(source), destination <= queue.count else { return }
        let entry = queue.remove(at: source)
        queue.insert(entry, at: min(destination > source ? destination - 1 : destination, queue.count))
        updateNextSong()
        saveQueue()
    }

    func clearUpcoming() {
        guard currentIndex >= 0 else { return }
        queue.removeSubrange((currentIndex + 1)...)
        updateNextSong()
        saveQueue()
    }

    // MARK: - Modes

    /// The shuffle button: off turns Smart Shuffle on; anything else turns shuffle off.
    func toggleShuffle() {
        setShuffleMode(shuffleMode == .off ? .smart : .off)
    }

    func cycleRepeat() {
        switch repeatMode {
        case .off: setRepeatMode(.all)
        case .all: setRepeatMode(.one)
        case .one: setRepeatMode(.off)
        }
    }

    func setRepeatMode(_ mode: RepeatMode) {
        guard mode != repeatMode else { return }
        repeatMode = mode
        saveModes()
        updateNextSong()
        updateNowPlaying()
    }

    /**
     * Mode changes rewrite only the songs after the current one (never the
     * song playing or the history):
     *
     *  - into Smart Shuffle: a fresh session from the song playing; the list
     *    after it makes way for the brain's picks (Play next songs stay);
     *  - into RANDOM: the rest of the original list, shuffled;
     *  - shuffle off: the original running order comes back.
     */
    func setShuffleMode(_ mode: ShuffleMode) {
        let previous = shuffleMode
        guard mode != previous else { return }
        shuffleMode = mode
        saveModes()
        if originalOrder == nil { originalOrder = queue.filter { !$0.generated && !$0.userQueued }.map(\.track) }

        switch mode {
        case .smart:
            queueGeneration += 1
            let kept = upcoming.filter(\.userQueued)
            if currentIndex >= 0 { queue = Array(queue[...currentIndex]) + kept }
            decisions = [:]
            let current = currentTrack?.id
            Task {
                await brain.resetSession()
                if let current { lastEvent = await brain.trackStarted(current) }
                topUpIfNeeded()
            }
        case .random:
            restoreOriginalOrder(shuffled: true)
        case .off:
            restoreOriginalOrder(shuffled: false)
            if previous == .smart { Task { await brain.resetSession() } }
        }
        updateNextSong()
        updateNowPlaying()
        saveQueue()
    }

    /// The original list after the song playing ([shuffled] for RANDOM); the listener's own songs first.
    private func restoreOriginalOrder(shuffled: Bool) {
        guard let current = currentEntry else { return }
        let userSongs = upcoming.filter(\.userQueued)
        decisions = [:]
        guard let original = originalOrder,
              let position = original.firstIndex(where: { $0.id == current.track.id }) else {
            // Another list plays now: only the brain's picks go.
            queue = Array(queue[...currentIndex]) + upcoming.filter { !$0.generated }
            return
        }
        var rest = Array(original[(position + 1)...])
        for song in userSongs {
            if let duplicate = rest.firstIndex(where: { $0.id == song.track.id }) { rest.remove(at: duplicate) }
        }
        if shuffled { rest.shuffle() }
        queue = Array(queue[...currentIndex]) + userSongs + rest.map { makeEntry($0) }
    }

    // MARK: - Smart Shuffle

    /// Appends picks at the end when fewer than [refillBelow] are left ahead. Never reorders.
    func topUpIfNeeded() {
        guard shuffleMode == .smart, brainLoaded, !toppingUp, let current = currentEntry else { return }
        let ahead = queue.count - currentIndex - 1
        guard ahead < Self.refillBelow else { return }
        toppingUp = true
        let generation = queueGeneration
        let prefix = upcoming.map(\.track.id)
        let exclude = Set(queue.map(\.track.id))
        let need = Self.queueTarget - ahead
        Task {
            defer { toppingUp = false }
            let result = await brain.generate(after: current.track.id, prefix: prefix, exclude: exclude, count: need)
            // The list or the mode changed while the brain was thinking.
            guard generation == queueGeneration, shuffleMode == .smart else { return }
            let tracks = result.ids.compactMap { store.track(id: $0) }
            guard !tracks.isEmpty else { return }
            queue += tracks.map { makeEntry($0, generated: true) }
            for decision in result.decisions { decisions[decision.trackId] = decision }
            lastAudit = result.audit
            updateNextSong()
            saveQueue()
        }
    }

    /// The Regenerate button: new picks for the songs the brain added (the next song stays).
    func regenerateSmartQueue() {
        guard shuffleMode == .smart, currentIndex >= 0 else { return }
        let keepThrough = min(currentIndex + 1, queue.count - 1)
        queue = Array(queue[...keepThrough]) + queue.dropFirst(keepThrough + 1).filter { !$0.generated }
        topUpIfNeeded()
    }

    /// Each song start: the brain (Markov habits, S_t seeding), the lock screen, the top-up.
    func songStarted() {
        guard let entry = currentEntry else { return }
        Task {
            let event = await brain.trackStarted(entry.track.id)
            if event.rawValue != 0 { lastEvent = event }
        }
        updateNowPlaying()
        saveQueue()
        topUpIfNeeded()
    }

    var playedFraction: Float {
        guard duration > 0 else { return 0 }
        return Float(max(0, min(1, bridge.positionSeconds / duration)))
    }

    /// A song was left: statistics, and what it teaches the brain (S_t, habits).
    func reportOutcome(of entry: QueueEntry, fraction: Float, natural: Bool) {
        let completed = natural || fraction >= 0.9
        let skipped = !natural && fraction < 0.2
        store.recordPlay(trackId: entry.track.id, completed: completed, skipped: skipped,
                         atMs: Int64(Date().timeIntervalSince1970 * 1000))
        let store = self.store
        Task {
            let (event, transition) = await brain.outcome(entry.track.id, fraction: fraction, shufflePick: entry.generated)
            if let transition { store.addTransition(from: transition.from, to: transition.to, delta: transition.weight) }
            lastEvent = event
        }
    }

    /// Loads the library into the brain (at launch, and after the library changed).
    func reloadBrain() {
        Task {
            let snapshot = await Task.detached(priority: .utility) {
                ShuffleLibrarySnapshot.read(from: HarmonyLibraryStore.shared)
            }.value
            await brain.load(snapshot)
            brainLoaded = true
            topUpIfNeeded()
        }
    }

    /// Debounced: a folder scan changes the library hundreds of times.
    func scheduleBrainReload() {
        brainReloadTask?.cancel()
        brainReloadTask = Task {
            try? await Task.sleep(nanoseconds: 3_000_000_000)
            guard !Task.isCancelled else { return }
            reloadBrain()
        }
    }

    // MARK: - Keeping the queue across launches

    private struct SavedQueue: Codable {
        var trackIds: [Int64]
        var generated: [Bool]
        var userQueued: [Bool]
        var index: Int
        var position: Double
        var original: [Int64]?
    }

    private struct SavedModes: Codable {
        var shuffle: ShuffleMode
        var repeatMode: RepeatMode
    }

    func saveQueue() {
        let saved = SavedQueue(
            trackIds: queue.map(\.track.id),
            generated: queue.map(\.generated),
            userQueued: queue.map(\.userQueued),
            index: currentIndex,
            position: needsLoad ? position : bridge.positionSeconds,
            original: originalOrder?.map(\.id)
        )
        if let data = try? JSONEncoder().encode(saved) {
            UserDefaults.standard.set(data, forKey: Self.queueKey)
        }
    }

    /// The last queue, paused where it was; it opens in the engine on Play.
    func restoreQueue() {
        guard queue.isEmpty,
              let data = UserDefaults.standard.data(forKey: Self.queueKey),
              let saved = try? JSONDecoder().decode(SavedQueue.self, from: data) else { return }
        let byId = Dictionary(store.allTracks().map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        var entries: [QueueEntry] = []
        var index = -1
        for (i, id) in saved.trackIds.enumerated() {
            guard let track = byId[id] else { continue }
            if i == saved.index { index = entries.count }
            entries.append(makeEntry(track,
                                     generated: saved.generated.indices.contains(i) && saved.generated[i],
                                     userQueued: saved.userQueued.indices.contains(i) && saved.userQueued[i]))
        }
        guard index >= 0 else { return }
        queue = entries
        currentIndex = index
        originalOrder = saved.original?.compactMap { byId[$0] }
        position = saved.position
        duration = Double(entries[index].track.durationMs) / 1000
        needsLoad = true
        updateNowPlaying()
    }

    func saveModes() {
        if let data = try? JSONEncoder().encode(SavedModes(shuffle: shuffleMode, repeatMode: repeatMode)) {
            UserDefaults.standard.set(data, forKey: Self.modesKey)
        }
    }

    func restoreModes() {
        guard let data = UserDefaults.standard.data(forKey: Self.modesKey),
              let saved = try? JSONDecoder().decode(SavedModes.self, from: data) else { return }
        shuffleMode = saved.shuffle
        repeatMode = saved.repeatMode
    }
}
