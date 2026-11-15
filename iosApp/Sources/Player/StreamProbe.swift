import Foundation

/// Looks at the first bytes of a VOD stream to learn what it REALLY is.
///
/// Streaming servers often label files ".mp4" that are actually MKV/AVI, or answer with an HTML/JSON
/// error page (expired account, too many connections) instead of the video. AVPlayer then fails
/// with a generic "format not supported". Probing first lets us route to the right engine and
/// show the real reason. The probe request asks for 4 KB only and is closed immediately, so it
/// never keeps a provider connection busy.
enum StreamProbe {
    enum Kind: Equatable {
        case mp4          // MP4 / MOV / M4V (ISO BMFF)
        case matroska     // MKV / WebM
        case avi
        case mpegTS
        case flv
        case hls          // HLS manifest text
        case serverError(httpStatus: Int?, snippet: String)  // HTML/JSON/text instead of media
        case unknown
    }

    struct Result: Equatable {
        let kind: Kind
        let httpStatus: Int?
    }

    /// Container hint that matches the bytes (nil when the probe can't tell).
    static func container(for kind: Kind) -> String? {
        switch kind {
        case .mp4: return "mp4"
        case .matroska: return "mkv"
        case .avi: return "avi"
        case .mpegTS: return "ts"
        case .flv: return "flv"
        case .hls: return "m3u8"
        default: return nil
        }
    }

    static func probe(url: URL, headers: [String: String], timeout: TimeInterval = 8) async -> Result? {
        guard url.scheme == "http" || url.scheme == "https" else { return nil }
        var request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: timeout)
        headers.forEach { request.setValue($0.value, forHTTPHeaderField: $0.key) }
        request.setValue("bytes=0-4095", forHTTPHeaderField: "Range")
        request.setValue("identity", forHTTPHeaderField: "Accept-Encoding")

        let config = URLSessionConfiguration.ephemeral
        config.httpMaximumConnectionsPerHost = 1
        config.timeoutIntervalForRequest = timeout
        let session = URLSession(configuration: config)
        defer { session.invalidateAndCancel() } // close the socket right away

        do {
            let (bytes, response) = try await session.bytes(for: request)
            let status = (response as? HTTPURLResponse)?.statusCode
            var data = Data()
            for try await byte in bytes {
                data.append(byte)
                if data.count >= 4096 { break }
            }
            if let status, status >= 400 {
                return Result(kind: .serverError(httpStatus: status, snippet: snippet(data)), httpStatus: status)
            }
            return Result(kind: classify(data), httpStatus: status)
        } catch {
            return nil // network problems are handled by the player's normal error path
        }
    }

    /// Same classification for a downloaded file (the extension saved with it can be wrong too).
    static func probeFile(path: String) -> Result? {
        guard let h = FileHandle(forReadingAtPath: path) else { return nil }
        defer { try? h.close() }
        guard let data = try? h.read(upToCount: 4096), !data.isEmpty else { return nil }
        return Result(kind: classify(data), httpStatus: nil)
    }

    static func classify(_ d: Data) -> Kind {
        let b = [UInt8](d.prefix(4096))
        guard b.count >= 4 else { return .unknown }
        func ascii(_ from: Int, _ len: Int) -> String {
            guard b.count >= from + len else { return "" }
            return String(bytes: b[from..<(from + len)], encoding: .ascii) ?? ""
        }
        // ISO BMFF, also fragmented MP4 / CMAF ("styp", "sidx", "moof") and progressive-download hints ("pdin").
        if b.count >= 12, ["ftyp", "moov", "mdat", "free", "skip", "wide", "styp", "sidx", "moof", "pdin"].contains(ascii(4, 4)) {
            return .mp4
        }
        if b[0] == 0x1A, b[1] == 0x45, b[2] == 0xDF, b[3] == 0xA3 { return .matroska }
        if ascii(0, 4) == "RIFF", ascii(8, 3) == "AVI" { return .avi }
        if ascii(0, 3) == "FLV" { return .flv }
        if b[0] == 0x47, b.count > 188, b[188] == 0x47 { return .mpegTS }
        if b.count > 196, b[4] == 0x47, b[196] == 0x47 { return .mpegTS } // M2TS (192-byte packets)
        let text = String(decoding: b.prefix(512), as: UTF8.self).trimmingCharacters(in: .whitespacesAndNewlines)
        if text.hasPrefix("#EXTM3U") { return .hls }
        let lower = text.lowercased()
        if lower.hasPrefix("<") || lower.hasPrefix("{") || lower.hasPrefix("[") || lower.contains("<html") {
            return .serverError(httpStatus: nil, snippet: snippet(d))
        }
        return .unknown
    }

    private static func snippet(_ d: Data) -> String {
        String(decoding: d.prefix(160), as: UTF8.self)
            .replacingOccurrences(of: "\n", with: " ")
            .trimmingCharacters(in: .whitespaces)
    }
}
