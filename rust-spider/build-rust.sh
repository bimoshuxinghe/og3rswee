#!/bin/bash
# 交叉编译 libspider_4kvm.so（arm64-v8a + armeabi-v7a）
# 用法：
#   export NDK_HOME=~/android-ndk-r26d
#   ./build-rust.sh
set -euo pipefail

NDK_HOME="${NDK_HOME:?请先设置 NDK_HOME 环境变量（指向 Android NDK 根目录）}"
BIN="$NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"
MIN_API=24

if [ ! -x "$BIN/aarch64-linux-android${MIN_API}-clang" ]; then
    echo "错误: NDK clang 不存在: $BIN/aarch64-linux-android${MIN_API}-clang" >&2
    exit 1
fi

# rustup target add aarch64-linux-android armv7-linux-androideabi

# ring 等 C 依赖（cc-rs 读取 CC_<target> / AR_<target>）
export CC_aarch64_linux_android="$BIN/aarch64-linux-android${MIN_API}-clang"
export AR_aarch64_linux_android="$BIN/llvm-ar"
export CC_armv7_linux_androideabi="$BIN/armv7a-linux-androideabi${MIN_API}-clang"
export AR_armv7_linux_androideabi="$BIN/llvm-ar"

# Rust linker（CARGO_TARGET_<triple>_LINKER 覆盖默认 linker，连字符转下划线）
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$BIN/aarch64-linux-android${MIN_API}-clang"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_AR="$BIN/llvm-ar"
export CARGO_TARGET_ARMV7_LINUX_ANDROIDEABI_LINKER="$BIN/armv7a-linux-androideabi${MIN_API}-clang"
export CARGO_TARGET_ARMV7_LINUX_ANDROIDEABI_AR="$BIN/llvm-ar"

cargo build --release --target aarch64-linux-android
cargo build --release --target armv7-linux-androideabi

echo "构建完成，产物："
ls -la target/aarch64-linux-android/release/libspider_4kvm.so \
       target/armv7-linux-androideabi/release/libspider_4kvm.so
