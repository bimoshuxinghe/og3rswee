#!/usr/bin/env bash
# spider_rust 一键交叉编译脚本（Android 双 ABI）
#
# 用法: ./build-rust.sh
# 依赖: NDK_HOME 指向 Android NDK（默认取脚本相对路径，也可 export 覆盖）
#
# 说明:
# 1. cc-rs 查找的 CC_/AR_ 环境变量使用【小写】目标名（连字符或下划线形式均可），
#    如 CC_aarch64_linux_android / CC_armv7_linux_androideabi；
# 2. cargo 的链接器变量使用【大写】目标名，连字符转下划线，
#    如 CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER；
# 3. .cargo/config.toml 的 [env] 不会传递给 cc-rs 等 build script 子进程，
#    故统一在本脚本 export。

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# NDK 定位：环境变量优先，其次常见相对/绝对路径
if [ -z "${NDK_HOME:-}" ]; then
    for cand in \
        "${SCRIPT_DIR}/../android-ndk-r26d" \
        "${SCRIPT_DIR}/android-ndk-r26d" \
        "/root/.codebuddy/artifact/2f8a7d30-9c21-4f0b-1d4e-eea0eb198000/android-ndk-r26d"; do
        if [ -d "$cand" ]; then NDK_HOME="$cand"; break; fi
    done
fi
if [ -z "${NDK_HOME:-}" ] || [ ! -d "$NDK_HOME" ]; then
    echo "错误: 未找到 Android NDK，请设置 NDK_HOME" >&2
    exit 1
fi
TOOLCHAIN="${NDK_HOME}/toolchains/llvm/prebuilt/linux-x86_64/bin"
echo "使用 NDK: ${NDK_HOME}"

build_abi() {
    local target="$1"      # 如 aarch64-linux-android（小写）
    local cc="$2"          # 如 aarch64-linux-android24-clang
    local triple_lower="${target//-/_}"                 # 小写下划线：cc-rs 用
    local triple_upper
    triple_upper="$(echo "$target" | tr 'a-z-' 'A-Z_')" # 大写下划线：cargo 用

    echo "==> 编译 ${target}"
    # cc-rs（ring 等 C 依赖的 build script）
    export "CC_${triple_lower}=${TOOLCHAIN}/${cc}"
    export "AR_${triple_lower}=${TOOLCHAIN}/llvm-ar"
    # cargo 链接器
    export "CARGO_TARGET_${triple_upper}_LINKER=${TOOLCHAIN}/${cc}"
    export "CARGO_TARGET_${triple_upper}_AR=${TOOLCHAIN}/llvm-ar"

    cargo build --release --target "${target}"
}

cd "${SCRIPT_DIR}"

build_abi "aarch64-linux-android"   "aarch64-linux-android24-clang"
build_abi "armv7-linux-androideabi" "armv7a-linux-androideabi24-clang"

# 外置站点 wasm（"Rust 版 py"：纯逻辑模块，运行依赖全在 .so 壳子内）
# wasm 与 ABI 无关，一份产物通吃所有设备；站点配置 "api":"./rust/xxx.wasm" 加载
build_wasm_sites() {
    echo "==> 编译外置站点 wasm (wasm32-unknown-unknown)"
    if ! rustup target list --installed | grep -q wasm32-unknown-unknown; then
        rustup target add wasm32-unknown-unknown
    fi
    for site in wasm-sites/*/; do
        [ -f "${site}Cargo.toml" ] || continue
        echo "    - ${site}"
        (cd "${site}" && cargo build --release --target wasm32-unknown-unknown)
    done
}

build_wasm_sites

echo ""
echo "构建完成，产物:"
echo "  [.so 引擎（JNI 内置方式）]"
ls -lh "target/aarch64-linux-android/release/libspider_rust.so" \
       "target/armv7-linux-androideabi/release/libspider_rust.so"
echo "  [.bin 独立二进制（推荐，新站点零壳子改动）]"
ls -lh "target/aarch64-linux-android/release/spider_rust_bin" \
       "target/armv7-linux-androideabi/release/spider_rust_bin"
echo "  [.wasm 外置站点（\"Rust 版 py\"：几十 KB 纯逻辑模块）]"
find wasm-sites -path '*/wasm32-unknown-unknown/release/*.wasm' ! -path '*/deps/*' ! -name '*.d' -exec ls -lh {} \;
echo ""
echo "部署到壳子:"
echo "  .bin → app/src/main/assets/rust/spider_rust.<abi>.bin（内置），或放到配置目录/网络地址（外置）"
echo "    cp target/aarch64-linux-android/release/spider_rust_bin   <repo>/app/src/main/assets/rust/spider_rust.arm64-v8a.bin"
echo "    cp target/armv7-linux-androideabi/release/spider_rust_bin <repo>/app/src/main/assets/rust/spider_rust.armeabi-v7a.bin"
echo "  .so  → app/src/main/jniLibs/<abi>/（仅 rust:// JNI 内置方式需要）"
echo "    cp target/aarch64-linux-android/release/libspider_rust.so   <repo>/app/src/main/jniLibs/arm64-v8a/"
echo "    cp target/armv7-linux-androideabi/release/libspider_rust.so <repo>/app/src/main/jniLibs/armeabi-v7a/"
echo "  .wasm → 直接随配置分发（如 ./rust/4kvm.wasm）；壳子按 .wasm 线路自动加载注册"
echo "    cp wasm-sites/4kvm/target/wasm32-unknown-unknown/release/site_4kvm.wasm <分发目录>/4kvm.wasm"
