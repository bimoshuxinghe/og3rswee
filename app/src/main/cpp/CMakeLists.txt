cmake_minimum_required(VERSION 3.22.1)
project(mpvkit LANGUAGES C)

# JNI 桥接库 libmpvkit.so
add_library(mpvkit SHARED mpv_bridge.c)

# 导入 jniLibs 中的 media-kit libmpv.so（仅链接，不重复打包）
add_library(mpv SHARED IMPORTED)
set_target_properties(mpv PROPERTIES
        IMPORTED_LOCATION ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libmpv.so)

target_include_directories(mpvkit PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/mpv)

target_link_libraries(mpvkit
        mpv
        android
        log
        EGL
        GLESv2)
