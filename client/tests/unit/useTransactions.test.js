import { describe, expect, mock, test } from "bun:test";

mock.module("noah-tools", () => ({ getAppVariant: () => "mainnet", nativeLog: () => {} }));
mock.module("react-native", () => ({ Platform: { OS: "ios" } }));
mock.module("../../src/lib/paymentsApi", () => ({ history: () => {}, onchainTransactions: () => {} }));
mock.module("../../src/lib/walletApi", () => ({ loadWalletIfNeeded: () => {} }));
mock.module("../../src/lib/esplora", () => ({ getEsploraApiBaseUrl: () => undefined }));
mock.module("../../src/store/walletStore", () => ({ useWalletStore: () => {} }));
mock.module("../../src/store/profileStore", () => ({ useProfileStore: () => {} }));
mock.module("../../src/store/esploraStore", () => ({ useEsploraStore: () => {} }));
mock.module("../../src/hooks/useMarketData", () => ({
  getHistoricalBtcToFiatRate: async () => ({ isOk: () => false }),
}));
mock.module("../../src/lib/log", () => ({
  default: () => ({ d: () => {}, i: () => {}, w: () => {}, e: () => {} }),
}));

const { shouldIncludeMovement, transformMovementToTransaction } = await import(
  "../../src/hooks/useTransactions"
);
const { getTransactionDisplayLabel } = await import("../../src/lib/transactionHistory");

const movement = (subsystem, balances) => ({
  id: 7,
  status: "successful",
  subsystem,
  metadata_json: "{}",
  sent_to: [],
  received_on: [],
  input_vtxos: ["input-vtxo"],
  output_vtxos: ["output-vtxo"],
  exited_vtxos: [],
  time: { created_at: "2026-10-01T00:00:00Z" },
  ...balances,
});

const refresh = (feeSat) =>
  movement(
    { name: "bark.round", kind: "refresh" },
    { intended_balance_sat: 0, effective_balance_sat: -feeSat, offchain_fee_sat: feeSat },
  );

describe("VTXO refresh history", () => {
  test("shows a fee-paying refresh as an Ark VTXO refresh for its fee", async () => {
    const transaction = await transformMovementToTransaction(refresh(300), "USD");

    expect(shouldIncludeMovement(refresh(300))).toBe(true);
    expect(transaction.type).toBe("Arkoor");
    expect(transaction.movementKind).toBe("refresh");
    expect(transaction.amount).toBe(300);
    expect(getTransactionDisplayLabel(transaction)).toBe("VTXO Refresh");
  });

  test("keeps free refreshes hidden", () => {
    expect(shouldIncludeMovement(refresh(0))).toBe(false);
  });

  test("still shows an offboard as onchain", async () => {
    const offboard = movement(
      { name: "bark.offboard", kind: "offboard" },
      { intended_balance_sat: -50_000, effective_balance_sat: -50_300, offchain_fee_sat: 300 },
    );
    const transaction = await transformMovementToTransaction(offboard, "USD");

    expect(transaction.type).toBe("Onchain");
    expect(getTransactionDisplayLabel(transaction)).toBe("Offboard");
  });
});
