# Native translations (final decisions, 2026-10-21)

Native English in Swift/Kotlin is the source of truth. Both apps keep source-text keys in code and ship only native localization resources: iOS `.xcstrings`, Android `strings.xml`. General Translation (GT) runs at development time in JSON file mode; no GT runtime or SDK is added to either app.

The existing GT project is `prj_rqmymh8xftristtvxx5exf7h`. Its native config and syntax hint live in `apps/i18n/gt.config.json` and `package.json` (`i18next`). The tool extracts strings actually used by either platform into `apps/i18n/en/native.json`, using stable IDs derived from the English source, then reads `{es,ja,ko,sv}/native.json` to generate native catalogs. RN retains its independent pipeline while `client/` remains.

Decisions agreed with the user:

- Stay with GT's CLI JSON file mode; keep native runtime localization.
- Keep English source-text keys in Swift and Kotlin. Hash IDs are an interchange/resource detail; developers do not write semantic translation IDs.
- Machine translation only, with no human review/approval step.
- Run locally with one command: `bun apps/tools/translations.mjs translate`. Commit JSON, GT lock, catalogs, and coverage together.
- Defer a CI bot until merge to master. It will need `GT_API_KEY` and `GT_PROJECT_ID` secrets. Current CI regeneration/stale checks are fully offline.

The validated `/private/tmp/noah-dev/gt-spike` trial confirmed `{{var}}` placeholders, i18next `_one`/`_other` suffixes, multiline copy and product names across es/ja/ko/sv with CLI 2.25.2. Native plurals keep their English category definitions in `apps/i18n/plurals.json`; platform formatting and count-first argument order remain unchanged.

The migration optionally seeds existing translations verbatim from RN, without overwriting native translations unless explicitly forced. Only this optional seed step depends on `client/`. Normal extraction, generation, checks, and translation continue without it.

The live migration also established that GT considers an uploaded partial locale file complete. To translate only gaps, the command stages missing English keys in a temporary JSON workspace (grouping locales with identical gaps and batching about 100 keys at a time, keeping plural siblings together), runs GT, and merges only returned missing keys into native JSON. It validates placeholders, uploads the complete catalogs to the same project, and generates platform resources. Existing seeds remain unchanged. This avoids asking GT to retranslate existing RN copy or relying on an unsupported partial-file completion assumption.

The native inventory reports missing keys per locale. English fallback remains available while copy is being added, and `--check` detects stale English JSON, resources, and coverage without contacting GT. The independent frozen RN resolver fixture verifies retained shared wording; native tests also exercise newly translated native-only templates.

Only static UI copy is submitted. Recovery phrases, addresses, and runtime/user data never enter the extraction process. The CLI exposes no safe file deletion operation, so the trial's `i18n/en/native-spike.json` remains in the GT dashboard for manual cleanup.

Developer workflow and command details: [Native translations](../../apps/tools/TRANSLATIONS.md).

## Migration evidence (2026-10-21)

The native union contains 1,413 messages / 1,414 JSON keys (one active plural adds a second key). For **each of es, ja, ko, sv**, 649 messages / 650 keys were seeded from RN and 764 messages / 764 keys were translated by GT. All 650 seeded values match RN's flattened text exactly, with only the documented placeholder conversion. Coverage inventories 1,093 iOS and 1,136 Android source strings, with zero missing locale keys.

The initial large Korean request failed at GT while es/ja/sv completed. Their completed translations were recovered from GT's cache without replacing seeds. Eight bounded Korean batches then completed successfully. The final complete catalogs were uploaded and GT's lock was committed. Only static source copy was submitted.

New GT output for `Recipient receives about {nativeValue1} sats`:

- es: `El destinatario recibe aproximadamente {{nativeValue1}} sats`
- ja: `受取人の受取額は約{{nativeValue1}} satsです`
- ko: `받는 사람의 수령액은 약 {{nativeValue1}} sats입니다`
- sv: `Mottagaren får cirka {{nativeValue1}} sats`

The active place plural preserves both the numeric count and suffix argument. Its `other` forms are:

- es: `{{_gt_value_4}} lugares{{_gt_value_3}}` (`one`: `Un lugar{{_gt_value_3}}`)
- ja: `{{_gt_value_4}} か所{{_gt_value_3}}` (`one`: `1か所{{_gt_value_3}}`)
- ko: `{{_gt_value_4}}곳{{_gt_value_3}}` (`one`: `한 곳{{_gt_value_3}}`)
- sv: `{{_gt_value_4}} platser{{_gt_value_3}}` (`one`: `En plats{{_gt_value_3}}`)

Named `{recipient}`, numbered `{_gt_value_2}` / `{_gt_value_3}`, and native interpolation `{nativeValue1}` samples were checked in every locale. Generation validates argument sets and keeps native argument indices. The unchanged 25-case RN oracle was regenerated byte for byte. Tool tests: 16 passing; offline `--check` passes. Commit hooks also pass RN lint, compiler lint, 148 unit tests, typecheck, and Rust formatting.

Platform verification used these commands (one full-suite run per platform):

```sh
nix develop --command sh -c 'cd apps/android && ./gradlew --max-workers=2 :app:testRegtestDebugUnitTest :app:assembleRegtestDebug :app:compileRegtestDebugAndroidTestKotlin'

cd apps/ios
xcodegen generate
xcodebuild -project Noah.xcodeproj -scheme Noah-Regtest \
  -destination 'platform=iOS Simulator,id=90C9E956-0FE7-465F-AD6A-5F856D74B8F4' \
  -derivedDataPath /private/tmp/noah-i18n-dd \
  -resultBundlePath /private/tmp/noah-i18n-ios.xcresult \
  -parallel-testing-enabled NO -jobs 2 ARCHS=arm64 ONLY_ACTIVE_ARCH=YES \
  CODE_SIGNING_ALLOWED=YES CODE_SIGN_IDENTITY=- CODE_SIGNING_REQUIRED=YES test
```

Results and focused retries:

- Android: 3,638 JVM tests / 157 suites; 3,637 passed, one failure in existing `BarkLogTest.concurrentCallbacksPreserveEachProducersOrderUnderLoad`, zero errors/skips. All nine `TranslationsTest` cases passed. The failed logging stress test passed on its isolated rerun; regtest APK assembly and instrumentation Kotlin compilation then succeeded. The full suite was not repeated.
- iOS: 841 tests / 172 suites; 812 passed, 27 opt-in skipped, two failures in existing logging cases (`AppLogTests.sharedSecretRedactionVectors`, three persistence assertions; `BarkLogTests.barkMessagesCannotPersistSecrets`, one assertion). All ten `TranslationsTests` passed in the full run. The two failed logging cases plus `TranslationsTests` passed 12/12 in a focused `test-without-building` retry. The full suite was not repeated.
- Logging implementation/tests were left unchanged; the full-run failures remain test-isolation/flakiness follow-ups, rather than being hidden by a full rerun.
- Only the owned iOS 27.0 iPhone 17 simulator above was used; it was shut down and deleted afterwards. Owned DerivedData and both result bundles were removed, Gradle was stopped, and no owned build job remains. No Docker, physical device, shared simulator/emulator, `client/` edits, or `server/` edits.

```sh
nix develop --command sh -c 'cd apps/android && ./gradlew --max-workers=2 :app:testRegtestDebugUnitTest --tests com.noahwallet.services.BarkLogTest.concurrentCallbacksPreserveEachProducersOrderUnderLoad :app:assembleRegtestDebug :app:compileRegtestDebugAndroidTestKotlin'

# Same iOS project/scheme/destination/DerivedData as above, using a separate result bundle:
xcodebuild -project Noah.xcodeproj -scheme Noah-Regtest \
  -destination 'platform=iOS Simulator,id=90C9E956-0FE7-465F-AD6A-5F856D74B8F4' \
  -derivedDataPath /private/tmp/noah-i18n-dd \
  -resultBundlePath /private/tmp/noah-i18n-ios-logging-retry.xcresult \
  -parallel-testing-enabled NO \
  '-only-testing:NoahTests/AppLogTests/sharedSecretRedactionVectors()' \
  '-only-testing:NoahTests/BarkLogTests/barkMessagesCannotPersistSecrets()' \
  -only-testing:NoahTests/TranslationsTests \
  ARCHS=arm64 ONLY_ACTIVE_ARCH=YES test-without-building
```
