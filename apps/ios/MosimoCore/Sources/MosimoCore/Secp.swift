import Foundation
import libsecp256k1
import Security

/// libsecp256k1 directly: compact low-S ECDSA (RFC 6979 nonces) and BIP-340 Schnorr, as acinq secp256k1-kmp on Android.
public enum Secp {
    nonisolated(unsafe) private static let ctx: OpaquePointer = {
        let c = secp256k1_context_create(UInt32(SECP256K1_CONTEXT_NONE))!
        var seed = randomBytes(32)
        _ = secp256k1_context_randomize(c, &seed)
        return c
    }()

    public static func randomBytes(_ n: Int) -> [UInt8] {
        var b = [UInt8](repeating: 0, count: n)
        precondition(SecRandomCopyBytes(kSecRandomDefault, n, &b) == errSecSuccess)
        return b
    }

    public static func secKeyVerify(_ key: [UInt8]) -> Bool { key.count == 32 && secp256k1_ec_seckey_verify(ctx, key) == 1 }

    public static func generateKey() -> [UInt8] {
        while true { let k = randomBytes(32); if secKeyVerify(k) { return k } }
    }

    private static func pubkey(_ secret: [UInt8]) throws -> secp256k1_pubkey {
        var pub = secp256k1_pubkey()
        try require(secret.count == 32 && secp256k1_ec_pubkey_create(ctx, &pub, secret) == 1, "Invalid secret key")
        return pub
    }

    private static func serialize(_ pub: secp256k1_pubkey) -> [UInt8] {
        var pub = pub
        var out = [UInt8](repeating: 0, count: 33)
        var len = 33
        _ = secp256k1_ec_pubkey_serialize(ctx, &out, &len, &pub, UInt32(SECP256K1_EC_COMPRESSED))
        return out
    }

    /// 33-byte compressed public key.
    public static func compressedPublicKey(_ secret: [UInt8]) throws -> [UInt8] { serialize(try pubkey(secret)) }

    /// 32-byte BIP-340 x-only public key.
    public static func xOnlyPublicKey(_ secret: [UInt8]) throws -> [UInt8] { Array(try compressedPublicKey(secret)[1...]) }

    /// Parses any SEC1 point and returns its compressed form; throws for invalid points.
    public static func compress(_ pub: [UInt8]) throws -> [UInt8] {
        var p = secp256k1_pubkey()
        try require(secp256k1_ec_pubkey_parse(ctx, &p, pub, pub.count) == 1, "Invalid curve point")
        return serialize(p)
    }

    /// 64-byte compact r‖s, always low-S.
    public static func sign(_ hash: [UInt8], _ secret: [UInt8]) throws -> [UInt8] {
        try require(hash.count == 32)
        var sig = secp256k1_ecdsa_signature()
        try require(secp256k1_ecdsa_sign(ctx, &sig, hash, secret, nil, nil) == 1, "ECDSA signing failed")
        var out = [UInt8](repeating: 0, count: 64)
        _ = secp256k1_ecdsa_signature_serialize_compact(ctx, &out, &sig)
        return out
    }

    /// True when [sig] parses as compact r‖s with a high S (i.e. normalization would change it).
    public static func isHighS(_ sig: [UInt8]) -> Bool {
        guard sig.count == 64 else { return true }
        var s = secp256k1_ecdsa_signature()
        guard secp256k1_ecdsa_signature_parse_compact(ctx, &s, sig) == 1 else { return true }
        var normalized = secp256k1_ecdsa_signature()
        return secp256k1_ecdsa_signature_normalize(ctx, &normalized, &s) == 1
    }

    public static func verify(_ sig: [UInt8], _ hash: [UInt8], _ pub: [UInt8]) -> Bool {
        guard sig.count == 64, hash.count == 32 else { return false }
        var s = secp256k1_ecdsa_signature()
        var p = secp256k1_pubkey()
        guard secp256k1_ecdsa_signature_parse_compact(ctx, &s, sig) == 1,
              secp256k1_ec_pubkey_parse(ctx, &p, pub, pub.count) == 1 else { return false }
        return secp256k1_ecdsa_verify(ctx, &s, hash, &p) == 1
    }

    public static func signSchnorr(_ message: [UInt8], _ secret: [UInt8], aux: [UInt8] = randomBytes(32)) throws -> [UInt8] {
        try require(message.count == 32 && aux.count == 32)
        var keypair = secp256k1_keypair()
        try require(secp256k1_keypair_create(ctx, &keypair, secret) == 1, "Invalid secret key")
        var sig = [UInt8](repeating: 0, count: 64)
        try require(secp256k1_schnorrsig_sign32(ctx, &sig, message, &keypair, aux) == 1, "Schnorr signing failed")
        return sig
    }

    public static func verifySchnorr(_ sig: [UInt8], _ message: [UInt8], _ xOnly: [UInt8]) -> Bool {
        guard sig.count == 64, xOnly.count == 32 else { return false }
        var key = secp256k1_xonly_pubkey()
        guard secp256k1_xonly_pubkey_parse(ctx, &key, xOnly) == 1 else { return false }
        return secp256k1_schnorrsig_verify(ctx, sig, message, message.count, &key) == 1
    }
}
