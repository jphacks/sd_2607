//! mosimo-ca の一連の操作をバイナリで確認する。

use age::secrecy::ExposeSecret;
use std::os::unix::fs::PermissionsExt;
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};

struct Env {
    dir: PathBuf,
}

impl Env {
    fn new(name: &str) -> Env {
        let dir = std::env::temp_dir().join(format!("mosimo-ca-{name}-{}-{}", std::process::id(), rand_suffix()));
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::set_permissions(&dir, std::fs::Permissions::from_mode(0o700)).unwrap();
        Env { dir }
    }

    fn keys(&self) -> PathBuf {
        self.dir.join("keys")
    }

    fn run_in(&self, keys: &Path, args: &[&str], extra_env: &[(&str, &str)]) -> (i32, String, String) {
        let mut cmd = Command::new(env!("CARGO_BIN_EXE_mosimo-ca"));
        cmd.arg("--keys").arg(keys).args(args).stdin(Stdio::null()).env_remove("MOSIMO_CA_AGE_IDENTITY");
        for (k, v) in extra_env {
            cmd.env(k, v);
        }
        let out = cmd.output().unwrap();
        (out.status.code().unwrap_or(-1), String::from_utf8_lossy(&out.stdout).into(), String::from_utf8_lossy(&out.stderr).into())
    }

    fn ok(&self, args: &[&str]) -> String {
        self.ok_in(&self.keys(), args)
    }

    fn ok_in(&self, keys: &Path, args: &[&str]) -> String {
        let (code, out, err) = self.run_in(keys, args, &[]);
        assert_eq!(code, 0, "{args:?}\n{out}\n{err}");
        out
    }

    fn ng(&self, pattern: &str, args: &[&str]) {
        let (code, out, err) = self.run_in(&self.keys(), args, &[]);
        assert_eq!(code, 1, "{args:?} should fail\n{out}\n{err}");
        assert!(err.contains(pattern), "{args:?}: expected {pattern:?} in\n{err}");
    }

    fn pubkey(&self, key: &str) -> String {
        let out = self.ok(&["show", key]);
        out.lines().find_map(|l| l.strip_prefix("公開鍵: ")).unwrap().to_string()
    }

    /// root → import（認証局）→ oriom（運営者）→ node-1（末端）
    fn build(&self) {
        self.ok(&["init-root", "oriom-root", "--display-name", "test root", "--plaintext"]);
        self.ok(&[
            "issue",
            "oriom-root",
            "import",
            "--max-depth",
            "2",
            "--domain",
            "import.example.org",
            "--owner-name",
            "CA",
            "--preset",
            "jma",
            "--preset",
            "kikikuru",
            "--plaintext",
            "--yes",
        ]);
        self.ok(&[
            "issue",
            "oriom-root/import",
            "oriom",
            "--owner-name",
            "Oriom",
            "--org-id",
            "oriom.dev:importer",
            "--org-name",
            "Oriom importer",
            "--days",
            "365",
            "--plaintext",
            "--yes",
        ]);
        self.ok(&["issue", "oriom-root/import/oriom", "node-1", "--leaf", "--owner-name", "node 1", "--preset", "jma", "--days", "30", "--yes"]);
    }
}

impl Drop for Env {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.dir);
    }
}

fn rand_suffix() -> u64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().subsec_nanos() as u64
}

const NODE: &str = "oriom-root/import/oriom/node-1";
const OPERATOR: &str = "oriom-root/import/oriom";

#[test]
fn issue_hierarchy() {
    let e = Env::new("hierarchy");
    e.build();
    let tree = e.ok(&["tree"]);
    assert!(tree.contains("oriom-root") && tree.contains("maxDepth=16") && tree.contains("      node-1"), "{tree}");
    e.ok(&["verify"]);
    let anchor = e.ok(&["trust-export", "oriom-root"]);
    assert!(anchor.contains("\"maxDepth\": 16"), "{anchor}");
    let show = e.ok(&["show", NODE]);
    assert!(show.contains("stratum: 3") && show.contains("oriom.dev:importer") && show.contains("\"import\""), "{show}");
    for entry in walk(&e.keys()) {
        let mode = std::fs::metadata(&entry).unwrap().permissions().mode();
        assert_eq!(mode & 0o077, 0, "{}", entry.display());
    }
    // 公開鍵の先頭でも指定できる
    let key = e.pubkey(NODE);
    assert!(e.ok(&["show", &key[..10]]).contains(NODE));
}

