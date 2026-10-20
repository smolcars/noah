#!/usr/bin/env bash
# Run after build-for-testing, using only the simulator and DerivedData you created.
set -euo pipefail
: "${1:?Pass your owned simulator UDID}"
: "${2:?Pass its DerivedData path}"
udid=$1
derived=$2
container=$(xcrun simctl get_app_container "$udid" com.noahwallet.regtest data)
control="$container/Documents/restore-settings-control"
ready="$container/Documents/restore-settings-process/ready"
logs=$(mktemp -d)
cleanup() {
    if [[ -n "${stage_pid:-}" ]]; then
        xcrun simctl terminate "$udid" com.noahwallet.regtest >/dev/null 2>&1 || true
        pkill -TERM -P "$stage_pid" || true
        kill "$stage_pid" 2>/dev/null || true
        wait "$stage_pid" || true
    fi
    # Installing the test host migrates its container, including the control file.
    container=$(xcrun simctl get_app_container "$udid" com.noahwallet.regtest data)
    rm -f "$container/Documents/restore-settings-control"
    rm -rf "$logs"
}
trap cleanup EXIT
run_test() {
    xcodebuild test-without-building -xctestrun "$derived"/Build/Products/Noah-Regtest_iphonesimulator*-arm64.xctestrun \
        -destination "id=$udid" -parallel-testing-enabled NO -collect-test-diagnostics never \
        -only-testing:'NoahTests/RestoreSettingsRecoveryTests/processDeathRestoreWithFailedSettings()'
}
for step in journal previousMoved installed committing seed markerRemoved previousRemoved journalRemoved flags markerOnly stateOnly corrupt initializing{1..6} settling{1..9}; do
    container=$(xcrun simctl get_app_container "$udid" com.noahwallet.regtest data)
    control="$container/Documents/restore-settings-control"
    printf '%s stage' "$step" > "$control"
    run_test > "$logs/stage" 2>&1 &
    stage_pid=$!
    staged=false
    for ((attempt=0; attempt<240; attempt++)); do
        container=$(xcrun simctl get_app_container "$udid" com.noahwallet.regtest data)
        control="$container/Documents/restore-settings-control"
        ready="$container/Documents/restore-settings-process/ready"
        if [[ -f "$ready" ]]; then staged=true; break; fi
        sleep 0.25
    done
    if [[ "$staged" != true ]]; then cat "$logs/stage"; exit 1; fi
    rm "$control" # A crash retry must not stage the checkpoint again.
    xcrun simctl terminate "$udid" com.noahwallet.regtest
    wait "$stage_pid" || true
    stage_pid=
    container=$(xcrun simctl get_app_container "$udid" com.noahwallet.regtest data)
    control="$container/Documents/restore-settings-control"
    printf '%s recover' "$step" > "$control"
    run_test > "$logs/recover" 2>&1
    if ! grep -q 'Test processDeathRestoreWithFailedSettings() passed' "$logs/recover"; then cat "$logs/recover"; exit 1; fi
    printf '%s: process terminated, fresh-process recovery passed\n' "$step"
done
