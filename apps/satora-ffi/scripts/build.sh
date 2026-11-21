#!/usr/bin/env bash
# Build the iOS xcframework or Android libraries plus bindings into dist/, then copy the
# stripped results into apple/ and android/, which the apps build from and git tracks.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
# Panic messages embed source paths; keep the builder's home directory out of the committed binaries.
export RUSTFLAGS="${RUSTFLAGS:-} --remap-path-prefix=$HOME=/home"
BOLTFFI="${BOLTFFI:-.tools/boltffi}"
[[ -x "$BOLTFFI" ]] || scripts/install-boltffi.sh
[[ "$("$BOLTFFI" --version)" == "boltffi 0.31.1" ]] || { echo 'Use BoltFFI CLI 0.31.1' >&2; exit 1; }
case "${1:?Choose ios or android}" in
    ios)
        # Rust's default (10.0) leaves aws-lc's ___chkstk_darwin undefined.
        export IPHONEOS_DEPLOYMENT_TARGET=18.0
        rm -rf dist/apple
        "$BOLTFFI" pack apple --release --deny-skipped --layout ffi-only
        # BoltFFI writes .v18 into a swift-tools-version 5.9 manifest, which predates it.
        sed -i '' 's/\.iOS(\.v18)/.iOS("18.0")/' dist/apple/Package.swift
        scripts/prelink-ios.sh
        rm -rf apple && mkdir apple
        cp -R dist/apple/Package.swift dist/apple/FFI dist/apple/Sources dist/apple/SatoraFFI.xcframework apple/
        ;;
    android)
        export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-${ANDROID_HOME:?Set ANDROID_HOME or ANDROID_NDK_HOME}/ndk/28.2.13676358}"
        rm -rf dist/android dist/kotlin
        "$BOLTFFI" pack android --release --deny-skipped
        rm -rf android && mkdir -p android/kotlin
        cp -R dist/android/jniLibs android/
        cp -R dist/kotlin/com android/kotlin/
        for so in android/jniLibs/*/libsatora_ffi.so; do
            "$ANDROID_NDK_HOME"/toolchains/llvm/prebuilt/*/bin/llvm-strip --strip-all "$so"
        done
        ;;
    *) echo "Unsupported platform: $1" >&2; exit 1 ;;
esac
if grep -rqaF "$HOME" apple android; then echo 'Built files contain the home directory path' >&2; exit 1; fi
