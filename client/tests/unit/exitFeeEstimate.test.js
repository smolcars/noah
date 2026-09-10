import { describe, expect, mock, test } from "bun:test";
import { QueryClient, QueryObserver } from "@tanstack/react-query";
import {
  EXIT_ESTIMATE_MAX_AGE_MS,
  exitEstimateKey,
  exitFeeWarning,
  exitReceiveAmount,
  isExitReviewCurrent,
} from "../../src/lib/exitFeeEstimate";

const estimate = {
  exit_broadcast_fee_sat: 12_000,
  claim_fee_sat: 800,
  total_fee_sat: 12_800,
  fee_rate_sat_per_vb: 5,
  txs_to_broadcast: 2,
  fundable: false,
};
const inputs = {
  walletId: "wallet-a",
  vtxoIds: ["a", "b"],
  amountSat: 100_000,
  scope: "wallet",
  revision: "1",
};

describe("emergency exit fee accounting", () => {
  test("deducts only the claim fee, regardless of broadcast funding", () => {
    expect(exitReceiveAmount(100_000, estimate.claim_fee_sat)).toBe(99_200);
    expect(exitFeeWarning(100_000, estimate)).toBeUndefined();
  });

  test("never displays negative proceeds when claim fees consume the value", () => {
    expect(exitReceiveAmount(800, 800)).toBe(0);
    expect(exitReceiveAmount(500, 800)).toBe(0);
    expect(exitFeeWarning(800, estimate, true)).toContain("entire recovered value");
  });

  test("warns about total costs at start without counting old broadcast fees at claim", () => {
    expect(exitFeeWarning(12_800, estimate)).toContain("meet or exceed");
    expect(exitFeeWarning(12_800, estimate, true)).toBeUndefined();
    expect(exitReceiveAmount(12_800, estimate.claim_fee_sat)).toBe(12_000);
  });
});

describe("exit review correspondence", () => {
  const review = {
    inputs,
    contextKey: exitEstimateKey(inputs),
    estimate,
    reviewedAt: 1000,
  };

  test("treats reordered VTXOs as the same set without mutating inputs", () => {
    const reordered = { ...inputs, vtxoIds: ["b", "a"] };
    expect(exitEstimateKey(reordered)).toBe(review.contextKey);
    expect(reordered.vtxoIds).toEqual(["b", "a"]);
  });

  test("rejects changed selection, scope, amount, wallet, destination, or wallet state", () => {
    for (const changed of [
      { vtxoIds: ["a"] },
      { vtxoIds: ["a", "b", "new-wallet-funds"] },
      { scope: "selected" },
      { amountSat: 200_000 },
      { walletId: "wallet-b" },
      { destinationAddress: "destination-b" },
      { revision: "2" },
    ]) {
      expect(isExitReviewCurrent(review, exitEstimateKey({ ...inputs, ...changed }), 1001)).toBe(
        false,
      );
    }
  });

  test("expires confirmations and applies the same input guard to the emergency fallback", () => {
    expect(isExitReviewCurrent(review, review.contextKey, 1001)).toBe(true);
    expect(isExitReviewCurrent(review, review.contextKey, 1000 + EXIT_ESTIMATE_MAX_AGE_MS)).toBe(
      false,
    );
    const fallback = { ...review, estimate: undefined };
    expect(isExitReviewCurrent(fallback, review.contextKey, 1001)).toBe(true);
    expect(isExitReviewCurrent(fallback, exitEstimateKey({ ...inputs, vtxoIds: [] }), 1001)).toBe(
      false,
    );
  });

  test("isolates late estimates after rapid selection and destination changes", async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    let resolveOld;
    const oldResult = new Promise((resolve) => {
      resolveOld = resolve;
    });
    const options = (context, queryFn) => ({
      queryKey: ["exit-fee-estimate", exitEstimateKey(context)],
      queryFn,
    });
    const observer = new QueryObserver(
      client,
      options(inputs, () => oldResult),
    );
    const unsubscribe = observer.subscribe(() => {});
    const oldFetch = client.fetchQuery(options(inputs, () => oldResult));
    const changed = {
      ...inputs,
      vtxoIds: ["b"],
      scope: "claim",
      destinationAddress: "destination-b",
    };
    const nextEstimate = { ...estimate, claim_fee_sat: 400 };
    observer.setOptions(options(changed, async () => nextEstimate));
    expect(observer.getCurrentResult().data).toBeUndefined();
    await client.fetchQuery(options(changed, async () => nextEstimate));
    resolveOld(estimate);
    await oldFetch;
    expect(observer.getCurrentResult().data).toEqual(nextEstimate);
    expect(isExitReviewCurrent(review, exitEstimateKey(changed), 1001)).toBe(false);
    unsubscribe();
    client.clear();
  });
});

const nativeEstimate = mock(async () => estimate);
const nativeSync = mock(async () => {});
const nativeProgress = mock(async () => []);
mock.module("react-native-nitro-ark", () => ({
  estimateEmergencyExitFee: nativeEstimate,
  syncExit: nativeSync,
  progressExits: nativeProgress,
}));
mock.module("../../src/lib/log", () => ({
  default: () => ({ d: () => {}, i: () => {}, w: () => {}, e: () => {} }),
}));
const { estimateEmergencyExitFee } = await import("../../src/lib/exitApi");

describe("read-only exit estimator boundary", () => {
  test("quotes the full set together with automatic rates and the exact claim destination", async () => {
    const result = await estimateEmergencyExitFee(["a", "b"], "claim-destination");
    expect(result.isOk()).toBe(true);
    expect(result.value).toEqual(estimate);
    expect(nativeEstimate).toHaveBeenLastCalledWith(["a", "b"], undefined, "claim-destination");
    expect(nativeSync).not.toHaveBeenCalled();
    expect(nativeProgress).not.toHaveBeenCalled();
  });

  test("does not require a destination for a new exit", async () => {
    await estimateEmergencyExitFee(["a", "b"]);
    expect(nativeEstimate).toHaveBeenLastCalledWith(["a", "b"], undefined, undefined);
  });

  test("returns recoverable estimator failures without progressing exits", async () => {
    nativeEstimate.mockRejectedValueOnce(new Error("Estimator unavailable"));
    const result = await estimateEmergencyExitFee(["a"]);
    expect(result.isErr()).toBe(true);
    expect(result.error.message).toBe("Estimator unavailable");
    expect(nativeSync).not.toHaveBeenCalled();
    expect(nativeProgress).not.toHaveBeenCalled();
  });
});