#[test]
fn issue_refuses_escalation() {
    let e = Env::new("escalation");
    e.build();
    e.ng(
        "attest を与えません",
        &["issue", OPERATOR, "bad", "--leaf", "--roles", "attest,import", "--owner-name", "x", "--preset", "jma", "--days", "1", "--yes"],
    );
    e.ng("親の期限", &["issue", OPERATOR, "bad", "--leaf", "--owner-name", "x", "--preset", "jma", "--days", "400", "--yes"]);
    e.ng(
        "転載範囲を超えて",
        &["issue", OPERATOR, "bad", "--leaf", "--owner-name", "x", "--source", "did:plc:aaaaaaaaaaaaaaaaaaaaaaaa=earthquake", "--days", "1", "--yes"],
    );
    e.ng("organization", &["issue", OPERATOR, "bad", "--leaf", "--owner-name", "x", "--org-id", "other:x", "--preset", "jma", "--days", "1", "--yes"]);
    e.ng("使っています", &["issue", "oriom-root", "dup", "--domain", "import.example.org", "--owner-name", "x", "--preset", "jma", "--plaintext", "--yes"]);
    e.ng("暗号化", &["issue", "oriom-root", "plain", "--owner-name", "x", "--preset", "jma", "--yes"]);
    e.ng("署名できません", &["issue", NODE, "child", "--owner-name", "x", "--yes"]);
    e.ng(
        "期限が必要",
        &["issue", "oriom-root/import", "forever", "--owner-name", "x", "--org-id", "a:b", "--org-name", "b", "--no-expiry", "--plaintext", "--yes"],
    );
    e.ng("maxDepth は 0..0", &["issue", OPERATOR, "deep", "--max-depth", "1", "--owner-name", "x", "--preset", "jma", "--days", "1", "--plaintext", "--yes"]);
    assert!(!e.keys().join(OPERATOR).join("bad").exists());
}

#[test]
fn revoke_and_publish() {
    let e = Env::new("publish");
    e.build();
    e.ok(&["revoke", OPERATOR, "--reason", "compromise", "--yes"]);
    e.ng("確認の入力が一致しません", &["publish", OPERATOR, "--confirm", "00000000"]);
    let list = e.keys().join("oriom-root/import/_revocation-list/revocations.cbor");
    assert!(!list.exists());
    let dry = e.ok(&["publish", OPERATOR, "--dry-run"]);
    assert!(dry.contains("影響を受ける鍵（2 件"), "{dry}");
    let key = e.pubkey(OPERATOR);
    e.ok(&["publish", OPERATOR, "--confirm", &key[..8]]);
    assert!(list.exists());
    assert!(e.ok(&["tree"]).contains("祖先 oriom が失効"));
    e.ng("失効しています", &["issue", OPERATOR, "node-2", "--leaf", "--owner-name", "x", "--preset", "jma", "--days", "1", "--yes"]);
    e.ng("確認に失敗", &["publish", OPERATOR, "--confirm", &key[..8]]);
}

#[test]
fn activate_pre_issued_notice() {
    let e = Env::new("activate");
    e.build();
    e.ok(&["activate", NODE, "--yes"]);
    let key = e.pubkey(NODE);
    let out = e.ok(&["publish", NODE, "--confirm", &key[..8]]);
    assert!(out.contains("事前発行形 + 活性化") && out.contains("1 件"), "{out}");
}

