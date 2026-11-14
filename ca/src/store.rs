//! 鍵ディレクトリ。ディレクトリの入れ子は「親鍵が子鍵に署名した」という関係だけを表す。
//!
//! ```text
//! keys/<名前>/                 管理の起点（anchor.json がある）。プロトコル上のルート鍵でも、
//!                              他の認証局から署名された鍵（upstream.cbor にそのチェーン）でもよい
//! keys/<名前>/<子>/            起点の鍵が署名した鍵（attestation.cbor）。名前は親が発行時に付けた owner.id
//! keys/<名前>/<子>/<孫>/ ...   以下同様
//! ```
//!
//! 鍵ディレクトリのファイル:
//! - `public.pem`              SubjectPublicKeyInfo（secp256k1）
//! - `secret.pem[.age]`        PKCS#8。外部で生成された鍵には無い
//! - `anchor.json`             起点だけ。チェーンの検証に使う信頼の起点（受信者に配るルート設定と同じ形）
//! - `upstream.cbor`           起点だけ・任意。anchor のルートからこの鍵までの鍵署名の配列
//! - `attestation.cbor`        起点以外。親が署名した KeyAttestation
//! - `pre-revocation.cbor`     親が発行時に署名した事前発行形の失効通知（起点では upstream の発行者から受け取ったもの）
//! - `revocation.cbor`         作成した失効（Revocation）。発信したかは失効リストへの掲載で決まる
//! - `_revocation-list/`       issuerDomain を持つ鍵だけ。entries/<id>.cbor と配信する revocations.cbor
//! - `_importer/`              転載サーバーの DID と atproto・PLC 鍵
//!
//! `.` や `_` で始まるディレクトリは子鍵として扱わない。

use crate::age_io;
use crate::cbor::{self, Value};
use crate::crypto;
use crate::protocol::{self as p, RootConfig};
use anyhow::{anyhow, bail, ensure, Context, Result};
use k256::SecretKey;
use std::fs;
use std::io::Write;
use std::os::unix::fs::{DirBuilderExt, OpenOptionsExt, PermissionsExt};
use std::path::{Path, PathBuf};

pub const LIST_DIR: &str = "_revocation-list";
pub const IMPORTER_DIR: &str = "_importer";

pub struct Node {
    pub dir: PathBuf,
    pub name: String,
    pub parent: Option<usize>,
    pub top: usize,
    pub pubkey: Vec<u8>,
    /// 親（起点では upstream の最後の発行者）が署名した鍵署名。プロトコル上のルート鍵だけ None。
    pub attestation: Option<Value>,
    /// 起点だけ。
    pub anchor: Option<RootConfig>,
    /// 起点だけ。anchor のルートから自分までの鍵署名。
    pub upstream: Vec<Value>,
}

pub struct Tree {
    pub keys_dir: PathBuf,
    pub nodes: Vec<Node>,
}

pub fn ensure_private_dir(dir: &Path) -> Result<()> {
    if !dir.exists() {
        fs::DirBuilder::new().recursive(true).mode(0o700).create(dir)?;
    }
    let mode = fs::metadata(dir)?.permissions().mode() & 0o777;
    ensure!(mode & 0o077 == 0, "{} は group/others から読めない権限にしてください（現在 {:o}）", dir.display(), mode);
    Ok(())
}

fn mkdir_private(dir: &Path) -> Result<()> {
    fs::DirBuilder::new().recursive(true).mode(0o700).create(dir).with_context(|| format!("{} を作れません", dir.display()))
}

/// 0600 で一時ファイルに書いて fsync し、rename で置き換える。exclusive なら既存ファイルを上書きしない。
pub fn write_file(path: &Path, data: &[u8], exclusive: bool) -> Result<()> {
    ensure!(!(exclusive && path.exists()), "{} は既にあります", path.display());
    if let Some(parent) = path.parent() {
        mkdir_private(parent)?;
    }
    let tmp = path.with_file_name(format!(".{}.tmp-{}", path.file_name().unwrap().to_string_lossy(), std::process::id()));
    let result = (|| -> Result<()> {
        let mut f = fs::OpenOptions::new().write(true).create_new(true).mode(0o600).open(&tmp)?;
        f.write_all(data)?;
        f.sync_all()?;
        fs::rename(&tmp, path)?;
        if let Some(parent) = path.parent() {
            fs::File::open(parent)?.sync_all()?;
        }
        Ok(())
    })();
    if result.is_err() {
        let _ = fs::remove_file(&tmp);
    }
    result.with_context(|| format!("{} を書き込めません", path.display()))
}

