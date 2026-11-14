use crate::age_io::{self, Plan};
use crate::cbor::{self, Value};
use crate::crypto;
use crate::nostr;
use crate::protocol::{self as p, ChainStatus, RootConfig};
use crate::store::{self, Tree, IMPORTER_DIR};
use anyhow::{anyhow, bail, ensure, Context, Result};
use clap::{Args, Parser, Subcommand};
use std::collections::BTreeMap;
use std::io::{BufRead, IsTerminal, Write};
use std::path::{Path, PathBuf};

const LIST_URL_PREFIX: &str = "/.well-known/mosimo/attest/revocations";
const PRESETS: [(&str, &str, &[&str]); 2] = [
    ("jma", "did:plc:vwi7zhfyamyvrg5v6ycjgzxi", &["ashfall", "earthquake", "eruption", "nankai-trough", "tsunami"]),
    (
        "kikikuru",
        "did:plc:kxwz5cz7o6g4jlmxh6doyfsm",
        &[
            "avalanche-warning",
            "dense-fog-warning",
            "dry-air-warning",
            "flood-warning",
            "frost-warning",
            "heavy-rain",
            "heavy-rain-warning",
            "icing-warning",
            "landslide-warning",
            "linear-rainband-warning",
            "low-temperature-warning",
            "melting-snow-warning",
            "other-warning",
            "significant-snowfall-warning",
            "snow-accretion-warning",
            "snow-warning",
            "storm-surge-warning",
            "thunderstorm-warning",
            "tornado-warning",
            "typhoon",
            "wave-warning",
            "weather-info",
            "wind-warning",
        ],
    ),
];
/// フィードの転載範囲のプリセット（importer §1.1）。名前、フィード名、types（原電文の Control/Title）。
const FEED_PRESETS: [(&str, &str, &[&str]); 1] = [(
    "jma-xml",
    "jma-xml",
    &[
        "震源・震度に関する情報",
        "気象警報・注意報（Ｒ０６）（大雨）",
        "気象警報・注意報（Ｒ０６）（土砂）",
        "気象警報・注意報（Ｒ０６）（高潮）",
        "気象警報・注意報（Ｒ０６）（暴風）",
        "気象警報・注意報（Ｒ０６）（波浪）",
        "気象警報・注意報（Ｒ０６）（大雪）",
        "気象警報・注意報（Ｒ０６）（その他注意報）",
    ],
)];

#[derive(Parser)]
#[command(name = "mosimo-ca", version, about = "dev.jisaku.mosimo.attest の認証局ツール（鍵署名・失効通知の発行と発信）")]
struct Cli {
    /// 鍵ディレクトリ（既定は作業ディレクトリから上にたどって見つけた ca/keys。MOSIMO_CA_KEYS でも指定できる）
    #[arg(long, global = true, env = "MOSIMO_CA_KEYS")]
    keys: Option<PathBuf>,
    #[command(subcommand)]
    command: Command,
}

#[derive(Args, Default, Clone)]
struct AgeArgs {
    /// age の受信者（age1...）。複数可
    #[arg(long = "age-recipient")]
    recipients: Vec<String>,
    /// age の受信者ファイル。複数可
    #[arg(long = "age-recipients-file")]
    recipients_files: Vec<PathBuf>,
    /// age のパスフレーズで暗号化する（端末から入力）
    #[arg(long = "age-passphrase")]
    passphrase: bool,
    /// 暗号化せずに保存する（非推奨）
    #[arg(long)]
    plaintext: bool,
}

#[derive(Subcommand)]
enum Command {
    /// 鍵の階層と状態を表示する
    Tree,
    /// 鍵署名・失効の内容を表示する
    Show { key: String },
    /// 信頼の起点からのチェーンを検証する（KEY 省略で全鍵）
    Verify { key: Option<String> },
    /// 新しいルート鍵を管理の起点として作る
    InitRoot {
        /// keys 直下のディレクトリ名
        name: String,
        /// 受信者に表示されるルートの名前
        #[arg(long)]
        display_name: Option<String>,
        /// 受信者に認めてもらう役割
        #[arg(long, value_delimiter = ',', default_value = "attest,import")]
        roles: Vec<String>,
        /// 下に作れる階層数（受信者の上限 16 = 実質無制限）
        #[arg(long, default_value_t = p::MAX_DEPTH as i64)]
        max_depth: i64,
        #[command(flatten)]
        age: AgeArgs,
    },
    /// 鍵ペアだけを作る（公開鍵を発行者に渡して issue --subject-public-pem で署名してもらう）
    Keygen {
        out: PathBuf,
        #[command(flatten)]
        age: AgeArgs,
    },
    /// 署名した鍵の受け取り側に渡す一式（upstream.cbor・pre-revocation.cbor・anchor.json）を書き出す
    Handoff { key: String, out: PathBuf },
    /// 他の発行者から署名された鍵を、管理の起点として取り込む（ディレクトリ名は発行者が付けた owner.id）
    Adopt {
        /// keygen で作ったディレクトリ（public.pem と secret.pem[.age]）
        #[arg(long)]
        key_dir: PathBuf,
        /// 発行者の handoff で受け取ったディレクトリ
        #[arg(long)]
        bundle: PathBuf,
    },
    /// 親鍵から子鍵を作成し、鍵署名と事前発行形の失効通知を発行する
    Issue(Box<IssueArgs>),
    /// 祖先の鍵（既定は親）で即時形の失効通知を作成する
    Revoke {
        key: String,
        /// 通知に署名する鍵（管理下の祖先、または self）
        #[arg(long)]
        by: Option<String>,
        /// compromise / superseded / ceased / unspecified
        #[arg(long)]
        reason: Option<String>,
        /// 理由の説明
        #[arg(long)]
        text: Option<String>,
        /// 失効の開始時刻（既定は現在。漏えい時は判明した時点より前にできる）
        #[arg(long)]
        effective_at: Option<String>,
        #[arg(long)]
        yes: bool,
    },
    /// 事前発行形の失効通知を本人の鍵で活性化する
    Activate {
        key: String,
        #[arg(long)]
        effective_at: Option<String>,
        #[arg(long)]
        yes: bool,
    },
    /// 作成済みの失効を確認したうえで失効リストに載せ、任意でリレーへ送る
    Publish {
        key: String,
        /// 制御イベント（kind 8173）を送るリレー。複数可
        #[arg(long = "relay")]
        relays: Vec<String>,
        /// 制御イベントをファイルにも書き出す（リレー以外の経路で運ぶとき）
        #[arg(long)]
        event_out: Option<PathBuf>,
        /// 非対話で発信するとき、対象の公開鍵の先頭 8 桁
        #[arg(long)]
        confirm: Option<String>,
        /// 確認だけ行い、何も書き込まない
        #[arg(long)]
        dry_run: bool,
    },
    /// 配信中の失効リストを取得し、手元の掲載内容と一致するか確認する
    CheckPublished {
        key: String,
        #[arg(long)]
        url: Option<String>,
    },
    /// 平文の秘密鍵を age で暗号化する
    Encrypt {
        key: String,
        #[command(flatten)]
        age: AgeArgs,
    },
    /// チェーン（Base64 の canonical CBOR）を出力する
    Chain {
        key: String,
        /// CBOR のまま書き出す
        #[arg(long)]
        cbor: Option<PathBuf>,
    },
    /// 転載サーバー（publisher）に配置する鍵一式（identity.json・chain.cbor・nostr.pem・atproto.pem・roots.json）を書き出す
    Credentials {
        key: String,
        /// 書き出すディレクトリ（新規作成）
        #[arg(long)]
        out: PathBuf,
    },
    /// 受信者に配るルート設定を出力する
    TrustExport { key: String },
    /// CBOR ファイル（鍵署名・失効通知・失効・失効リスト・チェーン）を種類を判別して読める形で表示し、署名を検証する
    Inspect {
        /// .cbor ファイル、Base64 のテキスト（chain の出力など）、または - で標準入力
        file: PathBuf,
        /// チェーンの検証に使うルート設定（省略時は鍵ディレクトリの起点から探す）
        #[arg(long)]
        anchor: Option<PathBuf>,
        /// JSON だけを出力する（jq 等に渡すとき）
        #[arg(long)]
        json: bool,
    },
    /// 旧 identity-cli.mjs の出力ディレクトリ（JSON 形式）を取り込む。子のディレクトリ名は鍵署名の owner.id
    ImportLegacy {
        /// keys 直下のディレクトリ名（ルート鍵の名前）
        name: String,
        dir: PathBuf,
        #[arg(long, default_value_t = p::MAX_DEPTH as i64)]
        max_depth: i64,
        #[command(flatten)]
        age: AgeArgs,
    },
}

#[derive(Args)]
struct IssueArgs {
    parent: String,
    /// 子の名前。鍵署名の owner.id になり、鍵ディレクトリ名にもなる
    name: String,
    /// delegation.maxDepth（既定は親より 1 小さい値）
    #[arg(long)]
    max_depth: Option<i64>,
    /// 末端の鍵（maxDepth 0）
    #[arg(long)]
    leaf: bool,
    /// 役割（親の役割の部分集合）
    #[arg(long, value_delimiter = ',')]
    roles: Option<Vec<String>>,
    /// owner.name（表示名）
    #[arg(long)]
    owner_name: Option<String>,
    /// issuerDomain（失効リストを配信するドメイン）
    #[arg(long)]
    domain: Option<String>,
    #[arg(long)]
    org_id: Option<String>,
    #[arg(long)]
    org_name: Option<String>,
    /// 有効日数
    #[arg(long)]
    days: Option<i64>,
    /// 有効期限（YYYY-MM-DD または Timestamp）
    #[arg(long)]
    until: Option<String>,
    /// 期限なし（信頼の起点がルート鍵で、その直下の鍵だけ）
    #[arg(long)]
    no_expiry: bool,
    /// 転載範囲のプリセット（jma, kikikuru, jma-xml）。複数可
    #[arg(long = "preset")]
    presets: Vec<String>,
    /// 転載範囲（DID=type1,type2）。複数可
    #[arg(long = "source")]
    sources: Vec<String>,
    /// フィードの転載範囲（FEED=type1,type2。importer §1.1）。複数可
    #[arg(long = "feed")]
    feeds: Vec<String>,
    #[arg(long)]
    trust_level: Option<i64>,
    #[arg(long)]
    trust_method: Option<String>,
    /// 外部で生成した鍵（x-only 32 バイト hex）に署名する
    #[arg(long)]
    subject_pubkey: Option<String>,
    /// 外部で生成した鍵（SPKI PEM）に署名する
    #[arg(long)]
    subject_public_pem: Option<PathBuf>,
    /// identity-cli の importer-secret.json の Nostr 鍵で作り、DID と atproto・PLC 鍵も保存する
    #[arg(long)]
    importer_identity: Option<PathBuf>,
    /// 最後の確認を省略する
    #[arg(long)]
    yes: bool,
    #[command(flatten)]
    age: AgeArgs,
}

