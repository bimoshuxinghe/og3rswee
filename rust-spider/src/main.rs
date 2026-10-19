//! spider_rust_bin — TVBox Rust 站点独立可执行二进制（CLI 入口）
//!
//! 壳子以子进程方式调用（ProcessBuilder），协议：
//!
//! ```text
//! spider_rust_bin <action> <ext_json> [args...]
//!
//! home                                  → stdout: HomeResult JSON
//! category <tid> <pg>                   → stdout: CategoryResult JSON
//! detail  <id>                          → stdout: DetailResult JSON
//! search  <key> <pg>                    → stdout: CategoryResult JSON
//! play    <id>                          → stdout: PlayResult JSON
//! warm                                  → stdout: "ok"
//! ```
//!
//! - stdout 只输出结果 JSON（壳子整体读取解析）；所有日志走 stderr；
//! - 退出码 0 = 成功，1 = 失败（错误信息在 stderr）；
//! - ext_json 为壳子站点配置的 ext（JSON 字符串，含 host/module 等），
//!   传 `-` 表示空 ext（仅当配置无 ext 时）。
//!
//! 新增站点：在 src/spider/ 加模块并在 spider/mod.rs 注册（见 README），
//! 本文件无需改动。

use std::process::ExitCode;

use spider_rust::spider::{self, SiteExt};

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().skip(1).collect();
    match run(&args) {
        Ok(json) => {
            println!("{}", json);
            ExitCode::SUCCESS
        }
        Err(e) => {
            eprintln!("[spider_rust_bin] {}", e);
            ExitCode::FAILURE
        }
    }
}

fn run(args: &[String]) -> Result<String, String> {
    let action = args.first().ok_or("缺少 action 参数").map(|s| s.as_str())?;

    // ext：第二个参数；"-" 或缺失表示空配置
    let ext_raw = args.get(1).map(|s| s.as_str()).unwrap_or("-");
    let ext_json = if ext_raw == "-" { "" } else { ext_raw };
    let ext = SiteExt::parse(ext_json)?;
    let rest = &args[2.min(args.len())..];

    match action {
        "home" => spider::home_content(&ext),
        "warm" => spider::warm_up(&ext),
        "category" => {
            let tid = rest.first().ok_or("category 缺少 tid 参数")?;
            let pg: u32 = rest
                .get(1)
                .and_then(|s| s.parse().ok())
                .unwrap_or(1)
                .max(1);
            spider::category_content(&ext, tid, pg)
        }
        "detail" => {
            let id = rest.first().ok_or("detail 缺少 id 参数")?;
            spider::detail_content(&ext, id)
        }
        "search" => {
            let key = rest.first().ok_or("search 缺少 key 参数")?;
            let pg: u32 = rest
                .get(1)
                .and_then(|s| s.parse().ok())
                .unwrap_or(1)
                .max(1);
            spider::search_content(&ext, key, pg)
        }
        "play" => {
            let id = rest.first().ok_or("play 缺少 id 参数")?;
            spider::player_content(&ext, id)
        }
        other => Err(format!(
            "未知 action: {}（可用: home/category/detail/search/play/warm）",
            other
        )),
    }
}
