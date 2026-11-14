//! dev.jisaku.mosimo.attest（protocol/mosimo/attest）の発行者側の実装。
//! 規則は受信者の実装 pds/attest.mjs と同じにしてあり、発行したものはその場で同じ規則で検証する。

use crate::cbor::{self, Value};
use crate::crypto;
use anyhow::{anyhow, bail, ensure, Result};
use k256::SecretKey;
use std::collections::BTreeMap;

pub const KEY_ATTESTATION: &str = "dev.jisaku.mosimo.attest#keyAttestation";
pub const REVOCATION: &str = "dev.jisaku.mosimo.attest.revocation";
pub const NOTICE: &str = "dev.jisaku.mosimo.attest.revocation#notice";
pub const ACTIVATION: &str = "dev.jisaku.mosimo.attest.revocation#activation";
pub const REASON: &str = "dev.jisaku.mosimo.attest.revocation#reason";
pub const REVOCATION_LIST: &str = "dev.jisaku.mosimo.attest.revocation.list";
pub const IMPORTER: &str = "dev.jisaku.mosimo.attest.importer";
pub const KEY_VALIDITY: &str = "dev.jisaku.mosimo.attest#keyValidity";
pub const DELEGATION: &str = "dev.jisaku.mosimo.attest#delegation";
pub const ORGANIZATION: &str = "dev.jisaku.mosimo.attest#organization";
pub const ISSUER_DOMAIN: &str = "dev.jisaku.mosimo.attest#issuerDomain";
pub const SUBJECT_DOMAIN: &str = "dev.jisaku.mosimo.attest#subjectDomain";
pub const TRUST_LEVEL: &str = "dev.jisaku.mosimo.attest#trustLevel";
pub const SUBKEY: &str = "dev.jisaku.mosimo.attest.revocation.subkey";
pub const REASONS: [&str; 4] = ["compromise", "superseded", "ceased", "unspecified"];
pub const KNOWN_ROLES: [&str; 4] = ["attest", "identity", "import", "public-information"];
/// 受信者の実装（JS・Kotlin・Swift）が受け付ける maxDepth の上限。チェーンは最大 8 要素なので実質無制限。
pub const MAX_DEPTH: i128 = 16;
const MAX_TIME: i64 = 253_402_300_799;

// ---- 基本 ---------------------------------------------------------------------------------------

pub fn body_hash(body: &Value) -> Result<[u8; 32]> {
    Ok(crypto::sha256(&cbor::encode(body, 65_536)?))
}

pub fn body_id(body: &Value) -> Result<String> {
    Ok(hex::encode(body_hash(body)?))
}

pub fn sign(body: Value, secret: &SecretKey, issuer: &[u8]) -> Result<Value> {
    ensure!(issuer.len() == 32, "Only 32-byte (BIP-340) issuer keys can sign here");
    ensure!(crypto::xonly(secret) == issuer, "Secret key does not match the issuer key");
    let sig = crypto::schnorr_sign(&body_hash(&body)?, secret)?;
    Ok(Value::map([("body", body), ("sig", Value::Bytes(sig.to_vec()))]))
}

fn check_signed(signed: &Value, key: &[u8]) -> Result<()> {
    let sig = signed.get("sig").and_then(Value::as_bytes).ok_or_else(|| anyhow!("Missing signature"))?;
    let body = signed.get("body").ok_or_else(|| anyhow!("Missing body"))?;
    ensure!(crypto::verify(sig, &body_hash(body)?, key), "Invalid signature");
    Ok(())
}

pub fn b64(bytes: &[u8]) -> String {
    use base64::Engine;
    base64::engine::general_purpose::STANDARD_NO_PAD.encode(bytes)
}

pub fn unb64(text: &str) -> Result<Vec<u8>> {
    use base64::Engine;
    let bytes = base64::engine::general_purpose::STANDARD_NO_PAD.decode(text.trim()).map_err(|_| anyhow!("Invalid Base64"))?;
    ensure!(b64(&bytes) == text.trim(), "Noncanonical Base64");
    Ok(bytes)
}

// ---- 時刻 ---------------------------------------------------------------------------------------

fn days_from_civil(y: i64, m: i64, d: i64) -> i64 {
    let y = if m <= 2 { y - 1 } else { y };
    let era = y.div_euclid(400);
    let yoe = y - era * 400;
    let doy = (153 * (if m > 2 { m - 3 } else { m + 9 }) + 2) / 5 + d - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    era * 146_097 + doe - 719_468
}

