// 公共合同是纯 Java 模块，不向宿主暴露 Android 或 Media3 类型。
plugins {
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}
