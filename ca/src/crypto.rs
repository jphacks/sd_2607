//! secp256k1 の鍵と署名（attest §1）。
//!
//! - 32 バイトの公開鍵: BIP-340 x-only。署名は BIP-340 Schnorr。
//! - 33 バイトの公開鍵: SEC1 compressed。署名は低 S の compact ECDSA（検証のみ）。
//!
//! 秘密鍵は PKCS#8 PEM、公開鍵は SubjectPublicKeyInfo PEM で保存する。

use anyhow::{anyhow, bail, ensure, Context, Result};
use k256::ecdsa::signature::hazmat::PrehashVerifier;
use k256::pkcs8::{DecodePrivateKey, DecodePublicKey, EncodePrivateKey, EncodePublicKey, LineEnding};
use k256::{schnorr, PublicKey, SecretKey};
use rand_core::{OsRng, RngCore};
use sha2::{Digest, Sha256};
use zeroize::Zeroizing;

pub fn sha256(data: &[u8]) -> [u8; 32] {
    Sha256::digest(data).into()
}

pub fn generate_secret() -> SecretKey {
    SecretKey::random(&mut OsRng)
}

pub fn secret_from_bytes(bytes: &[u8]) -> Result<SecretKey> {
    SecretKey::from_slice(bytes).map_err(|_| anyhow!("Invalid secret key"))
}

/// BIP-340 の x-only 公開鍵（Nostr 鍵）。
pub fn xonly(secret: &SecretKey) -> [u8; 32] {
    schnorr::SigningKey::from(secret.clone()).verifying_key().to_bytes().into()
}

/// SEC1 compressed 公開鍵（atproto・PLC 鍵）。
pub fn compressed(secret: &SecretKey) -> Vec<u8> {
    k256::elliptic_curve::sec1::ToEncodedPoint::to_encoded_point(&secret.public_key(), true).as_bytes().to_vec()
}

pub fn schnorr_sign(digest: &[u8; 32], secret: &SecretKey) -> Result<[u8; 64]> {
    let mut aux = [0u8; 32];
    OsRng.fill_bytes(&mut aux);
    schnorr_sign_with_aux(digest, secret, &aux)
}

pub fn schnorr_sign_with_aux(digest: &[u8; 32], secret: &SecretKey, aux: &[u8; 32]) -> Result<[u8; 64]> {
    let key = schnorr::SigningKey::from(secret.clone());
    let sig = key.sign_raw(digest, aux).map_err(|_| anyhow!("Schnorr signing failed"))?;
    let bytes: [u8; 64] = sig.to_bytes();
    ensure!(verify(&bytes, digest, &key.verifying_key().to_bytes()), "Signature self-check failed");
    Ok(bytes)
}

pub fn validate_public_key(key: &[u8]) -> Result<()> {
    match key.len() {
        32 => {
            schnorr::VerifyingKey::from_bytes(key).map_err(|_| anyhow!("Public key is not on secp256k1"))?;
        }
        33 => {
            ensure!(key[0] == 2 || key[0] == 3, "Compressed SEC1 key required");
            PublicKey::from_sec1_bytes(key).map_err(|_| anyhow!("Public key is not on secp256k1"))?;
        }
        _ => bail!("Public key must be 32 or 33 bytes"),
    }
    Ok(())
}

/// 鍵の長さで方式を決める。長さと方式が一致しない署名、DER 署名、高 S の ECDSA は受理しない。
pub fn verify(sig: &[u8], digest: &[u8; 32], key: &[u8]) -> bool {
    if sig.len() != 64 {
        return false;
    }
    match key.len() {
        32 => {
            let (Ok(vk), Ok(s)) = (schnorr::VerifyingKey::from_bytes(key), schnorr::Signature::try_from(sig)) else { return false };
            vk.verify_raw(digest, &s).is_ok()
        }
        33 => {
            let (Ok(vk), Ok(s)) = (k256::ecdsa::VerifyingKey::from_sec1_bytes(key), k256::ecdsa::Signature::from_slice(sig)) else {
                return false;
            };
            s.normalize_s().is_none() && vk.verify_prehash(digest, &s).is_ok()
        }
        _ => false,
    }
}

