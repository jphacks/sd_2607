import Foundation
import MosimoCore

/// UI snapshots contain public information only, never the private author or key set.
struct Neighbor: Identifiable, Equatable {
    let endpoint: String, name: String, status: String
    var id: String { endpoint }
}

struct PostView: Identifiable, Equatable {
    let id: String
    let pubkey: String
    let content: String
    let hop: Int?
    let relayAccepted: Bool
    var sourceRelay: String? = nil
    var volatile = false
    var deliveryDeadline: DeliveryDeadline = .unknown
    var acceptedBy: [String] = []
    var publicAuthorization: String? = nil
    var did: String? = nil
    /// Nostr created_at (seconds) and decoded known extensions.
    var createdAt: Int64 = 0
    var ext = PostExtensions()
}

struct MeshState: Equatable {
    var ready = false, running = false
    var did = "", publicKey = ""
    var relays: [String] = []
    var registration = "PLC 未登録"
    var neighbors: [Neighbor] = []
    var posts: [PostView] = []
    var diagnostics: [String] = []
    var error: String? = nil
    var lastPublishedId: String? = nil
    var publishAttempt: Int64 = 0
    var clockConfidence: ClockConfidence = .unknown
    var onlineEnabled = false, onlineActive = false
    /// Send status: default network present, relay socket in use, unsent count per relay, seconds until retry.
    var network = false
    var sendingRelay: String? = nil
    var queued: [String: Int] = [:]
    var retryInSeconds: Int64 = 0
    var pdsConfigured = true
    var publicBusy = false
    var publicStatus = "公的情報: trust config 未読込"
    var publicResults: [String] = []
    var blockedDids: [String] = []
    /// Display-only filter for posts past their signed delivery deadline; on by default.
    var hideExpired = true
    /// Last time any post arrived (wall ms) and whether it came online (relay/PDS) rather than over the mesh.
    var lastReceivedAtMillis: Int64? = nil
    var lastReceivedOnline = false
    /// Bluetooth radio usable (powered on and authorized); false shows the 「中継が止まっています」 banner.
    var bluetoothAllowed = true
}

/// One trusted Bot DID under an enabled root, with the BSAF types selected for it.
struct TrustSource: Identifiable {
    let org: String, botDid: String, types: [String]
    var id: String { botDid }
}
