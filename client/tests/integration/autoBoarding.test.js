import { afterAll, beforeEach, describe, expect, mock, spyOn, test } from "bun:test";
import * as React from "react";
import { err, ok } from "neverthrow";

let cursor = 0;
let needsRender = false;
let rendered;
let isReady = true;
const slots = [];
const effects = [];
let queryClient;
let queryClientContext;
let now = 1_000_000;
const clock = spyOn(Date, "now").mockImplementation(() => now);
afterAll(() => clock.mockRestore());
const appStateListeners = new Set();
const appState = {
  currentState: "active",
  addEventListener: (_event, listener) => {
    appStateListeners.add(listener);
    return { remove: () => appStateListeners.delete(listener) };
  },
};
let transactions = [];
let movements = [];
let pendingBoard = 0;
const storage = new Map();
const deposit = (txid, confirmed = true, amount = 150_000) => ({
  txid,
  has_confirmation: confirmed,
  balance_change_sat: amount,
});
const nativeBoard = mock(async () => ({ funding_txid: "board-tx", vtxos: [] }));
const nativeBoardAll = mock(async () => ({ funding_txid: "board-all-tx", vtxos: [] }));
const nativeTransactions = mock(async () => transactions);
const nativeHistory = mock(async () => movements);
const feeEstimate = mock(async (amount) => ({
  gross_amount_sat: amount,
  net_amount_sat: amount - 100,
  fee_sat: 100,
}));
const balance = { onchain: { confirmed: 20_000 } };
const readOnchainBalance = mock(async () => ok({ ...balance.onchain }));
const readArkInfo = mock(async () => ok(arkInfo));
const arkInfo = { min_board_amount: 20_000 };
let regularFeeRate = 1;
const showAlert = mock(() => {});

