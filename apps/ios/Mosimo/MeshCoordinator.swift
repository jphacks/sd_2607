import Foundation
import MosimoCore

/// SDK entry point, the single serialized owner of mesh, relay and disk state (main actor).
@MainActor
final class MeshCoordinator: ObservableObject {
    static let shared = MeshCoordinator()
    @Published private(set) var state = MeshState()

    /// User switch 「オフライン中継を有効にする」; persisted, resumes on launch.
    var relayEnabled: Bool {
        get { UserDefaults.standard.object(forKey: "meshEnabled") as? Bool ?? true }
        set { UserDefaults.standard.set(newValue, forKey: "meshEnabled") }
    }

    func publish(_ text: String, extensions: [[String: Cbor]] = [], deliveryExpiresAtSeconds: Int64? = nil) {}
    func addRelay(_ endpoint: String) {}
    func removeRelay(_ endpoint: String) {}
    func block(_ did: String) {}
    func unblock(_ did: String) {}
    func requestPublicInformation() {}
    func setOnlineForeground(_ visible: Bool) {}
    func setOnlineEnabled(_ enabled: Bool) {}
    func setClockConfidence(_ value: ClockConfidence) {}
    func start() {}
    func stop() {}
    func clearError() {}
    func reportError(_ text: String) {}
    func trustSources() -> [TrustSource] { [] }
}