pub fn run() -> i32 {
    let cli = Cli::parse();
    let keys = match cli.keys.clone().map(Ok).unwrap_or_else(default_keys_dir) {
        Ok(k) => k,
        Err(e) => {
            eprintln!("エラー: {e:#}");
            return 1;
        }
    };
    let mut ctx = Ctx { keys, now: p::now() };
    match ctx.dispatch(cli.command) {
        Ok(()) => 0,
        Err(e) => {
            eprintln!("エラー: {e:#}");
            1
        }
    }
}

fn is_mosimo_ca_crate(dir: &Path) -> bool {
    std::fs::read_to_string(dir.join("Cargo.toml")).is_ok_and(|t| t.lines().any(|l| l.trim() == "name = \"mosimo-ca\""))
}

/// 既定の鍵ディレクトリ。実行ファイルの場所（Nix ストアなど）には依存させず、作業ディレクトリから上にたどって
/// このクレートの ca/keys を探す。
fn default_keys_dir() -> Result<PathBuf> {
    let cwd = std::env::current_dir()?;
    for dir in cwd.ancestors() {
        for candidate in [dir.join("ca"), dir.to_path_buf()] {
            if is_mosimo_ca_crate(&candidate) && candidate.join("keys").is_dir() {
                return Ok(candidate.join("keys"));
            }
        }
    }
    bail!("鍵ディレクトリが見つかりません。リポジトリの中で実行するか、--keys か MOSIMO_CA_KEYS で指定してください")
}

struct Ctx {
    keys: PathBuf,
    now: i64,
}

// ---- 入出力 -----------------------------------------------------------------------------------------

fn tty() -> bool {
    std::io::stdin().is_terminal()
}

fn ask(label: &str, default: Option<&str>, flag: &str) -> Result<String> {
    if !tty() {
        return default.map(str::to_string).ok_or_else(|| anyhow!("{flag} を指定してください"));
    }
    loop {
        match default {
            Some(d) => print!("{label} [{d}]: "),
            None => print!("{label}: "),
        }
        std::io::stdout().flush()?;
        let mut line = String::new();
        ensure!(std::io::stdin().lock().read_line(&mut line)? > 0, "中断しました");
        let answer = line.trim();
        if !answer.is_empty() {
            return Ok(answer.to_string());
        }
        if let Some(d) = default {
            return Ok(d.to_string());
        }
    }
}

/// 空欄を許す質問。端末でなければ None。
fn ask_optional(label: &str) -> Result<Option<String>> {
    if !tty() {
        return Ok(None);
    }
    let answer = ask(&format!("{label}（空欄なら無し）"), Some(""), "")?;
    Ok((!answer.is_empty()).then_some(answer))
}

fn confirm(message: &str, yes: bool) -> Result<()> {
    if yes {
        return Ok(());
    }
    ensure!(tty(), "確認が必要です。内容を確認したうえで --yes を付けてください");
    let answer = ask(&format!("{message} [y/N]"), Some("n"), "")?;
    ensure!(answer.eq_ignore_ascii_case("y"), "取り消しました");
    Ok(())
}

fn pretty(v: &serde_json::Value) -> String {
    serde_json::to_string_pretty(v).unwrap_or_default()
}

/// 鍵の名前（= owner.id = ディレクトリ名）。DID（did:plc:...）も使えるよう : を許す。
pub fn check_name(name: &str) -> Result<()> {
    ensure!(
        !name.is_empty()
            && name.len() <= 128
            && name.bytes().next().is_some_and(|c| c.is_ascii_lowercase() || c.is_ascii_digit())
            && name.bytes().all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || b"._:-".contains(&c)),
        "名前は英小文字・数字・. _ : - で、英小文字か数字で始めてください（128 文字以内）: {name}"
    );
    Ok(())
}

fn parse_user_time(text: &str) -> Result<i64> {
    let full = if text.len() == 10 { format!("{text}T00:00:00Z") } else { text.to_string() };
    Ok(p::parse_time(&full)? as i64)
}

fn ts(seconds: i64) -> String {
    p::timestamp(seconds).unwrap_or_else(|_| seconds.to_string())
}

impl AgeArgs {
    /// 保存方法。指定がなければ keys/age-recipients.txt を使う。None は平文。
    fn plan(&self, keys: &Path, require_encryption: bool) -> Result<Option<Plan>> {
        let mut recipients = Vec::new();
        for r in &self.recipients {
            recipients.extend(age_io::parse_recipients(r, "--age-recipient")?);
        }
        for f in &self.recipients_files {
            recipients.extend(age_io::recipients_file(f)?);
        }
        let default_file = keys.join("age-recipients.txt");
        if recipients.is_empty() && !self.passphrase && !self.plaintext && default_file.exists() {
            recipients.extend(age_io::recipients_file(&default_file)?);
        }
        let encrypted = self.passphrase || !recipients.is_empty();
        ensure!(!(encrypted && self.plaintext), "--plaintext と age の指定は同時に使えません");
        ensure!(!(self.passphrase && !recipients.is_empty()), "パスフレーズと受信者は同時に使えません");
        if self.passphrase {
            return Ok(Some(Plan::Passphrase));
        }
        if !recipients.is_empty() {
            return Ok(Some(Plan::Recipients(recipients)));
        }
        ensure!(
            self.plaintext || !require_encryption,
            "この鍵は age で暗号化してください（--age-recipient / --age-passphrase / {}）。平文で保存するなら --plaintext",
            default_file.display()
        );
        Ok(None)
    }
}

/// 秘密鍵ファイルの (ファイル名, 内容)。
fn secret_file(secret: &k256::SecretKey, plan: Option<&Plan>, base: &str) -> Result<(String, Vec<u8>)> {
    let pem = crypto::secret_to_pem(secret)?;
    Ok(match plan {
        Some(plan) => (format!("{base}.pem.age"), age_io::encrypt(pem.as_bytes(), plan)?),
        None => (format!("{base}.pem"), pem.as_bytes().to_vec()),
    })
}

struct IssuePlan {
    dir: PathBuf,
    owner: (String, String),
    roles: Vec<String>,
    blocks: Vec<Value>,
    subject: Vec<u8>,
    secret: Option<k256::SecretKey>,
    identity: Option<serde_json::Value>,
    age: Option<Plan>,
}

impl Ctx {
    fn tree(&self) -> Result<Tree> {
        Tree::load(&self.keys)
    }

    fn dispatch(&mut self, command: Command) -> Result<()> {
        match command {
            Command::Tree => self.tree_cmd(),
            Command::Show { key } => self.show(&key),
            Command::Verify { key } => self.verify(key.as_deref()),
            Command::InitRoot { name, display_name, roles, max_depth, age } => self.init_root(&name, display_name, roles, max_depth, &age),
            Command::Keygen { out, age } => self.keygen(&out, &age),
            Command::Handoff { key, out } => self.handoff(&key, &out),
            Command::Adopt { key_dir, bundle } => self.adopt(&key_dir, &bundle),
            Command::Issue(args) => self.issue(*args),
            Command::Revoke { key, by, reason, text, effective_at, yes } => self.revoke(&key, by, reason, text, effective_at, yes),
            Command::Activate { key, effective_at, yes } => self.activate(&key, effective_at, yes),
            Command::Publish { key, relays, event_out, confirm, dry_run } => self.publish(&key, &relays, event_out, confirm, dry_run),
            Command::CheckPublished { key, url } => self.check_published(&key, url),
            Command::Encrypt { key, age } => self.encrypt(&key, &age),
            Command::Chain { key, cbor } => self.chain(&key, cbor),
            Command::Credentials { key, out } => self.credentials(&key, &out),
            Command::TrustExport { key } => self.trust_export(&key),
            Command::Inspect { file, anchor, json } => self.inspect(&file, anchor, json),
            Command::ImportLegacy { name, dir, max_depth, age } => self.import_legacy(&name, &dir, max_depth, &age),
        }
    }

    fn status_of(&self, t: &Tree, i: usize) -> Result<Vec<String>> {
        let mut flags = Vec::new();
        match t.revoked_by(i)? {
            Some(j) if j == i => flags.push("失効（発信済み）".to_string()),
            Some(j) => flags.push(format!("祖先 {} が失効", t.node(j).name)),
            None if t.revocation(i)?.is_some() => flags.push("失効通知あり（未発信）".into()),
            None => {}
        }
        let (from, until) = t.validity(i);
        if until.is_some_and(|u| u <= self.now as f64) {
            flags.push("期限切れ".into());
        }
        if from.is_some_and(|f| f > self.now as f64) {
            flags.push("開始前".into());
        }
        flags.push(match t.secret_file(i) {
            None => "外部鍵".into(),
            Some(f) if f.extension().is_some_and(|e| e == "age") => "age".into(),
            Some(_) => "平文".into(),
        });
        Ok(flags)
    }

    fn verify_now(&self, t: &Tree, i: usize) -> ChainStatus {
        if t.is_root(i) {
            return ChainStatus::Verified;
        }
        p::verify_chain(&t.chain(i), std::slice::from_ref(t.anchor(i)), self.now)
    }

    // ---- tree / show / verify -----------------------------------------------------------------------

    fn tree_cmd(&self) -> Result<()> {
        let t = self.tree()?;
        if t.nodes.is_empty() {
            println!("鍵がありません（{}）。init-root・adopt・import-legacy で作成します。", self.keys.display());
        }
        for i in 0..t.nodes.len() {
            let depth = t.path(i).len() - 1;
            let n = t.node(i);
            let mut detail = vec![t.roles(i).join(","), format!("maxDepth={}", t.max_depth(i))];
            if n.parent.is_none() {
                detail.push(if t.is_root(i) { "ルート鍵".into() } else { format!("起点（stratum {}）", t.stratum(i)) });
            }
            if let Some(d) = t.domain(i) {
                detail.push(format!("domain={d}"));
            }
            if let Some((id, _)) = t.organization(i) {
                detail.push(format!("org={id}"));
            }
            if let Some(u) = t.validity(i).1 {
                detail.push(format!("until={}", ts(u as i64)));
            }
            println!("{}{}  {}  {}  [{}]", "  ".repeat(depth), n.name, &t.pubkey_hex(i)[..8], detail.join(" "), self.status_of(&t, i)?.join(", "));
        }
        Ok(())
    }

