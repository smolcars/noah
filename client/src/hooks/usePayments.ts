import { useMutation, useQuery } from "@tanstack/react-query";
import { useAlert } from "~/contexts/AlertProvider";
import {
  newAddress,
  onchainAddress,
  boardArk,
  bolt11Invoice,
  onchainSend,
  sendOnchainFromOffchain,
  sendArkoorPayment,
  payLightningInvoice,
  payLightningAddress,
  payLightningOffer,
  type ArkoorPaymentResult,
  type LightningPayment,
  type NoahOnchainPaymentResult,
  type OnchainSendSource,
  type BarkFeeEstimate,
  type OnchainWalletFeeEstimate,
  boardAllArk,
  offboardAllArk,
  estimateArkoorPaymentFee,
  estimateLightningSendFee,
  estimateSendOnchainFee,
  estimateOnchainWalletSendFee,
  estimateOffboardAllFee,
  estimateBoardOffchainFee,
  estimateStandardOnchainTxFee,
  type StandardOnchainWalletFeeEstimate,
} from "../lib/paymentsApi";
import { queryClient } from "~/queryClient";
import { DestinationTypes } from "~/lib/sendUtils";
import logger from "~/lib/log";
import ky from "ky";
import { Result } from "neverthrow";
import { getLnurlDomain } from "~/constants";

const log = logger("usePayments");

interface LnurlpDefaultResponse {
  callback: string;
  maxSendable: number;
  minSendable: number;
  metadata: string;
  tag: "payRequest";
  commentAllowed: number;
}

interface LnurlpInvoiceResponse {
  pr: string;
  routes: string[];
  ark?: string;
}

export function useGenerateOffchainAddress() {
  const { showAlert } = useAlert();

  return useMutation({
    mutationFn: async () => {
      const result = await newAddress();
      if (result.isErr()) {
        throw result.error;
      }
      return result.value.address;
    },
    onError: (error: Error) => {
      showAlert({ title: "Vtxo Pubkey Generation Failed", description: error.message });
    },
  });
}

export function useGenerateOnchainAddress() {
  const { showAlert } = useAlert();

  return useMutation({
    mutationFn: async () => {
      const result = await onchainAddress();
      if (result.isErr()) {
        throw result.error;
      }
      return result.value;
    },
    onError: (error: Error) => {
      showAlert({ title: "On-chain Address Generation Failed", description: error.message });
    },
  });
}

export function useGenerateLightningInvoice() {
  const { showAlert } = useAlert();

  return useMutation({
    mutationFn: async (amount: number) => {
      const result = await bolt11Invoice(amount);
      if (result.isErr()) {
        throw result.error;
      }
      return result.value;
    },
    onError: (error: Error) => {
      showAlert({ title: "Lightning Invoice Generation Failed", description: error.message });
    },
  });
}

export function useBoardArk() {
  const { showAlert } = useAlert();

  return useMutation({
    mutationFn: async (amount: number) => {
      const result = await boardArk(amount);
      if (result.isErr()) {
        throw result.error;
      }
      return result.value;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["balance"] });
      queryClient.invalidateQueries({ queryKey: ["boarding-transactions"] });
    },
    onError: (error: Error) => {
      showAlert({ title: "Boarding Failed", description: error.message });
    },
  });
}

export function useBoardAllAmountArk() {
  const { showAlert } = useAlert();

  return useMutation({
    mutationFn: async () => {
      const result = await boardAllArk();
      if (result.isErr()) {
        throw result.error;
      }
      return result.value;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["balance"] });
      queryClient.invalidateQueries({ queryKey: ["boarding-transactions"] });
    },
    onError: (error: Error) => {
      showAlert({ title: "Boarding Failed", description: error.message });
    },
  });
}

export function useOffboardAllArk() {
  const { showAlert } = useAlert();

  return useMutation({
    mutationFn: async (address: string) => {
      const result = await offboardAllArk(address);
      if (result.isErr()) {
        throw result.error;
      }
      return result.value;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["balance"] });
      queryClient.invalidateQueries({ queryKey: ["boarding-transactions"] });
    },
    onError: (error: Error) => {
      showAlert({ title: "Offboarding Failed", description: error.message });
    },
  });
}

type SendVariables = {
  destination: string;
  amountSat: number | undefined;
  resolvedAmountSat: number;
  comment: string | null;
  onchainSource?: OnchainSendSource;
  btcPrice?: number;
};

type SendResult = ArkoorPaymentResult | LightningPayment | NoahOnchainPaymentResult;

export type SendFeeEstimateParams =
  | {
      method: "ark" | "lightning";
      amountSat: number;
    }
  | {
      method: "onchain";
      source: OnchainSendSource;
      destination: string;
      amountSat: number;
    };

