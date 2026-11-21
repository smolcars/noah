#!/usr/bin/env bash
# Pre-link each xcframework slice into one object whose only global symbols are the
# BoltFFI exports, then ship it as a dynamic framework.
#
# Pre-linking keeps the SDK's SQLite, ring and secp256k1 private, so they cannot collide
# with, or replace, the copies in bark's static library. BoltFFI's runtime helpers
# (boltffi_free_buf, boltffi_last_error_message, ...) have the same names in every
# BoltFFI library, bark's included, so they are exported as satora_boltffi_* and the
# generated header and Swift are rewritten to match.
#
# A dynamic framework, not a static library: arm64 compact unwind encodes at most three
# personality routines, and the app already uses three (bark's Rust, C++ and Objective-C).
# A second, private rust_eh_personality would fail the app's link; a dylib has its own.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
shopt -s nullglob
libs=(dist/apple/SatoraFFI.xcframework/*/libsatora_ffi.a)
[[ ${#libs[@]} -gt 0 ]] || { echo 'No xcframework slices; build first' >&2; exit 1; }
header=$(ls dist/apple/SatoraFFI.xcframework/*/Headers/satora_ffi/satora_ffi.h | head -1)
helpers=()
frameworks=()
for lib in "${libs[@]}"; do
    case "$lib" in
        *-simulator/*) sdk=iphonesimulator; suffix=-simulator; platform=iPhoneSimulator ;;
        *) sdk=iphoneos; suffix=; platform=iPhoneOS ;;
    esac
    sysroot=$(xcrun --sdk "$sdk" --show-sdk-path)
    slice=$(basename "$(dirname "$lib")")
    thin=()
    for arch in $(lipo -archs "$lib"); do
        a="$work/$slice-$arch.a" o="$work/$slice-$arch.o" syms="$work/$slice-$arch.exports" exports="$work/$slice-$arch.final"
        lipo "$lib" -thin "$arch" -output "$a" 2>/dev/null || cp "$lib" "$a"
        # Apple's nm cannot parse the bitcode Rust embeds; read the machine code only.
        nm --no-llvm-bc -gU -j "$a" 2>/dev/null | grep '^_boltffi_' | sort -u > "$syms"
        [[ -s "$syms" ]] || { echo "No BoltFFI symbols in $lib ($arch)" >&2; exit 1; }
        # Exports that do not name this crate are BoltFFI's shared runtime helpers.
        grep 'satora_ffi' "$syms" > "$exports"
        # Root the link at the exports, so only the members they reach are kept.
        aliases=() helpers=() roots=()
        while read -r sym; do roots+=("-Wl,-u,$sym"); done < "$syms"
        while read -r sym; do
            aliases+=("-Wl,-alias,$sym,_satora$sym")
            echo "_satora$sym" >> "$exports"
            helpers+=("${sym#_}")
        done < <(grep -v 'satora_ffi' "$syms")
        xcrun --sdk "$sdk" clang -target "$arch-apple-ios18.0$suffix" -isysroot "$sysroot" \
            -r -nostdlib "${roots[@]}" "${aliases[@]}" -Wl,-exported_symbols_list,"$exports" "$a" -o "$o"
        xcrun --sdk "$sdk" clang -target "$arch-apple-ios18.0$suffix" -isysroot "$sysroot" -dynamiclib "$o" \
            -install_name @rpath/satora_ffi.framework/satora_ffi -Wl,-exported_symbols_list,"$exports" -Wl,-dead_strip \
            -framework Security -framework SystemConfiguration -framework CoreFoundation -lc++ -lresolv -liconv \
            -o "$work/$slice-$arch.dylib"
        # Local symbols only name hidden code; the exports are all a caller needs.
        strip -x "$work/$slice-$arch.dylib"
        thin+=("$work/$slice-$arch.dylib")
    done
    fw="$work/$slice/satora_ffi.framework"
    mkdir -p "$fw/Headers"
    lipo -create "${thin[@]}" -output "$fw/satora_ffi"
    cp "$header" "$fw/Headers/"
    cat > "$fw/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleExecutable</key><string>satora_ffi</string>
    <key>CFBundleIdentifier</key><string>com.noahwallet.satora-ffi</string>
    <key>CFBundleName</key><string>satora_ffi</string>
    <key>CFBundlePackageType</key><string>FMWK</string>
    <key>CFBundleShortVersionString</key><string>0.1.0</string>
    <key>CFBundleVersion</key><string>1</string>
    <key>CFBundleSupportedPlatforms</key><array><string>$platform</string></array>
    <key>MinimumOSVersion</key><string>18.0</string>
</dict>
</plist>
PLIST
    frameworks+=(-framework "$fw")
    echo "prelinked $lib into a framework ($(wc -l < "$exports" | tr -d ' ') exports per arch, ${#helpers[@]} renamed)"
done

pattern=$(IFS='|'; echo "${helpers[*]}")
for f in "$work"/*/satora_ffi.framework/Headers/satora_ffi.h dist/apple/Sources/BoltFFI/*.swift; do
    perl -pi -e "s/\\b($pattern)\\b/satora_\$1/g" "$f"
done
rm -rf dist/apple/SatoraFFI.xcframework dist/apple/SatoraFFI.xcframework.zip
xcodebuild -create-xcframework "${frameworks[@]}" -output dist/apple/SatoraFFI.xcframework >/dev/null
(cd dist/apple && ditto -c -k --keepParent SatoraFFI.xcframework SatoraFFI.xcframework.zip)