#[test]
fn revoke_requires_ancestor() {
    let e = Env::new("ancestor");
    e.build();
    e.ok(&["issue", "oriom-root", "other", "--domain", "other.example.org", "--owner-name", "o", "--preset", "jma", "--plaintext", "--yes"]);
    e.ng("祖先ではない", &["revoke", NODE, "--by", "oriom-root/other", "--reason", "unspecified", "--yes"]);
    e.ng("対象外", &["revoke", "oriom-root", "--reason", "unspecified", "--yes"]);
}

/// 署名してもらった鍵を別の鍵ディレクトリの起点にし、その下で発行・失効できる。
#[test]
fn adopt_issued_key_as_new_top() {
    let e = Env::new("adopt");
    e.build();
    let other = e.dir.join("other-keys");
    let key_dir = e.dir.join("partner-key");
    e.ok_in(&other, &["keygen", key_dir.to_str().unwrap(), "--plaintext"]);
    e.ok(&[
        "issue",
        "oriom-root/import",
        "partner",
        "--max-depth",
        "1",
        "--owner-name",
        "Partner",
        "--org-id",
        "partner.example:importer",
        "--org-name",
        "Partner importer",
        "--domain",
        "partner.example.org",
        "--days",
        "100",
        "--preset",
        "jma",
        "--subject-public-pem",
        key_dir.join("public.pem").to_str().unwrap(),
        "--yes",
    ]);
    let bundle = e.dir.join("bundle");
    e.ok(&["handoff", "oriom-root/import/partner", bundle.to_str().unwrap()]);
    e.ok_in(&other, &["adopt", "--key-dir", key_dir.to_str().unwrap(), "--bundle", bundle.to_str().unwrap()]);
    e.ok_in(&other, &["issue", "partner", "node-a", "--leaf", "--owner-name", "a", "--days", "30", "--yes"]);
    let show = e.ok_in(&other, &["show", "partner/node-a"]);
    assert!(show.contains("stratum: 3") && show.contains("チェーン: verified"), "{show}");
    e.ok_in(&other, &["verify"]);
    // 起点の鍵の親は管理外なので、失効は活性化（事前発行形）か自己署名で行う
    let (code, _, err) = e.run_in(&other, &["revoke", "partner", "--reason", "superseded", "--yes"], &[]);
    assert_eq!(code, 1);
    assert!(err.contains("--by self"), "{err}");
    e.ok_in(&other, &["activate", "partner", "--yes"]);
    let out = e.run_in(&other, &["publish", "partner", "--dry-run"], &[]).1;
    assert!(out.contains("partner.example.org") && out.contains("影響を受ける鍵（2 件"), "{out}");
}

#[test]
fn age_recipients_and_encrypt() {
    let e = Env::new("age");
    let identity = age::x25519::Identity::generate();
    let identity_file = e.dir.join("identity.txt");
    std::fs::write(&identity_file, format!("{}\n", identity.to_string().expose_secret())).unwrap();
    let recipient = identity.to_public().to_string();
    let keys = e.keys();
    e.ok(&["init-root", "r", "--display-name", "r", "--age-recipient", &recipient]);
    assert!(keys.join("r/secret.pem.age").exists());
    let id = identity_file.to_str().unwrap();
    let issue = [
        "issue",
        "r",
        "ca",
        "--max-depth",
        "1",
        "--owner-name",
        "c",
        "--org-id",
        "t:org",
        "--org-name",
        "org",
        "--preset",
        "jma",
        "--age-recipient",
        &recipient,
        "--yes",
    ];
    let (code, _, err) = e.run_in(&keys, &issue, &[]);
    assert_eq!(code, 1, "{err}");
    assert!(err.contains("MOSIMO_CA_AGE_IDENTITY"), "{err}");
    let (code, out, err) = e.run_in(&keys, &issue, &[("MOSIMO_CA_AGE_IDENTITY", id)]);
    assert_eq!(code, 0, "{out}{err}");
    // 末端の鍵は平文で作り、後から暗号化する。identity がないと復号の確認ができないので平文を残す
    let (code, out, err) =
        e.run_in(&keys, &["issue", "r/ca", "leaf", "--leaf", "--owner-name", "l", "--days", "10", "--yes"], &[("MOSIMO_CA_AGE_IDENTITY", id)]);
    assert_eq!(code, 0, "{out}{err}");
    let leaf_plain = keys.join("r/ca/leaf/secret.pem");
    assert!(leaf_plain.exists());
    let (code, _, err) = e.run_in(&keys, &["encrypt", "r/ca/leaf", "--age-recipient", &recipient], &[]);
    assert_eq!(code, 1);
    assert!(err.contains("平文は残してあります"), "{err}");
    assert!(leaf_plain.exists() && !keys.join("r/ca/leaf/secret.pem.age").exists());
    let (code, out, err) = e.run_in(&keys, &["encrypt", "r/ca/leaf", "--age-recipient", &recipient], &[("MOSIMO_CA_AGE_IDENTITY", id)]);
    assert_eq!(code, 0, "{out}{err}");
    assert!(!leaf_plain.exists() && keys.join("r/ca/leaf/secret.pem.age").exists());
    let (code, out, err) = e.run_in(&keys, &["verify"], &[]);
    assert_eq!(code, 0, "{out}{err}");
}