fn civil_from_days(z: i64) -> (i64, i64, i64) {
    let z = z + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z - era * 146_097;
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let m = if mp < 10 { mp + 3 } else { mp - 9 };
    (yoe + era * 400 + i64::from(m <= 2), m, d)
}

pub fn timestamp(seconds: i64) -> Result<String> {
    ensure!((0..=MAX_TIME).contains(&seconds), "Unknown clock");
    let (y, m, d) = civil_from_days(seconds.div_euclid(86_400));
    let s = seconds.rem_euclid(86_400);
    Ok(format!("{y:04}-{m:02}-{d:02}T{:02}:{:02}:{:02}Z", s / 3600, s / 60 % 60, s % 60))
}

/// `YYYY-MM-DDTHH:MM:SS(.f)?Z` → 秒（小数あり）。オフセット付きや存在しない日付は拒否する。
pub fn parse_time(text: &str) -> Result<f64> {
    let b = text.as_bytes();
    let digits = |r: std::ops::Range<usize>| -> Option<i64> {
        b.get(r.clone()).filter(|s| s.iter().all(u8::is_ascii_digit)).map(|s| std::str::from_utf8(s).unwrap().parse().unwrap())
    };
    let fail = || anyhow!("Invalid Timestamp: {text:?}");
    ensure!(b.len() >= 20 && b[4] == b'-' && b[7] == b'-' && b[10] == b'T' && b[13] == b':' && b[16] == b':' && b.ends_with(b"Z"), fail());
    let (y, mo, d) = (digits(0..4).ok_or_else(fail)?, digits(5..7).ok_or_else(fail)?, digits(8..10).ok_or_else(fail)?);
    let (h, mi, s) = (digits(11..13).ok_or_else(fail)?, digits(14..16).ok_or_else(fail)?, digits(17..19).ok_or_else(fail)?);
    let frac = &text[19..text.len() - 1];
    let fraction = if frac.is_empty() {
        0.0
    } else {
        ensure!(frac.len() >= 2 && frac.starts_with('.') && frac[1..].bytes().all(|c| c.is_ascii_digit()), fail());
        format!("0{frac}").parse::<f64>()?
    };
    ensure!(y >= 1 && (1..=12).contains(&mo) && h <= 23 && mi <= 59 && s <= 59, "Invalid calendar date");
    let days = days_from_civil(y, mo, d);
    ensure!(d >= 1 && civil_from_days(days) == (y, mo, d), "Invalid calendar date");
    Ok((days * 86_400 + h * 3600 + mi * 60 + s) as f64 + fraction)
}

// ---- 構造の検査 -----------------------------------------------------------------------------------

pub fn keys(value: &Value, allowed: &[&str], required: &[&str]) -> Result<()> {
    let map = value.as_map().ok_or_else(|| anyhow!("Expected an object"))?;
    ensure!(
        map.keys().all(|k| allowed.contains(&k.as_str())) && required.iter().all(|k| map.contains_key(*k)),
        "Unexpected fields: {}",
        map.keys().cloned().collect::<Vec<_>>().join(",")
    );
    Ok(())
}

pub fn text(value: Option<&Value>, max: usize) -> Result<&str> {
    let s = value.and_then(Value::as_text).ok_or_else(|| anyhow!("Expected text"))?;
    check_text(s, max)?;
    Ok(s)
}

pub fn check_text(s: &str, max: usize) -> Result<()> {
    ensure!((1..=max).contains(&s.len()) && !s.chars().any(|c| (c as u32) < 0x20 || c == '\x7f'), "Invalid text: {s:?}");
    Ok(())
}

fn sorted_unique_texts(value: Option<&Value>, max: usize, item_max: usize) -> Result<Vec<String>> {
    let items = value.and_then(Value::as_array).ok_or_else(|| anyhow!("Expected a list"))?;
    ensure!(items.len() <= max, "List too long");
    let texts = items.iter().map(|v| text(Some(v), item_max).map(str::to_string)).collect::<Result<Vec<_>>>()?;
    ensure!(texts.windows(2).all(|w| w[0].as_bytes() < w[1].as_bytes()), "List must be sorted by UTF-8 bytes without duplicates");
    Ok(texts)
}

pub fn is_did(s: &str) -> bool {
    s.strip_prefix("did:plc:").is_some_and(|rest| rest.len() == 24 && rest.bytes().all(|c| c.is_ascii_lowercase() || (b'2'..=b'7').contains(&c)))
}

