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
    /// attest.chain verification of the sender, nil when the post carries no chain.
    var chain: PublicIngress.ChainView? = nil
}

/// mesh.time §2: an unverified peer clock difference, for diagnostics only.
struct ClockSkew: Equatable {
    let peer: String, deltaSeconds: Int64, confidence: Int
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
    /// Author Nostr public keys hidden from this device (mesh §10).
    var blockedPubkeys: [String] = []
    /// Display-only filter for posts past their signed delivery deadline; on by default.
    var hideExpired = true
    /// Last time any post arrived (wall ms) and whether it came online (relay/PDS) rather than over the mesh.
    var lastReceivedAtMillis: Int64? = nil
    var lastReceivedOnline = false
    /// Bluetooth radio usable (powered on and authorized); false shows the 「中継が止まっています」 banner.
    var bluetoothAllowed = true
    /// How many peers took each held event from us this run.
    var forwardedTo: [String: Int] = [:]
    /// Trusted roots from the local config, held revocations, and the last revocation-list check per issuer domain.
    var roots: [Attest.Root] = []
    var revocationCount = 0
    var revocationChecked: [String: Int64] = [:]
    /// Unverified peer clock hints, newest first (mesh.time).
    var clockSkews: [ClockSkew] = []
    /// Posts reported from this device in this run.
    var reported: Set<String> = []
}

/// A locally trusted root key from the trust config.
struct TrustRoot: Identifiable {
    let name: String, pubkey: String, roles: [String], enabled: Bool
    var id: String { pubkey }
}

/// One Bot DID selected for ingress, with the BSAF types selected for it.
struct TrustSource: Identifiable {
    let botDid: String, types: [String]
    var id: String { botDid }
}
