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
  payLightningInvoiceWithOrigin,
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
  type LightningPaymentOrigin,
} from "../lib/paymentsApi";
import { queryClient } from "~/queryClient";
import { DestinationTypes } from "~/lib/sendUtils";
import { getArkInfo } from "~/lib/walletApi";
import {
  buildLnurlPayerData,
  createLnurlPayCallbackUrl,
  normalizeLnurlPayCommentAllowed,
  parseLnurlPayCallbackResponse,
  parseLnurlPayRequestResponse,
  validateLnurlPayComment,
  validateLnurlPayInvoice,
  validateMatchingArkAddress,
  type LnurlPayerData,
  type LnurlPayerDataRequirements,
  type LnurlPayerIdentity,
  type LnurlPayRequestResponse,
} from "~/lib/lnurlPay";
import { useProfileStore } from "~/store/profileStore";
import { useServerStore } from "~/store/serverStore";
import logger from "~/lib/log";
import { fetch as expoFetch } from "expo/fetch";
import ky from "ky";
import { Result } from "neverthrow";

const log = logger("usePayments");

type LnurlPayRouteBase = {
  callback: string;
  metadata: string;
  commentAllowed: number;
  payerData?: LnurlPayerDataRequirements;
  minSendableMsat: number;
  maxSendableMsat: number;
  origin: LightningPaymentOrigin;
};

export type LnurlPayRoute = LnurlPayRouteBase &
  (
    | {
        method: "ark";
        destination: string;
        arkServerPubkey: string;
      }
    | {
        method: "lightning";
      }
  );

const parseLightningAddress = (destination: string) => {
  const normalized = destination
    .trim()
    .toLowerCase()
    .replace(/^lightning:/, "");
  const parts = normalized.split("@");
  if (parts.length !== 2 || !parts[0] || !parts[1]) {
    return null;
  }

  return {
    username: parts[0],
    domain: parts[1],
    normalizedAddress: `${parts[0]}@${parts[1]}`,
  };
};

const fetchLnurlPayRequestResponse = async (url: URL): Promise<LnurlPayRequestResponse> => {
  // LUD-01 assigns no protocol meaning to HTTP status codes; the JSON body is authoritative.
  const response = await ky.get(url.toString(), { throwHttpErrors: false }).json<unknown>();
  const parsedResponse = parseLnurlPayRequestResponse(response);
  if (parsedResponse.isErr()) {
    throw parsedResponse.error;
  }

  return parsedResponse.value;
};

const lnurlPayRouteFromRequestResponse = async (
  response: LnurlPayRequestResponse,
  arkServerPubkey: string | null,
  origin: LightningPaymentOrigin,
): Promise<LnurlPayRoute> => {
  const routeDetails: LnurlPayRouteBase = {
    callback: response.callback,
    metadata: response.metadata,
    commentAllowed: normalizeLnurlPayCommentAllowed(response.commentAllowed),
    payerData: response.payerData,
    minSendableMsat: response.minSendable,
    maxSendableMsat: response.maxSendable,
    origin,
  };

  if (arkServerPubkey && response.ark) {
    const validationResult = await validateArkoorPaymentAddress(response.ark);
    if (validationResult.isOk()) {
      return {
        method: "ark",
        destination: response.ark,
        arkServerPubkey,
        ...routeDetails,
      };
    }

    log.w("Ignoring incompatible Ark address returned by LNURL-pay service", [
      validationResult.error,
    ]);
  }

  return { method: "lightning", ...routeDetails };
};

export const resolveLnurlPayRouteForLightningAddress = async (
  lightningAddress: string,
): Promise<LnurlPayRoute> => {
  const parsed = parseLightningAddress(lightningAddress);
  if (!parsed) {
    throw new Error("Destination is not a lightning address");
  }

  // Persist only the public identifier; callback URLs can contain payer data and tokens.
  const origin: LightningPaymentOrigin = {
    method: "lightning-address",
    value: parsed.normalizedAddress,
  };
  const lnurlEndpoint = new URL(`https://${parsed.domain}/.well-known/lnurlp/${parsed.username}`);
  const arkInfoResult = await getArkInfo();
  if (arkInfoResult.isErr()) {
    log.w("Unable to load Ark server info, using standard LNURL-pay discovery", [
      arkInfoResult.error,
    ]);
    const response = await fetchLnurlPayRequestResponse(lnurlEndpoint);
    return lnurlPayRouteFromRequestResponse(response, null, origin);
  }

  const arkLnurlEndpoint = new URL(lnurlEndpoint);
  arkLnurlEndpoint.searchParams.set("ark", arkInfoResult.value.server_pubkey);

  let response: LnurlPayRequestResponse;
  try {
    response = await fetchLnurlPayRequestResponse(arkLnurlEndpoint);
  } catch (error) {
    log.w("Ark-aware LNURL-pay discovery failed, retrying standard LNURL-pay discovery", [error]);
    const standardResponse = await fetchLnurlPayRequestResponse(lnurlEndpoint);
    return lnurlPayRouteFromRequestResponse(standardResponse, null, origin);
  }

  return lnurlPayRouteFromRequestResponse(response, arkInfoResult.value.server_pubkey, origin);
};