// Model state updates, effect dependencies and cleanup while rendering the real component.
const reactMock = {
  ...React,
  useContext: (context) => (context === queryClientContext ? queryClient : context._currentValue),
  useCallback: (fn, dependencies) => reactMock.useMemo(() => fn, dependencies),
  useMemo: (factory, dependencies) => {
    const index = cursor++;
    const previous = slots[index];
    if (!previous || dependencies.some((value, i) => !Object.is(value, previous.dependencies[i]))) {
      slots[index] = { value: factory(), dependencies };
    }
    return slots[index].value;
  },
  useSyncExternalStore: (subscribe, getSnapshot) => {
    reactMock.useEffect(
      () =>
        subscribe(() => {
          needsRender = true;
        }),
      [subscribe],
    );
    return getSnapshot();
  },
  useDebugValue() {},
  memo: (component) => component,
  useState: (initial) => {
    const index = cursor++;
    slots[index] ??= {
      value: typeof initial === "function" ? initial() : initial,
      set: (value) => {
        const next = typeof value === "function" ? value(slots[index].value) : value;
        if (!Object.is(next, slots[index].value)) {
          slots[index].value = next;
          needsRender = true;
        }
      },
    };
    return [slots[index].value, slots[index].set];
  },
  useRef: (initial) => {
    const index = cursor++;
    return (slots[index] ??= { current: initial });
  },
  useEffect: (effect, dependencies) => {
    const index = cursor++;
    const previous = slots[index];
    if (!previous || dependencies.some((value, i) => !Object.is(value, previous.dependencies[i]))) {
      effects.push(() => {
        previous?.cleanup?.();
        slots[index] = { dependencies, cleanup: effect() };
      });
    }
  },
};
mock.module("react", () => ({ ...reactMock, default: reactMock }));
mock.module("gt-react-native", () => ({
  useGT: () => (text) => text,
  T: () => null,
  Var: () => null,
}));
mock.module("react-native", () => ({ View: () => null, AppState: appState }));
mock.module("../../src/components/ConfirmationDialog", () => ({ ConfirmationDialog: () => null }));
mock.module("../../src/components/ui/text", () => ({ Text: () => null }));
mock.module("../../src/contexts/AlertProvider", () => ({ useAlert: () => ({ showAlert }) }));
mock.module("../../src/lib/mmkv", () => ({
  mmkv: {
    getString: (key) => storage.get(key),
    set: (key, value) => {
      storage.set(key, value);
    },
    remove: (key) => storage.delete(key),
  },
}));
mock.module("../../src/lib/log", () => ({ default: () => ({ d() {}, e() {}, w() {} }) }));
mock.module("../../src/lib/utils", () => ({
  cn: (...values) => values.join(" "),
  formatNumber: String,
}));
mock.module("../../src/hooks/useBitcoinAmountFormatter", () => ({
  useBitcoinAmountFormatter: () => (amount) => `${amount} sats`,
}));
mock.module("../../src/lib/walletApi", () => ({
  ...Object.fromEntries(
    [
      "createWallet",
      "deleteWallet",
      "loadWalletIfNeeded",
      "sync",
      "onchainSync",
      "maintenanceDelegated",
      "getVtxos",
      "getExpiringVtxos",
      "closeWalletIfLoaded",
      "syncPendingRounds",
      "refreshVtxosDelegated",
      "estimateRefreshFee",
    ].map((name) => [name, () => {}]),
  ),
  fetchOffchainBalance: async () => ok({ pending_board: pendingBoard }),
  fetchOnchainBalance: readOnchainBalance,
  getArkInfo: readArkInfo,
}));
mock.module("react-native-nitro-ark", () => ({
  ...Object.fromEntries(
    [
      "boardAmount",
      "boardAll",
      "syncPendingBoards",
      "offboardAll",
      "subscribeArkoorAddressMovements",
      "subscribeLightningPaymentMovements",
      "sendArkoorPayment",
      "validateArkoorAddress",
      "payLightningAddress",
      "payLightningOffer",
      "checkLightningPayment",
      "bolt11Invoice",
      "newAddress",
      "onchainAddress",
      "onchainIsMine",
      "payLightningInvoice",
      "onchainDrain",
      "onchainSend",
      "sendOnchain",
      "onchainFeeRates",
      "onchainTransactions",
      "estimateArkoorPaymentFee",
      "estimateLightningSendFee",
      "estimateBoardOffchainFee",
      "estimateSendOnchain",
      "estimateOffboardAll",
      "history",
      "updateHistoryMetadata",
      "tryClaimAllLightningReceives",
      "tryClaimLightningReceive",
      "peekAddress",
    ].map((name) => [name, () => {}]),
  ),
  boardAmount: nativeBoard,
  boardAll: nativeBoardAll,
  onchainTransactions: nativeTransactions,
  history: nativeHistory,
  estimateBoardOffchainFee: feeEstimate,
  onchainFeeRates: async () => ({ regular: regularFeeRate, fast: 2, slow: 1 }),
}));
// Keep the app's real useBalance/useBoardArk and TanStack useQuery/useMutation.
// Only their native, storage and unrelated module boundaries are replaced.
mock.module("../../src/store/serverStore", () => ({ useServerStore: () => ({}) }));
mock.module("../../src/store/backupStore", () => ({ useBackupStore: () => ({}) }));
mock.module("../../src/store/esploraStore", () => ({ useEsploraStore: () => ({}) }));
mock.module("../../src/lib/backupService", () => ({ restoreWallet() {} }));
mock.module("../../src/lib/api", () => ({ deregister() {} }));
mock.module("../../src/lib/recurringPayments", () => ({ clearAllRecurringPayments() {} }));
mock.module("../../src/lib/recurringBackgroundTask", () => ({ syncRecurringBackgroundTask() {} }));
mock.module("../../src/store/walletStore", () => ({
  useWalletStore: () => ({ isInitialized: true }),
}));
const tanstack = await import("@tanstack/react-query");
queryClientContext = tanstack.QueryClientContext;
({ queryClient } = await import("../../src/queryClient"));
queryClient.setDefaultOptions({ queries: { gcTime: Infinity }, mutations: { gcTime: Infinity } });
const { useTransactionStore } = await import("../../src/store/transactionStore");
const { boardArk, boardAllArk, estimateStandardOnchainTxFee } =
  await import("../../src/lib/paymentsApi");
