import { decodeBolt11 } from "../constants";
import { validateLightningInvoice } from "bip-321";
import { err, ok, type Result } from "neverthrow";
import { isNetworkMatch } from "./utils";

export type LnurlPayerIdentity = {
  name: string;
  identifier: string | null;
};

export type LnurlPayerData = {
  name?: string;
  identifier?: string;
};

export type LnurlPayerDataRequirement = {
  mandatory: boolean;
  [property: string]: unknown;
};

export type LnurlPayerDataRequirements = Record<string, LnurlPayerDataRequirement>;

export type LnurlPayRequestResponse = {
  callback: string;
  maxSendable: number;
  minSendable: number;
  metadata: string;
  tag: "payRequest";
  commentAllowed?: unknown;
  ark?: string;
  payerData?: LnurlPayerDataRequirements;
};

export type LnurlPayCallbackResponse = {
  pr?: string;
  ark?: string;
};

const isRecord = (value: unknown): value is Record<string, unknown> =>
  typeof value === "object" && value !== null && !Array.isArray(value);

const parseLnurlPayerDataRequirements = (
  value: unknown,
): Result<LnurlPayerDataRequirements | undefined, Error> => {
  if (value === undefined) {
    return ok(undefined);
  }

  if (!isRecord(value)) {
    return err(new Error("The LNURL service returned invalid payer data requirements."));
  }

  const entries: Array<[string, LnurlPayerDataRequirement]> = [];
  for (const [field, descriptor] of Object.entries(value)) {
    if (!isRecord(descriptor) || typeof descriptor.mandatory !== "boolean") {
      return err(new Error(`The LNURL service returned an invalid ${field} requirement.`));
    }

    entries.push([field, { ...descriptor, mandatory: descriptor.mandatory }]);
  }

  return ok(Object.fromEntries(entries));
};

export const parseLnurlPayRequestResponse = (
  value: unknown,
): Result<LnurlPayRequestResponse, Error> => {
  if (!isRecord(value)) {
    return err(new Error("The LNURL service returned an invalid payment request."));
  }

  if (value.status === "ERROR") {
    const reason =
      typeof value.reason === "string" && value.reason.trim()
        ? value.reason.trim()
        : "The LNURL service rejected the payment request.";
    return err(new Error(reason));
  }

  if (
    value.tag !== "payRequest" ||
    typeof value.callback !== "string" ||
    !value.callback.trim() ||
    typeof value.metadata !== "string" ||
    !Number.isSafeInteger(value.minSendable) ||
    !Number.isSafeInteger(value.maxSendable) ||
    (value.minSendable as number) <= 0 ||
    (value.maxSendable as number) < (value.minSendable as number)
  ) {
    return err(new Error("The LNURL service returned an invalid payment request."));
  }

  if (value.ark !== undefined && typeof value.ark !== "string") {
    return err(new Error("The LNURL service returned an invalid Ark payment option."));
  }

  const payerDataResult = parseLnurlPayerDataRequirements(value.payerData);
  if (payerDataResult.isErr()) {
    return err(payerDataResult.error);
  }

  return ok({
    callback: value.callback,
    maxSendable: value.maxSendable as number,
    minSendable: value.minSendable as number,
    metadata: value.metadata,
    tag: "payRequest",
    ...(value.commentAllowed !== undefined ? { commentAllowed: value.commentAllowed } : {}),
    ...(value.ark !== undefined ? { ark: value.ark } : {}),
    ...(payerDataResult.value !== undefined ? { payerData: payerDataResult.value } : {}),
  });
};

export const normalizeLnurlPayCommentAllowed = (value: unknown): number =>
  typeof value === "number" && Number.isSafeInteger(value) && value > 0 ? value : 0;

export const validateLnurlPayComment = (
  comment: string | null,
  commentAllowed: number,
): Result<string | null, Error> => {
  if (comment === null || comment.length === 0) {
    return ok(null);
  }

  if (!Number.isSafeInteger(commentAllowed) || commentAllowed <= 0) {
    return err(new Error("This lightning address does not accept comments."));
  }

  if ([...comment].length > commentAllowed) {
    return err(
      new Error(`This lightning address accepts comments up to ${commentAllowed} characters.`),
    );
  }

  return ok(comment);
};

