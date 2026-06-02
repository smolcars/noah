import type { BarkArkInfo } from "react-native-nitro-ark";
import { err, ok, Result } from "neverthrow";
import { estimateBoardOffchainFee, estimateStandardOnchainTxFee } from "~/lib/paymentsApi";

export const AUTO_BOARD_FLOOR_AMOUNT = 20_000;
export const AUTO_BOARD_ONCHAIN_BUFFER_AMOUNT = 5_000;
const AUTO_BOARD_MAX_GROSS_ESTIMATE_ITERATIONS = 5;

export const getAutoBoardThreshold = (arkInfo: Pick<BarkArkInfo, "min_board_amount">): number =>
  Math.max(arkInfo.min_board_amount, AUTO_BOARD_FLOOR_AMOUNT);

export const formatAutoBoardThreshold = (amountSat: number): string =>
  `${amountSat.toLocaleString()} sats`;

export type AutoBoardPlan = {
  confirmedOnchainBalanceSat: number;
  minimumNetBoardAmountSat: number;
  minimumRequiredBalanceSat: number;
  grossBoardAmountSat: number;
  arkFeeSat: number;
  netBoardAmountSat: number;
  estimatedOnchainFeeSat: number;
  onchainBufferSat: number;
  estimatedRemainingOnchainSat: number;
  feeRateSatVb: number;
  estimatedVbytes: number;
};

const estimateGrossForMinimumNet = async (
  minimumNetBoardAmountSat: number,
): Promise<Result<{ grossAmountSat: number; arkFeeSat: number }, Error>> => {
  let grossAmountSat = minimumNetBoardAmountSat;
  let arkFeeSat = 0;

  for (let attempt = 0; attempt < AUTO_BOARD_MAX_GROSS_ESTIMATE_ITERATIONS; attempt += 1) {
    const estimateResult = await estimateBoardOffchainFee(grossAmountSat);
    if (estimateResult.isErr()) {
      return err(estimateResult.error);
    }

    const estimate = estimateResult.value;
    arkFeeSat = estimate.fee_sat;

    if (estimate.net_amount_sat >= minimumNetBoardAmountSat) {
      return ok({ grossAmountSat, arkFeeSat });
    }

    grossAmountSat += minimumNetBoardAmountSat - estimate.net_amount_sat;
  }

  return ok({ grossAmountSat, arkFeeSat });
};

export const buildAutoBoardPlan = async ({
  arkInfo,
  confirmedOnchainBalanceSat,
}: {
  arkInfo: Pick<BarkArkInfo, "min_board_amount">;
  confirmedOnchainBalanceSat: number;
}): Promise<Result<AutoBoardPlan | null, Error>> => {
  const minimumNetBoardAmountSat = getAutoBoardThreshold(arkInfo);
  const onchainFeeResult = await estimateStandardOnchainTxFee("regular");
  if (onchainFeeResult.isErr()) {
    return err(onchainFeeResult.error);
  }

  const grossMinimumResult = await estimateGrossForMinimumNet(minimumNetBoardAmountSat);
  if (grossMinimumResult.isErr()) {
    return err(grossMinimumResult.error);
  }

  const {
    fee_sat: estimatedOnchainFeeSat,
    fee_rate_sat_vb: feeRateSatVb,
    estimated_vbytes: estimatedVbytes,
  } = onchainFeeResult.value;
  const minimumRequiredBalanceSat =
    grossMinimumResult.value.grossAmountSat +
    estimatedOnchainFeeSat +
    AUTO_BOARD_ONCHAIN_BUFFER_AMOUNT;

  if (confirmedOnchainBalanceSat < minimumRequiredBalanceSat) {
    return ok(null);
  }

  const grossBoardAmountSat =
    confirmedOnchainBalanceSat - estimatedOnchainFeeSat - AUTO_BOARD_ONCHAIN_BUFFER_AMOUNT;
  const finalEstimateResult = await estimateBoardOffchainFee(grossBoardAmountSat);
  if (finalEstimateResult.isErr()) {
    return err(finalEstimateResult.error);
  }

  const finalEstimate = finalEstimateResult.value;
  if (finalEstimate.net_amount_sat < minimumNetBoardAmountSat) {
    return ok(null);
  }

  return ok({
    confirmedOnchainBalanceSat,
    minimumNetBoardAmountSat,
    minimumRequiredBalanceSat,
    grossBoardAmountSat: finalEstimate.gross_amount_sat,
    arkFeeSat: finalEstimate.fee_sat,
    netBoardAmountSat: finalEstimate.net_amount_sat,
    estimatedOnchainFeeSat,
    onchainBufferSat: AUTO_BOARD_ONCHAIN_BUFFER_AMOUNT,
    estimatedRemainingOnchainSat:
      confirmedOnchainBalanceSat - finalEstimate.gross_amount_sat - estimatedOnchainFeeSat,
    feeRateSatVb,
    estimatedVbytes,
  });
};
