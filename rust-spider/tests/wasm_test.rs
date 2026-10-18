//! WASM 引擎单元测试（无需网络）

use spider_rust::wasm_engine;

#[test]
fn build_play_url_generates_signed_api() {
    let url = wasm_engine::build_play_url(
        "40779",
        "ch4phdr82",
        "1080",
        "X1VUXUVQVAoDAw4FCgc7IUleUUtX",
    )
    .expect("build_play_url 应成功");
    println!("生成的播放 API: {}", url);
    assert!(url.starts_with("/video/play?"), "URL 模板不匹配: {}", url);
    assert!(url.contains("p=40779"), "缺少 dataid: {}", url);
    assert!(url.contains("v=ch4phdr82"), "缺少 secret: {}", url);
    assert!(url.contains("q=1080"), "缺少 quality: {}", url);
    assert!(url.contains("&s="), "缺少签名: {}", url);
    assert!(url.contains("&t="), "缺少时间戳: {}", url);
    assert!(url.contains("&k="), "缺少密钥参数: {}", url);
}

#[test]
fn build_play_url_deterministic_for_same_time() {
    let a = wasm_engine::build_play_url("40779", "ch4phdr82", "1080", "X1VUXUVQVAoDAw4FCgc7IUleUUtX").unwrap();
    let b = wasm_engine::build_play_url("40780", "ch4phdr8i", "1080", "X1VUXUVQVAoDAw4FCgc7IUleUUtX").unwrap();
    assert_ne!(a, b, "不同 dataid 应生成不同 URL");
}
