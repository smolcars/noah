# e2e (TesterArmy) vs Maestro for Noah's Android CI (2026-10-25)

**Question (user):** is "npx e2e" better and faster than Maestro?

**TL;DR**
- "npx e2e" is the **`e2e` package from TesterArmy**: Apache-2.0 and pre-1.0 (0.21.3), first released 3 months ago. Its mobile engine sits on Callstack's `agent-device`.
- **Faster?**
  - **Deterministic steps: yes.** On the same emulator and the same flows, its taps and typing took about 0.6–1 s each; Maestro 2.11.0 took about 3.1–3.6 s per tap. Whole flows ran 1.4× to 4× faster.
  - **Agentic steps: no.** Each model call adds about 3 s, and a goal that isn't replayed from the cache takes 10–70 s.
  - **In CI:** much of Noah's run is regtest work that no test tool shortens (wallet creation, `bark send --wait`). The saving is in the UI steps only.
- **Better?** Mixed.
  - **Better:** TypeScript tests, so host steps like `just bark send` can sit inline. Each failure gets a trace with the screen tree. Locators are strict. It coped with the keyboard covering fields, where Maestro silently typed into the wrong field.
  - **Worse:** maturity, with weekly 0.x releases and no 1.0. It also failed every time on Noah's animated screens until I raised one engine setting.
  - **Doesn't fit Noah:** its agentic features need a model key on CI and send wallet screen content to a provider, and Noah doesn't need them.
