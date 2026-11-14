//! atproto deterministic CBOR (attest §1 の `C(x)`).
//!
//! definite length、最短の整数表現、map key は UTF-8 バイト長昇順・同長なら辞書順。
//! float・indefinite length・tag・重複 key・末尾データ・非正規の表現は拒否する。

use anyhow::{bail, ensure, Result};
use std::collections::BTreeMap;

const MAX_DEPTH: usize = 16;
const MAX_ITEMS: usize = 8192;

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Value {
    Int(i128),
    Bytes(Vec<u8>),
    Text(String),
    Array(Vec<Value>),
    Map(BTreeMap<String, Value>),
    Bool(bool),
    Null,
}

impl Value {
    pub fn text(s: impl Into<String>) -> Value {
        Value::Text(s.into())
    }

    pub fn map<const N: usize>(entries: [(&str, Value); N]) -> Value {
        Value::Map(entries.into_iter().map(|(k, v)| (k.to_string(), v)).collect())
    }

    pub fn get(&self, key: &str) -> Option<&Value> {
        match self {
            Value::Map(m) => m.get(key),
            _ => None,
        }
    }

    pub fn as_map(&self) -> Option<&BTreeMap<String, Value>> {
        match self {
            Value::Map(m) => Some(m),
            _ => None,
        }
    }

    pub fn as_text(&self) -> Option<&str> {
        match self {
            Value::Text(s) => Some(s),
            _ => None,
        }
    }

    pub fn as_bytes(&self) -> Option<&[u8]> {
        match self {
            Value::Bytes(b) => Some(b),
            _ => None,
        }
    }

    pub fn as_int(&self) -> Option<i128> {
        match self {
            Value::Int(i) => Some(*i),
            _ => None,
        }
    }

    pub fn as_array(&self) -> Option<&[Value]> {
        match self {
            Value::Array(a) => Some(a),
            _ => None,
        }
    }

    /// 表示用の JSON。bytes は hex にする。
    pub fn to_display_json(&self) -> serde_json::Value {
        match self {
            Value::Int(i) => serde_json::Value::from(*i as i64),
            Value::Bytes(b) => serde_json::Value::String(hex::encode(b)),
            Value::Text(s) => serde_json::Value::String(s.clone()),
            Value::Array(a) => serde_json::Value::Array(a.iter().map(Value::to_display_json).collect()),
            Value::Map(m) => {
                let mut keys: Vec<&String> = m.keys().collect();
                // $type を先頭に置くと読みやすい
                keys.sort_by_key(|k| (k.as_str() != "$type", k.as_str()));
                serde_json::Value::Object(keys.into_iter().map(|k| (k.clone(), m[k].to_display_json())).collect())
            }
            Value::Bool(b) => serde_json::Value::Bool(*b),
            Value::Null => serde_json::Value::Null,
        }
    }
}

pub fn encode(value: &Value, max_bytes: usize) -> Result<Vec<u8>> {
    let mut out = Vec::new();
    write(&mut out, value, 0)?;
    ensure!(out.len() <= max_bytes, "CBOR resource limit");
    Ok(out)
}

fn head(out: &mut Vec<u8>, major: u8, n: u64) {
    let m = major << 5;
    if n < 24 {
        out.push(m | n as u8);
    } else if n < 1 << 8 {
        out.push(m | 24);
        out.push(n as u8);
    } else if n < 1 << 16 {
        out.push(m | 25);
        out.extend_from_slice(&(n as u16).to_be_bytes());
    } else if n < 1 << 32 {
        out.push(m | 26);
        out.extend_from_slice(&(n as u32).to_be_bytes());
    } else {
        out.push(m | 27);
        out.extend_from_slice(&n.to_be_bytes());
    }
}

fn write(out: &mut Vec<u8>, value: &Value, depth: usize) -> Result<()> {
    ensure!(depth <= MAX_DEPTH, "CBOR resource limit");
    match value {
        Value::Int(i) => {
            ensure!(*i >= -(1i128 << 64) && *i < (1i128 << 64), "Integer out of range");
            if *i >= 0 {
                head(out, 0, *i as u64)
            } else {
                head(out, 1, (-1 - *i) as u64)
            }
        }
        Value::Bytes(b) => {
            head(out, 2, b.len() as u64);
            out.extend_from_slice(b);
        }
        Value::Text(s) => {
            head(out, 3, s.len() as u64);
            out.extend_from_slice(s.as_bytes());
        }
        Value::Array(a) => {
            head(out, 4, a.len() as u64);
            for item in a {
                write(out, item, depth + 1)?;
            }
        }
        Value::Map(m) => {
            head(out, 5, m.len() as u64);
            let mut keys: Vec<&String> = m.keys().collect();
            keys.sort_by(|a, b| a.len().cmp(&b.len()).then_with(|| a.as_bytes().cmp(b.as_bytes())));
            for k in keys {
                write(out, &Value::Text(k.clone()), depth + 1)?;
                write(out, &m[k], depth + 1)?;
            }
        }
        Value::Bool(true) => out.push(0xf5),
        Value::Bool(false) => out.push(0xf4),
        Value::Null => out.push(0xf6),
    }
    Ok(())
}

