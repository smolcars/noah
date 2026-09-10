import { afterEach, describe, expect, mock, test } from "bun:test";
import * as ReactQuery from "@tanstack/react-query";
import { ok } from "neverthrow";

const { QueryClient, QueryObserver } = ReactQuery;

const readyWallet = {
  isInitialized: true,
  isWalletLoaded: true,
  isWalletSuspended: false,
  isBackgroundJobRunning: false,
  staticVtxoPubkey: "wallet-a",
};
let wallet = { ...readyWallet };
const nativeReads = [];
const read = (name, value) => async () => {
  nativeReads.push(name);
  return ok(value);
};
const useWalletStore = () => wallet;
useWalletStore.getState = () => wallet;

// Capture the actual hook options; exercise their lifecycle with a real QueryObserver.
mock.module("@tanstack/react-query", () => ({ ...ReactQuery, useQuery: (options) => options }));
mock.module("react-native", () => ({ AppState: {} }));
mock.module("@react-navigation/native", () => ({ useIsFocused: () => true }));
mock.module("../../src/store/walletStore", () => ({ useWalletStore }));
mock.module("../../src/contexts/AlertProvider", () => ({ useAlert: () => ({}) }));
mock.module("../../src/hooks/useMarketData", () => ({ getBlockHeight: async () => ok(100) }));
mock.module("../../src/lib/log", () => ({ default: () => ({ d() {} }) }));
mock.module("../../src/lib/walletApi", () => ({ getVtxos: read("getVtxos", []) }));
const unexpectedAction = () => {
  throw new Error("Reading the overview must not execute exit actions");
};
mock.module("../../src/lib/exitApi", () => ({
  cancelExit: unexpectedAction,
  claimExits: unexpectedAction,
  estimateEmergencyExitFee: unexpectedAction,
  progressExits: unexpectedAction,
  startExitForVtxos: unexpectedAction,
  syncExit: unexpectedAction,
  getExitVtxos: read("getExitVtxos", [{ vtxo_id: "exit-a" }]),
  getExitStatus: read("getExitStatus", { state: "Processing" }),
  listClaimable: read("listClaimable", []),
  hasPendingExits: read("hasPendingExits", true),
  pendingExitTotal: read("pendingExitTotal", 1000),
  allClaimableAtHeight: read("allClaimableAtHeight", 110),
}));
const { useExitOverview } = await import("../../src/hooks/useUnilateralExit");

let client;
function queryOptions() {
  return useExitOverview();
}

afterEach(() => {
  client?.clear();
  nativeReads.length = 0;
  wallet = { ...readyWallet };
});

describe("exit overview wallet coordination", () => {
  test("does not read on mount or invalidation during a background job, then resumes", async () => {
    client = new QueryClient();
    wallet.isBackgroundJobRunning = true;
    const observer = new QueryObserver(client, queryOptions());
    const unsubscribe = observer.subscribe(() => {});
    await client.invalidateQueries({ queryKey: ["exit-overview"] });
    expect(nativeReads).toEqual([]);

    wallet.isBackgroundJobRunning = false;
    observer.setOptions(queryOptions());
    expect(nativeReads).toContain("getExitVtxos");
    const result = await observer.refetch();
    expect(result.error).toBeNull();
    expect(result.data.spendableVtxos).toEqual([]);
    expect(nativeReads).toContain("getExitVtxos");
    expect(nativeReads).toContain("getExitStatus");
    expect(nativeReads).toContain("getVtxos");
    unsubscribe();
  });

  test("blocks explicit refetch through a stale observer when the wallet becomes unavailable", async () => {
    for (const unavailable of [
      { isBackgroundJobRunning: true },
      { isWalletSuspended: true },
      { isWalletLoaded: false },
      { isInitialized: false },
    ]) {
      client = new QueryClient();
      wallet = { ...readyWallet };
      const observer = new QueryObserver(client, queryOptions());
      wallet = { ...readyWallet, ...unavailable };
      const result = await observer.refetch();
      expect(nativeReads).toEqual([]);
      expect(result.isError).toBe(true);
      client.clear();
    }
  });
});
