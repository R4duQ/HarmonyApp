// Native FFmpeg command pump adapted from Mornye v5.2.5 (MIT).
// Copyright (c) 2026 zarzet. See Resources/Licenses/Mornye-MIT.txt.
import Foundation
import Darwin
import ffmpegkit

struct CoreFFmpegCommand: Decodable {
    let command_id: String
    let arguments: [String]
    var output_path: String? = nil
}

final class CoreFFmpegExecution {
    let wait: (Int64) throws -> [CoreFFmpegCommand]
    let active: (String) throws -> Bool
    let complete: (String, Bool, String, String) throws -> Void
    let close: () -> Void
    private let lock = NSLock()
    private var running = true

    init(wait: @escaping (Int64) throws -> [CoreFFmpegCommand], active: @escaping (String) throws -> Bool,
         complete: @escaping (String, Bool, String, String) throws -> Void, close: @escaping () -> Void = {}) {
        self.wait = wait
        self.active = active
        self.complete = complete
        self.close = close
    }

    func run(execute: @escaping ([String], @escaping () -> Bool) -> (Bool, String) = executeCoreFFmpeg,
             block: () throws -> String) rethrows -> String {
        DispatchQueue.global(qos: .userInitiated).async {
            defer { self.close() }
            while self.isRunning() {
                guard let commands = try? self.wait(1_000) else { return }
                // A different operation may own any command in this claimed batch.
                for command in commands {
                    let cancelled = { (try? self.active(command.command_id)) != true }
                    let result: (Bool, String)
                    if cancelled() { result = (false, "cancelled") }
                    else if command.arguments.isEmpty { result = (false, "FFmpeg arguments are empty") }
                    else { result = executeCoreFFmpegCommand(command: command, cancelled: cancelled, execute: execute) }
                    try? self.complete(command.command_id, result.0, result.1, result.0 ? "" : result.1)
                }
            }
        }
        defer {
            lock.lock()
            running = false
            lock.unlock()
        }
        return try block()
    }

    private func isRunning() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return running
    }
}

private enum CoreFFmpegStaging {
    static let lock = NSLock()
    static var directories = Set<String>()
    static let prefix = ".mornye-ffmpeg-\(Bundle.main.bundleIdentifier ?? "mornye")-"

    static func create(for target: URL) throws -> URL {
        let directory = target.deletingLastPathComponent().resolvingSymlinksInPath()
        lock.lock()
        defer { lock.unlock() }
        // One app process owns these stages. Sweep before its first command
        // in this directory; subsequent/concurrent commands may own live files.
        if !directories.contains(directory.path) {
            for file in try FileManager.default.contentsOfDirectory(
                at: directory, includingPropertiesForKeys: [.isRegularFileKey, .isSymbolicLinkKey]
            ) where file.lastPathComponent.hasPrefix(prefix) {
                let suffix = file.lastPathComponent.dropFirst(prefix.count)
                let token = String(suffix.prefix(36))
                let values = try file.resourceValues(forKeys: [.isRegularFileKey, .isSymbolicLinkKey])
                if UUID(uuidString: token) != nil, suffix.dropFirst(36).hasPrefix("."),
                   values.isRegularFile == true, values.isSymbolicLink != true {
                    try FileManager.default.removeItem(at: file)
                }
            }
            directories.insert(directory.path)
        }
        let staged = directory.appendingPathComponent("\(prefix)\(UUID().uuidString).\(target.pathExtension)")
        try Data().write(to: staged, options: .withoutOverwriting)
        return staged
    }
}

func executeCoreFFmpegCommand(command: CoreFFmpegCommand, cancelled: @escaping () -> Bool,
                              execute: ([String], @escaping () -> Bool) -> (Bool, String)) -> (Bool, String) {
    if cancelled() { return (false, "cancelled") }
    guard let output = command.output_path, !output.isEmpty else {
        return execute(command.arguments, cancelled)
    }
    guard command.arguments.last == output else { return (false, "FFmpeg output does not match command") }
    let target = URL(fileURLWithPath: output)
    do {
        let staged = try CoreFFmpegStaging.create(for: target)
        defer { try? FileManager.default.removeItem(at: staged) }
        var arguments = command.arguments
        arguments[arguments.count - 1] = staged.path
        let result = execute(arguments, cancelled)
        if cancelled() { return (false, "cancelled") }
        if !result.0 { return result }
        // POSIX rename replaces the destination atomically on the same filesystem.
        guard rename(staged.path, target.path) == 0 else {
            throw NSError(domain: NSPOSIXErrorDomain, code: Int(errno))
        }
        return result
    } catch {
        return (false, error.localizedDescription)
    }
}

private func executeCoreFFmpeg(arguments: [String], cancelled: @escaping () -> Bool) -> (Bool, String) {
    if cancelled() { return (false, "cancelled") }
    let finished = DispatchSemaphore(value: 0)
    guard let session = FFmpegKit.execute(withArgumentsAsync: arguments, withCompleteCallback: { _ in finished.signal() }) else {
        return (false, "FFmpeg session could not start")
    }
    while finished.wait(timeout: .now() + 0.2) == .timedOut {
        if cancelled() {
            session.cancel()
            _ = finished.wait(timeout: .now() + 5)
            return (false, "cancelled")
        }
    }
    if cancelled() { return (false, "cancelled") }
    return (ReturnCode.isSuccess(session.getReturnCode()), session.getOutput() ?? "")
}

enum MornyeAudioFile {
    /// Probe actual bytes before lossless finalization. Never relabel AAC/MP3
    /// as FLAC, and retain the original rate and depth when converting ALAC.
    static func losslessFile(_ input: URL, directory: URL, isCancelled: @escaping () -> Bool) throws -> URL {
        if isCancelled() { throw CancellationError() }
        guard let probe = FFprobeKit.execute(withArguments: [
            "-v", "error", "-select_streams", "a:0", "-show_entries", "stream=codec_name",
            "-of", "json", input.path
        ]), ReturnCode.isSuccess(probe.getReturnCode()),
           let root = try? MornyeJSON.object(probe.getOutput() ?? ""),
           let stream = (root["streams"] as? [[String: Any]])?.first else {
            throw MornyeError.message("The downloaded file could not be verified as audio.")
        }
        let codec = MornyeJSON.text(stream["codec_name"]).lowercased()
        guard codec == "flac" || codec == "alac" else {
            throw MornyeError.message("The provider returned \(codec.isEmpty ? "unverified audio" : codec.uppercased()), not lossless FLAC/ALAC.")
        }
        if HarmonyTranscoder.sniffContainer(atPath: input.path) == "flac" { return input }
        let output = directory.appendingPathComponent("final-\(UUID().uuidString).flac")
        let result = executeCoreFFmpeg(arguments: ["-nostdin", "-y", "-i", input.path, "-map", "0:a:0",
                                                  "-vn", "-c:a", "flac", output.path], cancelled: isCancelled)
        guard result.0, HarmonyTranscoder.sniffContainer(atPath: output.path) == "flac" else {
            try? FileManager.default.removeItem(at: output)
            if isCancelled() { throw CancellationError() }
            throw MornyeError.message("The lossless file could not be finalized: \(result.1)")
        }
        return output
    }
}