- **Recommendation: not now as a replacement. Try it for one thing:** once the Maestro rewrite lands, port only the `smoke` flows to deterministic e2e on a branch. Adopt it for `smoke` only if that is clearly faster with no flakes over about 10 CI runs. Keep `funded` on Maestro. Use no agent steps and no model key either way. See [Recommendation](#recommendation).

## 1. What it is

- **How I identified it.** `npx e2e` runs the unscoped npm package `e2e`. Its `bin` is `e2e` and its description is "SDK, runner, and CLI for agentic end-to-end testing". The repository is `tester-army/e2e` ([npm](https://www.npmjs.com/package/e2e)).
  - The name was reused: versions 0.0.1–0.0.8 date from 2014–2016, and 0.1.0 restarted it on 2026-07-21 (`npm view e2e time`).
  - No other agentic tool answers to `npx e2e`. Momentic, for example, uses `npx momentic`.
- **Maintainers.** The npm maintainers are `okwasniewski` and `szymonrybczak`. On GitHub, okwasniewski has 611 commits; the next contributor has 90 ([contributors](https://github.com/tester-army/e2e/graphs/contributors)). The product is TesterArmy's ([homepage](https://tester.army/e2e)).
- **Licence.** Apache-2.0 ([LICENSE](https://github.com/tester-army/e2e/blob/main/LICENSE)). `agent-device` is MIT ([repo](https://github.com/callstack/agent-device)).
- **Maturity on 2026-10-25:**
  - Repo created 2026-07-22, with 11.9k stars, 623 forks and 51 open issues ([repo](https://github.com/tester-army/e2e)).
  - `e2e@0.21.3` and `@e2e-dev/mobile@0.12.4` were released 2026-10-23. There are nightly and canary builds almost daily, and a release about every week ([releases](https://github.com/tester-army/e2e/releases)).
  - The README says: "e2e is in active development on the way to 1.0. APIs and config can still [change]" ([README](https://github.com/tester-army/e2e#readme)).
  - Open mobile bugs relevant to us: #1324 "Problems identifying visibility causing flakiness", #1296 "delayed keyboard movement can send the next tap to stale bounds", and #1299 "Mobile transition refresh can tap another control through a recycled native ref" ([issues](https://github.com/tester-army/e2e/issues)).
- **Maestro for comparison:** Apache-2.0, created 2022-03, 16.2k stars, 451 open issues and PRs, cli-2.11.0 on 2026-09-29, about one release a month ([repo](https://github.com/mobile-dev-inc/maestro), [releases](https://github.com/mobile-dev-inc/maestro/releases)).

## 2. How it works

- **Devices.**
  - **Web:** Playwright drives browsers.
  - **Mobile:** iOS simulators, Android emulators and USB-connected phones, all through agent-device ([mobile](https://e2e.tester.army/docs/mobile), [intro](https://e2e.tester.army/docs)).
  - **On Android,** agent-device keeps one instrumentation helper on the device that holds `UiAutomation`. It serves snapshots and touches over an `adb forward` socket, at about 6–17 ms per snapshot instead of 227–266 ms one-shot ([ADR 0002](https://github.com/callstack/agent-device/blob/main/docs/adr/0002-persistent-platform-helper-sessions.md)).
  - **Maestro has the same architecture:** an instrumentation APK running a gRPC server (`am instrument … MaestroDriverService#grpcServer`, [AndroidDriver.kt](https://github.com/mobile-dev-inc/maestro/blob/main/maestro-client/src/main/java/maestro/drivers/AndroidDriver.kt)).
  - **Only one tool at a time per device:** "Android permits only one reliable instrumentation-owned `UiAutomation` context per device" (ADR 0002).
  - **Keyboard:** agent-device also installs an IME helper (`com.callstack.agentdevice.imehelper`, seen on the trial emulator).
- **How tests are written.**
  - Tests are TypeScript files (`tests/**/*.e2e.ts`) that mix three kinds of step ([core concepts](https://e2e.tester.army/docs/core-concepts)):
    - `agent.act('…')`: a natural-language goal. It calls the model unless the step is replayed from the cache.
    - `agent.assert` / `waitFor` / `extract`: the model judges the screen, on every run.
    - `screen` / `expect` locators, such as `getByTestId`, `getByRole` and `getByText`: no model.
  - There is no recorder. `npx e2e mcp` gives a coding agent a live session for authoring ([migrate from Maestro](https://e2e.tester.army/docs/migrate/maestro)).
- **Is an LLM needed?** Only for agent steps. "Tests without agent steps need no model", and "There is no default" model ([models](https://e2e.tester.army/docs/models)).
  - Any AI SDK provider with tool calls and images works: Vercel AI Gateway, OpenRouter, OpenAI, Anthropic, Google, xAI and others.
  - It can also use a ChatGPT, Copilot, OpenCode or SuperGrok subscription, or a local OpenAI-compatible server such as Ollama.
- **Tokens.** In e2e's own Android CI run there were 20 agentic tests, with 15 steps replayed, 2 handed off and 3 missed. They used **196.9k tokens, 38 model calls, $0.0159** on `openai/gpt-6-luna-fast`, 78% prompt-cached ([run 40015955078](https://github.com/tester-army/e2e/actions/runs/40015955078), "android agentic" job log). Steps that ran live used 3.9k–64k tokens each.
- **Determinism and caching** ([cache](https://e2e.tester.army/docs/cache)):
  - **What is recorded:** an `act` step's actions, once a later check passes. The next run replays them with no model call, and the agent takes over if the app changed.
  - **In CI:** the cache is read-only, and `--strict-cache` fails a stale replay with `REPLAY_STALE` (exit 2) instead of calling the model.
  - **Never cached:** `agent.assert`, `agent.waitFor` and `agent.extract` "always run live".
  - **Re-recording:** a cache entry is keyed by test, target, instruction, params and agent context, so renaming a test or editing an instruction forces a new recording.

## 3. Speed

### Published and CI numbers

- e2e's docs and README make no speed claims, against Maestro or otherwise.
- Its own Android CI runs on the **same runner class Noah uses**. That is `blacksmith-4vcpu-ubuntu-2404` with the API 36 `google_apis` x86_64 `pixel_7` emulator through `reactivecircus/android-emulator-runner` ([mobile.yml](https://github.com/tester-army/e2e/blob/main/.github/workflows/mobile.yml)). In run [40015955078](https://github.com/tester-army/e2e/actions/runs/40015955078):
  - **Deterministic suite:** 56 tests in **621 s**, about 11 s per test including app launches, after a 44 s cold emulator boot. Across 4 recent green runs the suite step took 573–715 s.
  - **Agentic suite:** 20 tests in **405 s**. Replayed `act` steps took 1.7–5 s. Live steps took 2.5 s per `assert`, and goals took 13.8 s (4 calls), 30.3 s (9 calls) or 41.5 s (11 calls). That is **about 3 s per model call** on top of device time.

### Hands-on trial (this Mac, same emulator, same APK)

- **Setup:**
  - A new AVD `noah-e2e-phone` (API 35 `google_apis` arm64, `pixel_7`), headless on port 5596, holding a regtest debug APK built from `apps/android` at `703b2d2e`.
  - e2e 0.21.3 with agent-device 0.21.29: no model and no key; telemetry off.
  - Maestro 2.11.0, the version `flake.nix` pins for CI. Its zip hash matched the flake (`sha256-U4RZPLTn…`).
  - Each tool ran sequentially on the same emulator. Times are per run; "steps" is the tool's own step total.
- **Flows:**
  - **Restore:** clear state → "I already have a wallet" → fill 12 words → submit → `restore-error` shows "valid phrase".
  - **Nav:** clear state, then 3 loops of "Create wallet" → the beta sheet → back → "I already have a wallet" → "Welcome back" → Back → Welcome.

| Flow | e2e (deterministic) | Maestro 2.11.0 | Notes |
|---|---|---|---|
| Restore (12 fields + keyboard) | **16.0–19.9 s** test, 19–22 s wall (3 green runs; default settings) | **75.9–77.9 s** steps, 83–84 s wall (3 green runs) | Maestro averaged 3.1 s per tap, 1.6 s per `inputText` and 1.05 s per `hideKeyboard`. e2e averaged 0.63 s per locator action (tap + type). `waitToSettleTimeoutMs: 500` didn't change Maestro's time; Maestro 2.6.0 timed the same. |
| Nav (9 taps, 3 backs, 9 asserts) | **failed 2/2** at the default `transition: 500`; passed with `transition: 1500` in **31 s** | **36–38 s** steps, 43–46 s wall (2 green runs) | e2e: "Ref … had no confirmed resting position when the transition window ran out". Noah's restore screen staggers its word cells in, so the Back button was still moving. Maestro took 3.5 s per tap. |
| Startup overhead | about 2 s CLI + 1.2 s engine prepare (9 s the first time, when it installs its helper) | about 6–8 s (wall − steps) | |

- **What the trial shows:**
  - e2e's deterministic driver is faster per action.
  - **Its tap gate is strict.** It refuses to tap a control that is still moving, and fails clearly instead.
  - **Noah's animations cost it something.** With `transition: 1500`, every new control waits 1.5 s, so the restore flow went from 16–20 s to 23.5 s. That is still about 3× faster than Maestro.
  - **The run count is small.** It doesn't establish flake rates. After the tuning, e2e passed 4 out of 4 combined runs.

### What this means for Noah's jobs

- The old RN funded chain ran about 7 min, and each UI flow spent 44 s to 1m22s onboarding a new wallet (`ci-speed-plan.md` §2, run `40121735049`).
- The new plan onboards **one wallet per job**, so tool overhead is a smaller share of each job.
- **Smoke job** (onboarding, receive, delete, restore validation; mostly UI): a 2–3× faster driver could save **perhaps 30–90 s**. This is an estimate, not measured on CI.
- **Funded job:** time goes into `bark send --wait`, balance polling and board/exit confirmations. No test tool shortens those, so the saving there is small.

## 4. Reliability on CI

- **Flakiness from the model:**
  - Locator-only tests have none.
  - With agent steps, CI should replay committed recordings with `--strict-cache`, which is what e2e's own Android CI does.
  - Every `agent.assert` and `waitFor` remains a live model judgment ([cache](https://e2e.tester.army/docs/cache), [ci](https://e2e.tester.army/docs/ci)).
  - Model provider failures (`MODEL_PROVIDER_FAILED`, `MODEL_UNAVAILABLE`) fail the step but don't evict recordings.
- **Flakiness from the driver:**
  - The trial hit the "resting position" failure on Noah's animated onboarding, which was fixed by `transition`.
  - The open issues listed in §1 are the same class of problem: stale bounds, recycled refs and visibility.
  - Maestro hit a different problem. With the keyboard up, its `tapOn "Word 11"` reported COMPLETED, but the text went into field 10 ("abandonabandonabandon"). The flow then failed at the final assert. It needed a `hideKeyboard` after every field.
- **Failure reports:**
  - e2e writes `trace.md` per failed test, with the error code, the source line, step timings, a diff of the screen tree between steps, the full tree, and a screenshot. It also writes `report.json` and optionally `junit.xml` and video ([ci](https://e2e.tester.army/docs/ci), [debugging](https://e2e.tester.army/docs/debugging)).
  - In the trial its first failure was `LOCATOR_NOT_FOUND … getByTestId("restore-word-1")` at `tests/restore-invalid.e2e.ts:8`, with the tree showing `textbox "Word 1"` and no test id. That was faster to diagnose than Maestro's log directory.
- **Timeouts:** per-test `timeout`, per-goal model and action budgets, `--max-failures`, `--max-duration`, and a 120 s cap on each model request ([ci](https://e2e.tester.army/docs/ci), [cache](https://e2e.tester.army/docs/cache)).
- **Headless Linux emulator:** supported and used by e2e's own CI: "Linux with KVM", boot the emulator in its own step, `adb install`, then `npx e2e run --target android` ([ci](https://e2e.tester.army/docs/ci)). It needs Node `^22.22.3 || >=24.8.0` (npm `engines`); Noah's `android-ci` Nix shell has `nodejs_22` and Bun.
- **Parallelism:**
  - **On one runner:** a device pool, with `workers` set to at least the number of devices.
  - **Across runners:** `--shard i/n`. Shard reports can't be merged yet.
  - **Rerun failed tests:** `--last-failed`.
  - CI defaults to `workers: 1, retries: 1` ([ci](https://e2e.tester.army/docs/ci)).
  - Maestro has `--shard-split` / `--shard-all` ([CLI](https://docs.maestro.dev/maestro-cli/maestro-cli-commands-and-options)). Noah's `smoke`/`funded` split works in either tool, as tags (`--tag` in e2e).

## 5. Cost

| | e2e | Maestro |
|---|---|---|
| Licence | Apache-2.0, free | Apache-2.0, free (Maestro Cloud is $250 per device per month; not used, [pricing](https://www.maestro.dev/pricing)) |
| Model per CI run | **$0** with locators only. With agent steps, cents per run: e2e's 20-test agentic suite cost $0.0159 with most steps replayed ([run](https://github.com/tester-army/e2e/actions/runs/40015955078)). | $0 (no model) |
| Runner minutes | same Blacksmith job; minutes scale with flow time (§3) | same |
| Hidden costs | a model key in CI secrets for agent steps; re-recording when instructions change; Node in the Android job | none new |

## 6. Security

- **What a model provider would receive (agent steps only):** the instruction, the screen as an accessibility tree, the redacted URL and earlier step records. It also gets masked screenshots when it asks or a step sets `vision` ([security](https://e2e.tester.army/docs/security)). On Noah that means balances, addresses, activity and the profile, even on regtest.
- **Secrets:**
  - A `Secret` value is never shown to the model.
  - After a secret fill, the rest of the attempt sends no screenshots.
  - Redaction works by string match. Values typed as ordinary params, and committed cache entries, stay in plaintext.
- **FLAG_SECURE screens:** Noah sets FLAG_SECURE on restore, recovery phrase and app lock (`WalletLock.kt:104-108`, `RestoreWalletScreen.kt:41`). Screenshots there are black; Maestro's failure screenshot in the trial was black. Vision checks see nothing on those screens, but the text tree is still readable.
- **Local model:** supported through `@ai-sdk/openai-compatible`. The model must support tool calls and images ([models](https://e2e.tester.army/docs/models)). On a 4-vCPU Blacksmith Linux runner with no GPU, a local vision model is impractical (my judgment, not measured).
- **Telemetry:**
  - On by default: anonymous counts, versions and error codes. CI runs are identified as `ci:<vendor>`, plus a hash of the repo's root commit (null in a shallow clone).
  - Sent to `eu.i.posthog.com`.
  - On a terminal, it also checks npm for a newer release at most once a day.
  - Turn telemetry off with `E2E_TELEMETRY_DISABLED=1` or `DO_NOT_TRACK=1` ([telemetry](https://e2e.tester.army/docs/telemetry)).
- **Trust:** tests, config and engines run unsandboxed with the job's permissions. Give model keys only to trusted branches ([security](https://e2e.tester.army/docs/security), [ci](https://e2e.tester.army/docs/ci)).

## 7. Fit for Noah

- **Test IDs and Compose `testTag`:**
  - `getByTestId` reads the Android resource id. Compose tags need `testTagsAsResourceId = true` ([mobile](https://e2e.tester.army/docs/mobile#test-ids-by-framework)), which Noah already sets (`MainActivity.kt:118`, `WalletPrivacy.kt:43,71`).
  - Matching is exact and case-sensitive, and an ambiguous match fails (`LOCATOR_AMBIGUOUS`). Maestro uses case-insensitive regex and taps the first match ([migrate](https://e2e.tester.army/docs/migrate/maestro#rewrite-selectors)).
- **Deep links:** `device.openLink('noah://…')` ([mobile](https://e2e.tester.army/docs/mobile#the-device-fixture)).
- **Regtest funding between steps:** this is e2e's clearest advantage.
  - In e2e, a test is TypeScript, so `execFile('just', ['bark', 'send', '--wait', addr, '20000 sats'])` and clipboard reads (`device.clipboard()`) sit inline between UI steps.
  - Maestro's JavaScript runs "in a restricted sandbox … without direct access to your local file system" ([Maestro JS](https://docs.maestro.dev/advanced/javascript)). That is why Noah's host steps live in `scripts/test-maestro-native.sh` (`just bark send --wait` at line 92) and split the funded chain into separate `maestro test` calls.
- **Hold to send:**
  - Android's `HoldToConfirm` needs 800 ms (`HoldMillis`, `SendParts.kt:281`).
  - e2e: `.longPress({ duration })` ([migrate](https://e2e.tester.army/docs/migrate/maestro#map-each-command)).
  - Maestro's `longPressOn` is fixed at 3 s ([docs](https://docs.maestro.dev/api-reference/commands/longpresson)); on Android it is an `input swipe x y x y 3000` (`AndroidDriver.kt`). Both should complete an 800 ms hold. Not tested: Send needs a funded wallet.
- **Clear state, permissions and airplane mode:** `app.clearState()`, `app.permissions`, `device.setAirplaneMode()` (Android) ([mobile](https://e2e.tester.army/docs/mobile)).
- **Gaps against Maestro:** no relative selectors (`below`, `leftOf`), no HTML report, no `maestro hierarchy` equivalent, and no Maestro Studio ([migrate](https://e2e.tester.army/docs/migrate/maestro)).

## 8. Comparison

| | e2e 0.21 (TesterArmy) | Maestro 2.11 |
|---|---|---|
| **Speed** | Deterministic: about 0.6–1 s per action; 1.4–4× faster flows in the trial. Agent steps: about 3 s per model call, 10–70 s for a live goal. | About 3.1–3.6 s per tap and 1.6 s per `inputText` on Noah's screens. JVM start about 6–8 s. |
| **Reliability** | Fails closed on moving targets. Noah's animations needed `transition: 1500`. Open stale-bounds and visibility bugs. Model judgments are never cached. | Mature. Taps through animation by waiting it out. Typed into the wrong field under the keyboard and reported COMPLETED. |
| **Cost** | $0 with locators only; cents per run with agent steps, plus a key in CI. | $0 |
| **Determinism** | Locators are deterministic. `act` is deterministic when replayed under `--strict-cache`. `assert`/`waitFor`/`extract` are not. | Deterministic. |
| **Android support** | Emulators and USB devices through agent-device; Compose tags with `testTagsAsResourceId`; airplane mode and fold. | Full; the reference tool for Android flows. |
| **CI fit** | Node ≥22.22.3/24.8; same Blacksmith Linux emulator setup; `--shard`, `--tag`, junit, trace.md; host steps inline in TypeScript. | Already in `flake.nix` and `native-android.yml`; host steps in a separate shell script. |
| **Security** | Agent steps send the screen tree (and screenshots on request) to a provider; telemetry on by default; local models possible but impractical on CI. | Nothing leaves the runner unless you use Maestro Cloud or AI commands. |
| **Maturity** | 3 months old, 0.x with weekly releases, 11.9k stars, a small core team. | 3.5 years old, monthly releases, 16.2k stars, wide adoption. |

## Recommendation

**Not now as a replacement. Try it for one bounded thing.**

1. **Don't adopt the agentic features for CI.** Noah's flows have stable test IDs and need deterministic results. Agent steps add a model key on CI, send wallet screen content to a provider, and add about 3 s per call. They also add non-cached judgments that can flake.
2. **Don't stop the Maestro rewrite.** It is under way, and Maestro is mature and already wired into `flake.nix`, `native-android.yml` and `test-maestro-native.sh`.
3. **Try deterministic e2e for the `smoke` job only, once the Maestro rewrite merges:**
   - Port S1–S3 to `*.e2e.ts` with locators only: `mobile({ platform: 'android', transition: 1500 })`, `E2E_TELEMETRY_DISABLED=1`, and a pinned version.
   - Run it on a branch beside Maestro for about 10 CI runs.
   - **Switch `smoke` to e2e only if** its job is at least 30% faster and has zero flakes. Otherwise drop the branch, so `master` keeps one framework.
4. **Look again at e2e 1.0,** or once the mobile stale-bounds and visibility issues (#1296, #1299, #1324) are closed. Then consider `funded` as well: inline `just bark` steps would retire most of `test-maestro-native.sh`.

## Findings for the Maestro rewrite (from the trial)

- **The restore word fields have no test ID visible to UiAutomation.**
  - `restore-word-N` is a Compose `testTag` on an `AndroidView` that wraps an `EditText` (`RestoreWalletScreen.kt:314`, `RecoveryWordField.kt:108`). Neither Maestro (`Id matching regex: restore-word-1` not found) nor e2e can find it.
  - The planned S3 flow (`ci-speed-plan.md` §5) has two options: use the `Word N` hint text, or give the `EditText` itself a resource id.
- **On Android, fill one restore field at a time, with `hideKeyboard` after each.** Otherwise Maestro's taps land under the keyboard.
- **The restore screen is FLAG_SECURE.** Failure screenshots there are black, so check the hierarchy instead.

## Method and sources

- **e2e:**
  - The `tester-army/e2e` repo at `69e123b1` (2026-10-23): `docs/*.mdx`, `docs/migrate/maestro.mdx`, `apps/mobile-benchmark`, `.github/workflows/mobile.yml`. The same pages are at `https://e2e.tester.army/docs/<page>`.
  - npm metadata via `npm view e2e` and `npm view @e2e-dev/mobile`.
  - GitHub stats via `gh api`.
  - CI logs via `gh run view 40015955078 -R tester-army/e2e --log`.
- **agent-device:** `callstack/agent-device` at `7ee441f2`, `docs/adr/0002-persistent-platform-helper-sessions.md`.
- **Maestro:** `mobile-dev-inc/maestro` at `da0f0f2b`, `maestro-client/.../AndroidDriver.kt`, plus the docs linked inline.
- **Noah:** `docs/native-rewrite/ci-speed-plan.md`, `.github/workflows/native-android.yml`, `flake.nix` and `scripts/test-maestro-native.sh` at `703b2d2e`.
- **Trial:**
  - Run in `/private/tmp/noah-e2e-trial/`, with `npm install --ignore-scripts` (neither package has install scripts).
  - `AGENT_DEVICE_STATE_DIR` was pointed at the trial directory, so the user's running agent-device daemon was not touched.
  - Only `emulator-5596` was driven, confirmed with `emu avd name` = `noah-e2e-phone`.
  - No model or API key was used.
  - Afterwards the AVD was deleted and the trial daemon stopped.
  - The trial is local (Apple Silicon, arm64 image), not CI. Absolute times on a 4-vCPU x86_64 Blacksmith runner will differ; the per-action gap between the tools is the transferable part.