fn walk(dir: &Path) -> Vec<PathBuf> {
    let mut out = vec![dir.to_path_buf()];
    for entry in std::fs::read_dir(dir).unwrap() {
        let path = entry.unwrap().path();
        if path.is_dir() {
            out.extend(walk(&path));
        } else {
            out.push(path);
        }
    }
    out
}

#[test]
fn inspect_files() {
    let e = Env::new("inspect");
    e.build();
    let keys = e.keys();
    let att = keys.join(NODE).join("attestation.cbor");
    let out = e.ok(&["inspect", att.to_str().unwrap()]);
    assert!(out.contains("種類: 鍵署名") && out.contains("✓ 署名と構造が正しい") && out.contains(&format!("body.issuer = {OPERATOR}")), "{out}");
    let pre = e.ok(&["inspect", keys.join(NODE).join("pre-revocation.cbor").to_str().unwrap()]);
    assert!(pre.contains("事前発行形"), "{pre}");
    // chain の Base64 出力もそのまま読める
    let chain_file = e.dir.join("chain.b64");
    std::fs::write(&chain_file, e.ok(&["chain", NODE])).unwrap();
    let out = e.ok(&["inspect", chain_file.to_str().unwrap()]);
    assert!(out.contains("チェーン（鍵署名 3 件）") && out.contains("チェーン: verified"), "{out}");
    e.ok(&["revoke", NODE, "--reason", "superseded", "--yes"]);
    let key = e.pubkey(NODE);
    e.ok(&["publish", NODE, "--confirm", &key[..8]]);
    let list = keys.join("oriom-root/import/_revocation-list/revocations.cbor");
    let out = e.ok(&["inspect", list.to_str().unwrap()]);
    assert!(out.contains("失効リスト") && out.contains("通知 1 件が有効"), "{out}");
    let rev = e.ok(&["inspect", keys.join(NODE).join("revocation.cbor").to_str().unwrap()]);
    assert!(rev.contains("失効（Revocation・即時形）"), "{rev}");
    let json = e.ok(&["inspect", "--json", att.to_str().unwrap()]);
    assert!(json.trim_start().starts_with('{') && json.contains("\"$type\": \"dev.jisaku.mosimo.attest#keyAttestation\""), "{json}");
    // 壊したファイルは失敗として報告する（CBOR として正しいまま、末尾 = body 内の公開鍵の 1 バイトを変える）
    let mut bytes = std::fs::read(&att).unwrap();
    let last = bytes.len() - 1;
    bytes[last] ^= 1;
    let bad = e.dir.join("bad.cbor");
    std::fs::write(&bad, bytes).unwrap();
    let (code, out, _) = e.run_in(&keys, &["inspect", bad.to_str().unwrap()], &[]);
    assert_eq!(code, 1);
    assert!(out.contains("✗ "), "{out}");
}

