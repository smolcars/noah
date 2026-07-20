import {
  boardAmount as boardAmountNitro,
  boardAll as boardAllNitro,
  syncPendingBoards as syncPendingBoardsNitro,
  offboardAll as offboardAllNitro,
  subscribeArkoorAddressMovements as subscribeArkoorAddressMovementsNitro,
  subscribeLightningPaymentMovements as subscribeLightningPaymentMovementsNitro,
  sendArkoorPayment as sendArkoorPaymentNitro,
  validateArkoorAddress as validateArkoorAddressNitro,
  payLightningAddress as payLightningAddressNitro,
  payLightningOffer as payLightningOfferNitro,
  checkLightningPayment as checkLightningPaymentNitro,
  bolt11Invoice as bolt11InvoiceNitro,
  type ArkoorPaymentResult,
  type OnchainPaymentResult,
  type LightningPayment,
  newAddress as newAddressNitro,
  onchainAddress as onchainAddressNitro,
  onchainIsMine as onchainIsMineNitro,
  payLightningInvoice as payLightningInvoiceNitro,
  onchainDrain as onchainDrainNitro,
  onchainSend as onchainSendNitro,
  sendOnchain as sendOnchainNitro,
  onchainFeeRates as onchainFeeRatesNitro,
  onchainTransactions as onchainTransactionsNitro,
  estimateArkoorPaymentFee as estimateArkoorPaymentFeeNitro,
  estimateLightningSendFee as estimateLightningSendFeeNitro,
  estimateBoardOffchainFee as estimateBoardOffchainFeeNitro,
  estimateSendOnchain as estimateSendOnchainNitro,
  estimateOffboardAll as estimateOffboardAllNitro,
  history as historyNitro,
  tryClaimAllLightningReceives as tryClaimAllLightningReceivesNitro,
  tryClaimLightningReceive as tryClaimLightningReceiveNitro,
  peekAddress as peekAddressNitro,
  NewAddressResult,
  BarkMovement,
  BarkNotificationEvent,
  BarkFeeEstimate,
  BarkFeeRates,
  OnchainTransactionInfo,
  Bolt11Invoice,
  BoardResult,
  LightningReceive,
} from "react-native-nitro-ark";
import { err, ok, Result, ResultAsync } from "neverthrow";
import {
  isInvoiceDescriptionValid,
  MAX_INVOICE_DESCRIPTION_LENGTH,
  normalizeInvoiceDescription,
} from "./lightningInvoice";

export type {
  ArkoorPaymentResult,
  OnchainPaymentResult,
  LightningPayment,
  BarkFeeEstimate,
  BarkFeeRates,
  OnchainTransactionInfo,
};
export type { BarkNotificationEvent };

export type BarkNotificationSubscription = {
  stop(): void;
  isActive(): boolean;
};

export type OnchainSendSource = "onchain" | "offchain";

export type NoahOnchainPaymentResult = OnchainPaymentResult & {
  source: OnchainSendSource;
};

export type PaymentResult = ArkoorPaymentResult | NoahOnchainPaymentResult | LightningPayment;

export type OnchainWalletFeeEstimate = BarkFeeEstimate & {
  fee_rate_sat_vb: number;
  estimated_vbytes: number;
};

export type StandardOnchainWalletFeeEstimate = {
  fee_sat: number;
  fee_rate_sat_vb: number;
  estimated_vbytes: number;
  fee_rate_tier: OnchainFeeRateTier;
};

export type OnchainFeeRateTier = keyof Pick<BarkFeeRates, "fast" | "regular" | "slow">;

export const newAddress = async (): Promise<Result<NewAddressResult, Error>> => {
  return ResultAsync.fromPromise(
    newAddressNitro(),
    (error) =>
      new Error(
        `Failed to generate VTXO pubkey: ${error instanceof Error ? error.message : String(error)}`,
      ),
  );
};

export const peakAddress = async (index: number): Promise<Result<NewAddressResult, Error>> => {
  return ResultAsync.fromPromise(
    peekAddressNitro(index),
    (error) =>
      new Error(
        `Failed to generate peak address: ${error instanceof Error ? error.message : String(error)}`,
      ),
  );
};

export const onchainAddress = async (): Promise<Result<string, Error>> => {
  return ResultAsync.fromPromise(
    onchainAddressNitro(),
    (error) =>
      new Error(
        error instanceof Error ? error.message : "Failed to get onchain address",
      ),
  );
};

export const onchainIsMine = async (
  address: string,
): Promise<Result<boolean, Error>> => {
  return ResultAsync.fromPromise(
    onchainIsMineNitro(address),
    (error) =>
      new Error(
        error instanceof Error
          ? error.message
          : "Failed to check address ownership",
      ),
  );
};

