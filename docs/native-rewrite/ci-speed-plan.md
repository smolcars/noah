# Fast, cheap CI: research and plan (2026-10-25)

This started as research and planning only. **Update (2026-10-25):** a simplified version is implemented on `t3/native-ci-speed`; section 7 has the decisions, the baseline, what changed and what is still open. Where sections 3 to 6 disagree with section 7, section 7 wins.

**The goal, in the user's words:** "My goal is to have an incredibly fast CI … Ideally entire CI should be done in like 5 mins … Running slow CI makes it impossible for me to scale and also I'd be wasting a lot of money."

**Decision (user, 2026-10-25):** stay on Blacksmith and pay a little. GitHub's free standard runners are "incredibly slow". `smolcars/noah` is public, so those runners would be free, but this plan does not use them. GitHub prices below are for reference only.

## Summary

- **Today:** a React Native PR that changes the client takes **22.3 min** at the median and **30.0 min** at worst (first attempts, 2026-10-08 to 10-24, n=22). Each run costs about **$0.89** on Blacksmith.
  - The "2.5 h" run (`39853074743`) was not a slow build. It was a failed iOS build that was re-run 2.5 hours later and failed again.
  - One Android build hung until its 60-minute timeout (`38459305574`): `sdkmanager` sat at "Still waiting for package manifests to be fetched remotely", because the SDK cache had missed.
- **The biggest single cause is caching:** every new PR branch starts with cold caches. Caches are branch-scoped, and `master` never writes the caches the PR jobs use, so Nix, Gradle, Pods, the Xcode compilation cache, the Android SDK and the emulator image all miss on a branch's first run.
  - Later pushes to the same branch can hit (`37814436050`). But release-v0.1.15 missed on all 4 pushes, which points to eviction from cache churn as well.
  - Proof: a warm Gradle build took 3m53s (`37814436050`), against 8m42s cold (`40121735049`).
  - Seeding caches from `master` takes about 4 min off the critical path and costs almost nothing.
- **The native workflows have never run on GitHub.** `gh` finds no runs; their workflows aren't on `master`, and pushes to `feat/native-rewrite` trigger nothing. As written, they rebuild the app several times per job, cap the compilers at 2 parallel jobs and run every Maestro flow on every PR.
  - My estimate (unmeasured): 20–40 min wall time and about $2.25–4.2 per push.
- **Every shared Maestro flow in `apps/maestro` is broken** against the redesigned screens. Section 5 has the inventory and a lean replacement set with exact test IDs.
- **Is 5 minutes achievable?**
  - **Yes** for the PR fast lane: native build, unit tests, API drift, and a 2-flow Maestro smoke per platform. That needs warm caches, PRs that build only the regtest variant, no throttling, iOS tests split across 3 simulators, and build and emulator boot overlapping in one Android job. A realistic first target is **6–7 min**; reaching 5 depends on Xcode compilation-cache hits and on trimming the iOS test overhead.
  - **No** for the full Maestro end-to-end suite. The funded send chain alone runs for about 7 min sequentially (`40121735049`). The full suite moves to post-merge and nightly runs.
- **Cost (estimates):** about **$0.3** for an Android-only PR push, **$1.0** for iOS-only and **$1.3–1.6** for both. As written, every `apps/**` push would run both native platforms for about $2.25–4.2, before adding RN CI. At 100 pushes a month that is about $155 in total for the target design, against $360–555 as written (section 6).
  - Mac minutes dominate: they are 56% of Blacksmith spend on 19% of the Blacksmith minutes (2026-10-08 to 10-24).
  - Post-merge and nightly runs add about $1–2 per day (section 6).

## 1. Current state

### Runner prices

| Runner | $/min | Notes |
|---|---|---|
| `blacksmith-2vcpu-ubuntu-2404` | 0.004 | 1 billing unit |
| `blacksmith-4vcpu-ubuntu-2404` | 0.008 | derived: 2 units |
| `blacksmith-8vcpu-ubuntu-2404` | 0.016 | derived: 4 units |
| `blacksmith-16vcpu-ubuntu-2404` | 0.032 | derived: 8 units |
| `blacksmith-4vcpu-ubuntu-2404-arm` | 0.005 | ARM is $0.0025 per 2 vCPU; no nested virtualization |
| `blacksmith-6vcpu-macos-*` (M4, 24 GB) | 0.08 | 20 units |
| `blacksmith-12vcpu-macos-*` (M4, 48 GB) | 0.16 | derived: 40 units |
| Blacksmith free tier | −$12/mo | 3,000 units a month |
| Blacksmith sticky disks and Docker layer cache | $0.50/GB/mo | |
| Self-hosted (`mini-1..3`, `ubuntu1..3`) | $0 per min | hardware, power and maintenance are not counted |
| *Reference only:* GitHub `ubuntu-latest` / `macos-latest` | free on public repos | 4 vCPU Linux; 3-core M1 with 7 GB macOS; rejected by the user |
| *Reference only:* GitHub larger runners | Linux 8-core $0.022; macOS 12-core $0.077; macOS 5-core M2 Pro $0.102 | rounded up to whole minutes |

