# Translations going forward (design, 2026-10-20)

## Today

- **Source:** the native catalogs are generated from RN. `apps/tools/translations.mjs` runs GT's offline `generate` over `client/src` and converts `client/src/_gt/{es,ja,ko,sv}.json` into `Localizable.xcstrings` (iOS) and `res/values*/strings.xml` (Android).
- **Keys:** source text. Android IDs are `gt_` plus a SHA-256 prefix of the English text.
- **Coverage:** iOS 610 translated / 413 English-only; Android 528 / 505. English-only strings are copy that exists only in the native apps (`apps/fixtures/translations-coverage.json`).
- **Problems:**
  - Every native-only or reworded string stays English.
  - The native apps depend on `client/src` for their copy. That works while `client/` is kept, but the two drift as soon as native copy changes.

GT's CLI translates JSON, YAML, PO/POT and Markdown files. It has no `.xcstrings` or Android `strings.xml` format and no Swift or Kotlin SDK.

## Options

1. **Native-owned English source, translated by the GT CLI in JSON file mode (recommended).**
   - `apps/i18n/en.json` is generated from the strings both native apps actually use: the RN-shared ones plus native-only ones, keyed by source text as today.
   - `gt translate` (the CLI with `files.json`, using the existing GT project and API key) fills `apps/i18n/{locale}.json`. It translates missing keys only.
   - The existing tool converts those JSON files into `.xcstrings` and `strings.xml`, as it does today from RN's GT output.
   - Migration loses nothing: seed `apps/i18n/{locale}.json` from the current RN translations first, so GT only translates the roughly 400–500 native-only strings.
   - RN keeps its own GT pipeline for as long as `client/` exists. Native stops reading `client/src`.
2. **A mobile-native localisation vendor** (for example Localazy, Lingo.dev, Lokalise or Crowdin) that translates `.xcstrings` and `strings.xml` directly.
   - Good native tooling and a translator UI.
   - But it's a second vendor beside GT, and two catalogs need keeping in step.
3. **Status quo.** Keep native copy identical to RN and accept English for native-only text. Not viable once native copy evolves, or if `client/` is ever removed.

## Recommendation: option 1

Open questions to settle in a short spike before switching:
- **Plurals and placeholders.** The GT JSON file mode must round-trip our placeholder (`{name}`) and plural forms. If plain JSON can't carry plural categories, use ICU strings, or YAML/PO if GT handles plurals better there.
- **Where translation runs.** Either a CI job on `master` that runs `gt translate` and opens a PR with the updated catalogs (needs a `GT_API_KEY` secret), or developers running it locally before release.

## Decisions for the user

1. **Vendor:** stay with GT using JSON file mode (recommended), or move native to a mobile-native vendor.
2. **Who runs it:** a CI bot PR (recommended) or developers locally.
3. **Keys:** keep source-text keys (recommended: zero migration, the same model as RN), or move to semantic IDs. Semantic IDs keep translations when English copy is reworded, but every string needs an ID.
4. **Review:** decided 2026-10-20. Machine translation only; no per-language human review before merging.

Decisions 1–2 are to be talked through with the user before any implementation.