/// 一時ディレクトリに全ファイルを書いてから rename する。途中で失敗しても作りかけを残さない。
pub fn create_dir_atomically(dir: &Path, files: &[(String, Vec<u8>)]) -> Result<()> {
    ensure!(!dir.exists(), "{} は既にあります", dir.display());
    let parent = dir.parent().ok_or_else(|| anyhow!("invalid directory"))?;
    mkdir_private(parent)?;
    let staging = parent.join(format!(".staging-{}-{}", dir.file_name().unwrap().to_string_lossy(), std::process::id()));
    let result = (|| -> Result<()> {
        mkdir_private(&staging)?;
        for (name, data) in files {
            write_file(&staging.join(name), data, true)?;
        }
        fs::rename(&staging, dir)?;
        fs::File::open(parent)?.sync_all()?;
        Ok(())
    })();
    if result.is_err() {
        let _ = fs::remove_dir_all(&staging);
    }
    result
}

fn read_cbor(path: &Path, max: usize) -> Result<Value> {
    cbor::decode(&fs::read(path).with_context(|| format!("{} を読めません", path.display()))?, max).with_context(|| format!("{} が不正です", path.display()))
}

fn child_dirs(dir: &Path) -> Result<Vec<PathBuf>> {
    let mut dirs: Vec<PathBuf> = fs::read_dir(dir)?
        .filter_map(|e| e.ok())
        .filter(|e| e.file_type().is_ok_and(|t| t.is_dir()))
        .map(|e| e.path())
        .filter(|p| p.file_name().is_some_and(|n| !n.to_string_lossy().starts_with(['.', '_'])))
        .collect();
    dirs.sort();
    Ok(dirs)
}

impl Tree {
    pub fn load(keys_dir: &Path) -> Result<Tree> {
        ensure_private_dir(keys_dir)?;
        let mut tree = Tree { keys_dir: keys_dir.to_path_buf(), nodes: Vec::new() };
        for dir in child_dirs(keys_dir)? {
            if dir.join("anchor.json").exists() {
                tree.load_top(&dir)?;
            }
        }
        Ok(tree)
    }

    fn load_top(&mut self, dir: &Path) -> Result<()> {
        let json: serde_json::Value =
            serde_json::from_str(&fs::read_to_string(dir.join("anchor.json"))?).with_context(|| format!("{}/anchor.json が不正です", dir.display()))?;
        let anchor = RootConfig::from_json(&json).with_context(|| format!("{}/anchor.json", dir.display()))?;
        let upstream_path = dir.join("upstream.cbor");
        let upstream: Vec<Value> = if upstream_path.exists() {
            read_cbor(&upstream_path, 65_536)?.as_array().ok_or_else(|| anyhow!("upstream.cbor must be an array"))?.to_vec()
        } else {
            Vec::new()
        };
        for a in &upstream {
            p::validate_attestation(a)?;
        }
        let (pubkey, attestation) = match upstream.last() {
            None => (anchor.pubkey.clone(), None),
            Some(last) => {
                ensure!(p::bytes_field(p::body(&upstream[0]), "issuer") == anchor.pubkey, "{}: upstream が anchor のルートから始まっていません", dir.display());
                ensure!(
                    dir.file_name().is_some_and(|n| n.to_string_lossy() == p::owner_id(last)),
                    "{}: ディレクトリ名が発行者の付けた名前（owner.id = {}）と一致しません",
                    dir.display(),
                    p::owner_id(last)
                );
                (p::bytes_field(p::body(last), "subject").to_vec(), Some(last.clone()))
            }
        };
        let index = self.nodes.len();
        self.push(dir, None, index, pubkey, attestation, Some(anchor), upstream)
    }

    #[allow(clippy::too_many_arguments)]
    fn push(
        &mut self,
        dir: &Path,
        parent: Option<usize>,
        top: usize,
        pubkey: Vec<u8>,
        attestation: Option<Value>,
        anchor: Option<RootConfig>,
        upstream: Vec<Value>,
    ) -> Result<()> {
        let public = dir.join("public.pem");
        if public.exists() {
            let x = crypto::xonly_from_public_pem(&fs::read_to_string(&public)?)?;
            ensure!(x[..] == pubkey[pubkey.len() - 32..], "{}: public.pem が鍵署名の鍵と一致しません", dir.display());
        }
        let index = self.nodes.len();
        self.nodes.push(Node {
            dir: dir.to_path_buf(),
            name: dir.file_name().unwrap().to_string_lossy().into_owned(),
            parent,
            top,
            pubkey,
            attestation,
            anchor,
            upstream,
        });
        for child in child_dirs(dir)? {
            let path = child.join("attestation.cbor");
            if !path.exists() {
                continue;
            }
            let attestation = read_cbor(&path, 65_536)?;
            p::validate_attestation(&attestation).with_context(|| format!("{}", path.display()))?;
            let body = p::body(&attestation);
            ensure!(p::bytes_field(body, "issuer") == self.nodes[index].pubkey, "{}: 親ディレクトリの鍵が署名した鍵署名ではありません", path.display());
            let name = child.file_name().unwrap().to_string_lossy().into_owned();
            ensure!(
                p::owner_id(&attestation) == name,
                "{}: ディレクトリ名が親の付けた名前（owner.id = {}）と一致しません",
                child.display(),
                p::owner_id(&attestation)
            );
            let subject = p::bytes_field(body, "subject").to_vec();
            self.push(&child, Some(index), top, subject, Some(attestation), None, Vec::new())?;
        }
        Ok(())
    }

