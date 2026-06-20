import { decodeBolt11, isValidLightningAddress } from "../constants";
import {
  parseBIP321,
  type BIP321ParseResult,
  validateBitcoinAddress,
  validateLightningInvoice,
  validateArkAddress,
} from "bip-321";
import { APP_VARIANT } from "../config";
import logger from "./log";
import { isNetworkMatch } from "./utils";

const log = logger("sendUtils");

export type DestinationTypes =
  | "onchain"
  | "lightning"
  | "ark"
  | "lnurl"
  | "bip321"
  | "offer"
  | null;

export type ParsedBip321 = {
  onchainAddress?: string;
  arkAddress?: string;
  lightningInvoice?: string;
  offer?: string;
};

export type ParsedDestination = {
  destinationType: DestinationTypes;
  amount?: number;
  isAmountEditable: boolean;
  error?: string;
  bip321?: ParsedBip321;
};

const LIGHTNING_PREFIX_REGEX = /^lightning:/i;

export const normalizeLightningAddress = (address: string): string => address.trim().toLowerCase();

export const normalizeLightningAddressDestination = (destination: string): string => {
  const trimmedDestination = destination.trim();
  const cleanedDestination = trimmedDestination.replace(LIGHTNING_PREFIX_REGEX, "");
  const normalizedAddress = normalizeLightningAddress(cleanedDestination);

  if (!isValidLightningAddress(normalizedAddress)) {
    return destination;
  }

  return trimmedDestination.toLowerCase().startsWith("lightning:")
    ? `lightning:${normalizedAddress}`
    : normalizedAddress;
};

export const isValidDestination = (dest: string): boolean => {
  if (dest.toLowerCase().startsWith("bitcoin:")) {
    const expectedNetwork = APP_VARIANT;
    const result = parseBIP321(dest, expectedNetwork);
    return result.valid && result.paymentMethods.length > 0;
  }

  const cleanedDest = dest.trim().replace(LIGHTNING_PREFIX_REGEX, "");

  // Check Bitcoin address
  const btcResult = validateBitcoinAddress(cleanedDest);
  if (btcResult.valid && isNetworkMatch(btcResult.network, "onchain")) {
    return true;
  }

  // Check Lightning invoice (BOLT11)
  const lnResult = validateLightningInvoice(cleanedDest);
  if (lnResult.valid && isNetworkMatch(lnResult.network, "lightning")) {
    return true;
  }

  // Check Ark address
  const arkResult = validateArkAddress(cleanedDest);
  if (arkResult.valid && isNetworkMatch(arkResult.network, "ark")) {
    return true;
  }

  // Check Lightning Address (LNURL LUD-16)
  if (isValidLightningAddress(normalizeLightningAddress(cleanedDest))) {
    return true;
  }

  return false;
};

const btcToSats = (btc: number) => {
  return Math.round(btc * 100_000_000);
};

export const parseBip321Uri = (uri: string): ParsedDestination => {
  try {
    const expectedNetwork = APP_VARIANT;
    const result: BIP321ParseResult = parseBIP321(uri, expectedNetwork);

    if (!result.valid) {
      const errorMsg = result.errors.join(", ");
      log.w("Failed to parse BIP-321 URI", [errorMsg]);
      return {
        destinationType: null,
        isAmountEditable: true,
        error: errorMsg || "Invalid BIP-321 URI",
      };
    }

    if (result.paymentMethods.length === 0) {
      return {
        destinationType: null,
        isAmountEditable: true,
        error: "No valid payment methods found",
      };
    }

    const bip321: ParsedBip321 = {};

    // Extract payment methods
    for (const method of result.paymentMethods) {
      if (!method.valid) continue;

      switch (method.type) {
        case "onchain":
          bip321.onchainAddress = method.value;
          break;
        case "ark":
          bip321.arkAddress = method.value;
          break;
        case "lightning":
          bip321.lightningInvoice = method.value;
          break;
        case "offer":
          bip321.offer = method.value;
          break;
      }
    }

    const parsedAmount = typeof result.amount === "number" ? btcToSats(result.amount) : null;

    const parsed: ParsedDestination = {
      destinationType: "bip321",
      isAmountEditable: parsedAmount === null || parsedAmount <= 0,
      bip321,
    };

    if (parsedAmount !== null && parsedAmount > 0) {
      parsed.amount = parsedAmount;
    }

    return parsed;
  } catch (error) {
    log.w("Failed to parse BIP-321 URI", [error]);
    return {
      destinationType: null,
      isAmountEditable: true,
      error: "Invalid BIP-321 URI",
    };
  }
};

export const parseDestination = (destination: string): ParsedDestination => {
  if (destination.toLowerCase().startsWith("bitcoin:")) {
    return parseBip321Uri(destination);
  }

  const cleanedDestination = destination.trim().replace(LIGHTNING_PREFIX_REGEX, "");

  if (isValidLightningAddress(normalizeLightningAddress(cleanedDestination))) {
    return {
      destinationType: "lnurl",
      isAmountEditable: true,
    };
  }

  const lnResult = validateLightningInvoice(cleanedDestination);
  if (lnResult.valid) {
    if (!isNetworkMatch(lnResult.network, "lightning")) {
      return {
        destinationType: null,
        isAmountEditable: true,
        error: `Network mismatch: expected ${APP_VARIANT}, got ${lnResult.network}`,
      };
    }
    const decoded = decodeBolt11(cleanedDestination);
    if (decoded === null) {
      return {
        destinationType: null,
        isAmountEditable: true,
        error: "Failed to decode bolt11 invoice",
      };
    }

    const msats = decoded.sections.find((n) => n.name === "amount")?.value;

    if (msats === undefined) {
      return {
        destinationType: "lightning",
        isAmountEditable: true,
      };
    }

    if (Number(msats) > 0 && Number(msats) < 1000) {
      return {
        destinationType: "lightning",
        isAmountEditable: true,
        error: "Invoice amount is less than 1 satoshi.",
      };
    }

    const sats = Number(msats) / 1000;

    if (sats >= 1) {
      return {
        destinationType: "lightning",
        amount: sats,
        isAmountEditable: false,
      };
    } else {
      return {
        destinationType: "lightning",
        isAmountEditable: true,
      };
    }
  }

  const btcResult = validateBitcoinAddress(cleanedDestination);
  if (btcResult.valid) {
    if (!isNetworkMatch(btcResult.network, "onchain")) {
      return {
        destinationType: null,
        isAmountEditable: true,
        error: `Network mismatch: expected ${APP_VARIANT}, got ${btcResult.network}`,
      };
    }
    return {
      destinationType: "onchain",
      isAmountEditable: true,
    };
  }

  const arkResult = validateArkAddress(cleanedDestination);
  if (arkResult.valid) {
    if (!isNetworkMatch(arkResult.network, "ark")) {
      return {
        destinationType: null,
        isAmountEditable: true,
        error: `Network mismatch: expected ${APP_VARIANT}, got ${arkResult.network}`,
      };
    }
    return {
      destinationType: "ark",
      isAmountEditable: true,
    };
  }

  return {
    destinationType: null,
    isAmountEditable: true,
  };
};
