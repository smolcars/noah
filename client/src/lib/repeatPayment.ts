import type { BarkMovement } from "react-native-nitro-ark";

import { getMovementDestinationValue } from "~/lib/barkMovement";
import { isFiatCurrencyCode } from "~/lib/fiatCurrency";
import type {
  PaymentAmountMode,
  RepeatPaymentDetails,
  RepeatPaymentMetadata,
} from "~/types/repeatPayment";

const REPEAT_PAYMENT_METADATA_VERSION = 1;

const normalizeLightningAddress = (value: string): string | undefined => {
  const normalized = value
    .trim()
    .toLowerCase()
    .replace(/^lightning:/, "");
  const parts = normalized.split("@");
  if (parts.length !== 2 || !parts[0] || !parts[1]) {
    return undefined;
  }

  return normalized;
};

const parseAmountMode = (value: unknown): PaymentAmountMode | undefined => {
  if (value === "fiat") {
    return "FIAT";
  }
  if (value === "sats") {
    return "SATS";
  }
  return undefined;
};

export const buildRepeatPaymentMetadataPatch = (details: RepeatPaymentDetails): string =>
  JSON.stringify({
    noah: {
      repeat_payment: {
        version: REPEAT_PAYMENT_METADATA_VERSION,
        destination: details.destination,
        comment: details.comment,
        amount_mode: details.amountMode.toLowerCase(),
        amount_value: details.amountInput,
        ...(details.amountMode === "FIAT" && details.fiatCurrency
          ? { fiat_currency: details.fiatCurrency }
          : {}),
      },
    },
  });

export const parseRepeatPaymentMetadata = (value: unknown): RepeatPaymentMetadata | undefined => {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    return undefined;
  }

  const metadata = value as Record<string, unknown>;
  if (metadata.version !== REPEAT_PAYMENT_METADATA_VERSION) {
    return undefined;
  }

  const destination =
    typeof metadata.destination === "string"
      ? normalizeLightningAddress(metadata.destination)
      : undefined;
  const amountMode = parseAmountMode(metadata.amount_mode);
  const numericAmount =
    typeof metadata.amount_value === "string" ? Number(metadata.amount_value) : Number.NaN;
  const amountInput =
    typeof metadata.amount_value === "string" && Number.isFinite(numericAmount) && numericAmount > 0
      ? metadata.amount_value
      : undefined;
  const comment = typeof metadata.comment === "string" ? metadata.comment : "";

  if (!destination || !amountMode || !amountInput) {
    return undefined;
  }

  if (amountMode === "FIAT") {
    if (!isFiatCurrencyCode(metadata.fiat_currency)) {
      return undefined;
    }
    return {
      destination,
      comment,
      amountMode,
      amountInput,
      fiatCurrency: metadata.fiat_currency,
    };
  }

  return { destination, comment, amountMode, amountInput };
};

export const resolveRepeatPaymentDetails = ({
  metadata,
  destination,
  paymentMethod,
  amountSat,
}: {
  metadata?: RepeatPaymentMetadata;
  destination?: string;
  paymentMethod?: string;
  amountSat: number;
}): RepeatPaymentDetails | undefined => {
  if (!Number.isFinite(amountSat) || amountSat <= 0) {
    return undefined;
  }

  if (metadata) {
    return { ...metadata, amountSat };
  }

  const arkDestination = paymentMethod === "ark" ? destination?.trim() : undefined;
  const repeatDestination =
    paymentMethod === "lightning-address" && destination
      ? normalizeLightningAddress(destination)
      : arkDestination || undefined;
  if (!repeatDestination) {
    return undefined;
  }

  return {
    destination: repeatDestination,
    comment: "",
    amountMode: "SATS",
    amountInput: amountSat.toString(),
    amountSat,
  };
};

export const canRepeatPayment = (transaction: {
  direction: "incoming" | "outgoing";
  movementStatus?: string;
  repeatPayment?: RepeatPaymentDetails;
}): boolean =>
  transaction.direction === "outgoing" &&
  transaction.movementStatus === "successful" &&
  transaction.repeatPayment !== undefined;

export const getRepeatPaymentPrefill = (
  details: RepeatPaymentDetails,
  preferredFiatCurrency: RepeatPaymentDetails["fiatCurrency"],
): { amountInput: string; amountMode: PaymentAmountMode } => {
  const canRestoreFiat =
    details.amountMode === "FIAT" && details.fiatCurrency === preferredFiatCurrency;

  return canRestoreFiat
    ? { amountInput: details.amountInput, amountMode: "FIAT" }
    : { amountInput: details.amountSat.toString(), amountMode: "SATS" };
};

export const shouldUseArkDirectLightningAddressRoute = (
  routeMethod: "ark" | "lightning",
  comment: string | null,
): boolean => routeMethod === "ark" && !comment;

export const findNewArkoorMovementId = ({
  existingMovementIds,
  movements,
  destination,
  amountSat,
}: {
  existingMovementIds: ReadonlySet<number>;
  movements: BarkMovement[];
  destination: string;
  amountSat: number;
}): number | undefined =>
  movements
    .filter(
      (movement) =>
        !existingMovementIds.has(movement.id) &&
        movement.status === "successful" &&
        movement.subsystem?.name?.toLowerCase() === "bark.arkoor" &&
        movement.subsystem?.kind?.toLowerCase() === "send" &&
        movement.sent_to.some(
          (sentTo) =>
            getMovementDestinationValue(sentTo) === destination && sentTo.amount_sat === amountSat,
        ),
    )
    .sort((a, b) => b.id - a.id)[0]?.id;
