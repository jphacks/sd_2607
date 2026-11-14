//! 秘密鍵ファイルの age 暗号化（ASCII armor）。age CLI と互換。
//!
//! - 暗号化: X25519 受信者（`age1...`、受信者ファイル）またはパスフレーズ。
//! - 復号: `MOSIMO_CA_AGE_IDENTITY`（`:` 区切りの identity ファイル）、パスフレーズなら端末から入力。

use age::armor::{ArmoredReader, ArmoredWriter, Format};
use age::secrecy::SecretString;
use anyhow::{anyhow, bail, ensure, Context, Result};
use std::io::{Read, Write};
use std::path::{Path, PathBuf};
use std::str::FromStr;
use std::sync::Mutex;

pub enum Plan {
    Recipients(Vec<age::x25519::Recipient>),
    Passphrase,
}

static PASSPHRASE: Mutex<Option<String>> = Mutex::new(None);

impl Plan {
    pub fn describe(&self) -> String {
        match self {
            Plan::Recipients(r) => format!("age（受信者 {} 件）", r.len()),
            Plan::Passphrase => "age（パスフレーズ）".into(),
        }
    }
}

pub fn parse_recipients(text: &str, origin: &str) -> Result<Vec<age::x25519::Recipient>> {
    text.lines()
        .map(str::trim)
        .filter(|l| !l.is_empty() && !l.starts_with('#'))
        .map(|l| age::x25519::Recipient::from_str(l).map_err(|e| anyhow!("{origin}: age の受信者として読めません: {l} ({e})")))
        .collect()
}

pub fn recipients_file(path: &Path) -> Result<Vec<age::x25519::Recipient>> {
    let text = std::fs::read_to_string(path).with_context(|| format!("{} を読めません", path.display()))?;
    let recipients = parse_recipients(&text, &path.display().to_string())?;
    ensure!(!recipients.is_empty(), "{} に受信者がありません", path.display());
    Ok(recipients)
}

fn prompt_new_passphrase() -> Result<String> {
    if let Some(p) = PASSPHRASE.lock().unwrap().clone() {
        return Ok(p);
    }
    let first = rpassword::prompt_password("age パスフレーズ（暗号化）: ")?;
    ensure!(first.len() >= 12, "パスフレーズは 12 文字以上にしてください");
    let second = rpassword::prompt_password("age パスフレーズ（確認）: ")?;
    ensure!(first == second, "パスフレーズが一致しません");
    *PASSPHRASE.lock().unwrap() = Some(first.clone());
    Ok(first)
}

pub fn encrypt(plaintext: &[u8], plan: &Plan) -> Result<Vec<u8>> {
    let encryptor = match plan {
        Plan::Recipients(recipients) => {
            age::Encryptor::with_recipients(recipients.iter().map(|r| r as &dyn age::Recipient)).map_err(|e| anyhow!("age: {e}"))?
        }
        Plan::Passphrase => age::Encryptor::with_user_passphrase(SecretString::from(prompt_new_passphrase()?)),
    };
    let mut out = Vec::new();
    let armor = ArmoredWriter::wrap_output(&mut out, Format::AsciiArmor)?;
    let mut writer = encryptor.wrap_output(armor)?;
    writer.write_all(plaintext)?;
    writer.finish()?.finish()?;
    Ok(out)
}

fn identity_files() -> Vec<PathBuf> {
    std::env::var("MOSIMO_CA_AGE_IDENTITY").unwrap_or_default().split(':').filter(|s| !s.is_empty()).map(PathBuf::from).collect()
}

pub fn decrypt(ciphertext: &[u8], label: &str) -> Result<Vec<u8>> {
    let decryptor = age::Decryptor::new(ArmoredReader::new(ciphertext)).map_err(|e| anyhow!("{label}: age として読めません ({e})"))?;
    let mut reader = if decryptor.is_scrypt() {
        let cached = PASSPHRASE.lock().unwrap().clone();
        let passphrase = match cached {
            Some(p) => p,
            None => rpassword::prompt_password(format!("{label} の age パスフレーズ: "))?,
        };
        let identity = age::scrypt::Identity::new(SecretString::from(passphrase.clone()));
        let reader = decryptor.decrypt(std::iter::once(&identity as &dyn age::Identity)).map_err(|e| anyhow!("{label}: 復号できません（{e}）"))?;
        *PASSPHRASE.lock().unwrap() = Some(passphrase);
        reader
    } else {
        let files = identity_files();
        if files.is_empty() {
            bail!("{label} は受信者指定で暗号化されています。MOSIMO_CA_AGE_IDENTITY に identity ファイルを指定してください");
        }
        let mut identities: Vec<Box<dyn age::Identity>> = Vec::new();
        for file in &files {
            let parsed = age::IdentityFile::from_file(file.display().to_string())
                .with_context(|| format!("{} を読めません", file.display()))?
                .into_identities()
                .map_err(|e| anyhow!("{}: {e}", file.display()))?;
            identities.extend(parsed);
        }
        decryptor.decrypt(identities.iter().map(|i| i.as_ref() as &dyn age::Identity)).map_err(|e| anyhow!("{label}: 復号できません（{e}）"))?
    };
    let mut plain = Vec::new();
    reader.read_to_end(&mut plain)?;
    Ok(plain)
}