pub fn is_domain(s: &str) -> bool {
    let labels: Vec<&str> = s.split('.').collect();
    (1..=253).contains(&s.len())
        && labels.len() >= 2
        && labels.iter().all(|l| {
            (1..=63).contains(&l.len())
                && l.bytes().all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || c == b'-')
                && !l.starts_with('-')
                && !l.ends_with('-')
        })
}

fn is_org_id(s: &str) -> bool {
    match s.split_once(':') {
        Some((prefix, rest)) => !prefix.is_empty() && !prefix.chars().any(char::is_whitespace) && rest.chars().next().is_some_and(|c| !c.is_whitespace()),
        None => false,
    }
}

/// importer §1: `sources`（Bot DID）と `feeds`（登録済みフィード名）。少なくとも一方を持ち、持つものは空でない。
pub fn validate_importer_block(block: &Value) -> Result<()> {
    keys(block, &["$type", "sources", "feeds"], &["$type"])?;
    ensure!(block.get("sources").is_some() || block.get("feeds").is_some(), "Importer block without sources or feeds");
    let mut pairs = 0;
    for (field, name_field, max) in [("sources", "botDid", 16), ("feeds", "feed", 8)] {
        let Some(list) = block.get(field) else { continue };
        let list = list.as_array().filter(|l| (1..=max).contains(&l.len())).ok_or_else(|| anyhow!("Invalid importer {field}"))?;
        let mut names = Vec::new();
        for entry in list {
            keys(entry, &[name_field, "types"], &[name_field, "types"])?;
            let name = text(entry.get(name_field), 64)?;
            ensure!(field != "sources" || is_did(name), "Invalid Bot DID");
            let types = sorted_unique_texts(entry.get("types"), 32, 128)?;
            ensure!(!types.is_empty(), "Empty types");
            pairs += types.len();
            names.push(name.to_string());
        }
        ensure!(names.windows(2).all(|w| w[0].as_bytes() < w[1].as_bytes()), "Importer {field} must be sorted without duplicates");
    }
    ensure!(pairs <= 64, "Importer scope limit");
    Ok(())
}

fn validate_block(block: &Value) -> Result<()> {
    let t = block.get("$type").and_then(Value::as_text).filter(|t| (1..=256).contains(&t.len())).ok_or_else(|| anyhow!("Invalid block"))?;
    match t {
        KEY_VALIDITY => {
            keys(block, &["$type", "from", "until"], &["$type"])?;
            let from = block.get("from").map(|v| parse_time(v.as_text().unwrap_or(""))).transpose()?;
            let until = block.get("until").map(|v| parse_time(v.as_text().unwrap_or(""))).transpose()?;
            if let (Some(f), Some(u)) = (from, until) {
                ensure!(f < u, "Empty validity");
            }
        }
        DELEGATION => {
            keys(block, &["$type", "maxDepth"], &["$type", "maxDepth"])?;
            ensure!(block.get("maxDepth").and_then(Value::as_int).is_some_and(|d| (0..=MAX_DEPTH).contains(&d)), "Invalid maxDepth");
        }
        ORGANIZATION => {
            keys(block, &["$type", "orgId", "orgName"], &["$type", "orgId", "orgName"])?;
            ensure!(is_org_id(text(block.get("orgId"), 256)?), "orgId must be <prefix>:<identifier>");
            text(block.get("orgName"), 256)?;
        }
        ISSUER_DOMAIN => {
            keys(block, &["$type", "domain"], &["$type", "domain"])?;
            ensure!(is_domain(block.get("domain").and_then(Value::as_text).unwrap_or("")), "Invalid issuer domain");
        }
        SUBJECT_DOMAIN => {
            keys(block, &["$type", "domain", "method"], &["$type", "domain", "method"])?;
            ensure!(is_domain(block.get("domain").and_then(Value::as_text).unwrap_or("")), "Invalid subject domain");
            text(block.get("method"), 64)?;
        }
        TRUST_LEVEL => {
            keys(block, &["$type", "level", "method"], &["$type", "level"])?;
            ensure!(block.get("level").and_then(Value::as_int).is_some_and(|l| (0..=3).contains(&l)), "Invalid trust level");
            if block.get("method").is_some() {
                text(block.get("method"), 64)?;
            }
        }
        IMPORTER => validate_importer_block(block)?,
        SUBKEY => {
            keys(block, &["$type", "key"], &["$type", "key"])?;
            crypto::validate_public_key(block.get("key").and_then(Value::as_bytes).unwrap_or(&[]))?;
        }
        _ => {} // 未知のブロックは保持し、包含判定には参加させない
    }
    Ok(())
}

