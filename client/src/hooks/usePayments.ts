import { useMutation, useQuery } from "@tanstack/react-query";
import { useAlert } from "~/contexts/AlertProvider";
import {
  newAddress,
  onchainAddress,
  onchainIsMine,
  boardArk,
  bolt11Invoice,
  onchainDrain,
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
  validateArkoorPaymentAddress,
  type StandardOnchainWalletFeeEstimate,
} from "../lib/paymentsApi";
import { queryClient } from "~/queryClient";
import { DestinationTypes } from "~/lib/sendUtils";
import { getArkInfo } from "~/lib/walletApi";
import logger from "~/lib/log";
import ky from "ky";
import { Result } from "neverthrow";

const log = logger("usePayments");

interface LnurlpDefaultResponse {
  callback: string;
  maxSendable: number;
  minSendable: number;
  metadata: string;
  tag: "payRequest";
  commentAllowed: number;
  ark?: string;
}

export type LightningAddressPaymentRoute =
  | {
      method: "ark";
      destination: string;
      minSendableMsat: number;
      maxSendableMsat: number;
    }
  | {
      method: "lightning";
      minSendableMsat: number;
      maxSendableMsat: number;
    };

const parseLightningAddress = (destination: string) => {
  const normalized = destination
    .trim()
    .toLowerCase()
    .replace(/^lightning:/, "");
  const parts = normalized.split("@");
  if (parts.length !== 2 || !parts[0] || !parts[1]) {
    return null;
  }

  return { username: parts[0], domain: parts[1] };
};

const fetchLnurlpResponse = async (url: URL): Promise<LnurlpDefaultResponse> => {
  const response = await ky.get(url.toString()).json<LnurlpDefaultResponse>();
  if (response.tag !== "payRequest" || !response.callback) {
    throw new Error("Invalid LNURL response for lightning address payment");
  }

  return response;
};

const paymentRouteFromLnurlpResponse = async (
  response: LnurlpDefaultResponse,
  acceptArkAddress: boolean,
): Promise<LightningAddressPaymentRoute> => {
  const limits = {
    minSendableMsat: response.minSendable,
    maxSendableMsat: response.maxSendable,
  };

  if (acceptArkAddress && response.ark) {
    const validationResult = await validateArkoorPaymentAddress(response.ark);
    if (validationResult.isOk()) {
      return { method: "ark", destination: response.ark, ...limits };
    }

    log.w("Ignoring incompatible Ark address returned by LNURL provider", [validationResult.error]);
  }

  return { method: "lightning", ...limits };
};

export const resolveLightningAddressPaymentRoute = async (
  destination: string,
): Promise<LightningAddressPaymentRoute> => {
  const parsed = parseLightningAddress(destination);
  if (!parsed) {
    throw new Error("Destination is not a lightning address");
  }

  const lnurlEndpoint = new URL(`https://${parsed.domain}/.well-known/lnurlp/${parsed.username}`);
  const arkInfoResult = await getArkInfo();
  if (arkInfoResult.isErr()) {
    log.w("Unable to load Ark server info, using standard LNURL discovery", [arkInfoResult.error]);
    const response = await fetchLnurlpResponse(lnurlEndpoint);
    return paymentRouteFromLnurlpResponse(response, false);
  }

  const arkLnurlEndpoint = new URL(lnurlEndpoint);
  arkLnurlEndpoint.searchParams.set("ark", arkInfoResult.value.server_pubkey);

  const response = await fetchLnurlpResponse(arkLnurlEndpoint);
  return paymentRouteFromLnurlpResponse(response, true);
};

export function useLightningAddressPaymentRoute(destination: string | null) {
  return useQuery({
    queryKey: ["payment-route", "lightning-address", destination],
    queryFn: () => {
      if (!destination) {
        throw new Error("Lightning address payment destination is required");
      }

      return resolveLightningAddressPaymentRoute(destination);
    },
    enabled: destination !== null,
    staleTime: 0,
    retry: false,
  });
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
      queryClient.invalidateQueries({ queryKey: ["transactions"] });
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
      queryClient.invalidateQueries({ queryKey: ["transactions"] });
    },
    onError: (error: Error) => {
      showAlert({ title: "Boarding Failed", description: error.message });
    },
  });
}

type SendVariables = {
  destination: string;
  amountSat: number | undefined;
  resolvedAmountSat: number;
  isMaxAmount?: boolean;
  comment: string | null;
  onchainSource?: OnchainSendSource;
  lightningAddressPaymentRoute?: LightningAddressPaymentRoute;
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
      isMaxAmount?: boolean;
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
          return params.source === "offchain" && params.isMaxAmount
            ? readEstimateResult(estimateOffboardAllFee(params.destination))
            : params.source === "offchain"
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
  is_below_minimum_board_amount: boolean;
  minimum_board_amount_sat: number;
};