    fn show(&self, key: &str) -> Result<()> {
        let t = self.tree()?;
        let i = t.resolve(key)?;
        println!("鍵: {}", t.rel(i));
        println!("公開鍵: {}", t.pubkey_hex(i));
        println!("秘密鍵: {}", t.secret_file(i).map(|f| t.rel_path(&f)).unwrap_or_else(|| "なし（外部鍵）".into()));
        println!("stratum: {}  チェーン: {}", t.stratum(i), self.verify_now(&t, i));
        if t.node(i).parent.is_none() {
            println!("信頼の起点（anchor.json）:\n{}", pretty(&t.anchor(i).to_json()));
        }
        if let Some(a) = &t.node(i).attestation {
            println!("鍵署名 ID: {}", p::body_id(p::body(a))?);
            println!("鍵署名:\n{}", pretty(&p::body(a).to_display_json()));
        }
        if let Some(rev) = t.revocation(i)? {
            println!("失効（{}）:\n{}", if t.published(i)? { "発信済み" } else { "未発信" }, pretty(&rev.to_display_json()));
        }
        if let Some(d) = t.domain(i) {
            println!("失効リスト: {} 件掲載（https://{d}{LIST_URL_PREFIX}）", t.published_ids(i).len());
        }
        Ok(())
    }

    fn verify(&self, key: Option<&str>) -> Result<()> {
        let t = self.tree()?;
        let targets: Vec<usize> = match key {
            Some(k) => vec![t.resolve(k)?],
            None => (0..t.nodes.len()).collect(),
        };
        let mut failures = 0;
        for i in targets {
            let status = self.verify_now(&t, i);
            let pre_ok = match t.pre_revocation(i) {
                Ok(Some(n)) => p::bytes_field(p::body(&n), "subject") == t.node(i).pubkey,
                Ok(None) => true,
                Err(_) => false,
            };
            let ok = status == ChainStatus::Verified && pre_ok;
            if !ok {
                failures += 1;
            }
            let pre = if pre_ok { "" } else { " pre-revocation.cbor が不正" };
            println!("{} {}  {}{}  {}", if ok { "OK" } else { "NG" }, t.rel(i), status, pre, self.status_of(&t, i)?.join(", "));
        }
        ensure!(failures == 0, "{failures} 件の鍵が検証に失敗しました");
        Ok(())
    }

    // ---- init-root / keygen / handoff / adopt ----------------------------------------------------------

    fn init_root(&self, name: &str, display_name: Option<String>, roles: Vec<String>, max_depth: i64, age: &AgeArgs) -> Result<()> {
        check_name(name)?;
        let dir = self.keys.join(name);
        ensure!(!dir.exists(), "{} は既にあります", dir.display());
        store::ensure_private_dir(&self.keys)?;
        let display = match display_name {
            Some(d) => d,
            None => ask("ルートの表示名", None, "--display-name")?,
        };
        let plan = age.plan(&self.keys, true)?;
        let secret = crypto::generate_secret();
        let anchor = RootConfig { pubkey: crypto::xonly(&secret).to_vec(), name: display, roles, max_depth: max_depth as i128, enabled: true };
        let anchor = RootConfig::from_json(&anchor.to_json())?;
        let files = vec![
            secret_file(&secret, plan.as_ref(), "secret")?,
            ("public.pem".into(), crypto::public_to_pem(&anchor.pubkey)?.into_bytes()),
            ("anchor.json".into(), format!("{}\n", pretty(&anchor.to_json())).into_bytes()),
        ];
        store::create_dir_atomically(&dir, &files)?;
        println!("作成しました: {name}  公開鍵 {}", hex::encode(&anchor.pubkey));
        println!("受信者（アプリ・PDS）の信頼設定に入れるまで、このルートからのチェーンは検証されません（mosimo-ca trust-export {name}）。");
        Ok(())
    }

    fn keygen(&self, out: &Path, age: &AgeArgs) -> Result<()> {
        let plan = age.plan(&self.keys, false)?;
        ensure!(plan.is_some() || age.plaintext, "--plaintext か age の指定が必要です");
        let secret = crypto::generate_secret();
        let x = crypto::xonly(&secret);
        let out = std::path::absolute(out)?;
        store::create_dir_atomically(&out, &[secret_file(&secret, plan.as_ref(), "secret")?, ("public.pem".into(), crypto::public_to_pem(&x)?.into_bytes())])?;
        println!("作成しました: {}/public.pem  公開鍵 {}", out.display(), hex::encode(x));
        println!("発行者に public.pem を渡し、mosimo-ca issue PARENT NAME --subject-public-pem public.pem で署名してもらいます。");
        println!("受け取った一式（handoff の出力）は mosimo-ca adopt NAME --key-dir {} --bundle DIR で取り込めます。", out.display());
        Ok(())
    }

    fn handoff(&self, key: &str, out: &Path) -> Result<()> {
        let t = self.tree()?;
        let i = t.resolve(key)?;
        ensure!(!t.is_root(i), "ルート鍵には渡す鍵署名がありません");
        ensure!(self.verify_now(&t, i) == ChainStatus::Verified, "チェーンが検証できません: {}", self.verify_now(&t, i));
        let mut files = vec![
            ("upstream.cbor".to_string(), cbor::encode(&Value::Array(t.chain(i)), 65_536)?),
            ("anchor.json".to_string(), format!("{}\n", pretty(&t.anchor(i).to_json())).into_bytes()),
            ("public.pem".to_string(), crypto::public_to_pem(&t.node(i).pubkey)?.into_bytes()),
        ];
        if let Some(n) = t.pre_revocation(i)? {
            files.push(("pre-revocation.cbor".into(), cbor::encode(&n, 65_536)?));
        }
        store::create_dir_atomically(&std::path::absolute(out)?, &files)?;
        println!("書き出しました: {}（秘密鍵は含みません）", out.display());
        Ok(())
    }

    fn adopt(&self, key_dir: &Path, bundle: &Path) -> Result<()> {
        let anchor = RootConfig::from_json(&serde_json::from_str(&std::fs::read_to_string(bundle.join("anchor.json"))?)?)?;
        let upstream = cbor::decode(&std::fs::read(bundle.join("upstream.cbor"))?, 65_536)?;
        let chain = upstream.as_array().ok_or_else(|| anyhow!("upstream.cbor must be an array"))?.to_vec();
        let status = p::verify_chain(&chain, std::slice::from_ref(&anchor), self.now);
        ensure!(status == ChainStatus::Verified, "受け取ったチェーンが検証できません: {status}");
        let subject = p::bytes_field(p::body(chain.last().unwrap()), "subject").to_vec();
        let name = p::owner_id(chain.last().unwrap()).to_string();
        check_name(&name).context("発行者が付けた名前（owner.id）をディレクトリ名に使えません")?;
        let dir = self.keys.join(&name);
        ensure!(!dir.exists(), "{} は既にあります", dir.display());
        let public = std::fs::read_to_string(key_dir.join("public.pem"))?;
        ensure!(crypto::xonly_from_public_pem(&public)?[..] == subject[subject.len() - 32..], "鍵署名の鍵が {} の鍵と一致しません", key_dir.display());
        let secret_path = ["secret.pem.age", "secret.pem"].iter().map(|f| key_dir.join(f)).find(|p| p.exists());
        let mut files = vec![
            ("anchor.json".to_string(), format!("{}\n", pretty(&anchor.to_json())).into_bytes()),
            ("upstream.cbor".to_string(), cbor::encode(&upstream, 65_536)?),
            ("public.pem".to_string(), public.into_bytes()),
        ];
        if let Some(path) = secret_path {
            let secret = store::read_secret_pem(&path, &path.display().to_string())?;
            ensure!(crypto::xonly(&secret)[..] == subject[subject.len() - 32..], "秘密鍵が鍵署名の鍵と一致しません");
            files.push((path.file_name().unwrap().to_string_lossy().into_owned(), std::fs::read(&path)?));
        }
        let pre = bundle.join("pre-revocation.cbor");
        if pre.exists() {
            let notice = cbor::decode(&std::fs::read(&pre)?, 65_536)?;
            p::validate_notice(&notice)?;
            ensure!(p::bytes_field(p::body(&notice), "subject") == subject, "pre-revocation.cbor の対象がこの鍵ではありません");
            files.push(("pre-revocation.cbor".into(), cbor::encode(&notice, 65_536)?));
        }
        store::ensure_private_dir(&self.keys)?;
        store::create_dir_atomically(&dir, &files)?;
        println!("取り込みました: {name}（stratum {}、信頼の起点 {}）", chain.len(), anchor.name);
        println!("元の {} は不要になったら削除してください。", key_dir.display());
        Ok(())
    }

    // ---- issue ----------------------------------------------------------------------------------------

    fn issue(&self, a: IssueArgs) -> Result<()> {
        let t = self.tree()?;
        let parent = t.resolve(&a.parent)?;
        check_name(&a.name)?;
        let plan = self.build_issue(&t, parent, &a)?;
        self.preview_issue(&t, parent, &plan);
        confirm("この内容で発行しますか", a.yes)?;
        self.write_issue(&t, parent, plan)
    }

