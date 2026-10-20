#!/usr/bin/env bash
# Run after installing regtest app + androidTest APKs, on the caller's disposable AVD.
set -euo pipefail
: "${ANDROID_SERIAL:?Set ANDROID_SERIAL=emulator-5610}"
[[ "$ANDROID_SERIAL" == emulator-5610 ]]
: "${1:?Pass the name of the AVD you created}"
[[ "$(adb emu avd name | head -1 | tr -d '\r')" == "$1" ]]
logs=$(mktemp -d)
cleanup() {
    if [[ -n "${stage_pid:-}" ]]; then
        adb shell am force-stop com.noahwallet.regtest || true
        wait "$stage_pid" || true
    fi
    rm -rf "$logs"
}
trap cleanup EXIT
runner=com.noahwallet.regtest.test/androidx.test.runner.AndroidJUnitRunner
test=com.noahwallet.services.SettingsFailureTest#interruptedRestoreSettlesWhileSettingsFailAtEveryBoundary
for step in journal previousMoved installed committing seed markerRemoved previousRemoved journalRemoved flags markerOnly stateOnly corrupt initializing{1..6} settling{1..9}; do
    adb shell am instrument -w -r -e class "$test" -e restoreStep "$step" -e restoreMode stage "$runner" > "$logs/stage" 2>&1 &
    stage_pid=$!
    ready=false
    for ((attempt=0; attempt<120; attempt++)); do
        if adb shell run-as com.noahwallet.regtest test -f cache/restore-settings-process/ready; then ready=true; break; fi
        sleep 0.25
    done
    if [[ "$ready" != true ]]; then cat "$logs/stage"; exit 1; fi
    adb shell am force-stop com.noahwallet.regtest
    wait "$stage_pid" || true
    stage_pid=
    adb shell am instrument -w -r -e class "$test" -e restoreStep "$step" -e restoreMode recover "$runner" > "$logs/recover" 2>&1
    if ! grep -q 'OK (1 test)' "$logs/recover"; then cat "$logs/recover"; exit 1; fi
    printf '%s: process killed, fresh-process recovery passed\n' "$step"
done