export const bolt11Invoice = async (
  amountSat: number,
  description?: string,
): Promise<Result<Bolt11Invoice, Error>> => {
  const normalizedDescription = normalizeInvoiceDescription(description);

  if (!isInvoiceDescriptionValid(normalizedDescription)) {
    return err(
      new Error(
        `Lightning invoice descriptions must be ${MAX_INVOICE_DESCRIPTION_LENGTH} characters or fewer`,
      ),
    );
  }

  return ResultAsync.fromPromise(
    bolt11InvoiceNitro(amountSat, normalizedDescription),
    (error) =>
      new Error(
        `Failed to generate lightning invoice: ${error instanceof Error ? error.message : String(error)}`,
      ),
  );
};

export const boardArk = async (amountSat: number): Promise<Result<BoardResult, Error>> => {
  return ResultAsync.fromPromise(boardAmountNitro(amountSat), (error) => {
    const e = new Error(
      `Failed to board funds: ${error instanceof Error ? error.message : String(error)}`,
    );
    return e;
  });
};

export const boardAllArk = async (): Promise<Result<BoardResult, Error>> => {
  return ResultAsync.fromPromise(boardAllNitro(), (error) => {
    const e = new Error(
      `Failed to board funds: ${error instanceof Error ? error.message : String(error)}`,
    );
    return e;
  });
};

export const estimateBoardOffchainFee = async (
  amountSat: number,
): Promise<Result<BarkFeeEstimate, Error>> => {
  return ResultAsync.fromPromise(estimateBoardOffchainFeeNitro(amountSat), (error) => {
    return new Error(
      `Failed to estimate boarding fee: ${error instanceof Error ? error.message : String(error)}`,
    );
  });
};

export const offboardAllArk = async (address: string): Promise<Result<string, Error>> => {
  return ResultAsync.fromPromise(offboardAllNitro(address), (error) => {
    const e = new Error(
      `Failed to offboard funds: ${error instanceof Error ? error.message : String(error)}`,
    );
    return e;
  });
};

export const validateArkoorPaymentAddress = async (
  destination: string,
): Promise<Result<void, Error>> => {
  return ResultAsync.fromPromise(validateArkoorAddressNitro(destination), (error) => {
    const message = error instanceof Error ? error.message : String(error);
    return new Error(`Invalid Ark address: ${message}`);
  });
};

export const sendArkoorPayment = async (
  destination: string,
  amountSat: number,
): Promise<Result<ArkoorPaymentResult, Error>> => {
  const validationResult = await validateArkoorPaymentAddress(destination);
  if (validationResult.isErr()) {
    return err(validationResult.error);
  }

  return ResultAsync.fromPromise(sendArkoorPaymentNitro(destination, amountSat), (error) => {
    const e = new Error(
      `Failed to send arkoor payment: ${error instanceof Error ? error.message : String(error)}`,
    );
    return e;
  });
};

export const payLightningInvoice = async (
  destination: string,
  amountSat: number | undefined,
): Promise<Result<LightningPayment, Error>> => {
  return ResultAsync.fromPromise(
    payLightningInvoiceNitro(destination, true, amountSat),
    (error) => {
      const e = new Error(
        `Failed to send bolt11 payment: ${error instanceof Error ? error.message : String(error)}`,
      );
      return e;
    },
  );
};

export const payLightningOffer = async (
  destination: string,
  amountSat: number | undefined,
): Promise<Result<LightningPayment, Error>> => {
  return ResultAsync.fromPromise(payLightningOfferNitro(destination, true, amountSat), (error) => {
    const e = new Error(
      `Failed to send bolt12 payment: ${error instanceof Error ? error.message : String(error)}`,
    );
    return e;
  });
};

export const onchainSend = async ({
  destination,
  amountSat,
}: {
  destination: string;
  amountSat: number;
}): Promise<Result<NoahOnchainPaymentResult, Error>> => {
  return ResultAsync.fromPromise(onchainSendNitro(destination, amountSat), (error) => {
    const e = new Error(
      `Failed to send onchain funds: ${error instanceof Error ? error.message : String(error)}`,
    );

    return e;
  }).map((result) => ({ ...result, source: "onchain" }));
};