export type SendFeeEstimate = BarkFeeEstimate | OnchainWalletFeeEstimate;

const readEstimateResult = async <T>(estimatePromise: Promise<Result<T, Error>>): Promise<T> => {
  const result = await estimatePromise;
  if (result.isErr()) {
    throw result.error;
  }

  return result.value;
};

export function useSendFeeEstimate(params: SendFeeEstimateParams | null) {
  return useQuery({
    queryKey: ["fee-estimate", "send", params],
    queryFn: async () => {
      if (!params) {
        throw new Error("Fee estimate parameters are required");
      }

      switch (params.method) {
        case "ark":
          return readEstimateResult(estimateArkoorPaymentFee(params.amountSat));
        case "lightning":
          return readEstimateResult(estimateLightningSendFee(params.amountSat));
        case "onchain":
          return params.source === "offchain"
            ? readEstimateResult(
                estimateSendOnchainFee({
                  destination: params.destination,
                  amountSat: params.amountSat,
                }),
              )
            : readEstimateResult(estimateOnchainWalletSendFee({ amountSat: params.amountSat }));
      }
    },
    enabled: params !== null && params.amountSat > 0,
    staleTime: 20 * 1000,
    retry: false,
  });
}

export type BoardArkFeeEstimate = BarkFeeEstimate & {
  estimated_onchain_fee_sat: number;
  estimated_remaining_onchain_sat: number;
  fee_rate_sat_vb: StandardOnchainWalletFeeEstimate["fee_rate_sat_vb"];
  estimated_vbytes: StandardOnchainWalletFeeEstimate["estimated_vbytes"];
  fee_rate_tier: StandardOnchainWalletFeeEstimate["fee_rate_tier"];
  is_max_amount: boolean;
};

export type BoardArkFeeEstimateUnavailable = {
  reason: "below_minimum_board_amount";
  minimum_board_amount_sat: number;
  boardable_amount_sat: number;
  estimated_onchain_fee_sat: number;
  minimum_required_balance_sat: number;
  estimated_vbytes: number;
  fee_rate_sat_vb: number;
  fee_rate_tier: StandardOnchainWalletFeeEstimate["fee_rate_tier"];
  is_max_amount: boolean;
};

export type BoardArkFeeEstimateResult =
  | { kind: "estimate"; estimate: BoardArkFeeEstimate }
  | { kind: "unavailable"; unavailable: BoardArkFeeEstimateUnavailable };

type BoardArkFeeEstimateParams = {
  amountSat: number;
  confirmedOnchainBalanceSat: number;
  isMaxAmount: boolean;
  minimumBoardAmountSat: number;
};

export function useBoardArkFeeEstimate(params: BoardArkFeeEstimateParams | null) {
  return useQuery({
    queryKey: ["fee-estimate", "board-ark", params],
    queryFn: async (): Promise<BoardArkFeeEstimateResult> => {
      if (!params) {
        throw new Error("Boarding fee estimate parameters are required");
      }

      const onchainEstimate = await readEstimateResult(estimateStandardOnchainTxFee("regular"));
      const grossBoardAmountSat = params.isMaxAmount
        ? Math.max(params.confirmedOnchainBalanceSat - onchainEstimate.fee_sat, 0)
        : params.amountSat;

      if (grossBoardAmountSat < params.minimumBoardAmountSat) {
        return {
          kind: "unavailable",
          unavailable: {
            reason: "below_minimum_board_amount",
            minimum_board_amount_sat: params.minimumBoardAmountSat,
            boardable_amount_sat: grossBoardAmountSat,
            estimated_onchain_fee_sat: onchainEstimate.fee_sat,
            minimum_required_balance_sat: params.minimumBoardAmountSat + onchainEstimate.fee_sat,
            estimated_vbytes: onchainEstimate.estimated_vbytes,
            fee_rate_sat_vb: onchainEstimate.fee_rate_sat_vb,
            fee_rate_tier: onchainEstimate.fee_rate_tier,
            is_max_amount: params.isMaxAmount,
          },
        };
      }

      const boardEstimate = await readEstimateResult(estimateBoardOffchainFee(grossBoardAmountSat));

      return {
        kind: "estimate",
        estimate: {
          ...boardEstimate,
          estimated_onchain_fee_sat: onchainEstimate.fee_sat,
          estimated_remaining_onchain_sat:
            params.confirmedOnchainBalanceSat -
            boardEstimate.gross_amount_sat -
            onchainEstimate.fee_sat,
          fee_rate_sat_vb: onchainEstimate.fee_rate_sat_vb,
          estimated_vbytes: onchainEstimate.estimated_vbytes,
          fee_rate_tier: onchainEstimate.fee_rate_tier,
          is_max_amount: params.isMaxAmount,
        },
      };
    },
    enabled: params !== null && params.amountSat > 0 && params.confirmedOnchainBalanceSat > 0,
    staleTime: 20 * 1000,
    retry: false,
  });
}

