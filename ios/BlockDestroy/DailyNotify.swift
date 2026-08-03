import UserNotifications

/// デイリーボーナス通知(ネイティブ)。Android 版の DailyNotify.java に相当。
/// iOS の UNCalendarNotificationTrigger(repeats:true) は端末再起動後も
/// 自前で再登録しなくても発火し続けるため、BootReceiver 相当の処理は不要。
final class DailyNotify: NSObject, UNUserNotificationCenterDelegate {

    static let shared = DailyNotify()

    private static let dailyIdentifier = "daily_bonus"
    private static let eventIdentifier = "event"

    private override init() { super.init() }

    func checkPermission(_ completion: @escaping (Bool) -> Void) {
        UNUserNotificationCenter.current().getNotificationSettings { settings in
            let granted = settings.authorizationStatus == .authorized || settings.authorizationStatus == .provisional
            DispatchQueue.main.async { completion(granted) }
        }
    }

    func requestPermission(_ completion: @escaping (Bool) -> Void) {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { granted, _ in
            DispatchQueue.main.async { completion(granted) }
        }
    }

    func scheduleDaily(hour: Int, minute: Int) {
        let center = UNUserNotificationCenter.current()
        center.removePendingNotificationRequests(withIdentifiers: [Self.dailyIdentifier])

        let content = UNMutableNotificationContent()
        content.title = "🎁 Block Destroy"
        content.body = "今日もデイリー報酬を受け取ろう!"
        content.sound = .default

        var dateComponents = DateComponents()
        dateComponents.hour = hour
        dateComponents.minute = minute
        let trigger = UNCalendarNotificationTrigger(dateMatching: dateComponents, repeats: true)

        let request = UNNotificationRequest(identifier: Self.dailyIdentifier, content: content, trigger: trigger)
        center.add(request)
    }

    func cancel() {
        UNUserNotificationCenter.current().removePendingNotificationRequests(withIdentifiers: [Self.dailyIdentifier])
    }

    func notifyNow(title: String, body: String) {
        let content = UNMutableNotificationContent()
        content.title = title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? "🎁 Block Destroy" : title
        content.body = body.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? "今日もデイリー報酬を受け取ろう!" : body
        content.sound = .default

        let request = UNNotificationRequest(identifier: Self.eventIdentifier + "_\(Date().timeIntervalSince1970)", content: content, trigger: nil)
        UNUserNotificationCenter.current().add(request)
    }

    // アプリがフォアグラウンドでも通知バナーを表示する
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        completionHandler([.banner, .sound, .list])
    }
}
