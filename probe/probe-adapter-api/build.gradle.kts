// 适配器 SPI 只定义稳定 Android 合同，不包含任何具体播放器实现。
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.github.fongmi.adaudio.probe.adapter"
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
}