pub fn body(signed: &Value) -> &Value {
    signed.get("body").unwrap_or(&Value::Null)
}

pub fn bytes_field<'a>(v: &'a Value, key: &str) -> &'a [u8] {
    v.get(key).and_then(Value::as_bytes).unwrap_or(&[])
}

pub fn blocks(attestation: &Value) -> &[Value] {
    body(attestation).get("blocks").and_then(Value::as_array).unwrap_or(&[])
}

pub fn block_of<'a>(attestation: &'a Value, t: &str) -> Option<&'a Value> {
    blocks(attestation).iter().find(|b| b.get("$type").and_then(Value::as_text) == Some(t))
}

pub fn roles(attestation: &Value) -> Vec<String> {
    body(attestation).get("roles").and_then(Value::as_array).unwrap_or(&[]).iter().filter_map(|r| r.as_text().map(str::to_string)).collect()
}

/// 親が発行時に付けた名前（owner.id）。鍵ディレクトリ名に使う。
pub fn owner_id(attestation: &Value) -> &str {
    body(attestation).get("owner").and_then(|o| o.get("id")).and_then(Value::as_text).unwrap_or("")
}

pub fn delegation_depth(attestation: &Value) -> i128 {
    block_of(attestation, DELEGATION).and_then(|b| b.get("maxDepth")).and_then(Value::as_int).unwrap_or(0)
}

/// 鍵署名 1 件の構造と署名（attest §2, §7）。
pub fn validate_attestation(attestation: &Value) -> Result<()> {
    keys(attestation, &["body", "sig"], &["body", "sig"])?;
    ensure!(cbor::encode(attestation, 65_536)?.len() <= 4096, "Attestation exceeds 4096 bytes");
    let b = body(attestation);
    keys(b, &["$type", "issuer", "subject", "owner", "roles", "blocks"], &["$type", "issuer", "subject", "owner", "roles", "blocks"])?;
    ensure!(b.get("$type").and_then(Value::as_text) == Some(KEY_ATTESTATION), "Not a key attestation");
    let (issuer, subject) = (bytes_field(b, "issuer"), bytes_field(b, "subject"));
    crypto::validate_public_key(issuer)?;
    crypto::validate_public_key(subject)?;
    ensure!(issuer != subject, "Self-signed attestation");
    let owner = b.get("owner").ok_or_else(|| anyhow!("Missing owner"))?;
    keys(owner, &["id", "name"], &["id", "name"])?;
    text(owner.get("id"), 256)?;
    text(owner.get("name"), 256)?;
    sorted_unique_texts(b.get("roles"), 32, 64)?;
    let blocks = b.get("blocks").and_then(Value::as_array).ok_or_else(|| anyhow!("Invalid blocks"))?;
    ensure!(blocks.len() <= 16, "Too many blocks");
    let mut types = Vec::new();
    for block in blocks {
        validate_block(block)?;
        types.push(block.get("$type").and_then(Value::as_text).unwrap_or(""));
    }
    let n = types.len();
    types.sort();
    types.dedup();
    ensure!(types.len() == n, "Duplicate block type");
    check_signed(attestation, issuer)
}

pub struct Owner<'a> {
    pub id: &'a str,
    pub name: &'a str,
}

pub fn create_attestation(secret: &SecretKey, issuer: &[u8], subject: &[u8], owner: Owner, roles: &[String], blocks: Vec<Value>) -> Result<Value> {
    let mut roles = roles.to_vec();
    roles.sort();
    roles.dedup();
    let body = Value::map([
        ("$type", Value::text(KEY_ATTESTATION)),
        ("issuer", Value::Bytes(issuer.to_vec())),
        ("subject", Value::Bytes(subject.to_vec())),
        ("owner", Value::map([("id", Value::text(owner.id)), ("name", Value::text(owner.name))])),
        ("roles", Value::Array(roles.into_iter().map(Value::Text).collect())),
        ("blocks", Value::Array(blocks)),
    ]);
    let attestation = sign(body, secret, issuer)?;
    validate_attestation(&attestation)?;
    Ok(attestation)
}

// ---- ルート設定とチェーン -------------------------------------------------------------------------

