import { sourceText, type Translate } from "~/lib/i18n";
import type { BarkVtxo, ExitFeeEstimate } from "react-native-nitro-ark";

export const EXIT_ESTIMATE_MAX_AGE_MS = 30_000;
// Match Bark's P2TR_DUST check for starting a unilateral exit.
export const EXIT_DUST_LIMIT_SAT = 330;

export function isExitEligible(vtxo: BarkVtxo): boolean {
  return vtxo.state === "Spendable" && vtxo.amount >= EXIT_DUST_LIMIT_SAT;
}

export type NoahExitFeeEstimate = ExitFeeEstimate & { fundable: boolean };

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
  estimate?: NoahExitFeeEstimate;
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

export function exitFeeWarning(
  amountSat: number,
  estimate: ExitFeeEstimate,
  claimOnly = false,
  gt: Translate = sourceText,
) {
  if (estimate.claim_fee_sat >= amountSat) {
    return gt(
      "The estimated claim fee consumes the entire recovered value. A claim may not be possible.",
    );
  }
  if (!claimOnly && estimate.total_fee_sat >= amountSat) {
    return gt("Estimated total fees meet or exceed the value being recovered.");
  }
  return undefined;
}
