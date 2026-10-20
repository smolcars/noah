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