    fn build_issue(&self, t: &Tree, parent: usize, a: &IssueArgs) -> Result<IssuePlan> {
        let parent_roles = t.roles(parent);
        let parent_depth = t.max_depth(parent);
        ensure!(parent_roles.iter().any(|r| r == "attest") && parent_depth >= 1, "{} は署名できません（attest 役割または maxDepth が足りない）", t.rel(parent));
        if let Some(j) = t.revoked_by(parent)? {
            bail!("{} は失効しています（{}）", t.rel(parent), t.rel(j));
        }
        ensure!(t.revocation(parent)?.is_none(), "{} には失効通知が作成されています。発行を続ける前に状態を確認してください", t.rel(parent));
        let status = self.verify_now(t, parent);
        ensure!(status == ChainStatus::Verified, "{} のチェーンが検証できません: {status}", t.rel(parent));
        let dir = t.node(parent).dir.join(&a.name);
        ensure!(!dir.exists(), "{} は既にあります", t.rel_path(&dir));
        let stratum = t.stratum(parent) + 1;

        let depth: i128 = if a.leaf {
            ensure!(a.max_depth.is_none_or(|d| d == 0), "--leaf と --max-depth は同時に使えません");
            0
        } else if let Some(d) = a.max_depth {
            d as i128
        } else {
            let default = (parent_depth - 1).to_string();
            ask(&format!("maxDepth（0 は末端。親 {parent_depth}）"), Some(&default), "--max-depth")?.parse()?
        };
        ensure!((0..parent_depth).contains(&depth), "maxDepth は 0..{} にしてください（親 {parent_depth}）", parent_depth - 1);

        let mut roles = match &a.roles {
            Some(r) => r.clone(),
            None => {
                let default: Vec<&str> = parent_roles.iter().map(String::as_str).filter(|r| depth > 0 || *r != "attest").collect();
                ask(&format!("役割（親: {}）", parent_roles.join(",")), Some(&default.join(",")), "--roles")?
                    .split(',')
                    .map(|s| s.trim().to_string())
                    .filter(|s| !s.is_empty())
                    .collect()
            }
        };
        roles.sort();
        roles.dedup();
        let extra: Vec<&String> = roles.iter().filter(|r| !parent_roles.contains(r)).collect();
        ensure!(extra.is_empty(), "親にない役割があります: {extra:?}");
        let has = |r: &str| roles.iter().any(|x| x == r);
        ensure!(depth == 0 || has("attest"), "maxDepth 1 以上の鍵には attest が必要です");
        ensure!(depth > 0 || !has("attest"), "末端の鍵（maxDepth 0）には attest を与えません");
        for r in &roles {
            if !p::KNOWN_ROLES.contains(&r.as_str()) {
                eprintln!("注意: 未定義の役割 {r}");
            }
        }

        // ディレクトリ名は常に、親が発行時に付けた名前（owner.id）にする
        let owner_id = a.name.clone();
        let owner_name = match &a.owner_name {
            Some(o) => o.clone(),
            None => ask("owner.name（表示名）", None, "--owner-name")?,
        };
        p::check_text(&owner_id, 256)?;
        p::check_text(&owner_name, 256)?;

        let mut blocks = Vec::new();
        if depth > 0 {
            blocks.push(Value::map([("$type", Value::text(p::DELEGATION)), ("maxDepth", Value::Int(depth))]));
        }
        let domain = match &a.domain {
            Some(d) => Some(d.clone()),
            None if depth > 0 => ask_optional("issuerDomain（失効リストを配信するドメイン）")?,
            None => None,
        };
        if let Some(d) = &domain {
            ensure!(p::is_domain(d), "ドメインが不正です: {d}");
            for j in 0..t.nodes.len() {
                if t.domain(j).as_deref() == Some(d.as_str()) && t.revoked_by(j)?.is_none() {
                    bail!("{d} は {} が使っています。失効リストの URL がドメインごとに一つなので共用できません", t.rel(j));
                }
            }
            blocks.push(Value::map([("$type", Value::text(p::ISSUER_DOMAIN)), ("domain", Value::text(d.clone()))]));
        }
        if let Some(v) = self.build_validity(t, parent, stratum, depth, a)? {
            blocks.push(v);
        }
        if let Some(o) = self.build_organization(t, parent, depth, &roles, a)? {
            blocks.push(o);
        }
        if let Some(i) = self.build_importer(t, parent, depth, &roles, a)? {
            blocks.push(i);
        }
        let level = match a.trust_level {
            Some(l) => Some(l),
            None => ask_optional("trustLevel（0〜3。2 は発行者による手動確認）")?.map(|s| s.parse()).transpose()?,
        };
        if let Some(level) = level {
            let mut b = BTreeMap::from([("$type".to_string(), Value::text(p::TRUST_LEVEL)), ("level".to_string(), Value::Int(level as i128))]);
            let method = a.trust_method.clone().or_else(|| (level >= 2).then(|| "manual".to_string()));
            if let Some(m) = method {
                b.insert("method".into(), Value::Text(m));
            }
            blocks.push(Value::Map(b));
        }

        let (subject, secret, identity) = self.subject_key(a)?;
        let age = if secret.is_some() { a.age.plan(&self.keys, depth > 0)? } else { None };
        Ok(IssuePlan { dir, owner: (owner_id, owner_name), roles, blocks, subject, secret, identity, age })
    }

    fn build_validity(&self, t: &Tree, parent: usize, stratum: usize, depth: i128, a: &IssueArgs) -> Result<Option<Value>> {
        let (pfrom, puntil) = t.validity(parent);
        if a.no_expiry {
            ensure!(stratum == 1, "stratum 2 以降の鍵には期限が必要です（attest §3）");
            return Ok(None);
        }
        let until = if let Some(u) = &a.until {
            parse_user_time(u)?
        } else if let Some(d) = a.days {
            self.now + d * 86_400
        } else if stratum == 1 {
            match ask_optional("有効日数（ルート直下は空欄で期限なし）")? {
                None => return Ok(None),
                Some(d) => self.now + d.parse::<i64>()? * 86_400,
            }
        } else {
            let default = if depth == 0 { "730" } else { "1095" };
            self.now + ask("有効日数", Some(default), "--days")?.parse::<i64>()? * 86_400
        };
        ensure!(until > self.now, "有効期限が過去です");
        if let Some(pu) = puntil {
            ensure!(until as f64 <= pu, "有効期限 {} が親の期限 {} を超えています（子の期間は親に含まれる必要があります）", ts(until), ts(pu as i64));
        }
        let from = (self.now - 300).max(pfrom.map(|f| f.ceil() as i64).unwrap_or(0));
        Ok(Some(Value::map([("$type", Value::text(p::KEY_VALIDITY)), ("from", Value::Text(ts(from))), ("until", Value::Text(ts(until)))])))
    }

    fn build_organization(&self, t: &Tree, parent: usize, depth: i128, roles: &[String], a: &IssueArgs) -> Result<Option<Value>> {
        let org = |id: String, name: String| Value::map([("$type", Value::text(p::ORGANIZATION)), ("orgId", Value::Text(id)), ("orgName", Value::Text(name))]);
        if let Some((id, name)) = t.organization(parent) {
            ensure!(a.org_id.as_ref().is_none_or(|x| *x == id), "organization は親と同じ {id} です");
            return Ok(Some(org(id, a.org_name.clone().unwrap_or(name))));
        }
        let required = depth == 0 && roles.iter().any(|r| r == "import");
        let id = match &a.org_id {
            Some(id) => Some(id.clone()),
            None if required => Some(ask("organization.orgId（例 oriom.dev:mosimo-bsaf-importer）", None, "--org-id")?),
            None => ask_optional("organization.orgId（以下の鍵が代表する組織。例 oriom.dev:mosimo-bsaf-importer）")?,
        };
        let Some(id) = id else { return Ok(None) };
        let name = match &a.org_name {
            Some(n) => n.clone(),
            None => ask("organization.orgName（受信者に表示される組織名）", None, "--org-name")?,
        };
        Ok(Some(org(id, name)))
    }