/// 受信者のローカル設定（ルート鍵の信頼設定）。アプリの mosimo-public-trust.json の roots 要素と同じ形。
#[derive(Clone, Debug)]
pub struct RootConfig {
    pub pubkey: Vec<u8>,
    pub name: String,
    pub roles: Vec<String>,
    pub max_depth: i128,
    pub enabled: bool,
}

impl RootConfig {
    pub fn from_json(json: &serde_json::Value) -> Result<RootConfig> {
        let obj = json.as_object().ok_or_else(|| anyhow!("Root settings must be an object"))?;
        ensure!(obj.keys().all(|k| ["pubkey", "name", "roles", "maxDepth", "enabled"].contains(&k.as_str())), "Unexpected root fields");
        let pubkey = hex::decode(obj.get("pubkey").and_then(|v| v.as_str()).ok_or_else(|| anyhow!("Root pubkey required"))?)?;
        crypto::validate_public_key(&pubkey)?;
        let mut roles: Vec<String> = obj
            .get("roles")
            .and_then(|v| v.as_array())
            .ok_or_else(|| anyhow!("Root roles required"))?
            .iter()
            .map(|r| r.as_str().map(str::to_string).ok_or_else(|| anyhow!("Invalid root role")))
            .collect::<Result<_>>()?;
        roles.sort();
        let n = roles.len();
        roles.dedup();
        ensure!(roles.len() == n && n <= 32, "Invalid root roles");
        let max_depth = obj.get("maxDepth").and_then(|v| v.as_i64()).ok_or_else(|| anyhow!("Root maxDepth required"))? as i128;
        ensure!((1..=MAX_DEPTH).contains(&max_depth), "Root maxDepth must be 1..{MAX_DEPTH}");
        let name = obj.get("name").and_then(|v| v.as_str()).unwrap_or("").to_string();
        let enabled = obj.get("enabled").map(|v| v.as_bool().ok_or_else(|| anyhow!("Invalid enabled flag"))).transpose()?.unwrap_or(true);
        Ok(RootConfig { pubkey, name, roles, max_depth, enabled })
    }

    pub fn to_json(&self) -> serde_json::Value {
        serde_json::json!({
            "pubkey": hex::encode(&self.pubkey), "name": self.name, "roles": self.roles,
            "maxDepth": self.max_depth as i64, "enabled": self.enabled,
        })
    }
}

pub type Validity = (Option<f64>, Option<f64>);

pub fn validity(attestation: &Value) -> Validity {
    let block = block_of(attestation, KEY_VALIDITY);
    let field = |k| block.and_then(|b| b.get(k)).and_then(Value::as_text).and_then(|t| parse_time(t).ok());
    (field("from"), field("until"))
}

pub fn within((from, until): Validity, t: f64) -> bool {
    from.is_none_or(|f| f <= t) && until.is_none_or(|u| t < u)
}

/// importer ブロックの転載範囲。`sources` は (botDid, types)、`feeds` は (feed, types)。
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct Scope {
    pub sources: Vec<(String, Vec<String>)>,
    pub feeds: Vec<(String, Vec<String>)>,
}

impl Scope {
    /// self に含まれない子の組（"DID type" / "feed:NAME type"）。空なら子は self に収まる（importer §1 の包含規則）。
    pub fn outside(&self, child: &Scope) -> Vec<String> {
        let missing = |parent: &[(String, Vec<String>)], list: &[(String, Vec<String>)], prefix: &str| -> Vec<String> {
            list.iter().flat_map(|(n, ts)| ts.iter().filter(|t| !includes_pair(parent, n, t)).map(move |t| format!("{prefix}{n} {t}"))).collect()
        };
        [missing(&self.sources, &child.sources, ""), missing(&self.feeds, &child.feeds, "feed:")].concat()
    }
}

fn scope_entries(block: &Value, field: &str, name_field: &str) -> Vec<(String, Vec<String>)> {
    block
        .get(field)
        .and_then(Value::as_array)
        .unwrap_or(&[])
        .iter()
        .map(|s| {
            let name = s.get(name_field).and_then(Value::as_text).unwrap_or("").to_string();
            let types = s.get("types").and_then(Value::as_array).unwrap_or(&[]).iter().filter_map(|t| t.as_text().map(str::to_string)).collect();
            (name, types)
        })
        .collect()
}

pub fn importer_scope(block: &Value) -> Scope {
    Scope { sources: scope_entries(block, "sources", "botDid"), feeds: scope_entries(block, "feeds", "feed") }
}

