# Native translations

RN is the source of truth. Install the locked workspace dependencies (`bun install --frozen-lockfile`), then run from the repository root:

```sh
bun apps/tools/translations.mjs
bun apps/tools/translations.mjs --check
bun test apps/tools/translations.test.mjs
```

The tool runs GT's offline `generate --omit-config-ids` in `client/`, with a temporary config/output directory. It reads `client/src/_gt/{es,ja,ko,sv}.json`; it never updates RN files or contacts the GT service. No new dependency is needed. Commit regenerated resources and the coverage inventory together. Check mode compares exact bytes and fails for stale resources or inventory.

* iOS: `Noah/Resources/Localizable.xcstrings`, included by XcodeGen's Noah source tree. Source text is the lookup key. Static SwiftUI literals localize normally; dynamic strings use `L10n`.
* Android: `res/values{,-es,-ja,-ko,-sv}/strings.xml`. Resource IDs are `gt_` plus the first 16 hex digits of SHA-256 of the UTF-8 source key. `native.xml` keeps existing non-generated IDs. `tr` uses Compose `stringResource`; background callers use `L10n.text` with a context configured from the persisted language.
* Variables use first appearance in the source as argument order: `%1$@` on iOS, `%1$s` on Android. A plural's count is first and numeric (`%1$lld` / `%1$d`). Translations may reorder the indices. Literal percent signs are escaped. XML quotes, apostrophes, newlines, whitespace and ampersands survive Android resource compilation.
* GT rich text is flattened by recursively concatenating text children in reading order, removing presentation/component wrappers. `Var` nodes become `{name}` (GT's `_gt_value_N` names are preserved). No spaces are invented. Plural nodes retain their categories and surrounding text. Unknown structures or variables fail generation.
* Contextual duplicates collapse to one source key; the lowest sorted GT hash wins deterministically. This is a source-keyed native catalog limitation. Native-only copy remains English and is listed per platform in `apps/fixtures/translations-coverage.json`; iOS includes it as English-only catalog entries.

Language preference uses RN's `generaltranslation.locale`: iOS UserDefaults.standard, Android `gt_store` SharedPreferences. Without a supported preference, device locale candidates are checked in RN order, then English. Picking a language updates the UI and the same persisted key immediately. Notification code resolves that key when creating text, without a UI or in-memory model.
