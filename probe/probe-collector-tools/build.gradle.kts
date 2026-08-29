// 采集器工具提供指纹产出与 HLS 候选扫描，不向宿主暴露 PCM 或播放器实现。
// 已适配 TV-Mobile 复合构建：去掉 Maven 发布插件，依赖改为 :probe: 本地模块。
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.github.fongmi.adaudio.probe.tools"
    compileSdk = 36

    defaultConfig {
        minSdk = 23
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

dependencies {
    api(project(":probe:probe-api"))
    api(project(":probe:probe-adapter-api"))
    implementation(project(":probe:probe-core"))
    compileOnly("androidx.annotation:annotation:1.6.0")
}
