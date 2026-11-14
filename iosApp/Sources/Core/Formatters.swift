import Foundation
import SwiftUI

enum Fmt {
    private static let timeFormatter: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "it_IT")
        f.dateFormat = "HH:mm"
        return f
    }()
    private static let dayFormatter: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "it_IT")
        f.dateStyle = .medium
        f.timeStyle = .none
        return f
    }()
    private static let dayTimeFormatter: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "it_IT")
        f.dateFormat = "EEE d MMM, HH:mm"
        return f
    }()

    static func time(_ ms: Int64) -> String { timeFormatter.string(from: Date(ms: ms)) }
    static func day(_ ms: Int64) -> String { dayFormatter.string(from: Date(ms: ms)) }
    static func dayTime(_ ms: Int64) -> String { dayTimeFormatter.string(from: Date(ms: ms)) }

    /// 1:02:03 or 12:34
    static func clock(_ seconds: Double) -> String {
        guard seconds.isFinite, seconds >= 0 else { return "--:--" }
        let s = Int(seconds)
        let h = s / 3600, m = (s % 3600) / 60, sec = s % 60
        return h > 0 ? String(format: "%d:%02d:%02d", h, m, sec) : String(format: "%02d:%02d", m, sec)
    }

    /// "1 h 42 min"
    static func duration(seconds: Int) -> String {
        let h = seconds / 3600, m = (seconds % 3600) / 60
        if h > 0 { return m > 0 ? "\(h) h \(m) min" : "\(h) h" }
        return "\(max(m, 1)) min"
    }

    /// Runtime of a movie / episode: "42 min", "< 1 min" for a few seconds, nil when 0 or unknown.
    static func minutes(seconds: Int?) -> String? {
        guard let seconds, seconds > 0 else { return nil }
        return seconds < 60 ? "< 1 min" : "\(seconds / 60) min"
    }

    static func bytes(_ value: Int64) -> String {
        ByteCountFormatter.string(fromByteCount: value, countStyle: .file)
    }
}

extension Date {
    init(ms: Int64) { self.init(timeIntervalSince1970: TimeInterval(ms) / 1000) }
}

extension Fmt {
    private static let weekdayFormatter: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "it_IT")
        f.dateFormat = "EEE"
        return f
    }()
    private static let shortDateFormatter: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "it_IT")
        f.dateFormat = "d MMM"
        return f
    }()

    /// "Oggi 20:45", "Domani 15:00", "Ieri 21:00", "sab 21:00" (within a week), else "12 nov 21:00".
    static func relativeDayTime(_ ms: Int64) -> String {
        let date = Date(ms: ms)
        let cal = Calendar.current
        let time = Fmt.time(ms)
        if cal.isDateInToday(date) { return "Oggi \(time)" }
        if cal.isDateInTomorrow(date) { return "Domani \(time)" }
        if cal.isDateInYesterday(date) { return "Ieri \(time)" }
        let days = abs(cal.dateComponents([.day], from: cal.startOfDay(for: Date()), to: cal.startOfDay(for: date)).day ?? 99)
        if days < 7 { return "\(weekdayFormatter.string(from: date)) \(time)" }
        return "\(shortDateFormatter.string(from: date)) \(time)"
    }
}

/// Bolds the word prefixes that match the search stems (diacritic- and case-insensitive).
enum Highlight {
    static func text(_ string: String, terms: [String], base: Font = .body) -> Text {
        guard !terms.isEmpty else { return Text(string) }
        var attributed = AttributedString(string)
        let folded = string.folding(options: [.diacriticInsensitive, .caseInsensitive], locale: Locale(identifier: "it_IT"))
        // Folding keeps a 1:1 character mapping for Latin text; bail out if it doesn't.
        guard folded.count == string.count else { return Text(string) }
        let chars = Array(folded)
        var i = 0
        while i < chars.count {
            guard chars[i].isLetter || chars[i].isNumber else { i += 1; continue }
            var j = i
            while j < chars.count, chars[j].isLetter || chars[j].isNumber { j += 1 }
            let word = String(chars[i..<j])
            if let term = terms.filter({ !$0.isEmpty && word.hasPrefix($0) }).max(by: { $0.count < $1.count }) {
                let start = attributed.index(attributed.startIndex, offsetByCharacters: i)
                let end = attributed.index(start, offsetByCharacters: term.count)
                attributed[start..<end].inlinePresentationIntent = .stronglyEmphasized
                attributed[start..<end].foregroundColor = Theme.textPrimary
            }
            i = j
        }
        return Text(attributed)
    }
}