    fn build_importer(&self, t: &Tree, parent: usize, depth: i128, roles: &[String], a: &IssueArgs) -> Result<Option<Value>> {
        if !roles.iter().any(|r| r == "import") {
            return Ok(None);
        }
        let parent_scope = t.importer_scope(parent);
        let mut sources: BTreeMap<String, Vec<String>> = BTreeMap::new();
        let mut feeds: BTreeMap<String, Vec<String>> = BTreeMap::new();
        let mut add_preset = |name: &str| -> Result<()> {
            if let Some((_, did, types)) = PRESETS.iter().find(|(n, _, _)| *n == name) {
                sources.entry(did.to_string()).or_default().extend(types.iter().map(|s| s.to_string()));
            } else if let Some((_, feed, types)) = FEED_PRESETS.iter().find(|(n, _, _)| *n == name) {
                feeds.entry(feed.to_string()).or_default().extend(types.iter().map(|s| s.to_string()));
            } else {
                bail!("不明なプリセット: {name}");
            }
            Ok(())
        };
        let preset_names: Vec<&str> = PRESETS.iter().map(|(n, _, _)| *n).chain(FEED_PRESETS.iter().map(|(n, _, _)| *n)).collect();
        if !a.presets.is_empty() || !a.sources.is_empty() || !a.feeds.is_empty() {
            for name in &a.presets {
                add_preset(name)?;
            }
            for (specs, map, form) in [(&a.sources, &mut sources, "--source は DID=type1,type2"), (&a.feeds, &mut feeds, "--feed は FEED=type1,type2")] {
                for spec in specs {
                    let (name, types) = spec.split_once('=').ok_or_else(|| anyhow!("{form} の形です: {spec}"))?;
                    map.entry(name.to_string()).or_default().extend(types.split(',').map(|s| s.trim().to_string()).filter(|s| !s.is_empty()));
                }
            }
        } else if let Some(ps) = &parent_scope {
            for (entries, map, is_feed) in [(&ps.sources, &mut sources, false), (&ps.feeds, &mut feeds, true)] {
                for (name, types) in entries {
                    let label = if is_feed {
                        FEED_PRESETS.iter().find(|(_, f, _)| f == name).map(|(n, _, _)| n.to_string()).unwrap_or(format!("feed:{name}"))
                    } else {
                        PRESETS.iter().find(|(_, d, _)| d == name).map(|(n, _, _)| n.to_string()).unwrap_or(name.clone())
                    };
                    let answer = if tty() {
                        ask(&format!("{label}（{} 種）を転載範囲に含める？ y=全部 / n=含めない / 種別をカンマ区切り", types.len()), Some("y"), "")?
                    } else {
                        "y".into()
                    };
                    match answer.as_str() {
                        "y" => map.insert(name.clone(), types.clone()),
                        "n" => None,
                        list => map.insert(name.clone(), list.split(',').map(|s| s.trim().to_string()).collect()),
                    };
                }
            }
        } else {
            let answer = if depth == 0 {
                ask(&format!("転載範囲のプリセット（{}）", preset_names.join(",")), Some(&preset_names.join(",")), "--preset")?
            } else {
                ask_optional(&format!("転載範囲のプリセット（{}。空欄なら制限なし）", preset_names.join(",")))?.unwrap_or_default()
            };
            for name in answer.split(',').map(str::trim).filter(|s| !s.is_empty()) {
                add_preset(name)?;
            }
        }
        if sources.is_empty() && feeds.is_empty() {
            ensure!(depth > 0, "import 役割の末端の鍵には転載範囲（importer ブロック）が必要です（attest.importer §1）");
            return Ok(None);
        }
        let to_list = |map: BTreeMap<String, Vec<String>>| -> Vec<(String, Vec<String>)> {
            map.into_iter()
                .map(|(n, mut types)| {
                    types.sort();
                    types.dedup();
                    (n, types)
                })
                .collect()
        };
        let scope = p::Scope { sources: to_list(sources), feeds: to_list(feeds) };
        if let Some(ps) = &parent_scope {
            let outside = ps.outside(&scope);
            ensure!(outside.is_empty(), "親の転載範囲を超えています: {}", outside.join(", "));
        }
        let values = |list: &[(String, Vec<String>)], name_field: &'static str| -> Value {
            Value::Array(
                list.iter()
                    .map(|(n, types)| {
                        Value::map([(name_field, Value::Text(n.clone())), ("types", Value::Array(types.iter().cloned().map(Value::Text).collect()))])
                    })
                    .collect(),
            )
        };
        let mut fields = vec![("$type", Value::text(p::IMPORTER))];
        if !scope.sources.is_empty() {
            fields.push(("sources", values(&scope.sources, "botDid")));
        }
        if !scope.feeds.is_empty() {
            // feeds を知らない検証器はこの鍵署名を含むチェーン全体を拒否する（importer §1.1 の互換性）。
            eprintln!("注意: feeds を持つ鍵署名は、feeds に未対応の検証器（ルート設定 maxDepth 2 の旧版アプリなど）ではチェーンごと検証できません。");
            fields.push(("feeds", values(&scope.feeds, "feed")));
        }
        let block = Value::Map(fields.into_iter().map(|(k, v)| (k.to_string(), v)).collect());
        p::validate_importer_block(&block)?;
        Ok(Some(block))
    }

    #[allow(clippy::type_complexity)]
    fn subject_key(&self, a: &IssueArgs) -> Result<(Vec<u8>, Option<k256::SecretKey>, Option<serde_json::Value>)> {
        let given = [a.subject_pubkey.is_some(), a.subject_public_pem.is_some(), a.importer_identity.is_some()].iter().filter(|x| **x).count();
        ensure!(given <= 1, "--subject-pubkey / --subject-public-pem / --importer-identity はどれか一つです");
        if let Some(h) = &a.subject_pubkey {
            let key = hex::decode(h)?;
            ensure!(key.len() == 32, "--subject-pubkey は 32 バイト（64 桁）の x-only 鍵です");
            crypto::validate_public_key(&key)?;
            return Ok((key, None, None));
        }
        if let Some(f) = &a.subject_public_pem {
            return Ok((crypto::xonly_from_public_pem(&std::fs::read_to_string(f)?)?.to_vec(), None, None));
        }
        if let Some(f) = &a.importer_identity {
            let identity: serde_json::Value = serde_json::from_str(&std::fs::read_to_string(f)?)?;
            let field = |k: &str| identity.get(k).and_then(|v| v.as_str()).ok_or_else(|| anyhow!("{} に {k} がありません", f.display()));
            let secret = crypto::secret_from_bytes(&hex::decode(field("nostrSecret")?)?)?;
            ensure!(hex::encode(crypto::xonly(&secret)) == field("nostrPubkey")?, "importer の nostrPubkey と nostrSecret が一致しません");
            return Ok((crypto::xonly(&secret).to_vec(), Some(secret), Some(identity)));
        }
        let secret = crypto::generate_secret();
        Ok((crypto::xonly(&secret).to_vec(), Some(secret), None))
    }

    fn preview_issue(&self, t: &Tree, parent: usize, plan: &IssuePlan) {
        println!("発行者: {}（{}）", t.rel(parent), t.pubkey_hex(parent));
        println!("作成先: {}（stratum {}）", t.rel_path(&plan.dir), t.stratum(parent) + 1);
        let origin = if plan.identity.is_some() {
            "（importer-identity の Nostr 鍵）"
        } else if plan.secret.is_some() {
            "（新規生成）"
        } else {
            "（外部鍵。秘密鍵は保存しない）"
        };
        println!("被署名鍵: {}{origin}", hex::encode(&plan.subject));
        let storage = match (&plan.secret, &plan.age) {
            (None, _) => "なし".to_string(),
            (Some(_), Some(plan)) => plan.describe(),
            (Some(_), None) => "平文".into(),
        };
        println!("秘密鍵の保存: {storage}");
        let blocks: Vec<serde_json::Value> = plan
            .blocks
            .iter()
            .map(|b| {
                if b.get("$type").and_then(Value::as_text) == Some(p::IMPORTER) {
                    let scope = p::importer_scope(b);
                    let lines =
                        |list: Vec<(String, Vec<String>)>| -> Vec<String> { list.into_iter().map(|(n, ts)| format!("{n}: {}", ts.join(", "))).collect() };
                    serde_json::json!({ "$type": p::IMPORTER, "sources": lines(scope.sources), "feeds": lines(scope.feeds) })
                } else {
                    b.to_display_json()
                }
            })
            .collect();
        println!(
            "鍵署名の内容:\n{}",
            pretty(&serde_json::json!({ "owner": { "id": plan.owner.0, "name": plan.owner.1 }, "roles": plan.roles, "blocks": blocks }))
        );
    }

    fn write_issue(&self, t: &Tree, parent: usize, plan: IssuePlan) -> Result<()> {
        let parent_secret = t.secret(parent)?;
        let parent_key = t.node(parent).pubkey.clone();
        let attestation =
            p::create_attestation(&parent_secret, &parent_key, &plan.subject, p::Owner { id: &plan.owner.0, name: &plan.owner.1 }, &plan.roles, plan.blocks)?;
        let mut chain = t.chain(parent);
        chain.push(attestation.clone());
        let status = p::verify_chain(&chain, std::slice::from_ref(t.anchor(parent)), self.now);
        ensure!(status == ChainStatus::Verified, "発行したチェーンが検証できません: {status}");
        let notice = p::create_notice(&parent_secret, &parent_key, &plan.subject, &ts(self.now), None, None)?;
        let mut files = vec![
            ("public.pem".to_string(), crypto::public_to_pem(&plan.subject)?.into_bytes()),
            ("attestation.cbor".to_string(), cbor::encode(&attestation, 65_536)?),
            ("pre-revocation.cbor".to_string(), cbor::encode(&notice, 65_536)?),
        ];
        if let Some(secret) = &plan.secret {
            files.push(secret_file(secret, plan.age.as_ref(), "secret")?);
        }
        if let Some(identity) = &plan.identity {
            files.extend(importer_identity_files(identity, plan.age.as_ref())?);
        }
        store::create_dir_atomically(&plan.dir, &files)?;
        println!("発行しました: {}  鍵署名 ID {}", t.rel_path(&plan.dir), p::body_id(p::body(&attestation))?);
        println!("事前発行形の失効通知（pre-revocation.cbor）は鍵と別の場所にも保管してください。");
        Ok(())
    }

    // ---- encrypt -------------------------------------------------------------------------------------

    fn encrypt(&self, key: &str, age: &AgeArgs) -> Result<()> {
        let t = self.tree()?;
        let i = t.resolve(key)?;
        let plan = age.plan(&self.keys, true)?.ok_or_else(|| anyhow!("age の指定が必要です"))?;
        let dir = &t.node(i).dir;
        let files: Vec<PathBuf> = [dir.join("secret.pem"), dir.join(IMPORTER_DIR).join("atproto.pem"), dir.join(IMPORTER_DIR).join("plc-rotation.pem")]
            .into_iter()
            .filter(|f| f.exists())
            .collect();
        ensure!(!files.is_empty(), "{} に平文の秘密鍵はありません", t.rel(i));
        for path in files {
            let pem = zeroize::Zeroizing::new(std::fs::read(&path)?);
            crypto::secret_from_pem(std::str::from_utf8(&pem)?)?;
            let target = PathBuf::from(format!("{}.age", path.display()));
            store::write_file(&target, &age_io::encrypt(&pem, &plan)?, true)?;
            match age_io::decrypt(&std::fs::read(&target)?, &t.rel_path(&target)) {
                Ok(plain) if plain[..] == pem[..] => {}
                result => {
                    let _ = std::fs::remove_file(&target);
                    let reason = result.err().map(|e| e.to_string()).unwrap_or_else(|| "内容が一致しません".into());
                    bail!(
                        "{} を復号して確認できませんでした（{reason}）。平文は残してあります。受信者指定なら MOSIMO_CA_AGE_IDENTITY に対応する identity を指定してください",
                        t.rel_path(&target)
                    );
                }
            }
            std::fs::remove_file(&path)?;
            println!("暗号化しました: {}", t.rel_path(&target));
        }
        Ok(())
    }

    // ---- 失効の作成 ----------------------------------------------------------------------------------

    fn effective_time(&self, text: Option<String>) -> Result<i64> {
        let t = text.map(|s| parse_user_time(&s)).transpose()?.unwrap_or(self.now);
        if t > self.now {
            eprintln!("注意: effectiveAt {} は未来です。その時刻まで失効しません", ts(t));
        }
        Ok(t)
    }

    fn revoke(&self, key: &str, by: Option<String>, reason: Option<String>, text: Option<String>, effective_at: Option<String>, yes: bool) -> Result<()> {
        let t = self.tree()?;
        let i = t.resolve(key)?;
        ensure!(!t.is_root(i), "ルート鍵は失効通知の対象外です（attest.revocation §4）。アプリの更新で入れ替えます");
        ensure!(t.revocation(i)?.is_none(), "{} には既に revocation.cbor があります", t.rel(i));
        let signer = match by.as_deref() {
            None => t.node(i).parent.ok_or_else(|| {
                anyhow!("{} は管理の起点なので、親の鍵がありません。--by self（自己署名）か activate（事前発行形）を使ってください", t.rel(i))
            })?,
            Some("self") => i,
            Some(r) => t.resolve(r)?,
        };
        ensure!(t.path(i).contains(&signer), "{} は {} の祖先ではないため、受信者はこの通知を適用しません（revocation §2.1）", t.rel(signer), t.rel(i));
        let reason = match reason {
            Some(r) => r,
            None => ask(&format!("理由（{}）", p::REASONS.join(" / ")), None, "--reason")?,
        };
        p::ensure_reason(&reason)?;
        let effective = self.effective_time(effective_at)?;
        let secret = t.secret(signer)?;
        let notice =
            p::create_notice(&secret, &t.node(signer).pubkey, &t.node(i).pubkey, &ts(self.now), Some(&ts(effective)), Some((&reason, text.as_deref())))?;
        self.save_revocation(&t, i, p::revocation(notice, None), yes)
    }

    fn activate(&self, key: &str, effective_at: Option<String>, yes: bool) -> Result<()> {
        let t = self.tree()?;
        let i = t.resolve(key)?;
        ensure!(t.revocation(i)?.is_none(), "{} には既に revocation.cbor があります", t.rel(i));
        let notice = t.pre_revocation(i)?.ok_or_else(|| anyhow!("{} に pre-revocation.cbor がありません", t.rel(i)))?;
        ensure!(p::bytes_field(p::body(&notice), "subject") == t.node(i).pubkey, "pre-revocation.cbor の対象がこの鍵ではありません");
        let effective = self.effective_time(effective_at)?;
        let activation = p::create_activation(&t.secret(i)?, &t.node(i).pubkey, &notice, &ts(effective))?;
        self.save_revocation(&t, i, p::revocation(notice, Some(activation)), yes)
    }

    fn save_revocation(&self, t: &Tree, i: usize, revocation: Value, yes: bool) -> Result<()> {
        let facts = p::validate_revocation(&revocation)?;
        println!("失効の対象: {}（{}）", t.rel(i), t.pubkey_hex(i));
        println!("失効の内容:\n{}", pretty(&revocation.to_display_json()));
        confirm("この失効通知を作成しますか（まだ発信はしません）", yes)?;
        store::write_file(&t.node(i).dir.join("revocation.cbor"), &cbor::encode(&revocation, 65_536)?, true)?;
        println!("作成しました: {}/revocation.cbor  revocationId {}", t.rel(i), facts.id);
        println!("発信するには: mosimo-ca publish {}", t.rel(i));
        Ok(())
    }

    // ---- publish ---------------------------------------------------------------------------------------

    fn publish(&self, key: &str, relays: &[String], event_out: Option<PathBuf>, confirm_value: Option<String>, dry_run: bool) -> Result<()> {
        let t = self.tree()?;
        let i = t.resolve(key)?;
        let revocation = t.revocation(i)?.ok_or_else(|| anyhow!("{} に revocation.cbor がありません（revoke / activate で作成）", t.rel(i)))?;
        let publishers = t.list_publishers(i);
        let id = self.check_revocation(&t, i, &revocation, &publishers, !relays.is_empty() || event_out.is_some())?;
        if dry_run {
            println!("\n--dry-run のため、ここで終了します。");
            return Ok(());
        }
        let expected = t.pubkey_hex(i)[..8].to_string();
        let answer = match confirm_value {
            Some(c) => c,
            None if tty() => ask(&format!("発信すると取り消せません。対象の公開鍵の先頭 8 桁（{expected}）を入力"), None, "--confirm")?,
            None => bail!("--confirm に対象の公開鍵の先頭 8 桁（{expected}）を指定してください"),
        };
        ensure!(answer == expected, "確認の入力が一致しません（{expected}）。発信していません");
        for &j in &publishers {
            self.publish_to_list(&t, j, &id, &revocation)?;
        }
        if !relays.is_empty() || event_out.is_some() {
            let event = nostr::revocation_event(&revocation, &ts(self.event_exp(&t, i)), self.now)?;
            if let Some(path) = &event_out {
                std::fs::write(path, format!("{event}\n"))?;
                println!("制御イベントを書き出しました: {}", path.display());
            }
            for url in relays {
                match nostr::publish(url, &event) {
                    Ok((true, m)) => println!("送信しました: {url}  event {} {m}", event["id"].as_str().unwrap_or("")),
                    Ok((false, m)) => eprintln!("拒否されました: {url}  {m}"),
                    Err(e) => eprintln!("リレーへの送信に失敗しました: {url}: {e:#}（失効リストへの掲載は完了しています）"),
                }
            }
        }
        if !publishers.is_empty() {
            println!("\n次の手順:");
            for &j in &publishers {
                let domain = t.domain(j).unwrap();
                println!("  1. {} を https://{domain}{LIST_URL_PREFIX} に配置する（application/cbor）", t.rel_path(&t.list_file(j)));
                println!("  2. mosimo-ca check-published {} で配信内容を確認する", t.rel(j));
            }
        }
        Ok(())
    }

    fn check_revocation(&self, t: &Tree, i: usize, revocation: &Value, publishers: &[usize], carried: bool) -> Result<String> {
        let mut checks: Vec<(bool, String)> = Vec::new();
        let facts = match p::validate_revocation(revocation) {
            Ok(f) => {
                checks.push((true, "署名と構造（notice / activation）が正しい".into()));
                Some(f)
            }
            Err(e) => {
                checks.push((false, format!("署名または構造が不正: {e}")));
                None
            }
        };
        if let Some(f) = &facts {
            checks.push((f.subject == t.node(i).pubkey, format!("対象の鍵がこのディレクトリの鍵と一致する（{}…）", &hex::encode(&f.subject)[..16])));
            let path = t.path(i);
            let signer = path.iter().copied().find(|&j| t.node(j).pubkey == f.issuer);
            let upstream_signer = t.chain(i).iter().position(|a| p::bytes_field(p::body(a), "issuer") == f.issuer);
            let label = match (signer, upstream_signer) {
                (Some(j), _) => t.rel(j),
                (None, Some(_)) => format!("管理外の祖先 {}…", &hex::encode(&f.issuer)[..16]),
                (None, None) => hex::encode(&f.issuer)[..16].to_string(),
            };
            checks.push((signer.is_some() || upstream_signer.is_some(), format!("通知の署名者がチェーン上の祖先または本人である（{label}）")));
            let note = if f.effective_at > self.now as f64 { "（未来の時刻。その時刻まで失効しません）" } else { "" };
            checks.push((true, format!("effectiveAt {}{note}", ts(f.effective_at as i64))));
        }
        let status = self.verify_now(t, i);
        checks.push((!matches!(status, ChainStatus::Invalid(_)), format!("対象のチェーンの状態: {status}")));
        let domains: Vec<String> = publishers.iter().map(|&j| format!("{}（{}）", t.domain(j).unwrap(), t.rel(j))).collect();
        checks.push((
            !publishers.is_empty() || carried,
            if publishers.is_empty() {
                "掲載先の失効リストがない（制御イベントだけで運ぶ）".into()
            } else {
                format!("掲載先の失効リスト: {}", domains.join(", "))
            },
        ));
        let id = facts.as_ref().map(|f| f.id.clone()).unwrap_or_default();
        for &j in publishers {
            checks.push((!t.published_ids(j).contains(&id), format!("{} にはまだ掲載されていない", t.domain(j).unwrap())));
        }
        let already = t.path(i).into_iter().filter(|&j| j != i).find(|&j| t.published(j).unwrap_or(false));
        checks.push((already.is_none(), format!("祖先が既に失効していない{}", already.map(|j| format!("（{} が失効済み）", t.rel(j))).unwrap_or_default())));

        let affected: Vec<usize> = std::iter::once(i).chain(t.descendants(i)).collect();
        let (owner_id, owner_name) = t.owner(i);
        println!("失効の対象: {}", t.rel(i));
        println!("  owner: {owner_name}（{owner_id}）  roles: {}  stratum {}", t.roles(i).join(","), t.stratum(i));
        let notice_body = p::body(revocation.get("notice").unwrap_or(&Value::Null));
        let reason = notice_body
            .get("blocks")
            .and_then(Value::as_array)
            .unwrap_or(&[])
            .iter()
            .find(|b| b.get("$type").and_then(Value::as_text) == Some(p::REASON))
            .map(|b| format!("{} {}", b.get("code").and_then(Value::as_text).unwrap_or(""), b.get("text").and_then(Value::as_text).unwrap_or("")))
            .unwrap_or_else(|| "なし".into());
        let form = if revocation.get("activation").is_some() { "事前発行形 + 活性化" } else { "即時形" };
        println!("  理由: {reason}  形式: {form}");
        println!("  revocationId: {id}");
        println!("影響を受ける鍵（{} 件。子孫は祖先の失効で一緒に無効になります）:", affected.len());
        for &j in &affected {
            println!("  - {}  {}", t.rel(j), t.owner(j).1);
        }
        if t.max_depth(i) >= 1 && !affected[1..].is_empty() {
            println!("\n*** 署名を発行している鍵を失効させます。上の {} 件の子孫も全て無効になり、", affected.len() - 1);
            println!("*** 失効を取り消す手段はありません（受信者は失効状態を巻き戻さない）。");
        }
        println!("\n確認:");
        for (ok, text) in &checks {
            println!("  {} {text}", if *ok { "✓" } else { "✗" });
        }
        let failed = checks.iter().filter(|(ok, _)| !ok).count();
        ensure!(failed == 0, "{failed} 件の確認に失敗したため発信しません");
        Ok(id)
    }

    fn publish_to_list(&self, t: &Tree, j: usize, id: &str, revocation: &Value) -> Result<()> {
        let entries = t.list_entries_dir(j);
        let entry = entries.join(format!("{id}.cbor"));
        store::write_file(&entry, &cbor::encode(revocation, 65_536)?, true)?;
        let result = (|| -> Result<(Vec<u8>, usize)> {
            let revocations =
                t.published_ids(j).iter().map(|x| cbor::decode(&std::fs::read(entries.join(format!("{x}.cbor")))?, 65_536)).collect::<Result<Vec<_>>>()?;
            let list = p::create_revocation_list(&t.secret(j)?, &t.node(j).pubkey, &ts(self.now), revocations)?;
            let bytes = cbor::encode(&list, 1_048_576)?;
            let decoded = p::decode_revocation_list(&bytes)?;
            ensure!(decoded.signature_valid && decoded.rejected.is_empty() && decoded.accepted.iter().any(|f| f.id == id), "失効リストを検証できませんでした");
            Ok((bytes, decoded.accepted.len()))
        })();
        match result {
            Ok((bytes, count)) => {
                store::write_file(&t.list_file(j), &bytes, false)?;
                println!("掲載しました: {}（{count} 件、{} バイト）", t.rel_path(&t.list_file(j)), bytes.len());
                Ok(())
            }
            Err(e) => {
                let _ = std::fs::remove_file(&entry);
                Err(e.context(format!("{} への掲載を取り消しました", t.domain(j).unwrap_or_default())))
            }
        }
    }

    /// 制御イベントの配送期限: subject の keyValidity.until、無期限なら 1 年（revocation §5）。
    fn event_exp(&self, t: &Tree, i: usize) -> i64 {
        t.validity(i).1.map(|u| u as i64).filter(|u| *u > self.now).unwrap_or(self.now + 365 * 86_400)
    }

    // ---- check-published -----------------------------------------------------------------------------

    fn check_published(&self, key: &str, url: Option<String>) -> Result<()> {
        let t = self.tree()?;
        let i = t.resolve(key)?;
        let domain = t.domain(i).ok_or_else(|| anyhow!("{} は issuerDomain を持ちません", t.rel(i)))?;
        let url = url.unwrap_or_else(|| format!("https://{domain}{LIST_URL_PREFIX}"));
        ensure!(url.starts_with("https://"), "HTTPS のみ取得します");
        let agent = ureq::AgentBuilder::new().redirects(0).timeout(std::time::Duration::from_secs(20)).user_agent("mosimo-ca").build();
        let response = agent.get(&url).set("Accept", "application/cbor").call().map_err(|e| anyhow!("{url}: {e}（redirect は追いません）"))?;
        ensure!(response.status() == 200, "{url}: HTTP {}", response.status());
        let mut bytes = Vec::new();
        std::io::Read::read_to_end(&mut std::io::Read::take(response.into_reader(), 1_048_577), &mut bytes)?;
        ensure!(bytes.len() <= 1_048_576, "{url}: 応答が 1 MiB を超えています");
        let decoded = p::decode_revocation_list(&bytes)?;
        let remote: Vec<String> = decoded.accepted.iter().map(|f| f.id.clone()).collect();
        let local = t.published_ids(i);
        let missing: Vec<&String> = local.iter().filter(|x| !remote.contains(x)).collect();
        let extra: Vec<&String> = remote.iter().filter(|x| !local.contains(x)).collect();
        let same = std::fs::read(t.list_file(i)).is_ok_and(|b| b == bytes);
        println!(
            "{url}  generatedAt {}  {} 件  {}",
            decoded.generated_at,
            remote.len(),
            if same { "手元の revocations.cbor と同一" } else { "手元の revocations.cbor とは異なる" }
        );
        let checks = [
            (decoded.publisher == t.node(i).pubkey, "リストの publisher がこの鍵である".to_string()),
            (decoded.signature_valid, "リストの署名が正しい".into()),
            (
                decoded.rejected.is_empty(),
                format!("不正な通知を含まない{}", if decoded.rejected.is_empty() { String::new() } else { format!("（{}）", decoded.rejected.join("; ")) }),
            ),
            (
                missing.is_empty(),
                format!(
                    "手元で掲載した {} 件が全て配信されている{}",
                    local.len(),
                    if missing.is_empty() { String::new() } else { format!("（未配信 {} 件）", missing.len()) }
                ),
            ),
            (extra.is_empty(), format!("手元にない通知を配信していない{}", if extra.is_empty() { String::new() } else { format!("（{} 件）", extra.len()) })),
        ];
        for (ok, text) in &checks {
            println!("  {} {text}", if *ok { "✓" } else { "✗" });
        }
        ensure!(checks.iter().all(|(ok, _)| *ok), "配信内容が手元の掲載と一致しません");
        Ok(())
    }

    // ---- chain / credentials / trust-export ----------------------------------------------------------

    fn chain(&self, key: &str, out: Option<PathBuf>) -> Result<()> {
        let t = self.tree()?;
        let i = t.resolve(key)?;
        let status = self.verify_now(&t, i);
        ensure!(status == ChainStatus::Verified, "チェーンが検証できません: {status}");
        let bytes = cbor::encode(&Value::Array(t.chain(i)), 65_536)?;
        match out {
            Some(path) => {
                std::fs::write(&path, bytes)?;
                println!("書き出しました: {}", path.display());
            }
            None => println!("{}", p::b64(&bytes)),
        }
        Ok(())
    }

    /// 転載サーバーに配置する一式。サーバー側の配置は appview/bsaf/PUBLISHER.md の「鍵の配置」を参照。
    fn credentials(&self, key: &str, out: &Path) -> Result<()> {
        let t = self.tree()?;
        let i = t.resolve(key)?;
        let dir = t.node(i).dir.join(IMPORTER_DIR);
        let identity_path = dir.join("identity.json");
        ensure!(identity_path.exists(), "{} に {IMPORTER_DIR}/identity.json がありません（issue --importer-identity で作成）", t.rel(i));
        if let Some(j) = t.revoked_by(i)? {
            bail!("{} は失効しています（{}）", t.rel(i), t.rel(j));
        }
        let status = self.verify_now(&t, i);
        ensure!(status == ChainStatus::Verified, "チェーンが検証できません: {status}");
        let identity: serde_json::Value = serde_json::from_str(&std::fs::read_to_string(&identity_path)?)?;
        let field = |k: &str| identity.get(k).and_then(|v| v.as_str()).map(str::to_string).ok_or_else(|| anyhow!("identity.json に {k} がありません"));
        let atproto_path =
            ["atproto.pem.age", "atproto.pem"].iter().map(|f| dir.join(f)).find(|p| p.exists()).ok_or_else(|| anyhow!("atproto 鍵がありません"))?;
        let atproto = store::read_secret_pem(&atproto_path, &t.rel_path(&atproto_path))?;
        ensure!(field("atprotoPubkey")? == hex::encode(crypto::compressed(&atproto)), "atproto 鍵が identity.json と一致しません");
        let nostr = t.secret(i)?;
        ensure!(field("nostrPubkey")? == hex::encode(crypto::xonly(&nostr)), "Nostr 鍵が identity.json と一致しません");
        let public = serde_json::json!({ "did": field("did")?, "nostrPubkey": field("nostrPubkey")?, "atprotoPubkey": field("atprotoPubkey")? });
        let roots = serde_json::Value::Array(vec![t.anchor(i).to_json()]);
        let files = vec![
            ("identity.json".to_string(), format!("{}\n", pretty(&public)).into_bytes()),
            ("chain.cbor".to_string(), cbor::encode(&Value::Array(t.chain(i)), 65_536)?),
            ("nostr.pem".to_string(), crypto::secret_to_pem(&nostr)?.as_bytes().to_vec()),
            ("atproto.pem".to_string(), crypto::secret_to_pem(&atproto)?.as_bytes().to_vec()),
            ("roots.json".to_string(), format!("{}\n", pretty(&roots)).into_bytes()),
        ];
        store::create_dir_atomically(&std::path::absolute(out)?, &files)?;
        println!("書き出しました: {}（mode 0700/0600。nostr.pem と atproto.pem は平文の秘密鍵。PLC 管理鍵・親の鍵は含みません）", out.display());
        println!("配置が済んだら、このディレクトリは削除してください。");
        Ok(())
    }

    fn trust_export(&self, key: &str) -> Result<()> {
        let t = self.tree()?;
        let i = t.resolve(key)?;
        println!("# アプリの mosimo-public-trust.json の roots 要素 / PDS の publicInformation.roots 要素");
        println!("{}", pretty(&t.anchor(i).to_json()));
        Ok(())
    }

    // ---- inspect ---------------------------------------------------------------------------------------

    fn inspect(&self, file: &Path, anchor: Option<PathBuf>, json_only: bool) -> Result<()> {
        let raw = if file == Path::new("-") {
            let mut buf = Vec::new();
            std::io::Read::read_to_end(&mut std::io::stdin(), &mut buf)?;
            buf
        } else {
            std::fs::read(file).with_context(|| format!("{} を読めません", file.display()))?
        };
        ensure!(raw.len() <= 2 * 1_048_576, "ファイルが大きすぎます");
        // chain コマンドや credentials の chain のような Base64 テキストも受け付ける
        let bytes = match std::str::from_utf8(&raw).ok().map(str::trim).filter(|t| !t.is_empty()).and_then(|t| p::unb64(t).ok()) {
            Some(decoded) if cbor::decode(&decoded, 1_048_576).is_ok() => decoded,
            _ => raw,
        };
        let value = cbor::decode(&bytes, 1_048_576).context("canonical CBOR として読めません")?;
        if json_only {
            println!("{}", pretty(&value.to_display_json()));
            return Ok(());
        }
        // 鍵ディレクトリがあれば、既知の鍵に名前を付け、チェーンの起点を探す
        let tree = self.keys.exists().then(|| self.tree().ok()).flatten();
        let mut anchors: Vec<RootConfig> = Vec::new();
        if let Some(path) = &anchor {
            anchors.push(RootConfig::from_json(&serde_json::from_str(&std::fs::read_to_string(path)?)?)?);
        } else if let Some(t) = &tree {
            anchors.extend(t.nodes.iter().filter_map(|n| n.anchor.clone()));
        }
        let mut lines: Vec<(bool, String)> = Vec::new();
        let check = |r: Result<()>, ok: &str| -> (bool, String) {
            match r {
                Ok(()) => (true, ok.to_string()),
                Err(e) => (false, format!("{e:#}")),
            }
        };
        let body_type = value.get("body").and_then(|b| b.get("$type")).and_then(Value::as_text);
        let kind = match (&value, body_type, value.get("$type").and_then(Value::as_text)) {
            (Value::Array(chain), _, _) => {
                for (n, a) in chain.iter().enumerate() {
                    lines.push(check(
                        p::validate_attestation(a),
                        &format!("[{n}] 鍵署名の署名と構造が正しい（ID {}）", p::body_id(p::body(a)).unwrap_or_default()),
                    ));
                }
                if !chain.is_empty() {
                    let first = p::bytes_field(p::body(&chain[0]), "issuer");
                    match anchors.iter().find(|r| r.pubkey == first) {
                        Some(root) => {
                            let status = p::verify_chain(chain, std::slice::from_ref(root), self.now);
                            lines.push((status == ChainStatus::Verified, format!("ルート「{}」からのチェーン: {status}", root.name)));
                        }
                        None => lines.push((false, "チェーンの起点のルート設定が見つかりません（--anchor で指定）".into())),
                    }
                }
                format!("チェーン（鍵署名 {} 件）", chain.len())
            }
            (_, Some(p::KEY_ATTESTATION), _) => {
                lines.push(check(p::validate_attestation(&value), "署名と構造が正しい"));
                lines.push((true, format!("鍵署名 ID {}", p::body_id(p::body(&value))?)));
                "鍵署名（KeyAttestation）".into()
            }
            (_, Some(p::NOTICE), _) => {
                lines.push(check(p::validate_notice(&value), "署名と構造が正しい"));
                lines.push((true, format!("revocationId {}", p::body_id(p::body(&value))?)));
                if p::body(&value).get("effectiveAt").is_some() {
                    "失効通知（Notice・即時形）".into()
                } else {
                    lines.push((true, "事前発行形: activation を付けるまで失効としては効かない".into()));
                    "失効通知（Notice・事前発行形）".into()
                }
            }
            (_, Some(p::ACTIVATION), _) => {
                lines.push((true, "署名者（失効させる鍵）がこのファイルにないため、署名は失効（Revocation）として検証する".into()));
                "活性化（Activation）".into()
            }
            (_, Some(p::REVOCATION_LIST), _) => {
                let list = p::decode_revocation_list(&bytes)?;
                lines.push((list.signature_valid, "リストの署名が正しい".into()));
                lines.push((
                    list.rejected.is_empty(),
                    format!(
                        "通知 {} 件が有効、{} 件が不正{}",
                        list.accepted.len(),
                        list.rejected.len(),
                        if list.rejected.is_empty() { String::new() } else { format!("（{}）", list.rejected.join("; ")) }
                    ),
                ));
                for f in &list.accepted {
                    lines.push((true, format!("  {}  対象 {}…  effectiveAt {}", f.id, &hex::encode(&f.subject)[..16], ts(f.effective_at as i64))));
                }
                format!("失効リスト（generatedAt {}）", list.generated_at)
            }
            (_, _, Some(p::REVOCATION)) => {
                match p::validate_revocation(&value) {
                    Ok(f) => {
                        lines.push((true, "通知・活性化の署名と構造が正しい".into()));
                        lines.push((true, format!("revocationId {}  effectiveAt {}", f.id, ts(f.effective_at as i64))));
                    }
                    Err(e) => lines.push((false, format!("{e:#}"))),
                }
                if value.get("activation").is_some() {
                    "失効（Revocation・事前発行形 + 活性化）".into()
                } else {
                    "失効（Revocation・即時形）".into()
                }
            }
            _ => "不明（このプロトコルの既知のオブジェクトではない）".into(),
        };
        println!("種類: {kind}");
        println!("サイズ: {} バイト", bytes.len());
        for (ok, text) in &lines {
            println!("  {} {text}", if *ok { "✓" } else { "✗" });
        }
        if let Some(t) = &tree {
            let mut keys = Vec::new();
            collect_keys(&value, "", &mut keys);
            let mut known: Vec<String> = Vec::new();
            for (field, key) in keys {
                if let Some(i) = (0..t.nodes.len()).find(|&i| t.node(i).pubkey == key) {
                    let line = format!("{field} = {}", t.rel(i));
                    if !known.contains(&line) {
                        known.push(line);
                    }
                }
            }
            if !known.is_empty() {
                println!("鍵ディレクトリの鍵:");
                for line in known {
                    println!("  {line}");
                }
            }
        }
        println!("{}", pretty(&value.to_display_json()));
        ensure!(lines.iter().all(|(ok, _)| *ok), "検証に失敗した項目があります");
        Ok(())
    }

    // ---- import-legacy ---------------------------------------------------------------------------------

    fn import_legacy(&self, name: &str, src: &Path, max_depth: i64, age: &AgeArgs) -> Result<()> {
        check_name(name)?;
        let top = self.keys.join(name);
        ensure!(!top.exists(), "{} は既にあります", top.display());
        let load = |f: &str| -> Result<serde_json::Value> {
            serde_json::from_str(&std::fs::read_to_string(src.join(f)).with_context(|| format!("{f} を読めません"))?).with_context(|| format!("{f} が不正です"))
        };
        let field = |v: &serde_json::Value, k: &str| -> Result<String> {
            v.get(k).and_then(|x| x.as_str()).map(str::to_string).ok_or_else(|| anyhow!("{k} がありません"))
        };
        let decode = |text: String| cbor::decode(&p::unb64(&text)?, 65_536);
        let plan = age.plan(&self.keys, true)?;

        let root_secret = crypto::secret_from_bytes(&hex::decode(field(&load("root-secret.json")?, "secretKey")?)?)?;
        let legacy_root = load("roots.json")?.get(0).cloned().ok_or_else(|| anyhow!("roots.json が空です"))?;
        ensure!(hex::encode(crypto::xonly(&root_secret)) == field(&legacy_root, "pubkey")?, "root-secret.json と roots.json の公開鍵が一致しません");
        let mut anchor_json = legacy_root.clone();
        anchor_json["maxDepth"] = serde_json::json!(max_depth);
        anchor_json["enabled"] = serde_json::json!(legacy_root.get("enabled").and_then(|v| v.as_bool()).unwrap_or(true));
        let anchor = RootConfig::from_json(&anchor_json)?;

        let ca_secret = crypto::secret_from_bytes(&hex::decode(field(&load("ca-secret.json")?, "secretKey")?)?)?;
        let ca_att = decode(field(&load("ca-attestation.json")?, "attestation")?)?;
        let ca_notice = decode(field(&load("ca-revocation-notice.json")?, "notice")?)?;
        p::validate_attestation(&ca_att)?;
        p::validate_notice(&ca_notice)?;
        ensure!(p::bytes_field(p::body(&ca_att), "issuer") == anchor.pubkey, "認証局の鍵署名がルート鍵の署名ではありません");
        ensure!(crypto::xonly(&ca_secret)[..] == *p::bytes_field(p::body(&ca_att), "subject"), "ca-secret.json が認証局の鍵署名と一致しません");

        let importer = load("importer-secret.json")?;
        let leaf_att = decode(field(&load("leaf-attestation.json")?, "attestation")?)?;
        let leaf_notice = decode(field(&load("leaf-revocation-notice.json")?, "notice")?)?;
        p::validate_attestation(&leaf_att)?;
        p::validate_notice(&leaf_notice)?;
        let nostr_secret = crypto::secret_from_bytes(&hex::decode(field(&importer, "nostrSecret")?)?)?;
        ensure!(
            p::bytes_field(p::body(&leaf_att), "issuer") == p::bytes_field(p::body(&ca_att), "subject"),
            "転載サーバーの鍵署名の発行者が認証局ではありません"
        );
        ensure!(
            crypto::xonly(&nostr_secret)[..] == *p::bytes_field(p::body(&leaf_att), "subject"),
            "importer-secret.json が転載サーバーの鍵署名と一致しません"
        );
        let status = p::verify_chain(&[ca_att.clone(), leaf_att.clone()], std::slice::from_ref(&anchor), self.now);
        ensure!(status == ChainStatus::Verified, "既存のチェーンが検証できません: {status}");
        // ディレクトリ名は親が発行時に付けた名前（owner.id）
        let issuer_name = p::owner_id(&ca_att).to_string();
        let node_name = p::owner_id(&leaf_att).to_string();
        check_name(&issuer_name)?;
        check_name(&node_name)?;

        let mut files = vec![
            ("anchor.json".to_string(), format!("{}\n", pretty(&anchor.to_json())).into_bytes()),
            ("public.pem".to_string(), crypto::public_to_pem(&anchor.pubkey)?.into_bytes()),
            secret_file(&root_secret, plan.as_ref(), "secret")?,
        ];
        let node_rel = format!("{issuer_name}/{node_name}");
        for (prefix, secret, att, notice) in
            [(issuer_name.clone(), &ca_secret, &ca_att, &ca_notice), (node_rel.clone(), &nostr_secret, &leaf_att, &leaf_notice)]
        {
            let (secret_name, secret_data) = secret_file(secret, plan.as_ref(), "secret")?;
            files.push((format!("{prefix}/{secret_name}"), secret_data));
            files.push((format!("{prefix}/public.pem"), crypto::public_to_pem(p::bytes_field(p::body(att), "subject"))?.into_bytes()));
            files.push((format!("{prefix}/attestation.cbor"), cbor::encode(att, 65_536)?));
            files.push((format!("{prefix}/pre-revocation.cbor"), cbor::encode(notice, 65_536)?));
        }
        for (n, d) in importer_identity_files(&importer, plan.as_ref())? {
            files.push((format!("{node_rel}/{n}"), d));
        }
        let legacy_list = src.join("revocations.cbor");
        if legacy_list.exists() {
            let bytes = std::fs::read(&legacy_list)?;
            let decoded = p::decode_revocation_list(&bytes)?;
            ensure!(decoded.publisher == *p::bytes_field(p::body(&ca_att), "subject"), "既存の失効リストの publisher が認証局ではありません");
            let list = cbor::decode(&bytes, 1_048_576)?;
            for r in p::body(&list).get("revocations").and_then(Value::as_array).unwrap_or(&[]) {
                let id = p::validate_revocation(r)?.id;
                files.push((format!("{issuer_name}/{}/entries/{id}.cbor", store::LIST_DIR), cbor::encode(r, 65_536)?));
            }
            files.push((format!("{issuer_name}/{}/revocations.cbor", store::LIST_DIR), bytes));
        }
        store::ensure_private_dir(&self.keys)?;
        store::create_dir_atomically(&top, &files)?;
        println!("取り込みました: {name}（anchor maxDepth {}）", anchor.max_depth);
        self.tree_cmd()
    }
}

