// bun --tsconfig-override client/tsconfig.json apps/fixtures/tools/generate-rn-stores-android.ts
import { mock } from "bun:test";
import { writeFileSync } from "node:fs";
const bytes = new Map<string, string>();
mock.module("../../../client/src/lib/mmkv", () => ({ mmkv: {
  getString: (key: string) => bytes.get(key),
  set: (key: string, value: string) => bytes.set(key, value),
  remove: (key: string) => bytes.delete(key),
} }));
mock.module("../../../client/src/lib/log", () => ({ default: () => ({ w() {}, e() {}, i() {} }) }));
mock.module("../../../client/src/lib/utils", () => ({ formatNumber: () => "unused" }));
const cases: unknown[] = [];
for (const [module, exported, key, changes] of [
  ["walletStore", "useWalletStore", "wallet-storage", { isInitialized: true, isWalletLoaded: true, isDebugModeEnabled: true }],
  ["backupStore", "useBackupStore", "backup-storage", { backupPending: true, lastBackupStatus: "failed", lastBackupError: "offline" }],
  ["esploraStore", "useEsploraStore", "esplora-storage", { endpointOverride: "https://example.com/api" }],
  ["privacyStore", "usePrivacyStore", "privacy-storage", { isHomeBalanceHidden: true }],
  ["profileStore", "useProfileStore", "profile-storage", { displayName: "Upgrade test", preferredCurrency: "EUR", bitcoinAmountUnit: "sats" }],
  ["recurringPaymentStore", "useRecurringPaymentStore", "recurring-payment-storage", { serverSyncPending: true }],
  ["serverStore", "useServerStore", "server-storage", { isRegisteredWithServer: true, isMailboxAuthorizationEnabled: false }],
  ["transactionStore", "useTransactionStore", "transaction-storage", { isAutoBoardingEnabled: false, hasAttemptedAutoBoarding: true }],
] as const) {
  const store = (await import(`../../../client/src/store/${module}`))[exported];
  store.setState(changes);
  const envelope = JSON.parse(bytes.get(key)!);
  cases.push({ name: key, input: { key, envelope }, expected: envelope });
  // Exercise the real Zustand migration rather than reproducing it in the generator.
  if (key === "transaction-storage") {
    bytes.set(key, JSON.stringify({ state: { isAutoBoardingEnabled: false, hasAttemptedAutoBoarding: true, future: { keep: 1 } }, version: 0 }));
    await store.persist.rehydrate();
    const migrated = JSON.parse(bytes.get(key)!);
    cases.push({ name: "transaction v0 migration", input: { key, envelope: JSON.parse('{"state":{"isAutoBoardingEnabled":false,"hasAttemptedAutoBoarding":true,"future":{"keep":1}},"version":0}') }, expected: migrated });
  }
}
writeFileSync(new URL("../rn-stores-android-shapes.json", import.meta.url), JSON.stringify({ description: "Real RN Zustand persist bytes; only platform MMKV/logging and unused formatting mocked.", cases }, null, 2) + "\n");