/// (名前, types) の一覧が (name, t) の組を含むか。Bot の転載とフィードの転載で共通に使う。
pub fn includes_pair(entries: &[(String, Vec<String>)], name: &str, t: &str) -> bool {
    entries.iter().any(|(d, types)| d == name && types.iter().any(|x| x == t))
}

#[derive(Debug, PartialEq, Eq)]
pub enum ChainStatus {
    Verified,
    Expired(String),
    Invalid(String),
    Unverifiable(String),
}

impl std::fmt::Display for ChainStatus {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            ChainStatus::Verified => write!(f, "verified"),
            ChainStatus::Expired(r) => write!(f, "expired ({r})"),
            ChainStatus::Invalid(r) => write!(f, "invalid ({r})"),
            ChainStatus::Unverifiable(r) => write!(f, "unverifiable ({r})"),
        }
    }
}

/// チェーン検証（attest §5）。
pub fn verify_chain(chain: &[Value], roots: &[RootConfig], at: i64) -> ChainStatus {
    match verify_chain_inner(chain, roots, at) {
        Ok(status) => status,
        Err(e) => ChainStatus::Invalid(e.to_string()),
    }
}

fn verify_chain_inner(chain: &[Value], roots: &[RootConfig], at: i64) -> Result<ChainStatus> {
    ensure!((1..=8).contains(&chain.len()), "Chain length");
    for a in chain {
        validate_attestation(a)?;
    }
    let first_issuer = bytes_field(body(&chain[0]), "issuer");
    let Some(root) = roots.iter().find(|r| r.enabled && r.pubkey == first_issuer) else {
        return Ok(ChainStatus::Unverifiable("Unknown root".into()));
    };
    let mut parent_roles = root.roles.clone();
    let mut parent_depth = root.max_depth;
    let mut parent_validity: Validity = (None, None);
    let mut parent_org: Option<String> = None;
    let mut parent_importer: Option<Scope> = None;
    let mut parent_import_role = root.roles.iter().any(|r| r == "import");
    for (i, a) in chain.iter().enumerate() {
        let b = body(a);
        let leaf = i == chain.len() - 1;
        if i > 0 {
            ensure!(bytes_field(body(&chain[i - 1]), "subject") == bytes_field(b, "issuer"), "Broken chain linkage");
        }
        let r = roles(a);
        ensure!(r.iter().all(|x| parent_roles.contains(x)), "Role escalation");
        ensure!(leaf || r.iter().any(|x| x == "attest"), "Intermediate without attest role");
        let depth = delegation_depth(a);
        ensure!(depth < parent_depth, "Delegation depth escalation"); // d_i <= d_{i-1} - 1
        ensure!(leaf || depth >= 1, "Intermediate without delegation");
        let v = validity(a);
        ensure!(i == 0 || v.1.is_some(), "Stratum 2 and below require keyValidity.until");
        ensure!(
            parent_validity.0.is_none_or(|pf| v.0.is_some_and(|f| f >= pf)) && parent_validity.1.is_none_or(|pu| v.1.is_some_and(|u| u <= pu)),
            "Validity escalation"
        );
        let org = block_of(a, ORGANIZATION).and_then(|o| o.get("orgId")).and_then(Value::as_text).map(str::to_string);
        if let Some(p) = &parent_org {
            ensure!(org.as_ref() == Some(p), "Organization mismatch");
        }
        let importer = block_of(a, IMPORTER).map(importer_scope);
        if let Some(scope) = &importer {
            let ok = match &parent_importer {
                Some(parent) => parent.outside(scope).is_empty(),
                None => parent_import_role,
            };
            ensure!(ok, "Importer scope escalation");
        }
        parent_import_role = r.iter().any(|x| x == "import");
        parent_roles = r;
        parent_depth = depth;
        parent_validity = v;
        parent_org = org.or(parent_org);
        parent_importer = importer.or(parent_importer);
    }
    if chain.iter().any(|a| !within(validity(a), at as f64)) {
        return Ok(ChainStatus::Expired(format!("Key validity does not include {}", timestamp(at)?)));
    }
    Ok(ChainStatus::Verified)
}

// ---- 失効 -------------------------------------------------------------------------------------------