/// 転載サーバーの DID（PLC）と atproto 鍵。identity-cli の importer-secret.json から作る。
fn importer_identity_files(identity: &serde_json::Value, plan: Option<&Plan>) -> Result<Vec<(String, Vec<u8>)>> {
    let field = |k: &str| identity.get(k).and_then(|v| v.as_str()).ok_or_else(|| anyhow!("importer identity に {k} がありません"));
    ensure!(p::is_did(field("did")?), "DID が不正です: {}", field("did")?);
    identity.get("genesis").ok_or_else(|| anyhow!("importer identity に genesis がありません"))?;
    let atproto = crypto::secret_from_bytes(&hex::decode(field("atprotoSecret")?)?)?;
    ensure!(hex::encode(crypto::compressed(&atproto)) == field("atprotoPubkey")?, "atprotoPubkey と atprotoSecret が一致しません");
    let plc = crypto::secret_from_bytes(&hex::decode(field("plcSecret")?)?)?;
    let public = serde_json::json!({
        "did": field("did")?, "genesis": identity["genesis"], "nostrPubkey": field("nostrPubkey")?, "atprotoPubkey": field("atprotoPubkey")?,
    });
    let mut files = vec![("identity.json".to_string(), format!("{}\n", pretty(&public)).into_bytes())];
    files.push(secret_file(&atproto, plan, "atproto")?);
    files.push(secret_file(&plc, plan, "plc-rotation")?);
    Ok(files.into_iter().map(|(n, d)| (format!("{IMPORTER_DIR}/{n}"), d)).collect())
}

/// issuer・subject・publisher などの公開鍵フィールドを (パス, 鍵) で集める。
fn collect_keys(value: &Value, path: &str, out: &mut Vec<(String, Vec<u8>)>) {
    match value {
        Value::Map(m) => {
            for (k, v) in m {
                let child = if path.is_empty() { k.clone() } else { format!("{path}.{k}") };
                if matches!(k.as_str(), "issuer" | "subject" | "publisher" | "key") {
                    if let Some(b) = v.as_bytes().filter(|b| b.len() == 32 || b.len() == 33) {
                        out.push((child.clone(), b.to_vec()));
                    }
                }
                collect_keys(v, &child, out);
            }
        }
        Value::Array(a) => {
            for (n, v) in a.iter().enumerate() {
                collect_keys(v, &format!("{path}[{n}]"), out);
            }
        }
        _ => {}
    }
}
