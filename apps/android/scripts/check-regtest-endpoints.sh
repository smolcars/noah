#!/bin/sh
# Build-time checks for the regtest URL properties (regtestEndpoint in app/build.gradle.kts). Each bad value
# must fail Gradle with that property's "Invalid" message, each good one must reach the regtest BuildConfig,
# and mainnet/signet BuildConfig must keep the Ark/bitcoind overrides empty. Run inside the Nix shell:
#   nix develop --command sh apps/android/scripts/check-regtest-endpoints.sh
set -u
cd "$(dirname "$0")/.."
config=app/build/generated/source/buildConfig
failures=0
report() { echo "$1"; case "$1" in ok*) ;; *) failures=$((failures + 1)) ;; esac; }

rejects() { # rejects <property> <value>
    output=$(./gradlew -q --max-workers=2 "-P$1=$2" :app:generateRegtestDebugBuildConfig 2>&1)
    if [ $? -ne 0 ] && printf '%s' "$output" | grep -q "Invalid $1"; then report "ok   rejects $1=$2"
    else report "FAIL accepted (or failed for another reason) $1=$2"; fi
}
accepts() { # accepts <property> <value> <BuildConfig field>
    if ./gradlew -q --max-workers=2 "-P$1=$2" :app:generateRegtestDebugBuildConfig >/dev/null 2>&1 &&
        grep -q "String $3 = \"$2\";" "$config/regtest/debug/com/noahwallet/BuildConfig.java"; then report "ok   accepts $1=$2"
    else report "FAIL rejected $1=$2"; fi
}

for value in "http://:" "http://localhost:banana" "http://localhost:0" "ftp://x" "http://" "http://:13535" \
    "http://localhost:65536" "http://localhost:" "http://user:pw@localhost:1" "http://localhost:1/path" "http://bad_host:1"; do
    rejects noahRegtestArkURL "$value"
done
for value in "http://localhost:banana" "http://:"; do rejects noahRegtestBitcoindURL "$value"; done
for value in "http://:" "http://localhost:0" "ftp://x"; do rejects noahRegtestServerURL "$value"; done

accepts noahRegtestArkURL http://10.0.2.2:13535 REGTEST_ARK_URL
accepts noahRegtestArkURL https://ark.example.com REGTEST_ARK_URL
accepts noahRegtestBitcoindURL http://127.0.0.1:28443 REGTEST_BITCOIND_URL
accepts noahRegtestServerURL http://10.0.2.2:13000 REGTEST_SERVER_URL

# Mainnet and signet never carry the overrides, even when both flags are passed.
if ./gradlew -q --max-workers=2 -PnoahUnsignedRelease=true -PnoahRegtestArkURL=http://10.0.2.2:13535 -PnoahRegtestBitcoindURL=http://10.0.2.2:28443 \
    :app:generateMainnetDebugBuildConfig :app:generateMainnetReleaseBuildConfig \
    :app:generateSignetDebugBuildConfig :app:generateSignetReleaseBuildConfig >/dev/null 2>&1; then
    for variant in mainnet/debug mainnet/release signet/debug signet/release; do
        file="$config/$variant/com/noahwallet/BuildConfig.java"
        if grep -q 'String REGTEST_ARK_URL = "";' "$file" && grep -q 'String REGTEST_BITCOIND_URL = "";' "$file"; then report "ok   $variant overrides empty"
        else report "FAIL $variant carries an override"; fi
    done
else report "FAIL mainnet/signet BuildConfig generation"; fi

# Leave the regtest BuildConfig on today's defaults.
if ./gradlew -q --max-workers=2 :app:generateRegtestDebugBuildConfig >/dev/null 2>&1 &&
    grep -q 'String REGTEST_ARK_URL = "";' "$config/regtest/debug/com/noahwallet/BuildConfig.java" &&
    grep -q 'String REGTEST_SERVER_URL = "http://10.0.2.2:3000";' "$config/regtest/debug/com/noahwallet/BuildConfig.java"; then report "ok   defaults unchanged"
else report "FAIL defaults"; fi

echo "$failures failure(s)"
[ "$failures" -eq 0 ]
