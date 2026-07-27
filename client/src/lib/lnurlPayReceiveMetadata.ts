import { decodeBolt11 } from "~/constants";
import type { BarkMovement } from "react-native-nitro-ark";
import { isLightningReceiveMovement } from "~/lib/barkMovement";

const PAYMENT_HASH_PATTERN = /^[0-9a-f]{64}$/i;
const MAX_PAYER_NAME_LENGTH = 80;
const MAX_PAYER_IDENTIFIER_LENGTH = 320;
const MAX_COMMENT_LENGTH = 280;
const isUnsafeDisplayCharacter = (character: string): boolean => {
  const codePoint = character.codePointAt(0);
  return (
    codePoint !== undefined &&
    (codePoint <= 0x1f ||
      (codePoint >= 0x7f && codePoint <= 0x9f) ||
      codePoint === 0x61c ||
      codePoint === 0x200e ||
      codePoint === 0x200f ||
      codePoint === 0x2028 ||
      codePoint === 0x2029 ||
      (codePoint >= 0x202a && codePoint <= 0x202e) ||
      (codePoint >= 0x2066 && codePoint <= 0x2069))
  );
};

export type LnurlPayReceivePayerData = {
  name?: string;
  identifier?: string;
};

export type LnurlPayReceiveMetadataInput = {
  payer_data: LnurlPayReceivePayerData | null;
  comment: string | null;
};

export type PersistedLnurlPayReceiveMetadata = {
  schemaVersion: 1;
  payerData: LnurlPayReceivePayerData;
  comment?: string;
};

const normalizedDisplayValue = (value: unknown, maxLength: number): string | undefined => {
  if (typeof value !== "string") {
    return undefined;
  }

  const normalized = value.trim();
  if (
    !normalized ||
    Array.from(normalized).length > maxLength ||
    Array.from(normalized).some(isUnsafeDisplayCharacter)
  ) {
    return undefined;
  }

  return normalized;
};

const asObject = (value: unknown): Record<string, unknown> | null => {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    return null;
  }

  return value as Record<string, unknown>;
};

const normalizePaymentHash = (value: unknown): string | undefined => {
  if (typeof value !== "string" || !PAYMENT_HASH_PATTERN.test(value)) {
    return undefined;
  }

  return value.toLowerCase();
};

export const buildLnurlPayReceiveMetadataPatch = (
  metadata: LnurlPayReceiveMetadataInput,
): Record<string, unknown> => {
  const payerData: LnurlPayReceivePayerData = {};
  const name = normalizedDisplayValue(metadata.payer_data?.name, MAX_PAYER_NAME_LENGTH);
  const identifier = normalizedDisplayValue(
    metadata.payer_data?.identifier,
    MAX_PAYER_IDENTIFIER_LENGTH,
  );
  const comment = normalizedDisplayValue(metadata.comment, MAX_COMMENT_LENGTH);

  if (name) {
    payerData.name = name;
  }
  if (identifier) {
    payerData.identifier = identifier;
  }

  return {
    noah: {
      lnurl_pay: {
        schema_version: 1,
        payer_data: payerData,
        ...(comment ? { comment } : {}),
      },
    },
  };
};

export const parseLnurlPayReceiveMetadata = (
  metadataJson: string,
): PersistedLnurlPayReceiveMetadata | undefined => {
  if (!metadataJson) {
    return undefined;
  }

  try {
    const root = asObject(JSON.parse(metadataJson) as unknown);
    const noah = asObject(root?.noah);
    const lnurlPay = asObject(noah?.lnurl_pay);
    if (lnurlPay?.schema_version !== 1) {
      return undefined;
    }

    const rawPayerData = asObject(lnurlPay.payer_data);
    const payerData: LnurlPayReceivePayerData = {};
    const name = normalizedDisplayValue(rawPayerData?.name, MAX_PAYER_NAME_LENGTH);
    const identifier = normalizedDisplayValue(
      rawPayerData?.identifier,
      MAX_PAYER_IDENTIFIER_LENGTH,
    );
    const comment = normalizedDisplayValue(lnurlPay.comment, MAX_COMMENT_LENGTH);

    if (name) {
      payerData.name = name;
    }
    if (identifier) {
      payerData.identifier = identifier;
    }

    return {
      schemaVersion: 1,
      payerData,
      ...(comment ? { comment } : {}),
    };
  } catch {
    return undefined;
  }
};

export const formatLnurlPayPayerLabel = (
  payerData: LnurlPayReceivePayerData | undefined,
): string | undefined => {
  if (payerData?.name && payerData.identifier) {
    return `${payerData.name} (⚡ ${payerData.identifier})`;
  }

  return payerData?.name ?? payerData?.identifier;
};

export const getMovementLightningPaymentHash = (movement: BarkMovement): string | undefined => {
  if (!isLightningReceiveMovement(movement)) {
    return undefined;
  }

  try {
    const metadata = asObject(JSON.parse(movement.metadata_json) as unknown);
    const metadataPaymentHash = normalizePaymentHash(metadata?.payment_hash);
    if (metadataPaymentHash) {
      return metadataPaymentHash;
    }
  } catch {
    // Older Bark movements may only expose the invoice in received_on.
  }

  for (const destination of movement.received_on) {
    if (destination.payment_method !== "invoice") {
      continue;
    }

    const decoded = decodeBolt11(destination.destination);
    const paymentHashSection = decoded?.sections.find((section) => section.name === "payment_hash");
    const paymentHash = normalizePaymentHash(paymentHashSection?.value);
    if (paymentHash) {
      return paymentHash;
    }
  }

  return undefined;
};

export const findLightningReceiveMovement = (
  movements: BarkMovement[],
  paymentHash: string,
): BarkMovement | undefined => {
  const normalizedPaymentHash = normalizePaymentHash(paymentHash);
  if (!normalizedPaymentHash) {
    return undefined;
  }

  return movements.find(
    (movement) => getMovementLightningPaymentHash(movement) === normalizedPaymentHash,
  );
};

export const doesPersistedLnurlPayReceiveMetadataMatch = (
  persisted: PersistedLnurlPayReceiveMetadata | undefined,
  expected: LnurlPayReceiveMetadataInput,
): boolean => {
  if (!persisted) {
    return false;
  }

  const expectedPatch = buildLnurlPayReceiveMetadataPatch(expected);
  const expectedMetadata = parseLnurlPayReceiveMetadata(JSON.stringify(expectedPatch));
  if (!expectedMetadata) {
    return false;
  }

  return (
    persisted.payerData.name === expectedMetadata.payerData.name &&
    persisted.payerData.identifier === expectedMetadata.payerData.identifier &&
    persisted.comment === expectedMetadata.comment
  );
};
