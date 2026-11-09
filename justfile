# Noah Wallet Justfile

set positional-arguments := true

# Default recipe to display available commands
default:
    @just --list

# Format server Rust code
fmt:
    cargo fmt --all

# Install dependencies
install:
    bun install

# Bump native Android and iOS versions and build numbers
bump-native *args:
    bun scripts/bump_native_version.ts {{ args }}

# Refresh the BTC Map snapshot bundled with the apps
btcmap-snapshot:
    bun run btcmap:snapshot

# Refresh the GeoNames city index bundled with the apps
geonames-snapshot:
    bun run geonames:snapshot

# Native iOS app (apps/ios). variant: Regtest | Signet | Mainnet
ios-native-generate:
    cd apps/ios && xcodegen generate

ios-native-build variant="Regtest": ios-native-generate
    cd apps/ios && xcodebuild -project Noah.xcodeproj -scheme {{ if variant == "Mainnet" { "Noah" } else { "Noah-" + variant } }} -destination "generic/platform=iOS Simulator" -derivedDataPath build/DerivedData build

ios-native-test variant="Regtest" device="iPhone 17": ios-native-generate
    cd apps/ios && xcodebuild -project Noah.xcodeproj -scheme {{ if variant == "Mainnet" { "Noah" } else { "Noah-" + variant } }} -destination "platform=iOS Simulator,name={{ device }}" -derivedDataPath build/DerivedData test

# Native Android app (apps/android). variant: Regtest | Signet | Mainnet
android-native-build variant="Regtest":
    cd apps/android && ./gradlew :app:assemble{{ variant }}Debug

android-native-install variant="Regtest":
    cd apps/android && ./gradlew :app:install{{ variant }}Debug

android-native-test:
    cd apps/android && ./gradlew :app:testRegtestDebugUnitTest

# Native lint and format (apps/ios, apps/android). Tools come from the Nix shell; the pre-commit hook runs the same checks.
native-lint: ios-native-lint android-native-lint

native-format:
    cd apps/ios && swiftformat .
    cd apps/android && ktlint --format --relative "**/*.kt" "**/*.kts" "!**/build/**" "!**/generated/**"

ios-native-lint:
    cd apps/ios && swiftformat --lint . && swiftlint lint --strict

android-native-lint:
    cd apps/android && ktlint --relative "**/*.kt" "**/*.kts" "!**/build/**" "!**/generated/**"
    cd apps/android && detekt --input . --excludes "**/build/**,**/generated/**" --build-upon-default-config --all-rules --config Config/detekt/detekt.yml

# Server commands
server:
    bacon

server-build:
    cargo build

server-test:
    cargo test

server-check:
    cargo check

# Regenerate api/openapi.json after an intentional API change
generate-api:
    UPDATE_OPENAPI=1 cargo test -p server --bin server openapi::export

# Regenerate the iOS client in apps/ios/Noah/API/Generated from api/openapi.json
generate-api-ios:
    rm -rf apps/ios/Noah/API/Generated && mkdir -p apps/ios/Noah/API/Generated
    swift run --package-path apps/ios/Tools/OpenAPIGenerator swift-openapi-generator generate --config apps/ios/Noah/API/openapi-generator-config.yaml --output-directory apps/ios/Noah/API/Generated api/openapi.json

# Regenerate the Kotlin client with checksum-pinned Fabrikt
generate-api-android:
    python3 apps/android/tools/generate-api.py

# Start ngrok tunnel to localhost:3000
ngrok:
    ngrok http 3000 --domain $(cat .ngrok-domain)

# Load testing (requires server running on localhost:3000)
load-test-regtest users="30" time="2m" host="http://localhost:3000":
    LOADTEST_SCENARIO=all LOADTEST_HOST={{ host }} cargo run --bin loadtest --features loadtest --release -- --users {{ users }} --run-time {{ time }} --host {{ host }} --report-file report.html

# Load testing (requires server running on localhost:3000)
load-test-signet users="30" time="2m" host="https://signet.noahwallet.io":
    LOADTEST_SCENARIO=all LOADTEST_HOST={{ host }} cargo run --bin loadtest --features loadtest --release -- --users {{ users }} --run-time {{ time }} --host {{ host }} --report-file report.html

# Run tests
test:
    cargo test

# Local regtest environment commands
setup-everything:
    ./scripts/ark-dev.sh setup-everything

up:
    ./scripts/ark-dev.sh up

down:
    ./scripts/ark-dev.sh down

stop:
    ./scripts/ark-dev.sh stop

# Ark dev shortcuts - pass arguments directly
bark *args:
    ./scripts/ark-dev.sh bark "$@"

aspd *args:
    ./scripts/ark-dev.sh aspd "$@"

bcli *args:
    ./scripts/ark-dev.sh bcli "$@"

lncli *args:
    ./scripts/ark-dev.sh lncli "$@"

cln *args:
    ./scripts/ark-dev.sh cln "$@"

# Bitcoin commands
create-wallet:
    ./scripts/ark-dev.sh create-wallet

create-bark-wallet:
    ./scripts/ark-dev.sh create-bark-wallet

create-barkd-wallet:
    ./scripts/ark-dev.sh create-barkd-wallet

generate blocks="101":
    ./scripts/ark-dev.sh generate {{ blocks }}

fund-aspd amount:
    ./scripts/ark-dev.sh fund-aspd {{ amount }}

send-to address amount:
    ./scripts/ark-dev.sh send-to {{ address }} {{ amount }}

# Lightning commands
setup-lightning:
    ./scripts/ark-dev.sh setup-lightning-channels
