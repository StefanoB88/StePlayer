#if os(iOS)
import Foundation
import Observation

/// Live transfer speed per download (memory only), shown in the Downloads list.
@MainActor @Observable
final class DownloadSpeedStore {
    static let shared = DownloadSpeedStore()
    struct Speed: Equatable { let bytesPerSecond: Int64; let etaSeconds: Int64? }
    private(set) var speeds: [String: Speed] = [:]

    func set(_ id: String, _ speed: Speed) { speeds[id] = speed }
    func clear(_ id: String) { speeds[id] = nil }

    static func format(_ s: Speed) -> String {
        var parts: [String] = []
        let bps = Double(s.bytesPerSecond)
        parts.append(bps >= 1_048_576
            ? String(format: "%.1f MB/s", bps / 1_048_576).replacingOccurrences(of: ".", with: ",")
            : String(format: "%.0f KB/s", bps / 1024))
        if let eta = s.etaSeconds {
            if eta < 60 { parts.append("\(eta) s rimanenti") }
            else if eta < 3600 { parts.append("\((eta + 59) / 60) min rimanenti") }
            else { parts.append(String(format: "%d h %02d min rimanenti", eta / 3600, (eta % 3600) / 60)) }
        }
        return parts.joined(separator: " · ")
    }
}
#endif