export type BoardArkFeeEstimateUnavailable = {
  reason: "below_minimum_board_amount";
  minimum_board_amount_sat: number;
  boardable_amount_sat: number;
  estimated_onchain_fee_sat: number;
  estimated_remaining_onchain_sat: number;
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
      const isBelowMinimumBoardAmount = grossBoardAmountSat < params.minimumBoardAmountSat;

      const boardEstimateResult = await estimateBoardOffchainFee(grossBoardAmountSat);
      if (boardEstimateResult.isErr()) {
        if (!isBelowMinimumBoardAmount) {
          throw boardEstimateResult.error;
        }

        return {
          kind: "unavailable",
          unavailable: {
            reason: "below_minimum_board_amount",
            minimum_board_amount_sat: params.minimumBoardAmountSat,
            boardable_amount_sat: grossBoardAmountSat,
            estimated_onchain_fee_sat: onchainEstimate.fee_sat,
            estimated_remaining_onchain_sat:
              params.confirmedOnchainBalanceSat - grossBoardAmountSat - onchainEstimate.fee_sat,
            minimum_required_balance_sat: params.minimumBoardAmountSat + onchainEstimate.fee_sat,
            estimated_vbytes: onchainEstimate.estimated_vbytes,
            fee_rate_sat_vb: onchainEstimate.fee_rate_sat_vb,
            fee_rate_tier: onchainEstimate.fee_rate_tier,
            is_max_amount: params.isMaxAmount,
          },
        };
      }

      const boardEstimate = boardEstimateResult.value;

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
          is_below_minimum_board_amount: isBelowMinimumBoardAmount,
          minimum_board_amount_sat: params.minimumBoardAmountSat,
        },
      };
    },
    enabled: params !== null && params.amountSat > 0 && params.confirmedOnchainBalanceSat > 0,
    staleTime: 20 * 1000,
    retry: false,
  });
}

export function useIsOnchainAddressMine(address: string | null) {
  return useQuery({
    queryKey: ["is-onchain-address-mine", address],
    queryFn: async () => {
      if (!address) {
        throw new Error("Address is required");
      }

      const result = await onchainIsMine(address);
      if (result.isErr()) {
        throw result.error;
      }
      return result.value;
    },
    enabled: !!address,
    staleTime: Infinity,
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

const sendLightningAddressPayment = async (
  route: LightningAddressPaymentRoute,
  destination: string,
  amountSat: number,
  comment: string | null,
): Promise<ArkoorPaymentResult | LightningPayment> => {
  const amountMsat = amountSat * 1000;
  if (amountMsat < route.minSendableMsat || amountMsat > route.maxSendableMsat) {
    throw new Error("Payment amount is outside the supported range for this lightning address");
  }

  if (route.method === "ark") {
    log.d("Paying lightning address via Ark direct payment");
    const result = await sendArkoorPayment(route.destination, amountSat);
    if (result.isErr()) {
      throw result.error;
    }
    return result.value;
  }

  log.d("Paying via standard Lightning Address flow");
  return readLightningPayment(payLightningAddress(destination, amountSat, comment || ""));
};

export function useSend(destinationType: DestinationTypes) {
  return useMutation<SendResult, Error, SendVariables>({
    mutationFn: async (variables) => {
      const {
        destination,
        amountSat,
        resolvedAmountSat,
        isMaxAmount = false,
        comment,
        onchainSource,
        lightningAddressPaymentRoute,
      } = variables;
      if (!isMaxAmount && amountSat === undefined && destinationType !== "lightning") {
        throw new Error("Amount is required");
      }

      let result;
      switch (destinationType) {
        case "onchain":
          if (isMaxAmount) {
            if (!onchainSource) {
              throw new Error("A balance source is required to send the maximum amount");
            }

            if (onchainSource === "offchain") {
              const estimateResult = await estimateOffboardAllFee(destination);
              const sentAmountSat = estimateResult.isOk()
                ? estimateResult.value.net_amount_sat
                : resolvedAmountSat;
              const offboardResult = await offboardAllArk(destination);
              result = offboardResult.map((txid) => ({
                txid,
                amount_sat: sentAmountSat,
                destination_address: destination,
                source: "offchain" as const,
              }));
            } else {
              result = await onchainDrain({
                destination,
                fallbackAmountSat: resolvedAmountSat,
              });
            }
            break;
          }

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

          if (lightningAddressPaymentRoute) {
            return sendLightningAddressPayment(
              lightningAddressPaymentRoute,
              destination,
              amountSat,
              comment,
            );
          }

          try {
            const route = await resolveLightningAddressPaymentRoute(destination);
            return sendLightningAddressPayment(route, destination, amountSat, comment);
          } catch (routeError) {
            log.w("Failed to resolve lightning address payment route, using standard LNURL", [
              routeError,
            ]);
            return readLightningPayment(payLightningAddress(destination, amountSat, comment || ""));
          }
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
