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
