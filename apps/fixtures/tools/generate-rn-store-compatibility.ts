// NODE_PATH=<installed repo>/node_modules bun --tsconfig-override client/tsconfig.json apps/fixtures/tools/generate-rn-store-compatibility.ts
import { mock } from "bun:test";
import { writeFileSync } from "node:fs";
const bytes = new Map<string, string>();
const now = Date.now;
Date.now = () => 1_800_000_000_000;
mock.module("../../../client/src/lib/mmkv", () => ({ mmkv: {
  getString: (key: string) => bytes.get(key),
  set: (key: string, value: string) => { bytes.set(key, value); },
  remove: (key: string) => { bytes.delete(key); },
} }));
mock.module("../../../client/src/lib/utils", () => ({ formatNumber: (n: number) => String(n) }));
mock.module("../../../client/src/lib/log", () => ({ default: () => ({ w() {}, e() {}, i() {} }) }));
const { useWalletStore } = await import("../../../client/src/store/walletStore");
const { useProfileStore } = await import("../../../client/src/store/profileStore");
const { usePrivacyStore } = await import("../../../client/src/store/privacyStore");
const { useTransactionStore } = await import("../../../client/src/store/transactionStore");
const { useServerStore } = await import("../../../client/src/store/serverStore");
const { useBackupStore } = await import("../../../client/src/store/backupStore");
const { useEsploraStore } = await import("../../../client/src/store/esploraStore");
const { useRecurringPaymentStore } = await import("../../../client/src/store/recurringPaymentStore");
useWalletStore.getState().finishOnboarding();
useWalletStore.getState().setDebugModeEnabled(true);
useProfileStore.getState().setDisplayName("Upgrade fixture");
useProfileStore.getState().setPreferredCurrency("EUR");
useProfileStore.getState().setBitcoinAmountUnit("sats");
usePrivacyStore.getState().setHomeBalanceHidden(true);
useTransactionStore.getState().setAutoBoardingEnabled(false);
useServerStore.getState().setEmailAddress("fixture@example.com");
useServerStore.getState().setMailboxAuthorizationEnabled(false);
useBackupStore.getState().seedRestoredBackup("fixture-snapshot", "fixture-backup");
useEsploraStore.getState().setEndpointOverride("https://fixture.invalid/api");
useRecurringPaymentStore.getState().setServerSyncPending(false);
const snapshots = [...bytes].sort(([a], [b]) => a.localeCompare(b));
if (snapshots.length !== 8) throw new Error("Expected all eight actual RN stores");
type Expected = { available: boolean; state?: Record<string, unknown>; version?: number;
  consumed?: { preferredCurrency: string; bitcoinAmountUnit: string }; preservedTokens?: Record<string, string> };
const cases: { name: string; input: { key: string; raw: string }; expected: Expected }[] = [];
function add(name: string, key: string, raw: string, expected: Expected) {
  cases.push({ name, input: { key, raw }, expected });
}
const futureTokens = { futureWide: "9223372036854775809", futureUnderflow: "1e-400", futureNegativeZero: "-0" };
function augmented(raw: string) {
  // Insert numeric tokens into the original bytes; JSON.parse/stringify would erase their spelling.
  return raw.replace('"state":{', '"futureRoot":1e-400,"state":{' +
    Object.entries(futureTokens).map(([key, token]) => `"${key}":${token},`).join(""));
}
const preservedTokens = { "root.futureRoot": "1e-400",
  ...Object.fromEntries(Object.entries(futureTokens).map(([key, token]) => [`state.${key}`, token])) };