export function useOffboardAllFeeEstimate(destinationAddress: string | null) {
  return useQuery({
    queryKey: ["fee-estimate", "offboard-all", destinationAddress],
    queryFn: async () => {
      if (!destinationAddress) {
        throw new Error("Destination address is required");
      }

      return readEstimateResult(estimateOffboardAllFee(destinationAddress));
    },
    enabled: !!destinationAddress,
    staleTime: 20 * 1000,
    retry: false,
  });
}

const readLightningPayment = async (
  paymentPromise: Promise<Result<LightningPayment, Error>>,
): Promise<LightningPayment> => {
  const result = await paymentPromise;

  if (result.isErr()) {
    log.e("readLightningPayment error", [result.error]);
    throw result.error;
  }

  if (result.value.state !== "paid") {
    log.w("Lightning payment did not complete", [result.value]);
    throw new Error("Lightning payment did not complete.");
  }

  return result.value;
};

export function useSend(destinationType: DestinationTypes) {
  return useMutation<SendResult, Error, SendVariables>({
    mutationFn: async (variables) => {
      const { destination, amountSat, comment, onchainSource } = variables;
      if (amountSat === undefined && destinationType !== "lightning") {
        throw new Error("Amount is required");
      }

      let result;
      switch (destinationType) {
        case "onchain":
          if (amountSat === undefined) {
            throw new Error("Amount is required for onchain payments");
          }
          result =
            onchainSource === "offchain"
              ? await sendOnchainFromOffchain({ destination, amountSat })
              : await onchainSend({ destination, amountSat });
          break;
        case "ark":
          if (amountSat === undefined) {
            throw new Error("Amount is required for Ark payments");
          }
          result = await sendArkoorPayment(destination, amountSat);
          break;
        case "lightning":
          return readLightningPayment(payLightningInvoice(destination, amountSat));
        case "lnurl": {
          if (amountSat === undefined) {
            throw new Error("Amount is required for LNURL payments");
          }

          if (destination.toLowerCase().endsWith(getLnurlDomain())) {
            const noahResult = await handleNoahWalletPayment(destination, amountSat, comment);
            if (noahResult) {
              if (noahResult.isErr()) {
                throw noahResult.error;
              }
              const data = noahResult.value;
              if ("payment_hash" in data) {
                return readLightningPayment(
                  Promise.resolve(noahResult as Result<LightningPayment, Error>),
                );
              }
              return data;
            }
          }

          return readLightningPayment(payLightningAddress(destination, amountSat, comment || ""));
        }
        case "offer":
          return readLightningPayment(payLightningOffer(destination, amountSat));
        default:
          throw new Error("Invalid destination type");
      }

      if (result.isErr()) {
        throw result.error;
      }
      return result.value;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["balance"] });
      queryClient.invalidateQueries({ queryKey: ["transactions"] });
    },
  });
}

async function handleNoahWalletPayment(
  destination: string,
  amountSat: number,
  comment: string | null,
): Promise<Result<
  ArkoorPaymentResult | LightningPayment | NoahOnchainPaymentResult,
  Error
> | null> {
  try {
    const [user, domain] = destination.split("@");
    const lnurlEndpoint = `https://${domain}/.well-known/lnurlp/${user}`;
    const lnurlJson = await ky.get(lnurlEndpoint).json<LnurlpDefaultResponse>();

    if (lnurlJson.tag === "payRequest" && lnurlJson.callback) {
      const callbackUrl = new URL(lnurlJson.callback);
      callbackUrl.searchParams.append("amount", (amountSat * 1000).toString());
      callbackUrl.searchParams.append("wallet", "noahwallet");
      if (comment) {
        callbackUrl.searchParams.append("comment", comment);
      }

      const callbackJson = await ky.get(callbackUrl.toString()).json<LnurlpInvoiceResponse>();

      if (callbackJson.ark) {
        log.d("Paying via Ark direct payment");
        return await sendArkoorPayment(callbackJson.ark, amountSat);
      } else if (callbackJson.pr) {
        log.d("Paying via Lightning Invoice from LNURL");
        const lnResult = await payLightningInvoice(callbackJson.pr, amountSat);
        return lnResult;
      } else {
        log.w(
          "Invalid LNURL callback response for optimized Noah payment, falling back to standard LNURL.",
        );
      }
    }
  } catch (e) {
    log.w("Failed optimized Noah payment, falling back to standard LNURL", [e]);
  }
  return null;
}