// ---- 鍵ファイル -------------------------------------------------------------------------------

pub fn secret_to_pem(secret: &SecretKey) -> Result<Zeroizing<String>> {
    secret.to_pkcs8_pem(LineEnding::LF).map_err(|e| anyhow!("PKCS#8 encoding failed: {e}"))
}

pub fn secret_from_pem(pem: &str) -> Result<SecretKey> {
    SecretKey::from_pkcs8_pem(pem).map_err(|e| anyhow!("Not a secp256k1 PKCS#8 private key: {e}"))
}

/// x-only 鍵は偶数 y の点として SPKI に入れる（BIP-340 の lift_x と同じ点）。
pub fn public_to_pem(key: &[u8]) -> Result<String> {
    let point = match key.len() {
        32 => PublicKey::from_sec1_bytes(&[&[2u8][..], key].concat()),
        33 => PublicKey::from_sec1_bytes(key),
        _ => bail!("Public key must be 32 or 33 bytes"),
    }
    .map_err(|_| anyhow!("Public key is not on secp256k1"))?;
    point.to_public_key_pem(LineEnding::LF).map_err(|e| anyhow!("SPKI encoding failed: {e}"))
}

/// SPKI PEM → 32 バイト x-only 鍵。
pub fn xonly_from_public_pem(pem: &str) -> Result<[u8; 32]> {
    let point = PublicKey::from_public_key_pem(pem.trim()).context("Not a secp256k1 SubjectPublicKeyInfo PEM")?;
    let sec1 = k256::elliptic_curve::sec1::ToEncodedPoint::to_encoded_point(&point, true);
    Ok(sec1.as_bytes()[1..33].try_into()?)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn h(s: &str) -> Vec<u8> {
        hex::decode(s).unwrap()
    }

    /// BIP-340 公式テストベクタ 0, 1
    #[test]
    fn bip340_vectors() {
        let sk = secret_from_bytes(&h("0000000000000000000000000000000000000000000000000000000000000003")).unwrap();
        assert_eq!(hex::encode(xonly(&sk)), "f9308a019258c31049344f85f89d5229b531c845836f99b08601f113bce036f9");
        let sig = schnorr_sign_with_aux(&[0; 32], &sk, &[0; 32]).unwrap();
        assert_eq!(
            hex::encode(sig),
            "e907831f80848d1069a5371b402410364bdf1c5f8307b0084c55f1ce2dca821525f66a4a85ea8b71e482a74f382d2ce5ebeee8fdb2172f477df4900d310536c0"
        );
        let sk = secret_from_bytes(&h("b7e151628aed2a6abf7158809cf4f3c762e7160f38b4da56a784d9045190cfef")).unwrap();
        let msg: [u8; 32] = h("243f6a8885a308d313198a2e03707344a4093822299f31d0082efa98ec4e6c89").try_into().unwrap();
        let mut aux = [0u8; 32];
        aux[31] = 1;
        let sig = schnorr_sign_with_aux(&msg, &sk, &aux).unwrap();
        assert_eq!(
            hex::encode(sig),
            "6896bd60eeae296db48a229ff71dfe071bde413e6d43f917dc8dcf8c78de33418906d11ac976abccb20b091292bff4ea897efcb639ea871cfa95f6de339e4b0a"
        );
        assert!(!verify(&sig, &[0; 32], &xonly(&sk)));
        let mut bad = sig;
        bad[63] ^= 1;
        assert!(!verify(&bad, &msg, &xonly(&sk)));
    }

    #[test]
    fn pem_round_trip() {
        let sk = generate_secret();
        let pem = secret_to_pem(&sk).unwrap();
        assert!(pem.starts_with("-----BEGIN PRIVATE KEY-----"));
        assert_eq!(secret_from_pem(&pem).unwrap().to_bytes(), sk.to_bytes());
        assert_eq!(xonly_from_public_pem(&public_to_pem(&xonly(&sk)).unwrap()).unwrap(), xonly(&sk));
        assert_eq!(compressed(&sk).len(), 33);
    }
}