pub fn create_notice(
    secret: &SecretKey,
    issuer: &[u8],
    subject: &[u8],
    issued_at: &str,
    effective_at: Option<&str>,
    reason: Option<(&str, Option<&str>)>,
) -> Result<Value> {
    let mut blocks = Vec::new();
    if let Some((code, text)) = reason {
        ensure!(REASONS.contains(&code), "Reason must be one of {}", REASONS.join(", "));
        let mut block = BTreeMap::from([("$type".to_string(), Value::text(REASON)), ("code".to_string(), Value::text(code))]);
        if let Some(t) = text {
            check_text(t, 256)?;
            block.insert("text".into(), Value::text(t));
        }
        blocks.push(Value::Map(block));
    }
    let mut body = BTreeMap::from([
        ("$type".to_string(), Value::text(NOTICE)),
        ("issuer".to_string(), Value::Bytes(issuer.to_vec())),
        ("subject".to_string(), Value::Bytes(subject.to_vec())),
        ("issuedAt".to_string(), Value::text(issued_at)),
        ("blocks".to_string(), Value::Array(blocks)),
    ]);
    if let Some(e) = effective_at {
        body.insert("effectiveAt".into(), Value::text(e));
    }
    let notice = sign(Value::Map(body), secret, issuer)?;
    validate_notice(&notice)?;
    Ok(notice)
}

pub fn create_activation(secret: &SecretKey, signer: &[u8], notice: &Value, effective_at: &str) -> Result<Value> {
    let body = Value::map([
        ("$type", Value::text(ACTIVATION)),
        ("noticeId", Value::Bytes(body_hash(body(notice))?.to_vec())),
        ("effectiveAt", Value::text(effective_at)),
    ]);
    sign(body, secret, signer)
}

pub fn validate_notice(notice: &Value) -> Result<()> {
    keys(notice, &["body", "sig"], &["body", "sig"])?;
    let n = body(notice);
    keys(n, &["$type", "issuer", "subject", "issuedAt", "effectiveAt", "blocks"], &["$type", "issuer", "subject", "issuedAt", "blocks"])?;
    ensure!(n.get("$type").and_then(Value::as_text) == Some(NOTICE), "Not a revocation notice");
    crypto::validate_public_key(bytes_field(n, "issuer"))?;
    crypto::validate_public_key(bytes_field(n, "subject"))?;
    parse_time(n.get("issuedAt").and_then(Value::as_text).unwrap_or(""))?;
    let blocks = n.get("blocks").and_then(Value::as_array).ok_or_else(|| anyhow!("Invalid blocks"))?;
    ensure!(blocks.len() <= 16, "Too many blocks");
    let mut types = Vec::new();
    for block in blocks {
        let t = block.get("$type").and_then(Value::as_text).ok_or_else(|| anyhow!("Invalid block"))?;
        if t == REASON {
            keys(block, &["$type", "code", "text"], &["$type", "code"])?;
            ensure!(REASONS.contains(&block.get("code").and_then(Value::as_text).unwrap_or("")), "Invalid reason code");
            if block.get("text").is_some() {
                text(block.get("text"), 256)?;
            }
        }
        types.push(t);
    }
    let n_types = types.len();
    types.sort();
    types.dedup();
    ensure!(types.len() == n_types, "Duplicate block type");
    check_signed(notice, bytes_field(n, "issuer"))
}

pub struct RevocationFacts {
    pub id: String,
    pub subject: Vec<u8>,
    pub issuer: Vec<u8>,
    pub effective_at: f64,
}

pub fn revocation(notice: Value, activation: Option<Value>) -> Value {
    let mut m = BTreeMap::from([("$type".to_string(), Value::text(REVOCATION)), ("notice".to_string(), notice)]);
    if let Some(a) = activation {
        m.insert("activation".into(), a);
    }
    Value::Map(m)
}

