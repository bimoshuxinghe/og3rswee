//! 4KVM 爬虫端到端集成测试（需要网络访问 4kvms.org）
//!
//! 验证用户核心诉求：能否获取到数据（首页/分类/详情/搜索）和播放线路（m3u8 直链）

use spider_4kvm::Spider4KVM;

const HOST: &str = "https://www.4kvms.org/";

fn spider() -> Spider4KVM {
    Spider4KVM::new(HOST)
}

#[test]
fn home_content_returns_data() {
    let s = spider();
    let home = s.home_content();
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
    let s = spider();
    let cat = s.category_content(&format!("{}/movie", HOST), 1);
    println!("分类列表数: {} pagecount={}", cat.list.len(), cat.pagecount);
    for v in cat.list.iter().take(5) {
        println!("  * {} | {} | {}", v.vod_name, v.vod_remarks, v.vod_id);
    }
    assert!(!cat.list.is_empty(), "分类页应有数据");
}

#[test]
fn detail_content_returns_episodes() {
    let s = spider();
    // 取首页第一个视频作为详情目标
    let home = s.home_content();
    let first = home
        .list
        .first()
        .expect("首页应有数据")
        .vod_id
        .clone();
    println!("测试详情页: {}", first);
    let detail = s.detail_content(&first);
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
    let s = spider();
    let result = s.search_content("庆余年", 1);
    println!("搜索结果数: {}", result.list.len());
    for v in result.list.iter().take(5) {
        println!("  * {} | {}", v.vod_name, v.vod_id);
    }
    assert!(!result.list.is_empty(), "搜索应有结果");
}

#[test]
fn player_content_returns_m3u8() {
    let s = spider();
    // 完整链路：首页 → 详情 → 选集 → 播放
    let home = s.home_content();
    let first = home.list.first().expect("首页应有数据").vod_id.clone();
    let detail = s.detail_content(&first);
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

    let result = s.player_content(play_id);
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
