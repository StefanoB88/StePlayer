import Foundation
import Shared
#if os(iOS)
import UserNotifications
#endif

/// Local notifications 3 days and 1 day before the provider-reported expiry date.
/// iOS only; on tvOS the Home banner is the only reminder.
enum ExpiryReminderScheduler {
    static let prefix = "expiry."

    @MainActor
    static func reschedule(core: StePlayerCore, sources: [Source]) async {
        #if os(iOS)
        let center = UNUserNotificationCenter.current()
        let pending = await center.pendingNotificationRequests()
        let stale = pending.map(\.identifier).filter { $0.hasPrefix(prefix) }
        center.removePendingNotificationRequests(withIdentifiers: stale)

        let reminders = (try? await core.reminders.pendingReminders(localUtcOffsetSec: Int64(TimeZone.current.secondsFromGMT()))) ?? []
        // Ask for permission only once a list actually has an expiry date (never at first launch).
        guard !reminders.isEmpty, sources.contains(where: { $0.expiresAt != nil }) else { return }
        let settings = await center.notificationSettings()
        if settings.authorizationStatus == .notDetermined {
            guard (try? await center.requestAuthorization(options: [.alert, .sound])) == true else { return }
        } else if settings.authorizationStatus == .denied {
            return
        }
        let now = Int64.nowMs
        for r in reminders where r.fireAtMs > now + 1000 {
            let content = UNMutableNotificationContent()
            content.title = r.title
            content.body = r.body
            content.sound = .default
            let trigger = UNTimeIntervalNotificationTrigger(timeInterval: TimeInterval(r.fireAtMs - now) / 1000, repeats: false)
            try? await center.add(UNNotificationRequest(identifier: r.id, content: content, trigger: trigger))
        }
        #endif
    }
}
