import type { ExitFeeEstimate } from "react-native-nitro-ark";

export const EXIT_ESTIMATE_MAX_AGE_MS = 30_000;

export type ExitEstimateInputs = {
  walletId: string | null;
  vtxoIds: string[];
  amountSat: number;
  scope: "wallet" | "selected" | "claim";
  destinationAddress?: string;
  revision: string;
};

export type ExitFeeReview = {
  inputs: ExitEstimateInputs;
  contextKey: string;
  estimate?: ExitFeeEstimate;
  reviewedAt: number;
};

export function exitEstimateKey(inputs: ExitEstimateInputs): string {
  return JSON.stringify({ ...inputs, vtxoIds: [...inputs.vtxoIds].sort() });
}

export function isExitReviewCurrent(
  review: ExitFeeReview,
  contextKey: string,
  now = Date.now(),
): boolean {
  return review.contextKey === contextKey && now - review.reviewedAt < EXIT_ESTIMATE_MAX_AGE_MS;
}

export function exitReceiveAmount(amountSat: number, claimFeeSat: number): number {
  // Broadcast fees are funded separately by confirmed onchain UTXOs.
  return Math.max(0, amountSat - claimFeeSat);
}

export function exitFeeWarning(amountSat: number, estimate: ExitFeeEstimate, claimOnly = false) {
  if (estimate.claim_fee_sat >= amountSat) {
    return "The estimated claim fee consumes the entire recovered value. A claim may not be possible.";
  }
  if (!claimOnly && estimate.total_fee_sat >= amountSat) {
    return "Estimated total fees meet or exceed the value being recovered.";
  }
  return undefined;
}