pub fn decode(data: &[u8], max_bytes: usize) -> Result<Value> {
    ensure!(data.len() <= max_bytes, "CBOR resource limit");
    let mut reader = Reader { data, offset: 0, items: 0 };
    let value = reader.read(0)?;
    ensure!(reader.offset == data.len(), "Trailing CBOR");
    ensure!(encode(&value, max_bytes)? == data, "Noncanonical CBOR");
    Ok(value)
}

struct Reader<'a> {
    data: &'a [u8],
    offset: usize,
    items: usize,
}

impl Reader<'_> {
    fn take(&mut self, n: usize) -> Result<&[u8]> {
        ensure!(self.offset.checked_add(n).is_some_and(|end| end <= self.data.len()), "Truncated CBOR");
        let chunk = &self.data[self.offset..self.offset + n];
        self.offset += n;
        Ok(chunk)
    }

    fn read(&mut self, depth: usize) -> Result<Value> {
        self.items += 1;
        ensure!(depth <= MAX_DEPTH && self.items <= MAX_ITEMS, "CBOR resource limit");
        let first = self.take(1)?[0];
        let (major, ai) = (first >> 5, first & 31);
        if major == 7 {
            return match ai {
                20 => Ok(Value::Bool(false)),
                21 => Ok(Value::Bool(true)),
                22 => Ok(Value::Null),
                _ => bail!("Unsupported CBOR simple/float"),
            };
        }
        ensure!(ai < 28, "Indefinite/invalid CBOR");
        let n: u64 = match ai {
            0..=23 => ai as u64,
            _ => {
                let bytes = self.take(1 << (ai - 24))?;
                bytes.iter().fold(0u64, |acc, b| (acc << 8) | *b as u64)
            }
        };
        Ok(match major {
            0 => Value::Int(n as i128),
            1 => Value::Int(-1 - n as i128),
            2 => Value::Bytes(self.take(usize::try_from(n)?)?.to_vec()),
            3 => Value::Text(String::from_utf8(self.take(usize::try_from(n)?)?.to_vec()).map_err(|_| anyhow::anyhow!("Invalid UTF-8"))?),
            4 => {
                ensure!(n as usize <= MAX_ITEMS, "CBOR resource limit");
                Value::Array((0..n).map(|_| self.read(depth + 1)).collect::<Result<_>>()?)
            }
            5 => {
                ensure!(n as usize <= MAX_ITEMS, "CBOR resource limit");
                let mut map = BTreeMap::new();
                for _ in 0..n {
                    let Value::Text(key) = self.read(depth + 1)? else { bail!("Map keys must be text") };
                    let value = self.read(depth + 1)?;
                    ensure!(map.insert(key, value).is_none(), "Duplicate map key");
                }
                Value::Map(map)
            }
            _ => bail!("CBOR tags are not allowed"),
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn canonical_order_and_round_trip() {
        let v = Value::map([
            ("bb", Value::Int(1)),
            ("a", Value::Array(vec![Value::Int(-1), Value::text("x"), Value::Bytes(b"y".to_vec()), Value::Bool(true), Value::Null])),
            ("c", Value::Int(300)),
        ]);
        let bytes = encode(&v, 1 << 16).unwrap();
        assert_eq!(&bytes[1..3], &[0x61, b'a']); // 長さの短い key が先
        assert_eq!(decode(&bytes, 1 << 16).unwrap(), v);
    }

    #[test]
    fn rejects_noncanonical_input() {
        for bad in [
            &[0x18, 0x01][..],                           // 最短でない整数
            &[0xa2, 0x61, b'b', 0x01, 0x61, b'a', 0x02], // key の順序
            &[0xf9, 0x3c, 0x00],                         // float
            &[0xc1, 0x01],                               // tag
            &[0x01, 0x01],                               // 末尾データ
            &[0x9f, 0xff],                               // indefinite length
            &[0xa2, 0x61, b'a', 0x01, 0x61, b'a', 0x02], // 重複 key
        ] {
            assert!(decode(bad, 1 << 16).is_err(), "{bad:02x?}");
        }
    }
}
