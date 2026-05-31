import { useMutation, useQuery } from "@tanstack/react-query";
import { useAlert } from "~/contexts/AlertProvider";
import {
  newAddress,
  onchainAddress,
  boardArk,
  bolt11Invoice,
  onchainSend,
  sendArkoorPayment,
  payLightningInvoice,
  payLightningAddress,
  payLightningOffer,
  type ArkoorPaymentResult,
  type LightningPayment,
  type OnchainPaymentResult,
  type BarkFeeEstimate,
  boardAllArk,
  offboardAllArk,
  estimateArkoorPaymentFee,
  estimateLightningSendFee,
  estimateSendOnchainFee,
  estimateOffboardAllFee,
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
  btcPrice?: number;
};

type SendResult = ArkoorPaymentResult | LightningPayment | OnchainPaymentResult;

export type SendFeeEstimateParams =
  | {
      method: "ark" | "lightning";
      amountSat: number;
    }
  | {
      method: "onchain";
      destination: string;
      amountSat: number;
    };

const readEstimateResult = async (
  estimatePromise: Promise<Result<BarkFeeEstimate, Error>>,
): Promise<BarkFeeEstimate> => {
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
          return readEstimateResult(
            estimateSendOnchainFee({
              destination: params.destination,
              amountSat: params.amountSat,
            }),
          );
      }
    },
    enabled: params !== null && params.amountSat > 0,
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
  const { showAlert } = useAlert();

  return useMutation<SendResult, Error, SendVariables>({
    mutationFn: async (variables) => {
      const { destination, amountSat, comment } = variables;
      if (amountSat === undefined && destinationType !== "lightning") {
        throw new Error("Amount is required");
      }

      let result;
      switch (destinationType) {
        case "onchain":
          if (amountSat === undefined) {
            throw new Error("Amount is required for onchain payments");
          }
          result = await onchainSend({ destination, amountSat });
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
    onError: (error: Error) => {
      showAlert({ title: "Send Failed", description: error.message });
    },
  });
}

async function handleNoahWalletPayment(
  destination: string,
  amountSat: number,
  comment: string | null,
): Promise<Result<ArkoorPaymentResult | LightningPayment | OnchainPaymentResult, Error> | null> {
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