/// Revocation を検証し、事実を返す。
pub fn validate_revocation(revocation: &Value) -> Result<RevocationFacts> {
    keys(revocation, &["$type", "notice", "activation"], &["$type", "notice"])?;
    ensure!(revocation.get("$type").and_then(Value::as_text) == Some(REVOCATION), "Not a revocation");
    ensure!(cbor::encode(revocation, 65_536)?.len() <= 8192, "Revocation too large");
    let notice = revocation.get("notice").unwrap();
    validate_notice(notice)?;
    let n = body(notice);
    let issued_at = parse_time(n.get("issuedAt").and_then(Value::as_text).unwrap_or(""))?;
    let effective_at = if let Some(e) = n.get("effectiveAt") {
        ensure!(revocation.get("activation").is_none(), "Immediate notices carry no activation");
        parse_time(e.as_text().unwrap_or(""))?
    } else {
        let activation = revocation.get("activation").ok_or_else(|| anyhow!("Pre-issued notice without activation is not a revocation"))?;
        keys(activation, &["body", "sig"], &["body", "sig"])?;
        let a = body(activation);
        keys(a, &["$type", "noticeId", "effectiveAt"], &["$type", "noticeId", "effectiveAt"])?;
        ensure!(a.get("$type").and_then(Value::as_text) == Some(ACTIVATION) && bytes_field(a, "noticeId") == body_hash(n)?, "Activation does not match notice");
        let effective = parse_time(a.get("effectiveAt").and_then(Value::as_text).unwrap_or(""))?;
        ensure!(effective >= issued_at, "Activation precedes issuance");
        check_signed(activation, bytes_field(n, "subject")).map_err(|_| anyhow!("Invalid activation signature"))?;
        effective
    };
    Ok(RevocationFacts { id: body_id(n)?, subject: bytes_field(n, "subject").to_vec(), issuer: bytes_field(n, "issuer").to_vec(), effective_at })
}

pub fn create_revocation_list(secret: &SecretKey, publisher: &[u8], generated_at: &str, revocations: Vec<Value>) -> Result<Value> {
    for r in &revocations {
        validate_revocation(r)?;
    }
    let body = Value::map([
        ("$type", Value::text(REVOCATION_LIST)),
        ("publisher", Value::Bytes(publisher.to_vec())),
        ("generatedAt", Value::text(generated_at)),
        ("revocations", Value::Array(revocations)),
    ]);
    sign(body, secret, publisher)
}

pub struct DecodedList {
    pub publisher: Vec<u8>,
    pub generated_at: String,
    pub signature_valid: bool,
    pub accepted: Vec<RevocationFacts>,
    pub rejected: Vec<String>,
}

pub fn decode_revocation_list(bytes: &[u8]) -> Result<DecodedList> {
    let list = cbor::decode(bytes, 1_048_576)?;
    keys(&list, &["body", "sig"], &["body", "sig"])?;
    let b = body(&list);
    keys(b, &["$type", "publisher", "generatedAt", "revocations"], &["$type", "publisher", "generatedAt", "revocations"])?;
    ensure!(b.get("$type").and_then(Value::as_text) == Some(REVOCATION_LIST), "Invalid revocation list");
    let publisher = bytes_field(b, "publisher").to_vec();
    crypto::validate_public_key(&publisher)?;
    let generated_at = b.get("generatedAt").and_then(Value::as_text).unwrap_or("").to_string();
    parse_time(&generated_at)?;
    let signature_valid = check_signed(&list, &publisher).is_ok();
    let (mut accepted, mut rejected) = (Vec::new(), Vec::new());
    for r in b.get("revocations").and_then(Value::as_array).ok_or_else(|| anyhow!("Invalid revocation list"))? {
        match validate_revocation(r) {
            Ok(f) => accepted.push(f),
            Err(e) => rejected.push(e.to_string()),
        }
    }
    Ok(DecodedList { publisher, generated_at, signature_valid, accepted, rejected })
}

pub fn now() -> i64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_secs() as i64).unwrap_or(0)
}

pub fn ensure_reason(code: &str) -> Result<()> {
    if !REASONS.contains(&code) {
        bail!("理由は {} のどれかです", REASONS.join(" / "));
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn timestamps() {
        assert_eq!(timestamp(0).unwrap(), "1970-01-01T00:00:00Z");
        assert_eq!(timestamp(1_951_000_000).unwrap(), "2031-10-29T00:26:40Z");
        assert_eq!(parse_time("2031-10-29T00:26:40Z").unwrap(), 1_951_000_000.0);
        assert_eq!(parse_time("2024-02-29T00:00:00.5Z").unwrap(), 1_709_164_800.5);
        assert!(parse_time("2023-02-29T00:00:00Z").is_err());
        assert!(parse_time("2023-01-01T00:00:00+09:00").is_err());
    }

    #[test]
    fn domains_and_dids() {
        assert!(is_domain("pds.oriom.dev"));
        assert!(!is_domain("localhost") && !is_domain("-a.dev") && !is_domain("A.dev"));
        assert!(is_did("did:plc:vwi7zhfyamyvrg5v6ycjgzxi"));
        assert!(!is_did("did:plc:vwi7zhfyamyvrg5v6ycjgzx1"));
    }
}
