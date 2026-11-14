//! 失効の制御イベント（attest.revocation §5）と、リレーへの送信。

use crate::cbor::{self, Value};
use crate::crypto;
use crate::protocol as p;
use anyhow::{anyhow, bail, ensure, Result};
use serde_json::json;
use std::time::{Duration, Instant};

pub const CONTROL_KIND: u64 = 8173;
pub const TAG: &str = "mosimo.jisaku.dev";
pub const REVOCATION_TAG: &str = "mosimo.jisaku.dev/revocation";

/// 運搬者の鍵は通知の真正性に関係しないため、使い捨ての鍵で署名する。
pub fn revocation_event(revocation: &Value, exp: &str, created_at: i64) -> Result<serde_json::Value> {
    p::validate_revocation(revocation)?;
    let Value::Map(mut fields) = revocation.clone() else { bail!("Not a revocation") };
    fields.remove("$type");
    let tags = json!([
        ["t", TAG],
        ["t", REVOCATION_TAG],
        ["mosimo:exp", exp],
        [format!("mosimo:ext:{}", p::REVOCATION), p::b64(&cbor::encode(&Value::Map(fields), 65_536)?)],
    ]);
    let carrier = crypto::generate_secret();
    let pubkey = hex::encode(crypto::xonly(&carrier));
    let serialized = serde_json::to_string(&json!([0, pubkey, created_at, CONTROL_KIND, tags, ""]))?;
    let id = crypto::sha256(serialized.as_bytes());
    let sig = crypto::schnorr_sign(&id, &carrier)?;
    Ok(json!({ "id": hex::encode(id), "pubkey": pubkey, "created_at": created_at, "kind": CONTROL_KIND,
                "tags": tags, "content": "", "sig": hex::encode(sig) }))
}

/// `["EVENT", event]` を送り、`["OK", id, accepted, message]` を待つ。
pub fn publish(url: &str, event: &serde_json::Value) -> Result<(bool, String)> {
    ensure!(url.starts_with("wss://"), "wss:// のリレーだけを受け付けます: {url}");
    let (mut socket, _) = tungstenite::connect(url).map_err(|e| anyhow!("{url}: 接続できません ({e})"))?;
    if let tungstenite::stream::MaybeTlsStream::Rustls(s) = socket.get_mut() {
        s.get_mut().set_read_timeout(Some(Duration::from_secs(15)))?;
    }
    socket.send(tungstenite::Message::Text(json!(["EVENT", event]).to_string()))?;
    let id = event["id"].as_str().unwrap_or_default().to_string();
    let deadline = Instant::now() + Duration::from_secs(15);
    while Instant::now() < deadline {
        let message = socket.read().map_err(|e| anyhow!("{url}: 応答を読めません ({e})"))?;
        if let tungstenite::Message::Text(text) = message {
            let Ok(serde_json::Value::Array(items)) = serde_json::from_str::<serde_json::Value>(&text) else { continue };
            if items.first().and_then(|v| v.as_str()) == Some("OK") && items.get(1).and_then(|v| v.as_str()) == Some(id.as_str()) {
                let _ = socket.close(None);
                return Ok((items.get(2).and_then(|v| v.as_bool()) == Some(true), items.get(3).and_then(|v| v.as_str()).unwrap_or("").to_string()));
            }
        }
    }
    bail!("{url}: 応答がありません")
}