/// ディレクトリ名は親が付けた名前（owner.id）でなければならない。
#[test]
fn directory_name_must_match_owner_id() {
    let e = Env::new("names");
    e.build();
    let keys = e.keys();
    std::fs::rename(keys.join(NODE), keys.join(OPERATOR).join("renamed")).unwrap();
    let (code, _, err) = e.run_in(&keys, &["tree"], &[]);
    assert_eq!(code, 1);
    assert!(err.contains("owner.id = node-1"), "{err}");
    std::fs::rename(keys.join(OPERATOR).join("renamed"), keys.join(NODE)).unwrap();
    e.ok(&["verify"]);
    // DID のような : を含む名前も使える
    e.ok(&["issue", OPERATOR, "did:plc:vy6l6jpmhxupmelsphj7dpnx", "--leaf", "--owner-name", "x", "--preset", "jma", "--days", "1", "--yes"]);
    e.ng("名前は", &["issue", OPERATOR, "Bad/Name", "--leaf", "--owner-name", "x", "--preset", "jma", "--days", "1", "--yes"]);
}

/// 転載サーバーに配置する一式（identity.json・chain.cbor・nostr.pem・atproto.pem・roots.json）。
#[test]
fn credentials_directory() {
    let e = Env::new("credentials");
    e.build();
    let nostr = k256::SecretKey::random(&mut rand_core::OsRng);
    let atproto = k256::SecretKey::random(&mut rand_core::OsRng);
    let plc = k256::SecretKey::random(&mut rand_core::OsRng);
    let xonly = hex::encode(k256::schnorr::SigningKey::from(nostr.clone()).verifying_key().to_bytes());
    let compressed = hex::encode(k256::elliptic_curve::sec1::ToEncodedPoint::to_encoded_point(&atproto.public_key(), true).as_bytes());
    let identity = serde_json::json!({
        "did": "did:plc:abcdefghijklmnopqrstuvwx", "genesis": {"type": "plc_operation"}, "nostrPubkey": xonly, "atprotoPubkey": compressed,
        "nostrSecret": hex::encode(nostr.to_bytes()), "atprotoSecret": hex::encode(atproto.to_bytes()), "plcSecret": hex::encode(plc.to_bytes()),
    });
    let identity_file = e.dir.join("importer-secret.json");
    std::fs::write(&identity_file, identity.to_string()).unwrap();
    let node = format!("{OPERATOR}/importer-1");
    e.ok(&[
        "issue",
        OPERATOR,
        "importer-1",
        "--leaf",
        "--owner-name",
        "i",
        "--preset",
        "jma",
        "--days",
        "10",
        "--importer-identity",
        identity_file.to_str().unwrap(),
        "--yes",
    ]);
    let out = e.dir.join("deploy");
    e.ok(&["credentials", &node, "--out", out.to_str().unwrap()]);
    let mut names: Vec<String> = std::fs::read_dir(&out).unwrap().map(|e| e.unwrap().file_name().to_string_lossy().into_owned()).collect();
    names.sort();
    assert_eq!(names, ["atproto.pem", "chain.cbor", "identity.json", "nostr.pem", "roots.json"]);
    let public: serde_json::Value = serde_json::from_str(&std::fs::read_to_string(out.join("identity.json")).unwrap()).unwrap();
    assert_eq!(public, serde_json::json!({"did": "did:plc:abcdefghijklmnopqrstuvwx", "nostrPubkey": xonly, "atprotoPubkey": compressed}));
    let pem = std::fs::read_to_string(out.join("nostr.pem")).unwrap();
    assert_eq!(<k256::SecretKey as k256::pkcs8::DecodePrivateKey>::from_pkcs8_pem(&pem).unwrap().to_bytes(), nostr.to_bytes());
    assert_eq!(std::fs::metadata(out.join("nostr.pem")).unwrap().permissions().mode() & 0o777, 0o600);
    assert!(!std::fs::read_to_string(out.join("identity.json")).unwrap().contains("Secret"));
    // 既存のディレクトリには書かない
    let (code, _, _) = e.run_in(&e.keys(), &["credentials", &node, "--out", out.to_str().unwrap()], &[]);
    assert_eq!(code, 1);
}