const { buildAutoBoardPlan } = await import("../../src/lib/autoBoarding");
const store = () => useTransactionStore.getState();
const { AutoBoardingService } = await import("../../src/components/AutoBoardingService");

function render() {
  let count = 0;
  do {
    needsRender = false;
    cursor = 0;
    rendered = AutoBoardingService({ isReady });
    while (effects.length) effects.shift()();
    if (++count > 20) throw new Error("Auto-boarding render loop");
  } while (needsRender);
}
function unmount() {
  for (const slot of slots) slot?.cleanup?.();
  slots.length = 0;
  effects.length = 0;
}
async function settle() {
  for (let i = 0; i < 3; i++) {
    render();
    await new Promise((resolve) => setTimeout(resolve, 0));
  }
  render();
}
async function refresh() {
  now += 1;
  await queryClient.invalidateQueries({ queryKey: ["balance"] });
  await settle();
}
async function changeAppState(state) {
  appState.currentState = state;
  for (const listener of appStateListeners) listener(state);
  await settle();
}
async function rehydrate(state = JSON.parse(storage.get("transaction-storage"))) {
  unmount();
  useTransactionStore.setState({ handledAutoBoardFundingTxids: [] });
  storage.set("transaction-storage", JSON.stringify(state));
  await useTransactionStore.persist.rehydrate();
  await refresh();
}
beforeEach(() => {
  unmount();
  queryClient.clear();
  isReady = true;
  arkInfo.min_board_amount = 20_000;
  regularFeeRate = 1;
  appState.currentState = "active";
  now = 1_000_000;
  readOnchainBalance.mockReset();
  readOnchainBalance.mockImplementation(async () => ok({ ...balance.onchain }));
  readArkInfo.mockReset();
  readArkInfo.mockImplementation(async () => ok(arkInfo));
  store().reset();
  transactions = [deposit("deposit-1")];
  movements = [];
  pendingBoard = 0;
  balance.onchain.confirmed = 150_000;
  showAlert.mockClear();
  nativeBoard.mockReset();
  nativeBoard.mockImplementation(async () => ({ funding_txid: "board-tx", vtxos: [] }));
  nativeBoardAll.mockReset();
  nativeBoardAll.mockImplementation(async () => ({ funding_txid: "board-all-tx", vtxos: [] }));
  nativeHistory.mockReset();
  nativeHistory.mockImplementation(async () => movements);
  nativeTransactions.mockReset();
  nativeTransactions.mockImplementation(async () => transactions);
  feeEstimate.mockReset();
  feeEstimate.mockImplementation(async (amount) => ({
    gross_amount_sat: amount,
    net_amount_sat: amount - 100,
    fee_sat: 100,
  }));
});