export const onchainDrain = async ({
  destination,
  fallbackAmountSat,
}: {
  destination: string;
  fallbackAmountSat: number;
}): Promise<Result<NoahOnchainPaymentResult, Error>> => {
  const drainResult = await ResultAsync.fromPromise(onchainDrainNitro(destination), (error) => {
    return new Error(
      `Failed to drain onchain funds: ${error instanceof Error ? error.message : String(error)}`,
    );
  });

  if (drainResult.isErr()) {
    return err(drainResult.error);
  }

  const txid = drainResult.value;
  const transactionsResult = await onchainTransactions();
  const transaction = transactionsResult.isOk()
    ? transactionsResult.value.find((candidate) => candidate.txid === txid)
    : undefined;
  const feeSat = transaction?.has_onchain_fee ? transaction.onchain_fee_sat : 0;
  const amountSat = transaction
    ? Math.max(Math.abs(transaction.balance_change_sat) - feeSat, 0)
    : fallbackAmountSat;

  return ok({
    txid,
    amount_sat: amountSat,
    destination_address: destination,
    source: "onchain",
  });
};

export const sendOnchainFromOffchain = async ({
  destination,
  amountSat,
}: {
  destination: string;
  amountSat: number;
}): Promise<Result<NoahOnchainPaymentResult, Error>> => {
  return ResultAsync.fromPromise(sendOnchainNitro(destination, amountSat), (error) => {
    const e = new Error(
      `Failed to send onchain funds from Ark balance: ${
        error instanceof Error ? error.message : String(error)
      }`,
    );

    return e;
  }).map((txid) => ({
    txid,
    amount_sat: amountSat,
    destination_address: destination,
    source: "offchain",
  }));
};

export const estimateArkoorPaymentFee = async (
  amountSat: number,
): Promise<Result<BarkFeeEstimate, Error>> => {
  return ResultAsync.fromPromise(estimateArkoorPaymentFeeNitro(amountSat), (error) => {
    return new Error(
      `Failed to estimate Ark payment fee: ${
        error instanceof Error ? error.message : String(error)
      }`,
    );
  });
};

export const estimateLightningSendFee = async (
  amountSat: number,
): Promise<Result<BarkFeeEstimate, Error>> => {
  return ResultAsync.fromPromise(estimateLightningSendFeeNitro(amountSat), (error) => {
    return new Error(
      `Failed to estimate lightning send fee: ${
        error instanceof Error ? error.message : String(error)
      }`,
    );
  });
};

export const estimateSendOnchainFee = async ({
  destination,
  amountSat,
}: {
  destination: string;
  amountSat: number;
}): Promise<Result<BarkFeeEstimate, Error>> => {
  return ResultAsync.fromPromise(estimateSendOnchainNitro(destination, amountSat), (error) => {
    return new Error(
      `Failed to estimate onchain send fee: ${
        error instanceof Error ? error.message : String(error)
      }`,
    );
  });
};

const STANDARD_SEGWIT_INPUT_VBYTES = 68;
const STANDARD_SEGWIT_OUTPUT_VBYTES = 31;
const STANDARD_TX_OVERHEAD_VBYTES = 10;
const ONCHAIN_WALLET_ESTIMATE_INPUTS = 2;
const ONCHAIN_WALLET_ESTIMATE_OUTPUTS = 2;

export const ONCHAIN_WALLET_ESTIMATE_VBYTES =
  STANDARD_TX_OVERHEAD_VBYTES +
  ONCHAIN_WALLET_ESTIMATE_INPUTS * STANDARD_SEGWIT_INPUT_VBYTES +
  ONCHAIN_WALLET_ESTIMATE_OUTPUTS * STANDARD_SEGWIT_OUTPUT_VBYTES;

export const onchainFeeRates = async (): Promise<Result<BarkFeeRates, Error>> => {
  return ResultAsync.fromPromise(onchainFeeRatesNitro(), (error) => {
    return new Error(
      `Failed to fetch onchain fee rates: ${
        error instanceof Error ? error.message : String(error)
      }`,
    );
  });
};

export const estimateOnchainWalletSendFee = async ({
  amountSat,
}: {
  amountSat: number;
}): Promise<Result<OnchainWalletFeeEstimate, Error>> => {
  const standardFeeResult = await estimateStandardOnchainTxFee("regular");
  if (standardFeeResult.isErr()) {
    return err(standardFeeResult.error);
  }

  const { fee_sat: feeSat, fee_rate_sat_vb: feeRateSatVb } = standardFeeResult.value;

  return ok({
    gross_amount_sat: amountSat + feeSat,
    fee_sat: feeSat,
    net_amount_sat: amountSat,
    vtxos_spent: [],
    fee_rate_sat_vb: feeRateSatVb,
    estimated_vbytes: ONCHAIN_WALLET_ESTIMATE_VBYTES,
  });
};