Sources:
- [Blacksmith pricing](https://www.blacksmith.sh/pricing): $0.004/min Ubuntu x64, $0.0025 ARM, $0.08 macOS M4; 3,000 free min/mo; $0.50/GB/mo add-ons.
- [Blacksmith runner catalog](https://docs.blacksmith.sh/blacksmith-runners/overview): unit ratios, specs, and nested virtualization "on our x64 Linux runners" only.
- [GitHub runner pricing](https://docs.github.com/en/billing/reference/actions-runner-pricing), [GitHub-hosted runners](https://docs.github.com/en/actions/reference/runners/github-hosted-runners) ("free and unlimited on public repositories"), [limits](https://docs.github.com/en/actions/reference/limits): 20 concurrent jobs and 5 macOS on Free.
- The "derived" rows multiply Blacksmith's unit ratios by $0.004. Blacksmith doesn't publish its billing granularity, so cost figures here round each job up to whole minutes.

### Self-hosted runners

- `~/github-runners/actions-runner1..3` are registered to the BlixtWallet org as `mini-1`, `mini-2` and `mini-3`. They run on one Mac mini: M4, 10 cores, 24 GB, 110 GB free disk. All three listeners are running. `~/Documents/actions-runner1..3` hold the same registrations without a `_work` folder, so they look like old copies.
- Jobs show two labels:
  - `macOS`: the RN device and archive builds (`ios-device-build.yml`, `release.yml`, `native-signet-ios.yml`).
  - `self-hosted`: also matches the `ubuntu1..3` runners, which aren't on this Mac (presumably Linux). `lint`, `android_build` on `master`, Telegram, Release and `native-signet-android.yml` therefore land on the Mac or on Linux at random. From job metadata: CI `lint` ran on `mini-*` 48 times and on `ubuntu*` 63 times.
- **Contention:** three runners share one 10-core Mac. On 2026-10-20 the RN Android signet build on `mini-1` took 39.4 min (`39412986133`), while the iOS device build on `mini-2` took 43.6 min (`39412985992`). Their usual times are 5.3 and 6.2 min.
- **Security:** the repo is public, and `client.yml`'s `lint` job runs on `self-hosted` for `pull_request`. GitHub says "self-hosted runners should almost never be used for public repositories" because "any user can open pull requests against the repository and compromise the environment" ([secure use](https://docs.github.com/en/actions/reference/security/secure-use)). This plan moves every PR job off self-hosted.

### Workflows

Timings are from `gh run list` / `gh api …/runs/{id}/jobs` (first attempts only; method in the appendix). "Crit. path" lists the serial chain of jobs.

| Workflow | Trigger | Runners | Typical | Worst | Crit. path | Blacksmith $/run |
|---|---|---|---|---|---|---|
| **CI** (`client.yml`), client changed | every `pull_request`; internal path filter | `changes`: GitHub; `lint`: self-hosted; `android_build`: bs-8; `ios_build`: bs-mac-6; Maestro: bs-8 | **22.3 min** (n=22) | 30.0 (`40024484366`); 60.5 hung (`38459305574`); 151 across a re-run (`39853074743`) | changes 0.1 → lint 0.6 → android_build **9.3** → Maestro **11.7** (iOS 7.3 in parallel) | **0.89** p50, 1.86 max |
| CI, no client change | same | `changes` + gate only | ~0.3 | — | — | ~0 |
| **Server CI** (`server.yml`), server changed | every PR, plus `master` path push | bs-4 + Postgres/Dragonfly | **6.0** (n=14) | 9.5 (`39840853049`) | changes → build/test (release `noah-cli` build 2.5 p50; tests 1.7) | 0.06 |
| Native iOS CI | PR `apps/**` | bs-mac-6 | not run yet; est. **20–40** | — | one job: 3 scheme builds (`-jobs 2`), then the test build, then NoahTests (~5.5 min locally) | est. 1.6–3.2 |
| Native Android CI, plus Maestro | PR `apps/**` and others | bs-8 → bs-8 | not run yet; est. **20–30** | — | 3 flavors + unit tests (`--max-workers=2`), then Maestro (Docker, regtest, emulator, all flows, a second Gradle build for `connected…AndroidTest`) | est. 0.35–0.5 |
| Native API drift (`native-generated.yml`) | PR on many paths, including `client/src/**` and `server/**` | bs-4 → bs-mac-6 | not run yet; est. 8–12 | — | cold `cargo test` export, then macOS Bun + Swift generator | est. 0.3–0.5 |
| Native Maestro nightly + iOS | cron 06:17, label `ios-e2e` | 2× bs-mac-6, 3× bs-8 | not run yet | — | iOS: build + offline flows only (Blacksmith macOS has no Docker) | est. ~4.5 a night |
| Telegram Notification (RN signet APK) | `master` push | self-hosted | 5.5 (n=23) | 41.3 (`39412986133`, contention) | lint → build | 0 |
| iOS Telegram Notification (RN device build) | `master` push | self-hosted `macOS` | 6.9 (n=23) | 43.9 (`39412985992`) | build | 0 |
| Master Server Build and Push | `master` push / tags | bs-4 amd64 + bs-4-arm | 4.3 (n=17) | 20.2 (`37383422834`) | build → manifest → Fly | 0.06 |
| Release | tags | self-hosted | 10.7 (n=11) | 17.9 (`37383423236`) | parallel Android and iOS builds | 0 |
| `native-signet-android/ios` | `master` push | self-hosted | not run yet | — | signed build → Telegram/Diawi | 0 |

**Spend:** Blacksmith used **$27.85** from 2026-10-08 to 10-24, about $50 a month before the $12 free tier.
- RN CI's macOS job used $15.68 for 196 min, and its Linux jobs $10.69 for 668 min.
- Server workflows used $1.48.

**Wasted runs:** 9 of 48 consecutive PR pushes (19%) started while the previous run on the same branch was still going. No PR workflow sets `concurrency`.

## 2. Where the time goes (RN CI, measured)

**Run `40121735049`** (release-v0.1.15, 24.1 min end to end):

| Phase | Time | Note |
|---|---|---|
| `changes` + `lint` (self-hosted) | 1.2 min | `android_build` waits for lint (`needs: [changes, lint]`) |
| Android setup: Nix install, Nix restore **miss**, Gradle restore **miss**, SDK cache **miss** (12 s), `bun install` (60 s) | 1.3 min | "Gradle User Home cache not found. Will initialize empty." |
| Gradle `bun client build:android:regtest:release` | **8m42s** | 15 s until Gradle started. About 66 s from "Welcome to Gradle" to `Configure project :app` (18:42:55 → 18:44:01). "1551 actionable tasks: 1328 executed, 223 from cache". C++ was configured and built for **both** `arm64-v8a` and `x86_64`, though the emulator only needs `x86_64`. |
| Post steps (cache saves) | 30 s | the saves are wasted, because the next PR branch can't read them |
| Maestro: regtest stack | 67 s | `docker compose pull` + `just setup-everything` |
| Maestro: APK artifact download | **52 s** | 18:53:18 → 18:54:10 |
| Maestro: emulator SDK and image install (cache **miss**) | 32 s | |
| Maestro: boot, 2 emulators | 42 s | `-no-snapshot`, cold boot |
| Maestro: install + unlock | 32 s | |
| Maestro: flows | **6.9 min** | 5 UI flows took 5m24s on one emulator, in parallel with the funded script on the other. The funded chain is the long pole (until 19:03:11). Each UI flow starts with `clearState` and a new wallet (44 s to 1m22s per flow). |
| Maestro: `docker compose down --volumes` | 32 s | 10 s stop timeouts on an ephemeral VM that is about to be destroyed |

**Every new branch starts cold:**

| Run | Branch, push | Gradle home | Nix | iOS Pods / compile cache | Emulator image | Gradle result |
|---|---|---|---|---|---|---|
| `40121735049` | release-v0.1.15, 4th | miss | miss | miss / miss | miss | 8m42s, 1,328 tasks executed |
| `39851869419` | release-v0.1.15, 1st | miss | miss | miss / miss | miss | 9m04s |
| `38994688993` | feat/swedish-translation | miss | miss | miss / miss | miss | 7m31s |
| `39835692757` | feat/bark-0.8.0, 2nd | **hit** | — | miss / miss | miss | 4m54s, 551 from cache |
| `37814436050` | ci/android-ci-speed, 3rd | **hit** | — | **hit / hit** | **hit** | **3m53s**; iOS 4.4 min against 7–9 cold |

The release-v0.1.15 runs still missed on their 4th push. Every job also re-saves caches, and the iOS compilation cache key includes `run_id`, so each run writes a new multi-GB entry. With per-branch copies, that churns Blacksmith's 25 GB-per-repo-per-week cache.

- [Blacksmith cache](https://docs.blacksmith.sh/blacksmith-caching/dependencies-actions): "each cache entry will be scoped to its branch or tag", "consistent with GitHub's implementation". It also has a dashboard toggle that turns scoping off.
- [GitHub cache scoping](https://docs.github.com/en/actions/reference/workflows-and-actions/dependency-caching): runs "can restore caches created in either the current branch or the default branch"; they "cannot restore caches created for child branches or sibling branches."
- `setup-gradle` writes "only … from Jobs on the default (`main`/`master`) branch" by default ([docs](https://github.com/gradle/actions/blob/main/docs/setup-gradle.md)). `client.yml` overrides that with `cache-read-only: false`, yet `master` never runs the regtest Blacksmith build: on push it uses the self-hosted signet path with all cache steps skipped.

**Failures cost the most wall time:**
- `40024484366` and `39853074743` failed in `Install iOS Pods` on "CDN: trunk Repo update failed – 165 error(s)". That only happens on a cold Pods cache.
- `38459305574` hung in `android_build` for 60.5 min until the timeout. "Install Android build SDK" kept printing "Still waiting for package manifests to be fetched remotely" after its cache missed. `native-android.yml`, `native-maestro-android.yml` and `native-maestro-ios.yml`'s `adaptive-apk` also run `sdkmanager` on every run, with no cache-hit condition.

## 3. Ranked changes

The ranking is by minutes saved per dollar. Items that save both minutes and money come first. The saving is the PR critical-path time removed. "Est." means not measured on CI.

| # | Change | Platform | Saves on PR critical path | $ per run | Effort | Risk |
|---|---|---|---|---|---|---|
| 1 | **Seed caches from `master`**. Add a cache-warm workflow on `master` push (and nightly) that runs the same restore/build steps and saves. PR jobs restore only (`actions/cache/restore`, `cache-nix-action` `save: false`, `setup-gradle` default read-only). Drop `run_id` from cache keys and key them by lockfile hash. | all | RN Android −4 to −5 min (9.3 → ~5); RN iOS −3 min (off the critical path); Maestro −0.5; native similar (Gradle, SPM, Nix, compilation cache) | −$0.2 a run; about +$0.3 per master push for the warmer | S | Low |
| 2 | **`concurrency: { group: ${{ github.workflow }}-${{ github.event.pull_request.number \|\| github.ref }}, cancel-in-progress: true }`** on every PR workflow | all | wall time unchanged; 19% of pushes overlap a running run | −$0.5 to −1 per superseded run | XS | Low |
| 3 | **Fix path filters.** `client.yml` and `server.yml` count any `.github/workflows/**` change as a client or server change, so a native workflow edit runs the full 22-min RN CI. List each workflow's own file instead. `native-ios.yml` and `native-android.yml` both trigger on all of `apps/**`, so an Android-only change runs the macOS job and an iOS-only change runs the Android and Maestro jobs; scope them to `apps/ios/**` / `apps/android/**` plus the shared inputs (`apps/fixtures/**`, `apps/maestro/**`, `apps/i18n/**`). `native-generated.yml` also runs on `client/src/**`; scope that to what the drift check reads. | all | a full RN run on native-only PRs; the other platform's jobs on single-platform PRs | −$0.9 (RN) and −$0.4 to −3 (the other platform) a run | XS | Low |
| 4 | **PRs build only the regtest variant.** The flavors differ only in manifests, `google-services.json` and network config (`apps/android/app/src/{mainnet,signet,regtest}`), and the iOS configs differ only in xcconfig values (`apps/ios/Config/*.xcconfig`). Build mainnet and signet after merge; the signet distribution already builds signet release. | native | iOS est. −8 to −20 min (4+ compiles → 1); Android est. −2 to −4 min | −$1 to −1.5 (iOS) | XS | Low |
| 5 | **Remove throttles.** Drop `-jobs 2` (native-ios, native-maestro-ios, native-signet-ios) and `--max-workers=2` (native-android, maestro, adaptive-apk, signet-android) on 6- and 8-vCPU runners. `--max-workers=2` also caps `maxParallelForks = 3` at 2 forks. | native | est. 30–50% of compile and unit-test time | negative | XS | Low (24/32 GB RAM) |
| 6 | **iOS: build once, then shard tests.** `xcodebuild build-for-testing` on one simulator destination, then 3 parallel `test-without-building -only-testing:NoahTests/<Suite>…` runs on 3 cloned simulators on one `blacksmith-12vcpu-macos` (section 4.2). | iOS | est. NoahTests 5.5 → ~2.5 min, and a faster compile | about neutral (half the minutes at twice the rate) | M | Medium |
| 7 | **Android: build once, test many.** Build `assembleRegtestDebug` + `assembleRegtestDebugAndroidTest` once. The emulator job installs both APKs and runs `am instrument`, and stops calling `./gradlew connectedRegtestDebugAndroidTest`, which configures and compiles again inside the Maestro job. Drop the job's SDK install and Gradle cache steps. | Android | est. −2 to −4 min on the Maestro job | negative | S | Low |
| 8 | **Lean Maestro** (section 5): PR smoke of 2–3 unfunded flows with one wallet, and the funded chain post-merge and nightly. Creating one wallet per run instead of 6–7 alone saves about 4–6 min. | both | Maestro ~12 → ~4–5 min on PR | −$0.1 to −0.2 (Android) | M | Medium (coverage moves off PR) |
| 9 | **Emulator boot and teardown:** AVD snapshot cache (`force-avd-creation: false`, `-no-snapshot-save`, [emulator-runner README](https://github.com/ReactiveCircus/android-emulator-runner)); `cores: 4`; no `docker compose down` on ephemeral VMs (dump logs only on failure); retry `disable-animations: true` for native (the "blank stage" note was an RN issue) | Android | est. −1 to −1.5 min | negative | S | Low |
| 10 | **Overlap build and environment:** one Android PR-smoke job starts the regtest stack and the snapshot emulator in the background while Gradle builds the APK. That removes the 52 s artifact hop and about 2 min of serial setup. | Android | est. −2 to −3 min | 16 vCPU at $0.032; about neutral | M | Medium |
| 11 | **Gradle build cache and configuration cache:** `org.gradle.caching=true` and `org.gradle.configuration-cache=true` in `apps/android/gradle.properties`, plus `setup-gradle` `cache-encryption-key` so configuration-cache entries persist. The app is one module (`include(":app")`), so any Kotlin change recompiles it; the gain is in configuration and unchanged tasks. | Android | est. −0.5 to −1 min | negative | S | Medium (plugin compatibility: Sentry, google-services, dependency verification) |
| 12 | **Shorter timeouts:** 60 → about 20 min on build jobs, about 25 on Maestro; `timeout-minutes: 3` on `sdkmanager` steps, and skip them on a cache hit | all | stops 60-min hangs (`38459305574`, $0.97) | negative | XS | Low |
| 13 | **iOS slow suites** (section 4.2): `AppLogTests` 24.6 s, `MMKVRecoveryTests` 20.2 s, `RecurringExecutorTests` 16.1 s are 39% of the 158 s of test time. Another ~140 s of the 301 s wall time isn't attributed to any test. | iOS | est. −0.5 to −2 min | negative | M | Low |
| 14 | **Move `native-clients` drift off macOS** if `swift-openapi-generator` produces byte-identical output on Linux. Bun and the translation checks don't need a Mac. | API | ~0 | −$0.25 to −0.3 | M | Medium |
| 15 | **Server PR:** drop or relocate `cargo build --release --bin noah-cli` (2.5 of 6.0 min) | server | −2.5 min | −$0.02 | XS | Low |
| 16 | **RN until it is retired:** `-PreactNativeArchitectures=x86_64` for the PR regtest APK; move `lint` to a Blacksmith 2-vCPU runner and stop blocking `android_build` on it; then retire RN CI at cutover | RN | est. −1.5 to −2.5 min | negative | XS | Low |
| 17 | **Sticky disks** for `/nix` and `~/.gradle` on Linux. A sticky disk is "hot-loaded … and mounted", and "the last committed snapshot will be cloned" ([stickydisk](https://github.com/useblacksmith/stickydisk)). It skips the tar and untar and isn't branch-scoped. A 5 GB `/nix` plus 4 GB Gradle cost about $4.5 a month. Linux only (see `docs/blacksmith_ios_ci_research.md`). | Android, Maestro | est. −20 to −60 s against a warm Actions cache | +$4.5/mo | S | Medium (PR jobs also commit to the disk; check whether a no-commit mode exists) |

Item 1, the cache-warming workflow, is the safer form of a single dashboard switch: turning off Blacksmith's cache branch scoping. Turning it off would let PR caches flow both ways. But fork PRs on a public repo could then write caches that `master` reads. Use the switch only if you require approval for outside contributors' workflow runs and the release and signing jobs never restore those caches.

## 4. Changes by platform

### 4.1 Android (native)

- **Caching:**
  - Seed the Gradle home and Nix store from `master` (item 1), or put them on sticky disks (item 17).
  - Enable the build cache and configuration cache (item 11).
  - Robolectric 4.17 downloads `android-all-instrumented` jars into `~/.m2` on first use, outside the Gradle home. Cache that path, or make it a declared dependency with `robolectric.offline=true`. Check the first CI log before deciding.
- **Not rebuilding:**
  - PRs run `:app:assembleRegtestDebug :app:assembleRegtestDebugAndroidTest :app:testRegtestDebugUnitTest` and upload both APKs (items 4 and 7).
  - The Maestro and instrumentation job never runs Gradle.
  - Mainnet and signet `assemble…` run on `master` and nightly.
- **Parallelism:**
  - Drop `--max-workers=2`. The JVM suite went from 93 s to 45 s locally with 3 forks, and `maxParallelForks = 3` only takes effect when Gradle has at least 3 workers.
  - 3,751 JVM tests at 45 s don't justify sharding across jobs.
- **Runner size:**
  - bs-8 for build and unit tests ($0.016).
  - bs-16 only for the combined build-and-emulator smoke job (item 10), where Gradle, a 4-core emulator and the Docker stack share the CPUs.
- **Emulator:**
  - KVM is already enabled; Blacksmith x64 Linux supports nested virtualization.
  - Add AVD snapshot caching (item 9) and keep `-no-window -gpu swiftshader_indirect -noaudio -no-boot-anim`.
  - Keep API 36 `google_apis` x86_64. **ATD images** "currently support only API level 30" ([Gradle Managed Devices](https://developer.android.com/studio/test/managed-devices)), and that would change notification-permission behavior (API 33+), so they don't fit Maestro.
  - **Gradle Managed Devices** with `numManagedDeviceShards` could shard the instrumentation suite later. That isn't worth it while CI runs only 2 instrumentation classes.
- **Instrumentation coverage:**
  - `AdaptiveLayoutDeviceTest` is skipped in CI: it `assumeTrue(noahAdaptive == "owned-disposable-avd")`, and CI doesn't pass that argument.
  - Several useful opt-in suites never run in CI: `SettingsDeviceTest`, `SendHoldTest`, `RestoreUIDeviceTest` and `ExitDeviceTest`. Run the unfunded ones on the nightly emulator.

### 4.2 iOS (native)

- **Rebuilds today:**
  - `native-ios.yml` builds `Noah`, `Noah-Signet` and `Noah-Regtest` for `generic/platform=iOS Simulator` (every architecture in `ARCHS`), then `test` builds `Noah-Regtest` again for one simulator. Each configuration has its own build directory, so nothing is shared.
  - `native-maestro-ios.yml` builds once more per device.
  - Replace all of this with a single `build-for-testing` for `platform=iOS Simulator,id=<udid>` (item 4).
- **Caching:**
  - SPM: key on `Package.resolved`, which `xcodegen` generates (check where it lands), or keep `project.yml`. Restore only on PRs.
  - Compilation cache: Xcode 26 offers `COMPILATION_CACHE_ENABLE_CACHING=YES` with a cached CAS path; the RN workflow already uses it and gets warm builds of about 4 min against 7–9 cold. Key it by Xcode build and lockfile, save from `master` only, and never key it by `run_id`. Apple describes it as speeding up "clean builds" ([Xcode 26 release notes](https://developer.apple.com/documentation/xcode-release-notes/xcode-26-release-notes)). Third-party reports say cross-machine reuse has rough edges ([Swift forums](https://forums.swift.org/t/about-swift-shared-cache-across-machines/81850), [Tuist](https://tuist.dev/en/docs/guides/features/cache/xcode-cache)), so measure the hit rate.
  - Restored DerivedData is a weaker fallback, because Xcode's incremental builds track file mtimes and a fresh checkout resets them.
- **Simulator boot:**
  - Create and boot the simulators in the background while `build-for-testing` runs; `simctl bootstatus -b` takes about 20–40 s.
  - Use `simctl clone` from one booted-then-shutdown base device for the shards.
- **Swift Testing parallelism:**
  - `-parallel-testing-worker-count` splits only XCTest across clones, so it does nothing for this all-Swift-Testing suite (local finding).
  - In-process parallelism (width 3) was flaky because the log and telemetry suites share process-global state. `LogFile.shared` (`apps/ios/Noah/Services/Logging/AppLog.swift:45`) and `AppTelemetry` are static. Any code under test that logs writes to the same file, and `AppLogTests` also contains timing and performance samples (`AppLogTests.swift:53-58`).
  - Making in-process parallelism safe would mean routing the log sink and telemetry through a `@TaskLocal` override, so each test gets its own sink, and marking the timing-sampling tests `.serialized` or moving them to a separate opt-in suite. That is real refactoring of production logging code; 14 test files already use `.serialized`.
- **Shard by process instead (recommended):**
  - `-only-testing:NoahTests/<SuiteName>` filters Swift Testing at suite level. `.xctestrun`'s `OnlyTestIdentifiers` filters only XCTest, and a mis-filtered shard "matched zero Swift Testing tests, and exited 0" ([tuist#11329](https://hive.tuist.dev/forage/items/github-issue/fde4d966-d361-4530-b81c-35c9924e74bf)).
  - Each shard runs in its own simulator and process, so global state isn't shared and the flakiness disappears without code changes.
  - Split the 174 suites into 3 balanced lists using the durations from an xcresult (the largest 4 suites alone are about 71 s).
  - Each shard must assert a non-zero test count from `xcresulttool get test-results summary`, and the job checks that the 3 lists cover every suite. Merge the results with `xcresulttool merge`.
- **Across runners or one runner?** Shard on one 12-vCPU runner. 3 macOS runners would pay 3× setup (checkout, Nix, SPM, artifact download, about 1–1.5 min each) at $0.08/min each. One `blacksmith-12vcpu-macos` (48 GB) runs 3 simulators comfortably.
- **Measured local profile** (`/private/tmp/noah-orphan-board-t3-1d8f55ce/ios-full.xcresult`, 2026-10-21): 867 tests, 301 s wall time, 158 s summed test durations. The top suites are `AppLogTests` 24.6 s, `MMKVRecoveryTests` 20.2 s (`.serialized`), `RecurringExecutorTests` 16.1 s, `WalletRestorerTests` 9.7 s, `WalletSessionTests` 7.7 s and `RNStoreTests` 7.3 s. Find out what the 140 s not attributed to any test is before optimizing; it could be test-host launch, suite setup, or main-actor waits.
- **XcodeGen:** `brew install xcodegen` runs whenever the image lacks it. Pin it through the Nix shell, or cache the binary.

### 4.3 Maestro pipeline

- **Sharding:**
  - `maestro test --shard-split N` splits flows across N connected devices; `--shard-all N` runs all of them on each ([CLI options](https://docs.maestro.dev/maestro-cli/maestro-cli-commands-and-options)).
  - The funded chain is sequential by design: each step needs the previous wallet state and host-side bark sends. It can't be split; it can only run beside the unfunded flows. That is what RN CI already does with 2 emulators.
  - Plan: on PR, one emulator runs the smoke flows. Post-merge, 2 emulators in one job: shard A runs the funded chain, shard B the unfunded flows with `--shard-split`, one wallet per shard.
- **Running against a prebuilt app:** already done (`adb install -r`, `simctl install`, `--no-reinstall-driver`). Keep the driver installed across `run_flow` calls, and fold the 6 separate `maestro test` invocations into one invocation per shard, with host steps run through Maestro `runScript` or `evalScript` where practical, to save driver start-ups.
- **Maestro Cloud:** $250 per device per month, "based on max concurrent executions" ([pricing](https://www.maestro.dev/pricing)). Its devices can't reach a regtest stack on the runner's localhost; that needs a public regtest endpoint. Not recommended.
- **Self-hosted:** Blacksmith macOS has no nested virtualization, so it has no Docker regtest stack (`docs/blacksmith_ios_ci_research.md`; Colima/TCG took about 4 min to start). Full iOS wallet Maestro therefore needs the Mac mini (Docker is installed there) or a Linux job that forwards the regtest stack's ports over a tunnel. Nightly on `mini-*` costs $0, and latency doesn't matter at night.

### 4.4 RN client (until cutover)

- Seed caches (item 1), which also stops the CocoaPods CDN failures.
- Build an `x86_64`-only APK for the emulator (item 16).
- Stop gating `android_build` on `lint`, and move `lint` off self-hosted.
- After cutover, drop RN CI to `workflow_dispatch` or delete it. That removes the current $0.89 and 22 min per client PR.

### 4.5 Server

Fine as it is: 6.0 min at the median, $0.06. Swatinem's rust-cache comes through `setup-rust-toolchain`, and `master` push runs keep it warm. Drop the PR release build (item 15). `native-generated`'s `server-contract` job compiles the server cold on every PR, because nothing on `master` writes its rust-cache key. Give it a shared key, or warm it on `master`.

### 4.6 Self-hosted Mac or Blacksmith for iOS

| | Self-hosted `mini-*` | Blacksmith macOS |
|---|---|---|
| Cost | $0 per min | $0.08–0.16 per min |
| Speed | M4, 10 cores, but 3 runners share it; observed 7× slowdowns under contention | dedicated 6 or 12 vCPU VM; predictable |
| Docker / regtest | yes | no (no nested virtualization) |
| Signing | has the keychain and profiles (signet and release jobs depend on it) | would need explicit certificate and keychain setup |
| Security on a public repo | unsafe for PR jobs ([GitHub](https://docs.github.com/en/actions/reference/security/secure-use)) | ephemeral VM |
| Maintenance | Xcode updates, disk (110 GB free), Docker, runner upgrades | none |

**Recommendation:**
- Blacksmith for every PR job.
- Self-hosted for signing and distribution, and for the nightly iOS regtest Maestro run.
- Run one runner per machine for heavy jobs. Give the runners distinct labels (`mac-mini`, `linux-box`), so `self-hosted` stops matching both machines.

## 5. Maestro flow update

All 21 entry points in `apps/maestro` fail on both platforms (audit 2026-10-25, from code reading only; no flow was run). The root cause is `subflows/fresh-wallet.yml` and the onboarding subflows: they wait for copy the redesign removed ("Welcome to Noah", "Create Wallet", "I decline", "Your Recovery Phrase", "Emergency Email"), and every wallet flow starts there. `docs/native-rewrite/TODO.md:540` understates this: Send, Receive, Settings and Exit broke too. Lines 509–511 there are ticked, but they predate the 2026-10-22 to 10-24 redesigns.

### What exists and why it fails

| Flow | Covers | Why it fails now |
|---|---|---|
| `wallet-onboarding-back.yml` | beta decline, create, back at phrase, finish without email | the phrase step and the beta/email/username copy are gone |
| `receive.yml` | QR details, amount + note, remove amount | fresh-wallet; `receive-qr-button`, `receive-copy-onchain` (iOS), `receive-copy-lightning`, `receive-amount-remove` gone; "Edit amount" → "Change amount" |
| `delete-wallet.yml` | typed delete → Welcome | fresh-wallet; "Delete Wallet" → "Delete wallet"; "Welcome to Noah" gone. The `delete-wallet-input` and `delete-wallet-confirm` IDs still work. |
| `email-verification.yml` | code `000000`, username, Profile shows "Verified" | onboarding order is now Name → Email; Android onboarding inputs have no tags |
| `manual-boarding-settings.yml` | on-chain page, board amount | iOS uses `settings-onchain`, not `settings-board-ark`; `board-open` is disabled with 0 confirmed on-chain, so it can't pass on a fresh wallet |
| `offline/welcome.yml` | Welcome, beta, restore inputs | copy gone; restore entry is now `onboarding-restore` (iOS only) |
| `subflows/prepare-funded-send.yml` | copy Ark address | `receive-qr-button` gone; formats open via `receive-copy-button` (iOS) or `receive-formats` (Android), then `receive-copy-ark` |
| `subflows/send-recipient-reset-on-back.yml`, `send-funded-ark.yml`, `send-max-back-fixed-request.yml`, `send-amountless-request.yml` | funded send chain | `send-start-over` and `send-confirm` gone (now hold-to-send `send-hold`); ".*sats available in Ark" → "{amount} available"; "How much?" gone; Android's review back is `send-back` |
| `subflows/exit-deposit-landscape.yml` | exit deposit address | "Deposit Onchain Funds" gone; the deposit is reachable only via `exit-add-funds` when on-chain funds are short |
| `adaptive/adaptive-layout-smoke.yml`, `-checks`, `-settings-checks`, `-rotation-checks` | rotation and wide layouts | fresh-wallet; `settings-board-ark` (iOS), "List Backups" → "Backups"; `profile-name-input` moved into a sheet (open with `profile-name`) |
| `apps/maestro/README.md` | ID table and pass counts | stale file:line references, such as `SendScreen.kt:128` |

The **redesigned iOS onboarding already has working Maestro flows** with current IDs in `apps/ios/OnboardingTests/{create,skip,verify,create-and-*}.yaml`. Use them as the template.

### Changes needed first in the apps

1. **Tag Android's redesigned onboarding** (`ui/onboarding/*`, which has no tags today) with the iOS IDs:
   - `onboarding-create`, `onboarding-restore`, `onboarding-beta-confirm`
   - `onboarding-name-skip`, `onboarding-name-continue`, `lightning-username-input`
   - `onboarding-email-skip`, `email-address-input`, `onboarding-email-send`, `verification-code-input`
   - `notifications-enable`, `onboarding-open-wallet`, plus one for the Android battery step's "Skip"
2. **Pick one ID for each pair that differs** (iOS file / Android file):
   - Receive details: `receive-copy-button` (`ReceiveView.swift:190`) / `receive-formats` (`ReceiveContent.kt:219`)
   - On-chain copy: `receive-copy-bitcoin` / `receive-copy-onchain`
   - Receive share: `receive-share-button` / `receive-share`
   - Restore submit: `restore-wallet-submit` / `restore-submit`
   - Settings on-chain row: `settings-onchain` (`SettingsView.swift:227`) / `settings-board-ark` (`SettingsRoot.kt:157`)
   - Review back: `send-review-back` (`SendReviewView.swift:60`) / `send-back` (`SendContent.kt:262`)
   - Send result: `send-result-primary` / `send-result`
   - Home backup nudge: `home-backup-prompt` / `home-nudge-backup`
   - Transaction detail: `transaction-detail*` / `activity-detail*`
   - Exit modes: `exit-mode-all` / `exit-all`; claim targets `exit-claim-other`/`-phone` / `exit-to-other`/`-phone`
   - Settings back: `settings-pane-back` / `settings-back`
3. **Android Profile:** add tags to the verification-code field and the Profile rows (`profile-email`, `profile-lightning-address`).

### Proposed lean set

The **PR smoke** runs on both platforms, needs no funds, and shares one wallet. Estimate: about 2–3 min after boot.

- **S1 `smoke/create-and-receive.yml`:**
  - `launchApp` with `clearState` and `clearKeychain`
  - Onboarding: `onboarding-create` → `onboarding-beta-confirm` → `onboarding-name-skip` → `onboarding-email-skip` → `notifications-enable` (iOS) or battery "Skip" (Android) → `onboarding-open-wallet`
  - Home: assert `home-balance`
  - Receive tab: assert `receive-qr` → open details (the unified ID) → `receive-copy-ark` → the harness checks that the clipboard starts with `tark1`
- **S2 `smoke/delete-wallet.yml`** (no clear; reuses S1's wallet):
  - `home-settings-button` → `settings-delete-wallet` → type `delete` into `delete-wallet-input` → `delete-wallet-confirm`
  - assert `onboarding-create`
- **S3 `smoke/restore-validation.yml`** (offline):
  - `onboarding-restore` → fill `restore-word-1…12` with an invalid phrase → submit (the unified ID)
  - assert `restore-error` (Android) or `restore-phrase-error` (iOS); unify these too

The **post-merge funded chain** runs on one wallet, sequentially. Estimate: about 5–7 min.

- **F1:** S1's onboarding → host `just bark send --wait <tark1…> '20000 sats'` → `home-balance` updates.
  - Send tab: `send-paste` (bark address in the clipboard) → `send-key-5` `send-key-0` `send-key-0` `send-key-0` → `send-continue` → assert `send-review-title` → `longPressOn: { id: send-hold }` (hold is 0.8 s on iOS; check that Maestro's long press completes `HoldToConfirm` on Android) → `send-success-done`.
  - Activity: assert an `activity-row-*`.
- **F2:** BIP-321 fixed amount `bitcoin:<bcrt1…>?amount=0.00005` → review shows 5,000 sats → back (the unified review-back ID) → recipient cleared.
- **F3:** amountless `bitcoin:<bcrt1…>?amount=0` keeps the keypad (`send-amount`, `send-key-*`).

**Nightly** (a funded or slow setup):

- Email verification: `settings-profile` → `email-address-input` → `onboarding-email-send` → `verification-code-input` `000000`.
- Manual boarding: fund on-chain with `just bcli` and mine → `settings-onchain`/`settings-board-ark` → `board-open` → `board-amount` → `board-review`.
- Exit: `settings-emergencyExit` → `exit-choose`/`exit-review`; deposit via `exit-add-funds`.
- Adaptive tablet, fold and Duo smoke.

**Move to native UI tests instead of Maestro:**
- Rotation and state restoration: `AdaptiveStateRestorationTest` already covers receive amount and note, the recurring editor, rail switching and restore inputs across recreation.
- Settings back navigation: `SettingsDeviceTest`.
- Hold-to-send semantics: `SendHoldTest`.
- Restore field behavior: `RestoreUIDeviceTest`.

Run the unfunded ones nightly on the Android emulator. On iOS, the render fixtures in `NoahTests` (`*ShotTests`) cover layout. XCUITest stays for system popovers and Face ID (`TODO.md:512`).

**Speed rules for the new flows:**
- One `clearState` per shard.
- No fixed `timeout` above 30 s except where the app waits on bark; replace the 120 s phrase wait, which is gone along with the phrase step.
- Assert on IDs, not copy, so translations can't break the flows.
- Tag flows `smoke`, `funded` and `nightly`, and select them with `--include-tags` instead of folder layout.

## 6. Target design

> **Superseded in part (2026-10-25).** The user chose a simpler design: no nightly or scheduled runs, no smoke/funded/nightly tiers (every Maestro flow runs on every PR), Android Maestro only, no iOS test shards, and signet plus mainnet sent to Telegram on merge. Section 7 describes what was built.

### PR, aiming for about 5 min wall time

Jobs run in parallel. Each is path-filtered and has `concurrency` cancellation.

| Job | Runner | Contents | Est. time | Est. $ |
|---|---|---|---|---|
| `changes` | GitHub-hosted (6 s job, as today) | paths-filter | 0.2 | 0 |
| `android` | bs-8 | warm Nix and Gradle; assemble regtest app + test APK; JVM tests; upload APKs | 4–5 | 0.08 |
| `android-smoke` | bs-16 | regtest stack + snapshot emulator in the background; Gradle `assembleRegtestDebug` in the foreground; install; S1–S3 | 5–6 | 0.19 |
| `ios` | bs-mac-12 | xcodegen; SPM and compilation cache restore; `build-for-testing` (Noah-Regtest); 3 simulator shards of NoahTests; S3 offline smoke on one simulator | 5–7 | 0.80–1.10 |
| `api-drift` | bs-4 (+ bs-mac-6 until item 14) | contract export with a warm rust cache; translation and log checks; client generation | 2–4 | 0.03–0.30 |
| `server` | bs-4 + services | fmt + tests (no release build) | ~3.5 | 0.03 |
| `rn` (until cutover) | bs-8 / bs-mac-6 | only when `client/**` changes: lint, warm x86_64 APK, iOS build; RN Maestro post-merge | ~5–6 | 0.4–0.6 |

`android` and `android-smoke` both build the regtest APK on purpose. That keeps the end-to-end job from waiting on an artifact, and it costs about $0.03.

**Per push:** Android only ≈ **$0.3**; iOS only ≈ **$1.0**; both ≈ **$1.3–1.6**. For comparison:
- As written, every `apps/**` push runs both native platforms plus the drift check: native-ios $1.6–3.2, native-android with Maestro $0.35–0.5, drift $0.3–0.5. That is about **$2.25–4.2**, all estimates.
- RN CI adds about $0.9 when it is triggered.

**Is 5 min achievable?**
- The Android lanes reach 5–6 min with warm caches.
- iOS reaches about 5 min only if (a) the compilation cache makes `build-for-testing` ≤2 min, (b) the test shards take ≤2 min, which needs item 13 and an explanation of the 140 s overhead, and (c) setup stays ≤1 min.
- A realistic first milestone is 6–7 min; 5 needs the iOS work.

### Post-merge on `master` (or the merge queue later)

- Cache warmer (item 1)
- All flavors and schemes build (mainnet and signet)
- Full Android Maestro on 2 emulators (funded chain + unfunded shard): about 10–12 min, $0.20
- RN Maestro until cutover
- Signet distributions (self-hosted, as today)

A merge queue needs `merge_group` triggers ([GitHub](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/configuring-pull-request-merges/managing-a-merge-queue)). It is worth adopting once many agent PRs merge each day. Until then, a post-merge run with fix-forward is simpler. Check eligibility in the repo settings first; the docs page I read doesn't state which plans qualify.

### Nightly (only if `master` changed since the last nightly)

- Android adaptive tablet and fold: 2× bs-8, about $0.5.
- Nightly-tagged Maestro flows and the unfunded opt-in instrumentation suites.
- iOS full regtest Maestro on iPhone 17 and the Duo: self-hosted `mini-*` with Docker, $0. Building the `.app` once and installing it on both simulators halves the build time.
- RN → native upgrade tests (`TODO.md` / PLAN.md "CI testing for the native UI").

Estimate: about $0.5–1 a night on Blacksmith, plus the free self-hosted iOS run. As written today, the nightly runs every night on 2 Blacksmith macOS jobs plus 3 Linux jobs, about $4.5 a night.

### Monthly cost estimate

These are estimates, not measurements. Assumptions:
- Pushes split half single-platform and half both, so a target push averages about $1.05.
- Merges are about a third of pushes, at about $0.8 per merge (warmer, full Android Maestro, other flavors).
- Nightly is about $0.75 a night when `master` changed.
- As written: $2.25–4.2 a push, plus $4.5 every night.

| PR pushes/month | Target: PR + post-merge + nightly | Native workflows as written |
|---|---|---|
| 50 | $52 + $13 + $22 ≈ **$90** | $112–210 + $135 ≈ $250–345 |
| 100 | $105 + $27 + $22 ≈ **$155** | $225–420 + $135 ≈ $360–555 |
| 300 | $315 + $80 + $22 ≈ **$415** | $675–1,260 + $135 ≈ $810–1,395 |

Subtract the $12 free tier, and add RN CI ($0.9 per client PR push) until cutover. Today's actual spend is about $50 a month, with RN CI only.

## 7. Rollout status (2026-10-25)

### Decisions (user, 2026-10-25)

- "5 minutes" is a goal, not a hard limit. Maestro can take longer; builds should be as fast as caching allows.
- Keep CI simple: build and test on PRs; no cron or nightly jobs.
- Android Maestro only: one phone profile; no iOS Maestro, tablet, fold or adaptive runs. Two parallel jobs select flows with `--include-tags smoke` and `--include-tags funded`; each onboards one wallet and reuses it. The Maestro flow rewrite happens separately.
- Merges to `master` send signet and mainnet builds to Telegram. Android builds on Blacksmith; iOS stays on the self-hosted signing Mac until Apple signing secrets exist.
- Server CI stays paused (`workflow_dispatch` only) for now.

### Baseline (before), run at `34381878` on PR #374

| Workflow (run) | Job | Result | Time | Where the time went |
|---|---|---|---|---|
| Native iOS CI (`40191514718`) | build-and-test | failed | 2.1 min | Swift 6.3.3 (default Xcode 26.6) crashed in IRGen after 117 s of building; SPM resolved cold in 25 s. Not a real build time. |
| Native Android CI (`40191514468`) | build-and-test | failed | 6.0 min | Nix store miss, fetched in 77 s; Gradle download 10 s; daemon and configuration 33 s; 3 flavors 145 s; unit tests 76 s (4,264 tests, 1 failed, running as root). `--max-workers=2`. |
| | maestro | skipped | — | |
| Native API drift (`40191514489`) | server-contract | passed | 1.9 min | cold `cargo test` export 89 s |
| | native-clients | failed | 1.2 min | log-checker false positive; Nix install 28 s, `bun install` 33 s |
| Server CI (`40191514607`) | server_build_and_test | passed | 5.7 min | tests 106 s, release `noah-cli` build 162 s, service containers 43 s |

### Local measurements (this Mac: M4, 10 cores, Xcode 27.1)

- iOS `build-for-testing` of Noah-Regtest: **114 s** cold. With the Xcode compilation cache restored and DerivedData deleted: **14 s** with no source change (426 hits, 0 misses), **46 s** after a one-line change (11 misses). The cache is about 650 MB.
- SPM checkout: 3.9 GB, of which 2.9 GB is Sentry's binary artifacts. Restoring that takes longer than the 25 s cold resolve on the runner, so only the git clones are cached.
- NoahTests: 1,233 tests, 307 s of summed test time. Split across 3 simulators, the shards took 110 s, 166 s and 172 s, but one `xcodebuild` sat idle for about 10 minutes after its tests passed. Shards were dropped: they add a script and a failure mode, and Android Maestro is the longer path anyway.
- Gradle configuration cache: reused cleanly for the debug builds (83 tasks up to date in 1 s), but it fails on the release builds' `upload<Variant>SentryMapping` task (its `onlyIf` captures the Project). CI passes `--build-cache` only.

### What changed (`t3/native-ci-speed`, on top of `fb2d057b`)

| Commit | Change |
|---|---|
| `f8c4244e` | `concurrency` (new PR pushes cancel old runs), path filters per platform, `!**/*.md`, 15–25 min timeouts |
| `78473614` | The two API drift checks run in parallel |
| `c9b1ad12` | iOS on `blacksmith-6vcpu-macos-27` with the newest installed Xcode 27 |
| `1224069b` | Android builds the regtest app and test APKs once; Maestro installs them and runs `am instrument` instead of a second Gradle build; no `--max-workers=2` |
| `7b9077da` | iOS: one `xcodebuild test` on one simulator, arm64 only, no `-jobs 2`, no mainnet/signet builds |
| `9ed26315` | Caches saved by merges and restored by PRs: Xcode compilation cache, SPM git clones, Gradle (read-only on PRs, plus `--build-cache`), Robolectric jars, `sdkmanager` skipped on a hit. Temporary: PR #374 saves too. |
| `ad27622e` | Removed `native-maestro-ios.yml` (nightly cron, iOS Maestro, tablet and fold) |
| `3a7d27dd` | Android Maestro folded into `native-android.yml` as two parallel jobs, `smoke` and `funded`; no regtest teardown |
| `abf22ae6` | `native-telegram-android.yml`: signed signet and mainnet APKs from Blacksmith, both to Telegram |
| `359185c8` | `native-telegram-ios.yml`: signet and mainnet archives on the signing Mac, both to Diawi and Telegram |
| `3ce5da02` | Lint config and comment tidy-up |

### Expected after (estimates, not measured)

| Job | Cold (first run) | Warm (master cache) |
|---|---|---|
| iOS build and NoahTests | 9–10 min | 7–8 min (about 1 min setup, 1–1.5 min build, 5–6 min tests) |
| Android build and unit tests | ~5 min | 3–3.5 min |
| Android Maestro, each job | setup 3–4 min, then the flows | setup 2.5–3 min, then the flows; the funded chain is the long pole |
| API drift | ~2 min | ~2 min |
| Telegram Android (merge only) | ~8 min | 6–7 min (two R8 runs) |

Per PR push: about $0.4 for Android only, $0.8 for iOS only and $1.2 for both, mostly the Mac.

### Still open

- **Measure:** push the branch to PR #374, then re-run it once so the second run uses the caches the first one saved.
- **Remove after the merge:** the `github.head_ref == 'feat/native-rewrite'` cache-save exception in `native-android.yml` and `native-ios.yml`.
- **Maestro flows:** the flow rewrite must tag every flow `smoke` or `funded`, adapt the `funded` block of `scripts/test-maestro-native.sh` (its host steps), and update the CI section of `apps/maestro/README.md`.
- **iOS Telegram on Blacksmith:** needs an App Store Connect API key, or a distribution certificate and ad hoc profiles, as secrets.
- **Server CI, when re-enabled:** dropping the PR release `noah-cli` build saves about 2.7 of its 5.7 min.
- **Not done, to keep it simple:** iOS test shards, emulator snapshots, starting the regtest stack in the background, the Gradle configuration cache, and seeding the drift job's Rust cache from `master`.

## Appendix: method and sources

- **Runs:** `gh run list -R BlixtWallet/noah --limit 1000 --json workflowName,status,conclusion,createdAt,updatedAt,startedAt,headBranch,event,databaseId,attempt` (2026-07-02 → 10-24). Jobs and steps came from `gh api repos/BlixtWallet/noah/actions/runs/<id>/jobs?filter=all` for 331 runs. Wall time = last job completion − first job creation, first attempt only. Cost = each job rounded up to whole minutes × the label price above.
- **Logs:** `gh run view <id> --log` for `40121735049`, `39851869419`, `39835692757`, `38994688993`, `38990297505` and `37814436050` (cache lines, Gradle summary, Maestro timeline); `--log-failed` for `40024484366`.
- **Self-hosted:** runner names from job metadata; `.runner` files (`agentName`, `gitHubUrl` only); `sysctl` and `ps`. I didn't read `.env`, `.credentials` or runner secrets. The org runner API needs `admin:org`, so label sets come from the jobs.
- **iOS profile:** `xcrun xcresulttool get test-results summary|tests` on local xcresult bundles from 2026-10-21.
- **Native local numbers** (from the user, this Mac, warm): Android JVM tests 45 s with `maxParallelForks = 3` (93 s before); Android build + test APK 1–2 min; iOS clean build about 2 min; NoahTests about 5.5 min serial.
- **Vendor docs:** linked inline above. [Blacksmith pricing](https://www.blacksmith.sh/pricing), [Blacksmith runners](https://docs.blacksmith.sh/blacksmith-runners/overview), [Blacksmith cache](https://docs.blacksmith.sh/blacksmith-caching/dependencies-actions), [sticky disks](https://github.com/useblacksmith/stickydisk), [GitHub pricing](https://docs.github.com/en/billing/reference/actions-runner-pricing), [GitHub runners](https://docs.github.com/en/actions/reference/runners/github-hosted-runners), [GitHub limits](https://docs.github.com/en/actions/reference/limits), [GitHub cache scoping](https://docs.github.com/en/actions/reference/workflows-and-actions/dependency-caching), [GitHub secure use](https://docs.github.com/en/actions/reference/security/secure-use), [merge queue](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/configuring-pull-request-merges/managing-a-merge-queue), [setup-gradle](https://github.com/gradle/actions/blob/main/docs/setup-gradle.md), [android-emulator-runner](https://github.com/ReactiveCircus/android-emulator-runner), [Gradle Managed Devices / ATD](https://developer.android.com/studio/test/managed-devices), [Maestro CLI](https://docs.maestro.dev/maestro-cli/maestro-cli-commands-and-options), [Maestro pricing](https://www.maestro.dev/pricing), [Xcode 26 release notes](https://developer.apple.com/documentation/xcode-release-notes/xcode-26-release-notes).
- **Earlier research:** `docs/blacksmith_ios_ci_research.md` (no Docker on Blacksmith macOS; Nix cache behavior) and `docs/native-rewrite/PLAN.md` "CI testing for the native UI".
