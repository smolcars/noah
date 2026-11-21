#!/usr/bin/env bash
# Link an iOS device executable the way the app is linked: bark's static library, the
# Satora framework from apple/, and code using the C++ and Objective-C personality
# routines. Each order, and -force_load on bark, must link with no duplicate symbol or
# compact-unwind failure, and bark's and the SDK's SQLite builds must both be present.
# Usage: scripts/check-bark-link.sh path/to/BarkBoltFFI.xcframework
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
bark="${1:?Pass the BarkBoltFFI.xcframework path}/ios-arm64/libbark_bolt_ffi.a"
slice=apple/SatoraFFI.xcframework/ios-arm64
[[ -f "$bark" && -f "$slice/satora_ffi.framework/satora_ffi" ]] || { echo "Missing $bark or $slice" >&2; exit 1; }
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

# Reference every BoltFFI export of both libraries so the linker keeps them all.
{
    echo '#import <Foundation/Foundation.h>'
    echo '#include <stdexcept>'
    { nm --no-llvm-bc -gU -j "$bark" 2>/dev/null; nm -gU -j "$slice/satora_ffi.framework/satora_ffi"; } |
        grep -E '^_(satora_)?boltffi_' | sort -u | sed 's/^_//' |
        awk '{ print "extern \"C\" void " $0 "(void);"; refs = refs "(void*)" $0 ",\n" }
             END { print "void *refs[] = {\n" refs "};" }'
    echo 'int main() { @try { try { throw std::runtime_error("x"); } catch (...) {} } @catch (id e) {} return refs[0] == 0; }'
} > "$work/main.mm"

sdk_path=$(xcrun --sdk iphoneos --show-sdk-path)
xcrun --sdk iphoneos clang++ -target arm64-apple-ios18.0 -isysroot "$sdk_path" -fobjc-arc -c "$work/main.mm" -o "$work/main.o"

fail=0
check() {
    local name=$1; shift
    local log="$work/$name.log" bin="$work/$name"
    if ! xcrun --sdk iphoneos clang++ -target arm64-apple-ios18.0 -isysroot "$sdk_path" "$work/main.o" "$@" \
        -F "$slice" -framework Foundation -framework Security -framework SystemConfiguration -framework CoreFoundation \
        -lresolv -liconv -Wl,-dead_strip -o "$bin" > "$log" 2>&1; then
        echo "FAIL $name: link failed"; grep -m5 -E 'error|duplicate|personality' "$log"; fail=1; return
    fi
    local dups ids
    dups=$(grep -c 'duplicate symbol' "$log" || true)
    # SQLite source ids look like "2024-01-15 17:01:03 <64 hex>": bark's in the binary, the SDK's in the framework.
    ids=$(cat "$bin" "$slice/satora_ffi.framework/satora_ffi" | strings -a | grep -oE '^20[0-9]{2}-[0-9]{2}-[0-9]{2} [0-9:]{8} [0-9a-f]{64}' | sort -u)
    echo "$name: duplicate symbols $dups, linker warnings $(grep -c 'warning' "$log" || true), SQLite source ids $(printf '%s\n' "$ids" | grep -c .)"
    printf '%s\n' "$ids" | sed 's/^/    /'
    grep 'warning' "$log" | sed 's/^/    /' || true
    [[ "$dups" == 0 && $(printf '%s\n' "$ids" | grep -c .) == 2 ]] || fail=1
}
check satora-then-bark -framework satora_ffi "$bark"
check bark-then-satora "$bark" -framework satora_ffi
check force-load-bark -Wl,-force_load,"$bark" -framework satora_ffi
[[ $fail == 0 ]] && echo PASS || { echo FAIL; exit 1; }