export const buildLnurlPayerData = (
  requirements: LnurlPayerDataRequirements | undefined,
  identity: LnurlPayerIdentity,
): Result<LnurlPayerData | null, Error> => {
  const requirementsResult = parseLnurlPayerDataRequirements(requirements);
  if (requirementsResult.isErr()) {
    return err(requirementsResult.error);
  }

  const parsedRequirements = requirementsResult.value;
  if (parsedRequirements === undefined) {
    return ok(null);
  }

  const availableIdentity: LnurlPayerData = {
    ...(identity.name.trim() ? { name: identity.name.trim() } : {}),
    ...(identity.identifier?.trim() ? { identifier: identity.identifier.trim() } : {}),
  };
  const payerData: LnurlPayerData = {};

  for (const [field, descriptor] of Object.entries(parsedRequirements)) {
    if (field !== "name" && field !== "identifier") {
      if (descriptor.mandatory) {
        return err(new Error(`The LNURL service requires unsupported payer data: ${field}.`));
      }
      continue;
    }

    const value = availableIdentity[field];
    if (value) {
      payerData[field] = value;
    } else if (descriptor.mandatory) {
      return err(new Error(`The LNURL service requires a saved ${field}.`));
    }
  }

  return ok(Object.keys(payerData).length > 0 ? payerData : null);
};

export const createLnurlPayCallbackUrl = (
  callback: string,
  amountMsat: number,
  payerDataJson: string | null,
  comment: string | null,
  arkServerPubkey?: string,
): Result<string, Error> => {
  if (!Number.isSafeInteger(amountMsat) || amountMsat <= 0) {
    return err(new Error("The LNURL callback amount must be a positive integer."));
  }

  let url: URL;
  try {
    url = new URL(callback);
  } catch {
    return err(new Error("The LNURL service returned an invalid callback URL."));
  }
  if (url.protocol !== "https:") {
    return err(new Error("The LNURL service returned an insecure callback URL."));
  }

  url.searchParams.set("amount", amountMsat.toString());

  if (payerDataJson === null) {
    url.searchParams.delete("payerdata");
  } else {
    url.searchParams.set("payerdata", payerDataJson);
  }

  if (comment === null || comment.length === 0) {
    url.searchParams.delete("comment");
  } else {
    url.searchParams.set("comment", comment);
  }

  if (arkServerPubkey !== undefined) {
    url.searchParams.set("ark", arkServerPubkey);
  } else {
    url.searchParams.delete("ark");
  }

  return ok(url.toString());
};

export const parseLnurlPayCallbackResponse = (
  value: unknown,
): Result<LnurlPayCallbackResponse, Error> => {
  if (!isRecord(value)) {
    return err(new Error("The LNURL service returned an invalid callback response."));
  }

  if (value.status === "ERROR") {
    const reason =
      typeof value.reason === "string" && value.reason.trim()
        ? value.reason.trim()
        : "The LNURL service rejected the payment.";
    return err(new Error(reason));
  }

  const pr = typeof value.pr === "string" ? value.pr.trim() : "";
  const ark = typeof value.ark === "string" ? value.ark.trim() : "";

  return ok({
    ...(pr ? { pr } : {}),
    ...(ark ? { ark } : {}),
  });
};

export const validateLnurlPayInvoice = (
  invoice: string,
  expectedAmountMsat: number,
): Result<void, Error> => {
  if (!Number.isSafeInteger(expectedAmountMsat) || expectedAmountMsat <= 0) {
    return err(new Error("The expected LNURL invoice amount must be a positive integer."));
  }

  const validation = validateLightningInvoice(invoice);
  if (!validation.valid) {
    return err(new Error("The LNURL service returned an invalid Lightning invoice."));
  }

  if (!isNetworkMatch(validation.network, "lightning")) {
    return err(new Error("The LNURL service returned an invoice for a different network."));
  }

  const decoded = decodeBolt11(invoice);
  if (decoded === null) {
    return err(new Error("The LNURL service returned an invalid Lightning invoice."));
  }

  const amountSection = decoded.sections.find((section) => section.name === "amount");
  if (!amountSection || amountSection.name !== "amount") {
    return err(new Error("The LNURL service returned an invoice without an amount."));
  }

  try {
    if (BigInt(amountSection.value) !== BigInt(expectedAmountMsat)) {
      return err(new Error("The LNURL invoice amount does not match the requested amount."));
    }
  } catch {
    return err(new Error("The LNURL service returned an invalid invoice amount."));
  }

  return ok(undefined);
};

export const validateMatchingArkAddress = (
  returned: string | undefined,
  expected: string,
): Result<string, Error> => {
  const returnedAddress = returned?.trim();
  if (!returnedAddress) {
    return err(new Error("The LNURL service did not return the negotiated Ark address."));
  }

  const expectedAddress = expected.trim();
  if (!expectedAddress || returnedAddress.toLowerCase() !== expectedAddress.toLowerCase()) {
    return err(new Error("The LNURL service returned a different Ark address."));
  }

  return ok(returnedAddress);
};
