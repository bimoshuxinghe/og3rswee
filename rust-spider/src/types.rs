//! Spider 协议数据结构（与 TVBox / CatVod JSON 协议对齐）

use serde::{Deserialize, Serialize};

/// 视频条目（首页/分类/搜索列表项）
#[derive(Serialize, Deserialize, Debug, Clone, Default)]
pub struct VideoItem {
    pub vod_id: String,
    pub vod_name: String,
    #[serde(default)]
    pub vod_pic: String,
    #[serde(default)]
    pub vod_remarks: String,
    #[serde(default)]
    pub vod_year: String,
}

/// 分类结果（列表 + 分页）
#[derive(Serialize, Deserialize, Debug, Clone)]
pub struct CategoryResult {
    pub list: Vec<VideoItem>,
    pub page: u32,
    pub pagecount: u32,
    pub limit: u32,
    pub total: u32,
}

/// 分类定义（首页 class 数组）
#[derive(Serialize, Deserialize, Debug, Clone)]
pub struct VodClass {
    pub type_name: String,
    pub type_id: String,
}

/// 首页结果
#[derive(Serialize, Deserialize, Debug, Clone)]
pub struct HomeResult {
    pub class: Vec<VodClass>,
    pub list: Vec<VideoItem>,
}

/// 视频详情（含播放线路）
#[derive(Serialize, Deserialize, Debug, Clone, Default)]
pub struct VideoDetail {
    pub vod_id: String,
    pub vod_name: String,
    #[serde(default)]
    pub vod_pic: String,
    #[serde(default)]
    pub vod_content: String,
    #[serde(default)]
    pub vod_year: String,
    #[serde(default)]
    pub vod_area: String,
    #[serde(default)]
    pub vod_remarks: String,
    #[serde(default)]
    pub vod_actor: String,
    #[serde(default)]
    pub vod_director: String,
    #[serde(default)]
    pub type_name: String,
    pub vod_play_from: String,
    pub vod_play_url: String,
}

/// 详情结果
#[derive(Serialize, Deserialize, Debug, Clone)]
pub struct DetailResult {
    pub list: Vec<VideoDetail>,
}

/// 清晰度线路（来自 /video/play API）
#[derive(Serialize, Deserialize, Debug, Clone)]
pub struct QualityUrl {
    pub mtype: String,
    #[serde(default)]
    pub bitrate: i64,
    #[serde(default)]
    pub title: String,
    #[serde(default)]
    pub description: String,
    #[serde(default)]
    pub isvip: bool,
    #[serde(default)]
    pub locked: bool,
    /// 播放地址；"1" 表示 VIP 锁定占位
    pub url: String,
}

/// 播放数据（来自 /video/play API 的 data 字段）
#[derive(Serialize, Deserialize, Debug, Clone)]
pub struct PlayData {
    #[serde(default)]
    pub play_id: i64,
    #[serde(default)]
    pub subtitle_url: String,
    #[serde(default)]
    pub current_quality: i64,
    #[serde(default)]
    pub quality_urls: Vec<QualityUrl>,
}

/// /video/play API 响应
#[derive(Serialize, Deserialize, Debug, Clone)]
pub struct PlayApiResponse {
    #[serde(default)]
    pub code: i64,
    #[serde(default)]
    pub message: String,
    #[serde(default)]
    pub data: Option<PlayData>,
}

/// 播放结果（交给 ExoPlayer）
#[derive(Serialize, Deserialize, Debug, Clone)]
pub struct PlayResult {
    /// 0 = 直链播放；1 = 嗅探（WebView）
    pub parse: u8,
    pub url: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub header: Option<std::collections::HashMap<String, String>>,
}