for (const [key, raw] of snapshots) {
  const envelope = JSON.parse(raw);
  const expected = { available: true, state: envelope.state, version: envelope.version };
  add(`RN ${key}`, key, raw, expected);
  add(`unknown tokens ${key}`, key, augmented(raw), { ...expected, preservedTokens });
  const state = JSON.stringify(envelope.state);
  for (const token of ["-0", "0e5", "1e-400", "0.0", "1.0", "1e0", "-1", "99", "true", "null", '"0"', "1e400"]) {
    add(`reject version ${token} ${key}`, key, `{"state":${state},"version":${token}}`, { available: false });
  }
  add(`absent version ${key}`, key, `{"state":${state}}`, expected);
  add(`spaced zero ${key}`, key, `{"state":${state},"version": \n0\t}`, expected);
  add(`version one ${key}`, key, `{"state":${state},"version":1}`, { ...expected, available: key === "transaction-storage" });
  for (const raw of ["broken", "null", "{}", '{"state":[]}', '{"state":null}'])
    add(`reject envelope ${raw} ${key}`, key, raw, { available: false });
}
// Exercise Zustand's real v0 -> v1 migrate hook, including RN partialization on its write.
const transactionRaw = augmented('{"state":{"isAutoBoardingEnabled":false,"hasAttemptedAutoBoarding":true},"version":0}');
bytes.set("transaction-storage", transactionRaw);
await useTransactionStore.persist.rehydrate();
const migrated = JSON.parse(bytes.get("transaction-storage")!);
if (migrated.version !== 1 || migrated.state.hasAttemptedAutoBoarding !== false) throw new Error("RN migration did not run");
add("real RN transaction v0 migration", "transaction-storage", transactionRaw,
  { available: true, state: migrated.state, version: 1, preservedTokens });
// RN's merge consumes fallback preferences without repairing the persisted original.
for (const state of [
  { displayName: "Future preferences", preferredCurrency: "future-currency", bitcoinAmountUnit: "future-unit" },
  { displayName: null, preferredCurrency: null, bitcoinAmountUnit: null },
]) {
  const raw = JSON.stringify({ state, version: 0 });
  bytes.set("profile-storage", raw);
  await useProfileStore.persist.rehydrate();
  const consumed = useProfileStore.getState();
  if (bytes.get("profile-storage") !== raw) throw new Error("RN unexpectedly rewrote preferences");
  add(`RN profile fallback ${state.preferredCurrency === null ? "null" : "future strings"}`, "profile-storage", raw,
    { available: true, state, version: 0, consumed: { preferredCurrency: consumed.preferredCurrency, bitcoinAmountUnit: consumed.bitcoinAmountUnit } });
}
for (const [key, field, nullable] of [
  ["wallet-storage", "staticVtxoPubkey", true], ["wallet-storage", "isInitialized", false],
  ["privacy-storage", "isHomeBalanceHidden", false], ["transaction-storage", "isAutoBoardingEnabled", false],
  ["server-storage", "emailAddress", true], ["server-storage", "mailboxAuthorizationExpiry", true],
  ["server-storage", "isMailboxAuthorizationEnabled", false], ["backup-storage", "lastBackupAt", true],
  ["backup-storage", "lastBackupError", true], ["backup-storage", "lastBackupStatus", false],
  ["backup-storage", "backupPending", false], ["esplora-storage", "endpointOverride", true],
] as const) {
  const version = key === "transaction-storage" ? 1 : 0;
  add(`nullability ${key}.${field}`, key, `{"state":{"${field}":null},"version":${version}}`,
    { available: nullable, state: { [field]: null }, version });
}
for (const [key, field] of [["profile-storage", "preferredCurrency"], ["profile-storage", "bitcoinAmountUnit"],
  ["wallet-storage", "isInitialized"], ["backup-storage", "lastBackupAt"], ["server-storage", "emailAddress"]]) {
  add(`wrong type ${key}.${field}`, key, `{"state":{"${field}":[]},"version":0}`, { available: false });
}
Date.now = now;
writeFileSync(new URL("../rn-store-compatibility.json", import.meta.url), JSON.stringify({
  description: "One deterministic RN Zustand generator; input.raw retains original numeric token spelling. Real RN writes, transaction migration and profile merge; explicitly labelled native envelope/type/version rejection probes. Unknown raw tokens must survive native writes.",
  cases: cases.map(({ expected: { state, ...expected }, ...item }) => ({ ...item,
    expected: { ...expected, ...(state ? { stateRaw: JSON.stringify(state) } : {}), imported: item.input.key !== "recurring-payment-storage" && expected.available } })),
}, null, 2) + "\n");
