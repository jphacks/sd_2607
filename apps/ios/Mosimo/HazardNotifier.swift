import CoreLocation
import MosimoCore
import UserNotifications

/// Local notifications for important hazard information from others (HazardAlerts decides which). Fed with every
/// post snapshot the coordinator builds, so mesh, LAN and relay arrivals alike are seen, also while running in the
/// background for Bluetooth.
@MainActor
final class HazardNotifier: NSObject, ObservableObject, UNUserNotificationCenterDelegate {
    static let shared = HazardNotifier()
    /// The genres switched on in settings; persisted per genre, all on by default.
    @Published private(set) var genres: Set<HazardAlerts.Genre>
    @Published private(set) var status: UNAuthorizationStatus = .notDetermined
    /// The post a tapped notification asks to open; the root view consumes it.
    @Published var opened: String?

    /// The earlier single switch; when it was saved off, every genre starts off.
    private static let legacyKey = "hazardAlerts"
    private static func key(_ g: HazardAlerts.Genre) -> String { "hazardAlerts.\(g.rawValue)" }
    private static let notifiedKey = "hazardAlertsNotified"
    /// A fix older than this no longer tells where the device is.
    private static let fixMaxAge: TimeInterval = 24 * 3600
    private let defaults = UserDefaults.standard
    private let center = UNUserNotificationCenter.current()
    /// Only for the system's last fix (works in the background without new requests); the app's own fixes go there too.
    private let locations = CLLocationManager()
    /// Notified ids to their created_at, kept for the six hours they could come up again.
    private var notified: [String: Int64]
    private var batch: HazardAlerts.Batch?
    /// The shown notification's text, kept while later alerts are folded into it.
    private var batchText: (title: String, body: String)?
    private var lastPosts = [PostView](), lastMe: String?

    private override init() {
        let d = UserDefaults.standard
        let fallback = d.object(forKey: Self.legacyKey) as? Bool ?? true
        genres = Set(HazardAlerts.Genre.allCases.filter { d.object(forKey: Self.key($0)) as? Bool ?? fallback })
        notified = (d.dictionary(forKey: Self.notifiedKey) as? [String: Int64]) ?? [:]
        super.init()
        center.delegate = self
        refreshStatus()
    }

    var granted: Bool { [.authorized, .provisional, .ephemeral].contains(status) }
    var anyOn: Bool { !genres.isEmpty }

    func set(_ genre: HazardAlerts.Genre, _ on: Bool) {
        if on { genres.insert(genre) } else { genres.remove(genre) }
        defaults.set(on, forKey: Self.key(genre))
        if on { requestPermission(); check(lastPosts, me: lastMe) }
    }

    /// Asks once (alert, sound, badge) while some switch is on; later calls only re-read the status.
    func requestPermission() {
        guard anyOn, status == .notDetermined else { refreshStatus(); return }
        center.requestAuthorization(options: [.alert, .sound, .badge]) { _, _ in
            Task { @MainActor in self.refreshStatus() }
        }
    }

    /// Re-read on return to the foreground: the user may have changed it in the Settings app.
    func refreshStatus() {
        center.getNotificationSettings { s in
            let status = s.authorizationStatus
            Task { @MainActor in
                let gained = !self.granted && [.authorized, .provisional, .ephemeral].contains(status)
                self.status = status
                if gained { self.check(self.lastPosts, me: self.lastMe) }
            }
        }
    }

    /// Notifies the posts of [posts] that are due; folded into the shown notification within 30 s of its first alert.
    func check(_ posts: [PostView], me: String?) {
        lastPosts = posts; lastMe = me
        let now = MeshCoordinator.now()
        let due = HazardAlerts.due(posts.map { HazardAlerts.Candidate(id: $0.id, pubkey: $0.pubkey, createdAt: $0.createdAt, official: $0.officialHazard,
                                                                      danger: $0.category == .danger, ext: $0.ext) },
                                   me: me, here: here(), nowSeconds: now / 1000, notified: Set(notified.keys))
        guard !due.isEmpty else { return }
        // Switched-off genres and a refused permission are recorded without showing; while the permission is still being
        // read, switched-on genres are left for later.
        let (show, record) = HazardAlerts.split(due, on: genres, permission: granted ? .granted : status == .notDetermined ? .unknown : .refused)
        guard !record.isEmpty else { return }
        for a in record { notified[a.post.id] = a.post.createdAt }
        notified = HazardAlerts.prune(notified, nowSeconds: now / 1000)
        defaults.set(notified, forKey: Self.notifiedKey)
        guard !show.isEmpty else { return }

        let next = HazardAlerts.fold(batch, show.map(\.post.id), nowMillis: now)
        if next.id != batch?.id, let primary = posts.first(where: { $0.id == next.primary }) {
            // A user's danger report says so in the title: it is not verified.
            let title = show.first?.genre == .nearbyDanger ? "近くの危険情報（利用者の投稿）" : primary.ext.hazard.map(HazardLabels.title) ?? "災害情報"
            batchText = (title, HazardAlerts.excerpt(primary.body))
        }
        guard let text = batchText else { return }
        let content = UNMutableNotificationContent()
        content.title = text.title
        content.body = [text.body.nilIfEmpty, next.others > 0 ? "ほか\(next.others)件" : nil].compactMap { $0 }.joined(separator: "\n")
        // A fold replaces the shown notification quietly; only a new one sounds.
        if next.id != batch?.id { content.sound = .default }
        content.threadIdentifier = "hazard"
        content.userInfo = ["post": next.primary]
        batch = next
        center.add(UNNotificationRequest(identifier: "hazard.\(next.id)", content: content, trigger: nil))
    }

    /// The last fix, when location is allowed and the fix is recent.
    private func here() -> GeoPoint? {
        guard let fix = locations.location, -fix.timestamp.timeIntervalSinceNow <= Self.fixMaxAge else { return nil }
        return GeoPoint(lat: fix.coordinate.latitude, lng: fix.coordinate.longitude)
    }

    // Shown as a banner in the foreground too.
    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification) async -> UNNotificationPresentationOptions {
        [.banner, .list, .sound]
    }

    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse) async {
        guard let id = response.notification.request.content.userInfo["post"] as? String else { return }
        await MainActor.run { self.opened = id }
    }
}
