//! mosimo-ca: dev.jisaku.mosimo.attest の発行者側の実装（README.md を参照）。

mod age_io;
mod cbor;
mod cli;
mod crypto;
mod nostr;
mod protocol;
mod store;

fn main() {
    // `mosimo-ca inspect ... | head` のように出力先が閉じられたら、panic せずに終了する
    unsafe {
        libc::signal(libc::SIGPIPE, libc::SIG_DFL);
    }
    std::process::exit(cli::run());
}