    pub fn node(&self, i: usize) -> &Node {
        &self.nodes[i]
    }

    pub fn rel(&self, i: usize) -> String {
        self.nodes[i].dir.strip_prefix(&self.keys_dir).map(|p| p.display().to_string()).unwrap_or_else(|_| self.nodes[i].dir.display().to_string())
    }

    pub fn rel_path(&self, path: &Path) -> String {
        path.strip_prefix(&self.keys_dir).map(|p| p.display().to_string()).unwrap_or_else(|_| path.display().to_string())
    }

    pub fn anchor(&self, i: usize) -> &RootConfig {
        self.nodes[self.nodes[i].top].anchor.as_ref().unwrap()
    }

    pub fn is_root(&self, i: usize) -> bool {
        self.nodes[i].attestation.is_none()
    }

    /// 起点から i までの管理下の鍵（i を含む）。
    pub fn path(&self, i: usize) -> Vec<usize> {
        let mut path = vec![i];
        while let Some(parent) = self.nodes[*path.last().unwrap()].parent {
            path.push(parent);
        }
        path.reverse();
        path
    }

    pub fn children(&self, i: usize) -> Vec<usize> {
        (0..self.nodes.len()).filter(|&j| self.nodes[j].parent == Some(i)).collect()
    }

    pub fn descendants(&self, i: usize) -> Vec<usize> {
        self.children(i).into_iter().flat_map(|c| std::iter::once(c).chain(self.descendants(c))).collect()
    }

    /// anchor のルートから i までの鍵署名。
    pub fn chain(&self, i: usize) -> Vec<Value> {
        let path = self.path(i);
        let mut chain = self.nodes[path[0]].upstream.clone();
        chain.extend(path[1..].iter().map(|&j| self.nodes[j].attestation.clone().unwrap()));
        chain
    }

    pub fn stratum(&self, i: usize) -> usize {
        self.chain(i).len()
    }

    pub fn roles(&self, i: usize) -> Vec<String> {
        match &self.nodes[i].attestation {
            None => self.anchor(i).roles.clone(),
            Some(a) => p::roles(a),
        }
    }

    pub fn max_depth(&self, i: usize) -> i128 {
        match &self.nodes[i].attestation {
            None => self.anchor(i).max_depth,
            Some(a) => p::delegation_depth(a),
        }
    }

    pub fn validity(&self, i: usize) -> p::Validity {
        self.nodes[i].attestation.as_ref().map(p::validity).unwrap_or((None, None))
    }

    pub fn block(&self, i: usize, t: &str) -> Option<&Value> {
        self.nodes[i].attestation.as_ref().and_then(|a| p::block_of(a, t))
    }

    pub fn domain(&self, i: usize) -> Option<String> {
        self.block(i, p::ISSUER_DOMAIN).and_then(|b| b.get("domain")).and_then(Value::as_text).map(str::to_string)
    }

    /// チェーン上で最も近い organization（orgId, orgName）。
    pub fn organization(&self, i: usize) -> Option<(String, String)> {
        self.chain(i).iter().rev().find_map(|a| {
            p::block_of(a, p::ORGANIZATION).map(|o| {
                (o.get("orgId").and_then(Value::as_text).unwrap_or("").to_string(), o.get("orgName").and_then(Value::as_text).unwrap_or("").to_string())
            })
        })
    }

    /// チェーン上で最も近い importer の範囲。None は「import 役割があれば制限なし」。
    pub fn importer_scope(&self, i: usize) -> Option<p::Scope> {
        self.chain(i).iter().rev().find_map(|a| p::block_of(a, p::IMPORTER).map(p::importer_scope))
    }

    pub fn owner(&self, i: usize) -> (String, String) {
        match &self.nodes[i].attestation {
            None => ("root".into(), self.anchor(i).name.clone()),
            Some(a) => {
                let o = p::body(a).get("owner").unwrap();
                (o.get("id").and_then(Value::as_text).unwrap_or("").into(), o.get("name").and_then(Value::as_text).unwrap_or("").into())
            }
        }
    }

    pub fn pubkey_hex(&self, i: usize) -> String {
        hex::encode(&self.nodes[i].pubkey)
    }

