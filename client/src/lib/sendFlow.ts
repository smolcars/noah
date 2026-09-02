import type { DestinationTypes, ParsedBip321 } from "~/lib/sendUtils";
import type { OnchainSendSource } from "~/lib/paymentsApi";

export type SendRail = "ark" | "lightning" | "onchain";
export type SendStage = "amount" | "recipient" | "method" | "source" | "review";
export type SendEntry = "amount-first" | "recipient-first" | "max";

export type SendFlowProgress = {
  entry: SendEntry;
  amountConfirmed: boolean;
  recipientConfirmed: boolean;
  railConfirmed: boolean;
  sourceConfirmed: boolean;
  rails: readonly SendRail[];
  selectedRail: SendRail | null;
  selectedRailAvailable: boolean;
  sourceOptions: readonly OnchainSendSource[];
};

export const getBip321Rails = (request: ParsedBip321): SendRail[] => {
  const rails: SendRail[] = [];

  if (request.arkAddress) {
    rails.push("ark");
  }
  if (request.lightningInvoice || request.offer) {
    rails.push("lightning");
  }
  if (request.onchainAddress) {
    rails.push("onchain");
  }

  return rails;
};

export const getBip321MethodForRail = (
  rail: SendRail,
  request: ParsedBip321,
): "ark" | "lightning" | "offer" | "onchain" | null => {
  if (rail === "ark") {
    return request.arkAddress ? "ark" : null;
  }
  if (rail === "lightning") {
    return request.lightningInvoice ? "lightning" : request.offer ? "offer" : null;
  }
  return request.onchainAddress ? "onchain" : null;
};

export const getRecommendedRail = (
  rails: readonly SendRail[],
  availability: Readonly<Partial<Record<SendRail, boolean>>>,
): SendRail | null => rails.find((rail) => availability[rail] !== false) ?? rails[0] ?? null;

export const canAddRecipientNote = (
  destinationType: DestinationTypes,
  commentAllowed: number,
): boolean => destinationType === "lnurl" && commentAllowed > 0;

export const isMaxCompatibleDestination = (
  destinationType: DestinationTypes,
  request: ParsedBip321 | null,
): boolean =>
  destinationType === "onchain" ||
  (destinationType === "bip321" && request?.onchainAddress !== undefined);

export const getNextSendStage = (progress: SendFlowProgress): SendStage => {
  if (progress.entry === "max") {
    if (!progress.sourceConfirmed) {
      return "source";
    }
    return progress.recipientConfirmed ? "review" : "recipient";
  }

  const primaryStages =
    progress.entry === "amount-first"
      ? (["amount", "recipient"] as const)
      : (["recipient", "amount"] as const);

  for (const stage of primaryStages) {
    if (stage === "amount" && !progress.amountConfirmed) {
      return "amount";
    }
    if (stage === "recipient" && !progress.recipientConfirmed) {
      return "recipient";
    }
  }

  if (
    (progress.rails.length > 1 || progress.selectedRailAvailable === false) &&
    !progress.railConfirmed
  ) {
    return "method";
  }

  if (
    progress.selectedRail === "onchain" &&
    progress.sourceOptions.length !== 1 &&
    !progress.sourceConfirmed
  ) {
    return "source";
  }

  return "review";
};