export const estimateStandardOnchainTxFee = async (
  feeRateTier: OnchainFeeRateTier,
): Promise<Result<StandardOnchainWalletFeeEstimate, Error>> => {
  const ratesResult = await onchainFeeRates();
  if (ratesResult.isErr()) {
    return err(ratesResult.error);
  }

  const feeRateSatVb = ratesResult.value[feeRateTier];
  const feeSat = Math.ceil(feeRateSatVb * ONCHAIN_WALLET_ESTIMATE_VBYTES);

  return ok({
    fee_sat: feeSat,
    fee_rate_sat_vb: feeRateSatVb,
    estimated_vbytes: ONCHAIN_WALLET_ESTIMATE_VBYTES,
    fee_rate_tier: feeRateTier,
  });
};

export const estimateOffboardAllFee = async (
  destinationAddress: string,
): Promise<Result<BarkFeeEstimate, Error>> => {
  return ResultAsync.fromPromise(estimateOffboardAllNitro(destinationAddress), (error) => {
    return new Error(
      `Failed to estimate offboarding fee: ${
        error instanceof Error ? error.message : String(error)
      }`,
    );
  });
};

export const payLightningAddress = async (
  addr: string,
  amountSat: number,
  comment: string,
): Promise<Result<LightningPayment, Error>> => {
  const normalizedAddress = addr.trim().toLowerCase();

  return ResultAsync.fromPromise(
    payLightningAddressNitro(normalizedAddress, amountSat, comment, true),
    (error) => {
      const e = new Error(
        `Failed to send to lightning address: ${
          error instanceof Error ? error.message : String(error)
        }`,
      );

      return e;
    },
  );
};

export const checkLightningPayment = async (
  paymentHash: string,
  wait: boolean = false,
): Promise<Result<LightningPayment, Error>> => {
  return ResultAsync.fromPromise(checkLightningPaymentNitro(paymentHash, wait), (error) => {
    const e = new Error(
      `Failed to check lightning payment: ${
        error instanceof Error ? error.message : String(error)
      }`,
    );

    return e;
  });
};

export const syncPendingBoards = async (): Promise<Result<void, Error>> => {
  return ResultAsync.fromPromise(syncPendingBoardsNitro(), (error) => {
    const e = new Error(
      `Failed to sync pending boards: ${error instanceof Error ? error.message : String(error)}`,
    );

    return e;
  });
};

export const history = async (): Promise<Result<BarkMovement[], Error>> => {
  return ResultAsync.fromPromise(historyNitro(), (error) => {
    const e = new Error(
      `Failed to get movements: ${error instanceof Error ? error.message : String(error)}`,
    );

    return e;
  });
};

export const onchainTransactions = async (): Promise<Result<OnchainTransactionInfo[], Error>> => {
  return ResultAsync.fromPromise(onchainTransactionsNitro(), (error) => {
    const e = new Error(
      `Failed to get onchain wallet transactions: ${
        error instanceof Error ? error.message : String(error)
      }`,
    );

    return e;
  });
};

export const subscribeArkoorAddressMovements = (
  address: string,
  onEvent: (event: BarkNotificationEvent) => void,
): Result<BarkNotificationSubscription, Error> => {
  try {
    return ok(subscribeArkoorAddressMovementsNitro(address, onEvent));
  } catch (error) {
    return err(
      new Error(
        `Failed to subscribe to Ark address movements: ${
          error instanceof Error ? error.message : String(error)
        }`,
      ),
    );
  }
};

export const subscribeLightningPaymentMovements = (
  paymentHash: string,
  onEvent: (event: BarkNotificationEvent) => void,
): Result<BarkNotificationSubscription, Error> => {
  try {
    return ok(subscribeLightningPaymentMovementsNitro(paymentHash, onEvent));
  } catch (error) {
    return err(
      new Error(
        `Failed to subscribe to lightning payment movements: ${
          error instanceof Error ? error.message : String(error)
        }`,
      ),
    );
  }
};

export const tryClaimLightningReceive = async (
  paymentHash: string,
  wait: boolean = false,
): Promise<Result<LightningReceive, Error>> => {
  return ResultAsync.fromPromise(tryClaimLightningReceiveNitro(paymentHash, wait), (error) => {
    const e = new Error(
      `Failed to check and claim lightning receive: ${error instanceof Error ? error.message : String(error)}`,
    );

    return e;
  });
};

export const tryClaimAllLightningReceives = async (
  wait: boolean = false,
): Promise<Result<void, Error>> => {
  return ResultAsync.fromPromise(tryClaimAllLightningReceivesNitro(wait), (error) => {
    const e = new Error(
      `Failed to check and claim all open lightning receives: ${error instanceof Error ? error.message : String(error)}`,
    );

    return e;
  });
};