const lnurlPayRouteQueryOptions = (lightningAddress: string | null) => ({
  queryKey: ["lnurl-pay-route", "lightning-address", lightningAddress],
  queryFn: () => {
    if (!lightningAddress) {
      throw new Error("Lightning address is required for LNURL-pay discovery");
    }

    return resolveLnurlPayRouteForLightningAddress(lightningAddress);
  },
  staleTime: 0,
  retry: false,
});

export function useLnurlPayRouteForLightningAddress(lightningAddress: string | null) {
  return useQuery({
    ...lnurlPayRouteQueryOptions(lightningAddress),
    enabled: lightningAddress !== null,
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
    mutationFn: async ({ amountSat, description }: { amountSat: number; description?: string }) => {
      const result = await bolt11Invoice(amountSat, description);
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
  confirmedLnurlPayMethod?: LnurlPayRoute["method"];
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

const sendLnurlPayCallbackPayment = async (
  route: LnurlPayRoute,
  amountSat: number,
  payerData: LnurlPayerData | null,
  comment: string | null,
): Promise<ArkoorPaymentResult | LightningPayment> => {
  const payerDataJson = payerData === null ? null : JSON.stringify(payerData);
  const callbackUrlResult = createLnurlPayCallbackUrl(
    route.callback,
    amountSat * 1000,
    payerDataJson,
    comment,
    route.method === "ark" ? route.arkServerPubkey : undefined,
  );
  if (callbackUrlResult.isErr()) {
    throw callbackUrlResult.error;
  }

  let response: unknown;
  const abortController = new AbortController();
  const timeout = setTimeout(() => abortController.abort(), 60_000);
  try {
    const httpResponse = await expoFetch(callbackUrlResult.value, {
      redirect: "manual",
      signal: abortController.signal,
    });
    response = (await httpResponse.json()) as unknown;
  } catch {
    throw new Error("Failed to request a payment from this lightning address");
  } finally {
    clearTimeout(timeout);
  }

  const callbackResponseResult = parseLnurlPayCallbackResponse(response);
  if (callbackResponseResult.isErr()) {
    throw callbackResponseResult.error;
  }
  const callbackResponse = callbackResponseResult.value;

  if (route.method === "ark") {
    const matchingAddressResult = validateMatchingArkAddress(
      callbackResponse.ark,
      route.destination,
    );
    if (matchingAddressResult.isErr()) {
      throw matchingAddressResult.error;
    }

    const validationResult = await validateArkoorPaymentAddress(matchingAddressResult.value);
    if (validationResult.isErr()) {
      throw new Error("The recipient returned an invalid Ark address");
    }

    log.d("Paying lightning address via Ark after LNURL-pay callback");
    const result = await sendArkoorPayment(matchingAddressResult.value, amountSat);
    if (result.isErr()) {
      throw result.error;
    }
    return result.value;
  }

  if (!callbackResponse.pr) {
    throw new Error("The recipient did not return a Lightning invoice");
  }

  const invoiceValidationResult = validateLnurlPayInvoice(callbackResponse.pr, amountSat * 1000);
  if (invoiceValidationResult.isErr()) {
    throw invoiceValidationResult.error;
  }

  log.d("Paying Lightning invoice after LNURL-pay callback");
  return readLightningPayment(payLightningInvoiceWithOrigin(callbackResponse.pr, route.origin));
};

const sendLnurlPayPayment = async (
  route: LnurlPayRoute,
  amountSat: number,
  comment: string | null,
  payerIdentity: LnurlPayerIdentity,
): Promise<ArkoorPaymentResult | LightningPayment> => {
  const amountMsat = amountSat * 1000;
  if (amountMsat < route.minSendableMsat || amountMsat > route.maxSendableMsat) {
    throw new Error("Payment amount is outside the supported range for this lightning address");
  }

  const commentToSend = route.commentAllowed > 0 ? comment : null;
  const commentResult = validateLnurlPayComment(commentToSend, route.commentAllowed);
  if (commentResult.isErr()) {
    throw commentResult.error;
  }

  const payerDataResult = buildLnurlPayerData(route.payerData, payerIdentity);
  if (payerDataResult.isErr()) {
    throw payerDataResult.error;
  }

  return sendLnurlPayCallbackPayment(route, amountSat, payerDataResult.value, commentResult.value);
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
        confirmedLnurlPayMethod,
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
        case "lightning-address": {
          if (amountSat === undefined) {
            throw new Error("Amount is required for LNURL-pay payments");
          }

          const payerIdentity: LnurlPayerIdentity = {
            name: useProfileStore.getState().displayName,
            identifier: useServerStore.getState().lightningAddress,
          };

          const route = await queryClient.fetchQuery(lnurlPayRouteQueryOptions(destination));
          if (!confirmedLnurlPayMethod || route.method !== confirmedLnurlPayMethod) {
            throw new Error("The payment route changed. Review the updated fee and try again.");
          }

          return sendLnurlPayPayment(route, amountSat, comment, payerIdentity);
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
