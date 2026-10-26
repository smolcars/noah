# Native translations

English in Swift/Kotlin is the native source of truth. Keep writing source-text keys in SwiftUI or the existing `L10n` / Compose `tr` helpers. GT is a build-time CLI only: iOS ships `.xcstrings` and Android ships `strings.xml`; neither app includes a GT SDK or runtime.

Install workspace dependencies (`bun install --frozen-lockfile`), sign in once per developer, then translate from the repository root:

```sh
(cd apps/i18n && ../../node_modules/.bin/gt login)
bun apps/tools/translations.mjs translate
```

Commit `apps/i18n/`, generated native resources, and `apps/fixtures/translations-coverage.json` together. Translation is machine-only, with no human approval step. Only static UI copy is extracted; recovery phrases, addresses, and runtime user content must never be submitted.

The command extracts the union of strings used by both native platforms into `apps/i18n/en/native.json`, translates missing keys to es/ja/ko/sv with the workspace GT CLI, then generates both native catalogs. IDs are `gt_` plus the first 16 hex digits of SHA-256 of the English source. Editing English produces a new ID. Contextual duplicates share one translation.

GT's `upload` treats each uploaded locale file as complete, even when keys are missing. The tool therefore groups locales with identical gaps, batches about 100 keys at a time (keeping plural siblings together), and writes just those English keys into an isolated temporary JSON workspace with the same config/project and relative filename, runs `gt translate`, and merges the returned keys. Existing native translations are never replaced. After validation it uploads the complete catalogs from `apps/i18n/` and keeps GT's `gt-lock.json`. Do not run bare `gt translate` over partially seeded catalogs; use the command above. Temporary files are removed on completion or failure; seeded files survive failed GT runs.

Offline commands (no GT calls, credentials, RN source, or RN catalogs needed):

```sh
bun apps/tools/translations.mjs extract   # Write English JSON only
bun apps/tools/translations.mjs           # Regenerate catalogs and coverage
bun apps/tools/translations.mjs --check   # Compare English JSON, catalogs and coverage byte for byte
bun apps/tools/translations.mjs translate --dry-run   # Count the keys translate would send per locale; writes nothing
bun test apps/tools/translations.test.mjs
```

`--check` fails if English is stale against native code, or catalogs/coverage differ from the JSON. It also fails on copy only one platform uses unless `apps/i18n/platform-only.json` lists it for that platform with a reason, and on listed entries that both platforms (or neither) now use. Prefer the same English on both platforms; see `docs/native-rewrite/copy-unification.md`. Coverage lists all used source strings per platform and missing JSON keys per locale. Missing translations fall back to English and remain visible in that inventory. An unchanged English product name counts as translated when its locale key exists.

Optional migration only, while RN is available:

```sh
bun apps/tools/translations.mjs seed           # Fill missing keys from RN; never overwrite native text
bun apps/tools/translations.mjs seed --force   # Explicitly replace matching keys with RN translations
```

Only `seed` runs GT's offline RN extractor and reads `client/src/_gt/{locale}.json`. RN files stay read-only. Rich text is flattened in reading order, variables become i18next `{{name}}`, and the lowest sorted RN GT hash wins contextual duplicates deterministically. Seeding preserves the existing translated text, including whitespace and punctuation, apart from the placeholder representation.

* iOS: `Noah/Resources/Localizable.xcstrings`, included by XcodeGen in the app and the widget extension (which also compiles `L10n` and `LegacyLocale`, and reads the language the app copies to its app group). Source text remains the lookup key. Static SwiftUI literals localize normally; dynamic strings use `L10n`.
* Android: `res/values{,-es,-ja,-ko,-sv}/strings.xml`. Hashed resource IDs remain unchanged. `native.xml` retains existing non-generated IDs; generated localized aliases cover tabs, the launch gate, and the widget's layout text and picker descriptions.
* Variables retain their names (`{name}`, `{nativeValueN}`, `{_gt_value_N}`) and become `{{name}}` in JSON. Native formatting uses source argument order, `%1$@` on iOS and `%1$s` on Android. Translations may reorder indices. Unknown/missing arguments fail generation. Literal percent signs and XML/newline escaping remain native-safe.
* Plural callers use the English `other` text as their key. Define its English categories in `apps/i18n/plurals.json`; the first source variable is the count. The extractor rejects plural calls without definitions. JSON uses `_one`, `_other`, and any additional source CLDR categories; generation retains locale categories as `.xcstrings` variations / Android `<plurals>`. The count stays first (`%1$lld` on iOS; a numeric selector plus separately localized `%1$s` display count on Android). Native copy uses many plurals, such as block, VTXO and payment counts and BTC Map's place count; their category keys are in `apps/i18n/en/native.json`. The RN fixture also retains two historical plurals no longer used natively.
* Discovery scans `apps/ios/Noah`, `apps/ios/NoahWidget` and the Android app's Kotlin for native UI literals, display labels, explicit localization calls, and user-facing error messages. Native interpolation expressions are inventoried as `{nativeValueN}` without evaluating them. Comments, raw regexes, API-generated code, identifiers, and URL-only literals are excluded. Dynamic strings still need the existing localization helpers at their call sites.

Language preference stays `generaltranslation.locale`: iOS UserDefaults.standard and Android `gt_store` SharedPreferences. Without a supported preference, native device-locale fallback follows RN order, then English. UI selection and headless notifications reread that preference.

`TranslationsTests` / `TranslationsTest` check compiled resources for every used native key in all five locales, the 25-case RN resolver oracle, active plural forms (including zero and grouped millions on Android), live preference re-resolution, and translated native-only notification templates. The frozen RN oracle in `apps/fixtures/translations.json` remains independent of native generation. Its optional RN-only regeneration command is `bun apps/tools/generate-translations-fixture.ts`.

## CI bot

After each merge to `master` that touches native copy, `.github/workflows/native-translations.yml` runs this tool. If no locale key is missing, it stops without contacting GT. Otherwise it runs `translate` with the `GT_API_KEY` and `GT_PROJECT_ID` repository secrets, then `--check` and the tool tests, and opens or updates one PR from `bot/native-translations`. The GT CLI reads both secrets from the environment; `GT_API_KEY` takes precedence over `gt login`, and `GT_PROJECT_ID` must match `gt.config.json`. The key needs the project's file permissions, including `project:files:read`: GT CLI 2.25 looks up branch data before every translation, and a key without that permission fails with "Missing required permission: project:files:read". Translation and validation failures publish nothing. If a GitHub PR call fails after the push, the bot branch is left updated, and the next run rebuilds it (translating its keys again if no PR was opened). While the PR is open, later runs `reuse` its translations whose English is unchanged, so GT never translates the same key twice. Each run, including a rerun of an older one, rebuilds the branch from the current `master`, so don't push to it. Once the PR is merged, the next run finds nothing missing and stops. If another merge already supplied (or removed) everything an open bot PR adds, the next run closes that PR and deletes its branch. PR checks stay offline.

The bot fills any missing locale keys after merge. Hand translations in PRs stay welcome, and the bot never overwrites them.

CI on the bot PR must be started manually: Actions → workflow → Run workflow → branch `bot/native-translations` (Native Android CI, Native iOS CI and Native API drift). PRs opened with `GITHUB_TOKEN` don't trigger PR workflows. The bot needs the repository setting "Allow GitHub Actions to create and approve pull requests".

CLI 2.25.2 exposes no safe file-delete operation; the trial's `i18n/en/native-spike.json` remains in the GT dashboard for manual cleanup.
