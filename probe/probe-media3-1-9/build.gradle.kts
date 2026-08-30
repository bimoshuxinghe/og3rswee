// Media3 适配器隔离不稳定音频接口。
// 注意：所有 androidx.media3:* 依赖会被 settings.gradle 的 dependencySubstitution
// 统一替换为 TV-Mobile 本地 fork（1.10.0），因此这里不再锁 strictly 版本。
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.github.fongmi.adaudio.probe.adapter.media3.v1_9"
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
    api(project(":probe:probe-adapter-api"))
    // AudioSink 属于 Media3 不稳定 API，所有组件必须绑定同一版本。
    // 此处仅声明 androidx.media3:* 坐标，由 dependencySubstitution 路由到本地 fork。
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.common)
    implementation(libs.media3.container)
    implementation(libs.media3.datasource)
    implementation(libs.media3.decoder)
    implementation(libs.media3.extractor)
    implementation(libs.media3.database)
    // Media3 1.11.0 起 AudioSink.AudioSinkConfig#outputChannelMapping 使用 Guava 的
    // ImmutableIntArray；探针需要读取该字段以适配新的 configure(AudioSinkConfig) 路径。
    // 运行时由 media3-common 的 api(libs.guava) 提供，此处仅编译期可见。
    compileOnly(libs.guava)
    // 对齐 media3 fork 解析出的 annotation 版本(1.6.0)，避免 compile/runtime consistent resolution 冲突
    compileOnly("androidx.annotation:annotation:1.6.0")
}