describe("foreground prompts", () => {
  test.each([2, 3])("%s successive deposits each prompt and board once", async (count) => {
    for (let i = 1; i <= count; i++) {
      transactions.push(deposit(`deposit-${i}`));
      balance.onchain.confirmed = i * 150_000;
      await refresh();
      expect(rendered.props.open).toBe(true);
      expect(nativeBoard).toHaveBeenCalledTimes(i - 1);
      rendered.props.onConfirm();
      await settle();
      expect(nativeBoard).toHaveBeenCalledTimes(i);
      await refresh();
      expect(rendered.props.open).toBe(false);
      await rehydrate();
      expect(rendered.props.open).toBe(false);
    }
  });
  test("rerender, refresh and restart do not repeat dismissed funding", async () => {
    await refresh();
    rendered.props.onOpenChange(false);
    await settle();
    await refresh();
    expect(rendered.props.open).toBe(false);
    await rehydrate();
    expect(rendered.props.open).toBe(false);
  });
  test("restart with the dialog open does not repeat funding", async () => {
    await refresh();
    expect(rendered.props.open).toBe(true);
    await rehydrate();
    expect(rendered.props.open).toBe(false);
  });
  test("real balance query refetch preserves an open review even while loading", async () => {
    await refresh();
    const reviewedAmount =
      rendered.props.children.props.children[0].props.children[1].props.children;
    let finish;
    readOnchainBalance.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve;
        }),
    );
    const refreshing = queryClient.invalidateQueries({ queryKey: ["balance"] });
    await settle();
    expect(queryClient.getQueryState(["balance"]).fetchStatus).toBe("fetching");
    expect(rendered.props.open).toBe(true);
    now += 1;
    finish(ok({ ...balance.onchain }));
    await refreshing;
    await settle();
    expect(rendered.props.open).toBe(true);
    expect(rendered.props.children.props.children[0].props.children[1].props.children).toBe(
      reviewedAmount,
    );
    expect(
      queryClient
        .getQueryCache()
        .getAll()
        .map((query) => query.queryKey),
    ).toEqual([["balance"]]);
  });
  test.each(["fee", "ark-info"])(
    "%s failure backs off, keeps funding unhandled and recovers",
    async (source) => {
      if (source === "fee")
        feeEstimate.mockImplementation(async () => {
          throw new Error("offline");
        });
      else readArkInfo.mockImplementation(async () => err(new Error("offline")));
      await refresh();
      expect(rendered.props.open).toBe(false);
      expect(store().handledAutoBoardFundingTxids).toEqual([]);
      expect(showAlert).toHaveBeenCalledTimes(1);
      const attempts = readArkInfo.mock.calls.length;
      await refresh();
      expect(readArkInfo).toHaveBeenCalledTimes(attempts);
      now += 30_000;
      await refresh();
      expect(readArkInfo).toHaveBeenCalledTimes(attempts + 1);
      expect(showAlert).toHaveBeenCalledTimes(1);
      feeEstimate.mockImplementation(async (amount) => ({
        gross_amount_sat: amount,
        net_amount_sat: amount - 100,
        fee_sat: 100,
      }));
      readArkInfo.mockImplementation(async () => ok(arkInfo));
      now += 30_000;
      await refresh();
      expect(rendered.props.open).toBe(true);
      expect(store().handledAutoBoardFundingTxids).toEqual(["deposit-1"]);
    },
  );
  test.each([100_000, 20_000])(
    "outgoing spend to %s sats prevents stale submission",
    async (remaining) => {
      await refresh();
      balance.onchain.confirmed = remaining;
      transactions.push(deposit("outgoing", true, remaining - 150_000));
      rendered.props.onConfirm();
      await settle();
      expect(nativeBoard).not.toHaveBeenCalled();
      if (remaining === 100_000) {
        expect(rendered.props.open).toBe(true);
        rendered.props.onConfirm();
        await settle();
        expect(nativeBoard).toHaveBeenCalledWith(100_000 - 5_000 - 208);
      } else {
        expect(rendered.props.open).toBe(false);
        expect(showAlert).toHaveBeenCalledTimes(1);
      }
    },
  );
  test("fee changes require reviewing the new plan", async () => {
    await refresh();
    feeEstimate.mockImplementation(async (amount) => ({
      gross_amount_sat: amount,
      net_amount_sat: amount - 200,
      fee_sat: 200,
    }));
    rendered.props.onConfirm();
    await settle();
    expect(nativeBoard).not.toHaveBeenCalled();
    expect(rendered.props.open).toBe(true);
    rendered.props.onConfirm();
    await settle();
    expect(nativeBoard).toHaveBeenCalledTimes(1);
  });
  for (const stage of ["ark-info", "fee"]) {
    test.each(["balance", "funding", "pending-balance", "pending-movement"])(
      `${stage} planning delayed while %s changes cannot submit the stale plan`,
      async (change) => {
        await refresh();
        let finish;
        const planningRead = stage === "ark-info" ? readArkInfo : feeEstimate;
        planningRead.mockImplementationOnce(
          (amount) =>
            new Promise((resolve) => {
              finish = () =>
                resolve(
                  stage === "ark-info"
                    ? ok(arkInfo)
                    : { gross_amount_sat: amount, net_amount_sat: amount - 100, fee_sat: 100 },
                );
            }),
        );
        const confirm = rendered.props.onConfirm;
        confirm();
        await settle();
        expect(finish).toBeFunction();
        expect(nativeBoard).not.toHaveBeenCalled();
        if (change === "balance") balance.onchain.confirmed -= 2_000;
        if (change === "funding") transactions.push(deposit("deposit-2"));
        if (change === "pending-balance") pendingBoard = 100_000;
        if (change === "pending-movement") movements = [boardMovement(5, "pending")];
        confirm();
        finish();
        await settle();
        expect(nativeBoard).not.toHaveBeenCalled();
        if (change === "balance" || change === "funding") {
          // Moved funds get a fresh review of the current amount, not an error.
          expect(showAlert).not.toHaveBeenCalled();
          expect(rendered.props.open).toBe(true);
          if (change === "balance") {
            rendered.props.onConfirm();
            await settle();
            expect(nativeBoard).toHaveBeenCalledTimes(1);
            expect(nativeBoard).toHaveBeenCalledWith(148_000 - 5_000 - 208);
          }
        } else {
          expect(showAlert).toHaveBeenCalledWith({
            title: "Auto-Boarding Failed",
            description: "Unable to prepare Auto-Board. Please try again later.",
          });
          // Nothing was submitted, so the reviewed deposit is offered again later.
          expect(store().handledAutoBoardFundingTxids).not.toContain("deposit-1");
        }
      },
    );
  }
  test("the confirmation guard stays held during the final status read", async () => {
    await refresh();
    nativeTransactions.mockClear();
    let finish;
    nativeTransactions
      .mockImplementationOnce(async () => transactions)
      .mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            finish = resolve;
          }),
      );
    const confirm = rendered.props.onConfirm;
    confirm();
    await settle();
    expect(finish).toBeFunction();
    expect(nativeBoard).not.toHaveBeenCalled();
    confirm();
    await settle();
    expect(nativeTransactions).toHaveBeenCalledTimes(2);
    finish(transactions);
    await settle();
    expect(nativeBoard).toHaveBeenCalledTimes(1);
    expect(nativeBoard).toHaveBeenCalledWith(150_000 - 5_000 - 208);
  });
  test("a failed final status read cannot submit", async () => {
    await refresh();
    nativeTransactions
      .mockImplementationOnce(async () => transactions)
      .mockRejectedValueOnce(new Error("offline during final status read"));
    rendered.props.onConfirm();
    await settle();
    expect(nativeBoard).not.toHaveBeenCalled();
    expect(rendered.props.open).toBe(false);
    expect(showAlert).toHaveBeenCalledTimes(1);
    expect(store().handledAutoBoardFundingTxids).toEqual([]);
    await refresh();
    expect(rendered.props.open).toBe(false);
    now += 30_000;
    await refresh();
    expect(rendered.props.open).toBe(true);
  });
  test("onchain fee changes require a new review with the reserve intact", async () => {
    await refresh();
    regularFeeRate = 4;
    rendered.props.onConfirm();
    await settle();
    expect(nativeBoard).not.toHaveBeenCalled();
    expect(rendered.props.open).toBe(true);
    rendered.props.onConfirm();
    await settle();
    expect(nativeBoard).toHaveBeenCalledWith(150_000 - 5_000 - 4 * 208);
  });
  test.each([30_000, 200_000])(
    "server minimum changing to %s requires renewed eligibility",
    async (minimum) => {
      await refresh();
      arkInfo.min_board_amount = minimum;
      rendered.props.onConfirm();
      await settle();
      expect(nativeBoard).not.toHaveBeenCalled();
      expect(rendered.props.open).toBe(minimum === 30_000);
      if (minimum === 200_000) expect(showAlert).toHaveBeenCalledTimes(1);
    },
  );
  test("confirmation read failure closes without submitting and releases its funding", async () => {
    await refresh();
    nativeTransactions.mockImplementationOnce(async () => {
      throw new Error("offline");
    });
    rendered.props.onConfirm();
    await settle();
    expect(nativeBoard).not.toHaveBeenCalled();
    expect(rendered.props.open).toBe(false);
    expect(showAlert).toHaveBeenCalledTimes(1);
    expect(store().handledAutoBoardFundingTxids).toEqual([]);
    await refresh();
    expect(rendered.props.open).toBe(false);
    now += 30_000;
    await refresh();
    expect(rendered.props.open).toBe(true);
    rendered.props.onConfirm();
    await settle();
    expect(nativeBoard).toHaveBeenCalledTimes(1);
  });
  test("new funding at confirmation needs a new review", async () => {
    await refresh();
    transactions.push(deposit("deposit-2"));
    balance.onchain.confirmed += 150_000;
    rendered.props.onConfirm();
    await settle();
    expect(nativeBoard).not.toHaveBeenCalled();
    expect(rendered.props.open).toBe(true);
    expect(store().handledAutoBoardFundingTxids).toEqual(["deposit-1", "deposit-2"]);
  });
  test("No, turn off disables evaluation; re-enabling permits a new review", async () => {
    await refresh();
    rendered.props.onCancel();
    await settle();
    const reads = nativeTransactions.mock.calls.length;
    await refresh();
    expect(store().isAutoBoardingEnabled).toBe(false);
    expect(nativeTransactions).toHaveBeenCalledTimes(reads);
    store().setAutoBoardingEnabled(true);
    await refresh();
    expect(rendered.props.open).toBe(true);
  });
  test("background refresh cannot evaluate or claim funding", async () => {
    appState.currentState = "background";
    await refresh();
    expect(nativeTransactions).not.toHaveBeenCalled();
    expect(rendered.props.open).toBe(false);
    expect(store().handledAutoBoardFundingTxids).toEqual([]);
    await changeAppState("active");
    expect(rendered.props.open).toBe(true);
  });
  test("backgrounding during estimation does not claim unseen funding", async () => {
    let finish;
    feeEstimate.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve;
        }),
    );
    await refresh();
    await changeAppState("background");
    finish({ gross_amount_sat: 20_000, net_amount_sat: 19_900, fee_sat: 100 });
    await settle();
    expect(rendered.props.open).toBe(false);
    expect(store().handledAutoBoardFundingTxids).toEqual([]);
    await changeAppState("active");
    expect(rendered.props.open).toBe(true);
  });
  test("native AppState is rechecked even before its event is delivered", async () => {
    let finish;
    feeEstimate.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve;
        }),
    );
    await refresh();
    appState.currentState = "background";
    finish({ gross_amount_sat: 20_000, net_amount_sat: 19_900, fee_sat: 100 });
    await settle();
    expect(rendered.props.open).toBe(false);
    expect(store().handledAutoBoardFundingTxids).toEqual([]);
  });
  test.each(["disabled", "not-ready"])(
    "becoming %s during confirmation prevents submitting",
    async (reason) => {
      await refresh();
      let finish;
      readArkInfo.mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            finish = resolve;
          }),
      );
      rendered.props.onConfirm();
      await settle();
      if (reason === "disabled") store().setAutoBoardingEnabled(false);
      else isReady = false;
      await settle();
      finish(ok(arkInfo));
      await settle();
      expect(nativeBoard).not.toHaveBeenCalled();
      expect(rendered.props.open).toBe(false);
    },
  );
  test("backgrounding during confirm-time checks cannot submit", async () => {
    await refresh();
    let finish;
    readArkInfo.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve;
        }),
    );
    rendered.props.onConfirm();
    await settle();
    await changeAppState("background");
    finish(ok(arkInfo));
    await settle();
    expect(nativeBoard).not.toHaveBeenCalled();
  });
  test.each(["balance", "movement"])(
    "pending board in %s blocks until resolved",
    async (source) => {
      pendingBoard = source === "balance" ? 100_000 : 0;
      movements = source === "movement" ? [boardMovement(1, "pending")] : [];
      await refresh();
      expect(rendered.props.open).toBe(false);
      expect(store().handledAutoBoardFundingTxids).toEqual([]);
      pendingBoard = 0;
      movements = [boardMovement(1)];
      await refresh();
      expect(rendered.props.open).toBe(true);
    },
  );
  test("a pending board appearing after review prevents submission", async () => {
    await refresh();
    pendingBoard = 100_000;
    rendered.props.onConfirm();
    await settle();
    expect(nativeBoard).not.toHaveBeenCalled();
    expect(rendered.props.open).toBe(false);
  });
  test("unconfirmed and outgoing transactions never trigger prompts", async () => {
    transactions = [deposit("unconfirmed", false), deposit("outgoing", true, -100_000)];
    await refresh();
    expect(rendered.props.open).toBe(false);
    transactions[0].has_confirmation = true;
    await refresh();
    expect(rendered.props.open).toBe(true);
  });
  test("unconfirmed balance cannot make confirmed funding eligible", async () => {
    balance.onchain.confirmed = 20_000;
    balance.onchain.untrusted_pending = 150_000;
    await refresh();
    expect(rendered.props.open).toBe(false);
  });
  test("a deposit arriving with the dialog open survives dismissal", async () => {
    await refresh();
    transactions.push(deposit("deposit-2"));
    await refresh();
    expect(store().handledAutoBoardFundingTxids).toEqual(["deposit-1"]);
    rendered.props.onOpenChange(false);
    await settle();
    expect(rendered.props.open).toBe(true);
    expect(store().handledAutoBoardFundingTxids).toEqual(["deposit-1", "deposit-2"]);
  });
});
const boardMovement = (id, status = "successful") => ({
  id,
  status,
  subsystem: { name: "bark.board", kind: "board" },
});
describe("submission protection", () => {
  test("a rejected board call allows later manual and auto boards without a movement", async () => {
    nativeBoard.mockRejectedValueOnce(new Error("insufficient funds"));
    expect((await boardArk(100_000)).isErr()).toBe(true);
    expect((await boardAllArk()).isOk()).toBe(true);
    await refresh();
    expect(rendered.props.open).toBe(true);
    rendered.props.onConfirm();
    await settle();
    expect(nativeBoard).toHaveBeenCalledTimes(2);
    expect((await boardArk(50_000)).isOk()).toBe(true);
  });
  test("a rejected auto board allows the next deposit to prompt and submit", async () => {
    await refresh();
    nativeBoard.mockRejectedValueOnce(new Error("cosign failed"));
    rendered.props.onConfirm();
    await settle();
    expect(nativeBoard).toHaveBeenCalledTimes(1);
    transactions.push(deposit("deposit-2"));
    balance.onchain.confirmed += 150_000;
    await refresh();
    expect(rendered.props.open).toBe(true);
    rendered.props.onConfirm();
    await settle();
    expect(nativeBoard).toHaveBeenCalledTimes(2);
  });
  test.each(["amount", "all"])(
    "overlapping %s calls are blocked only until native settles",
    async (manual) => {
      let finish;
      nativeBoard.mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            finish = resolve;
          }),
      );
      const first = boardArk(100_000);
      expect((await (manual === "all" ? boardAllArk() : boardArk(50_000))).isErr()).toBe(true);
      expect(nativeBoard).toHaveBeenCalledTimes(1);
      expect(nativeBoardAll).not.toHaveBeenCalled();
      finish({ funding_txid: "board-tx", vtxos: [] });
      expect((await first).isOk()).toBe(true);
      expect((await boardAllArk()).isOk()).toBe(true);
      expect(JSON.parse(storage.get("transaction-storage")).state.boardSubmission).toBeUndefined();
    },
  );
  test("a rejected MAX board releases the guard without waiting for a movement", async () => {
    nativeBoardAll.mockImplementationOnce(async () => {
      throw new Error("fee error");
    });
    expect((await boardAllArk()).isErr()).toBe(true);
    expect((await boardArk(50_000)).isOk()).toBe(true);
    expect((await boardAllArk()).isOk()).toBe(true);
  });
  test("a synchronous native exception also releases the guard", async () => {
    nativeBoard.mockImplementationOnce(() => {
      throw new Error("native unavailable");
    });
    expect((await boardArk(100_000)).isErr()).toBe(true);
    expect((await boardAllArk()).isOk()).toBe(true);
  });
  test("double confirmation does not submit twice", async () => {
    await refresh();
    const confirm = rendered.props.onConfirm;
    confirm();
    confirm();
    await settle();
    expect(nativeBoard).toHaveBeenCalledTimes(1);
  });
});
describe("store migration", () => {
  test.each([
    [0, true],
    [0, false],
    [1, true],
    [1, false],
  ])("v%s attempted=%s allows one review for stuck funding", async (version, attempted) => {
    await rehydrate({
      version,
      state: { isAutoBoardingEnabled: true, hasAttemptedAutoBoarding: attempted },
    });
    expect(rendered.props.open).toBe(true);
    expect(store().handledAutoBoardFundingTxids).toEqual(["deposit-1"]);
    expect(store().hasAttemptedAutoBoarding).toBeUndefined();
    await rehydrate();
    expect(rendered.props.open).toBe(false);
    expect(JSON.parse(storage.get("transaction-storage")).version).toBe(2);
  });
  test("v1 migration waits for a pending board and preserves disabled setting", async () => {
    pendingBoard = 100_000;
    await rehydrate({
      version: 1,
      state: { isAutoBoardingEnabled: true, hasAttemptedAutoBoarding: true },
    });
    expect(rendered.props.open).toBe(false);
    pendingBoard = 0;
    await refresh();
    expect(rendered.props.open).toBe(true);
    await rehydrate({
      version: 1,
      state: { isAutoBoardingEnabled: false, hasAttemptedAutoBoarding: true },
    });
    expect(store().isAutoBoardingEnabled).toBe(false);
    expect(rendered.props.open).toBe(false);
  });
});
describe("unchanged eligibility boundaries", () => {
  test.each([10_000, 20_000, 30_000])(
    "net minimum and 5,000 sat reserve at server minimum %s",
    async (serverMinimum) => {
      const minimum = Math.max(serverMinimum, 20_000);
      const fee = (await estimateStandardOnchainTxFee("regular")).value.fee_sat;
      const threshold = minimum + 100 + fee + 5_000;
      const below = await buildAutoBoardPlan({
        arkInfo: { min_board_amount: serverMinimum },
        confirmedOnchainBalanceSat: threshold - 1,
      });
      expect(below.value).toBeNull();
      const at = await buildAutoBoardPlan({
        arkInfo: { min_board_amount: serverMinimum },
        confirmedOnchainBalanceSat: threshold,
      });
      expect(at.value.netBoardAmountSat).toBe(minimum);
      expect(at.value.estimatedRemainingOnchainSat).toBe(5_000);
    },
  );
});
