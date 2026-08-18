// Android 运行时承载门面、规则缓存和生命周期，不绑定具体媒体播放器。
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.github.fongmi.adaudio.probe.runtime"
    compileSdk = 35

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
    compileOnly(libs.annotation)
}