    pub fn secret_file(&self, i: usize) -> Option<PathBuf> {
        ["secret.pem", "secret.pem.age"].iter().map(|f| self.nodes[i].dir.join(f)).find(|p| p.exists())
    }

    pub fn secret(&self, i: usize) -> Result<SecretKey> {
        let path = self.secret_file(i).ok_or_else(|| anyhow!("{}: 秘密鍵がありません（外部で生成された鍵）", self.rel(i)))?;
        let secret = read_secret_pem(&path, &self.rel_path(&path))?;
        ensure!(crypto::xonly(&secret)[..] == self.nodes[i].pubkey[self.nodes[i].pubkey.len() - 32..], "{}: 秘密鍵が公開鍵と一致しません", self.rel(i));
        Ok(secret)
    }

    pub fn revocation(&self, i: usize) -> Result<Option<Value>> {
        let path = self.nodes[i].dir.join("revocation.cbor");
        path.exists().then(|| read_cbor(&path, 65_536)).transpose()
    }

    pub fn pre_revocation(&self, i: usize) -> Result<Option<Value>> {
        let path = self.nodes[i].dir.join("pre-revocation.cbor");
        let notice = path.exists().then(|| read_cbor(&path, 65_536)).transpose()?;
        if let Some(n) = &notice {
            p::validate_notice(n)?;
        }
        Ok(notice)
    }

    pub fn list_entries_dir(&self, i: usize) -> PathBuf {
        self.nodes[i].dir.join(LIST_DIR).join("entries")
    }

    pub fn list_file(&self, i: usize) -> PathBuf {
        self.nodes[i].dir.join(LIST_DIR).join("revocations.cbor")
    }

    pub fn published_ids(&self, i: usize) -> Vec<String> {
        let mut ids: Vec<String> = fs::read_dir(self.list_entries_dir(i))
            .map(|rd| {
                rd.filter_map(|e| e.ok())
                    .filter_map(|e| e.file_name().to_str().and_then(|n| n.strip_suffix(".cbor")).map(str::to_string))
                    .filter(|n| n.len() == 64 && n.bytes().all(|c| c.is_ascii_hexdigit()))
                    .collect()
            })
            .unwrap_or_default();
        ids.sort();
        ids
    }

    /// 失効通知を掲載する失効リスト: 管理下のチェーン上（自身を含む）で issuerDomain を持つ鍵（list §3）。
    pub fn list_publishers(&self, i: usize) -> Vec<usize> {
        self.path(i).into_iter().filter(|&j| self.domain(j).is_some()).collect()
    }

    pub fn published(&self, i: usize) -> Result<bool> {
        let Some(rev) = self.revocation(i)? else { return Ok(false) };
        let id = p::body_id(p::body(rev.get("notice").unwrap_or(&Value::Null)))?;
        Ok(self.list_publishers(i).iter().any(|&j| self.published_ids(j).contains(&id)))
    }

    /// i または管理下の祖先のうち、失効を発信済みの最も近い鍵。
    pub fn revoked_by(&self, i: usize) -> Result<Option<usize>> {
        for j in self.path(i).into_iter().rev() {
            if self.published(j)? {
                return Ok(Some(j));
            }
        }
        Ok(None)
    }

    /// 引数の解決: keys からの相対パス、実パス、公開鍵 hex の先頭 8 桁以上。
    pub fn resolve(&self, reference: &str) -> Result<usize> {
        if reference.len() >= 8 && reference.len() <= 66 && reference.bytes().all(|c| c.is_ascii_hexdigit() && !c.is_ascii_uppercase()) {
            let hits: Vec<usize> = (0..self.nodes.len()).filter(|&i| self.pubkey_hex(i).starts_with(reference)).collect();
            match hits.len() {
                1 => return Ok(hits[0]),
                0 => {}
                n => bail!("公開鍵 {reference} に一致する鍵が {n} 件あります"),
            }
        }
        let path = if Path::new(reference).is_absolute() { PathBuf::from(reference) } else { self.keys_dir.join(reference) };
        let path = fs::canonicalize(&path).unwrap_or(path);
        (0..self.nodes.len()).find(|&i| fs::canonicalize(&self.nodes[i].dir).is_ok_and(|d| d == path)).ok_or_else(|| anyhow!("鍵が見つかりません: {reference}"))
    }
}

pub fn read_secret_pem(path: &Path, label: &str) -> Result<SecretKey> {
    let data = fs::read(path).with_context(|| format!("{} を読めません", path.display()))?;
    let pem =
        if path.extension().is_some_and(|e| e == "age") { zeroize::Zeroizing::new(age_io::decrypt(&data, label)?) } else { zeroize::Zeroizing::new(data) };
    crypto::secret_from_pem(std::str::from_utf8(&pem)?)
}
