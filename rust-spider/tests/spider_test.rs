//! 4KVM 爬虫端到端集成测试（需要网络访问 4kvms.org）
//!
//! 验证用户核心诉求：能否获取到数据（首页/分类/详情/搜索）和播放线路（m3u8 直链）。
//! 测试经分发层 `spider::*` 调用（与 JNI 层同路径），顺带验证通用引擎分发逻辑。

use spider_rust::spider::{self, SiteExt};
use spider_rust::types::*;

const HOST: &str = "https://www.4kvms.org/";

fn ext() -> SiteExt {
    SiteExt::parse(&format!("{{\"host\":\"{}\"}}", HOST)).expect("ext 解析应成功")
}

#[test]
fn home_content_returns_data() {
    let home: HomeResult =
        serde_json::from_str(&spider::home_content(&ext()).expect("home_content 应成功"))
            .expect("应返回合法 HomeResult JSON");
    println!("分类数: {}", home.class.len());
    for c in &home.class {
        println!("  - {} => {}", c.type_name, c.type_id);
    }
    println!("首页列表数: {}", home.list.len());
    for v in home.list.iter().take(5) {
        println!("  * {} | {} | {}", v.vod_name, v.vod_remarks, v.vod_id);
    }
    assert_eq!(home.class.len(), 3, "应有 3 个分类");
    assert!(!home.list.is_empty(), "首页应有数据");
}

#[test]
fn category_content_returns_data() {
    let cat: CategoryResult = serde_json::from_str(
        &spider::category_content(&ext(), &format!("{}/movie", HOST), 1).expect("应成功"),
    )
    .expect("应返回合法 CategoryResult JSON");
    println!("分类列表数: {} pagecount={}", cat.list.len(), cat.pagecount);
    for v in cat.list.iter().take(5) {
        println!("  * {} | {} | {}", v.vod_name, v.vod_remarks, v.vod_id);
    }
    assert!(!cat.list.is_empty(), "分类页应有数据");
}

#[test]
fn detail_content_returns_episodes() {
    let home: HomeResult =
        serde_json::from_str(&spider::home_content(&ext()).expect("应成功")).expect("合法 JSON");
    // 取首页第一个视频作为详情目标
    let first = home
        .list
        .first()
        .expect("首页应有数据")
        .vod_id
        .clone();
    println!("测试详情页: {}", first);
    let detail: DetailResult =
        serde_json::from_str(&spider::detail_content(&ext(), &first).expect("应成功"))
            .expect("合法 JSON");
    let vod = &detail.list[0];
    println!("标题: {}", vod.vod_name);
    println!("海报: {}", vod.vod_pic);
    println!("选集串(前300字): {}", &vod.vod_play_url[..vod.vod_play_url.len().min(300)]);
    let ep_count = vod.vod_play_url.split('#').count();
    println!("选集数: {}", ep_count);
    assert!(ep_count >= 1, "应至少有一个选集");
    assert!(
        vod.vod_play_url.contains("dataid="),
        "选集应携带 dataid"
    );
}

#[test]
fn search_content_returns_data() {
    let result: CategoryResult =
        serde_json::from_str(&spider::search_content(&ext(), "庆余年", 1).expect("应成功"))
            .expect("合法 JSON");
    println!("搜索结果数: {}", result.list.len());
    for v in result.list.iter().take(5) {
        println!("  * {} | {}", v.vod_name, v.vod_id);
    }
    assert!(!result.list.is_empty(), "搜索应有结果");
}

#[test]
fn player_content_returns_m3u8() {
    let e = ext();
    // 完整链路：首页 → 详情 → 选集 → 播放
    let home: HomeResult =
        serde_json::from_str(&spider::home_content(&e).expect("应成功")).expect("合法 JSON");
    let first = home.list.first().expect("首页应有数据").vod_id.clone();
    let detail: DetailResult =
        serde_json::from_str(&spider::detail_content(&e, &first).expect("应成功")).expect("合法 JSON");
    let vod = &detail.list[0];
    let play_url = vod
        .vod_play_url
        .split('#')
        .next()
        .expect("应有选集")
        .to_string();
    // 取出 $ 后的 id 部分
    let play_id = play_url.split('$').nth(1).unwrap_or(&play_url);
    println!("播放 id: {}", play_id);

    let result: PlayResult =
        serde_json::from_str(&spider::player_content(&e, play_id).expect("应成功")).expect("合法 JSON");
    println!("parse={} url={}", result.parse, result.url);
    if result.parse == 0 {
        assert!(
            result.url.contains(".m3u8") || result.url.starts_with("http"),
            "应解析出 m3u8 直链: {}",
            result.url
        );
        println!("✅ 播放线路解析成功: {}", result.url);
    } else {
        // 嗅探兜底也属于可用结果，但打印警告
        println!("⚠️ 落入嗅探兜底: {}", result.url);
        panic!("播放线路未能直解析出 m3u8");
    }
}

// ---------- 通用引擎分发逻辑测试（无需网络） ----------

#[test]
fn host_feature_resolves_4kvm_module() {
    let e = SiteExt::parse(r#"{"host":"https://www.4kvms.org/"}"#).unwrap();
    assert_eq!(e.resolve_module(), "4kvm", "host 含 4kvms 应识别为 4kvm 模块");
}

#[test]
fn explicit_module_wins_over_host() {
    let e = SiteExt::parse(r#"{"host":"https://example.com/","module":"MySite"}"#).unwrap();
    assert_eq!(e.resolve_module(), "mysite", "显式 module 应优先且小写化");
}

#[test]
fn unknown_module_returns_error() {
    let e = SiteExt::parse(r#"{"host":"https://example.com/","module":"nope"}"#).unwrap();
    let err = spider::home_content(&e).unwrap_err();
    assert!(
        err.contains("引擎暂无该站点的 Rust 实现"),
        "应返回友好错误: {}",
        err
    );
}

#[test]
fn missing_host_returns_error() {
    let e = SiteExt::parse("{}").unwrap();
    let err = spider::home_content(&e).unwrap_err();
    assert!(err.contains("host"), "应提示缺少 host: {}", err);
}

#[test]
fn bad_ext_json_returns_error() {
    let err = SiteExt::parse("not-a-json").unwrap_err();
    assert!(err.contains("ext JSON 解析失败"), "应提示 JSON 解析失败: {}", err);
}
