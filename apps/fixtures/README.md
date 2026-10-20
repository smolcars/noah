# Shared native fixtures

One JSON file per domain, consumed by Android JUnit and iOS XCTest:

```json
{
  "cases": [
    { "name": "descriptive unique case name", "input": {}, "expected": {} }
  ]
}
```

`input` and `expected` are domain-specific JSON values. Keep fixtures deterministic,
platform independent and free of real wallet secrets. `scaffold.json` is a loader
smoke test; future domains should exercise actual shared behavior.

Android adds this directory to its test resources. `SharedFixtures.load("domain")`
parses the envelope and returns named cases without depending on the working directory.

M6 boarding/exits: run `bun --tsconfig-override client/tsconfig.json
apps/fixtures/tools/generate-exits.ts` from the repository root. The generator imports
RN's `exitFeeEstimate`, `exitTimeline`, `useExitOverview`, and `buildAutoBoardPlan`;
only platform/network dependencies are mocked. `exit-fee-estimate` (82),
`exit-cancellation` (56), `exit-timeline` (12), `exit-overview-coordination` (16),
and `auto-boarding` (21) contain 187 portable cases. Swift runs these in
`ExitFixtureTests`; Android can reuse the files without running React Native.

M7 unattended fee cap: `recurring-fee-cap` (24 cases) is native-only (RN pays recurring
Lightning without a cap) and hand-specified from the decision recorded in
`docs/native-rewrite/TODO.md` (M7). The cap comes from `ArkInfo.feeSchedule.lightningSend`,
with the published fallback schedule when ArkInfo is unavailable. The description field
states the formula. The regtest cases use captaind's local schedule
(`scripts/captaind.toml`) and include the two BOLT11 quotes verified on the Pixel.

M7 RN numeric storage: `recurring-rn-range.json` retains the exact 11 RN-written
schedule rows and numeric tokens from Android commit `a4ea8f0e`, with a native-only
`expectedClassifications` map added at envelope level. Both platforms assert its
classification for every row; the reason documents the boundary being exercised.
The four representable count rows are active. Counts beyond signed 64-bit range,
fractional timestamps and starts beyond JavaScript Date range are stopped-unsupported:
visible as Needs attention, never executed or rounded, and cancellable.
Android's historical local resource is unchanged; tests use the shared expectations.

RN's real `validateRecurringPaymentInput`, `createRecurringPayment`, and
`useRecurringPaymentStore.upsertSchedule` through Zustand `persist` /
`createJSONStorage` produced the original rows. Only MMKV, logging, translation
and unused React hooks were mocked. The original writer script remains at
`/tmp/noah-android-recurring-r3-rn.ts` and uses Bun with
`--tsconfig-override client/tsconfig.json` and installed dependencies via `NODE_PATH`.
The pre-expectations shared/Android copies had SHA-256
`c96c5f940008ac97f3f0757b79cef276381a56ac8e9fcca4a8d1cd27b23954b3`.
The fixture contains no wallet secrets.

RN's store does not configure a persistence version. Zustand defaults to numeric
0 and `createJSONStorage` uses `JSON.stringify`, producing only the token `0`.
Native accepts that exact token or an absent legacy version. `1e-400`, `0.0`,
`-0`, `0e5`, `"0"`, `1`, and `1e400` all make the entire store unavailable;
original bytes survive and execution/mutation stop. Whitespace around `0` is JSON
formatting and remains accepted. Even `JSON.stringify(-0)` produces `0`.

Normal RN timestamps come from `Date.now()`, the editor's local `Date` constructor
(with seconds/milliseconds zero), and calendar `Date.getTime()`; retries add whole
millisecond delays. Thus normal writes have whole-millisecond start/end/next-run,
created/updated, retry, in-flight and run-history times. The factory/validator can
accept synthetic fractional inputs (as these range rows demonstrate), but native
classifies any fractional time field as an unsupported row and preserves its values.
