import { useQuery } from "@tanstack/react-query";
import type { BarkMovement } from "react-native-nitro-ark";
import { history } from "~/lib/paymentsApi";
import {
  getMovementDestinationValue,
  getMovementSubsystemKind,
  getMovementSubsystemName,
  isBoardMovement,
  isOffboardMovement,
} from "~/lib/barkMovement";
import type { BoardingTransaction } from "~/types/boardingTransaction";
import logger from "~/lib/log";

const log = logger("useBoardingTransactions");

type BoardingMetadata = {
  offboard_tx?: string;
  offboard_txid?: string;
  onchain_fee_sat?: number;
  chain_anchor?: string;
};

const parseMetadata = (metadataJson: string): BoardingMetadata => {
  if (!metadataJson) {
    return {};
  }

  try {
    const parsed: unknown = JSON.parse(metadataJson);
    if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
      return {};
    }

    const metadata = parsed as Record<string, unknown>;
    return {
      offboard_tx: typeof metadata.offboard_tx === "string" ? metadata.offboard_tx : undefined,
      offboard_txid:
        typeof metadata.offboard_txid === "string" ? metadata.offboard_txid : undefined,
      onchain_fee_sat:
        typeof metadata.onchain_fee_sat === "number" ? metadata.onchain_fee_sat : undefined,
      chain_anchor: typeof metadata.chain_anchor === "string" ? metadata.chain_anchor : undefined,
    };
  } catch (error) {
    log.w("Failed to parse boarding movement metadata", [error]);
    return {};
  }
};

const getMovementDateIso = (movement: BarkMovement): string => {
  const createdAt =
    (movement as { time?: { created_at?: string } }).time?.created_at ??
    movement.completed_at ??
    movement.created_at;

  if (!createdAt) {
    return new Date().toISOString();
  }

  let parsed = new Date(createdAt);
  if (Number.isNaN(parsed.getTime()) && !createdAt.endsWith("Z")) {
    parsed = new Date(`${createdAt}Z`);
  }

  return Number.isNaN(parsed.getTime()) ? new Date().toISOString() : parsed.toISOString();
};

const getBoardingAmount = (movement: BarkMovement): number => {
  const intendedAmount = Math.abs(movement.intended_balance_sat ?? 0);
  if (intendedAmount > 0) {
    return intendedAmount;
  }

  const effectiveAmount = Math.abs(movement.effective_balance_sat ?? 0);
  if (effectiveAmount > 0) {
    return effectiveAmount;
  }

  const sentAmount = movement.sent_to.reduce((sum, destination) => sum + destination.amount_sat, 0);
  if (sentAmount > 0) {
    return sentAmount;
  }

  return movement.received_on.reduce((sum, destination) => sum + destination.amount_sat, 0);
};

const getBoardingTxid = (
  movement: BarkMovement,
  metadata: BoardingMetadata,
): string | undefined => {
  if (metadata.offboard_txid) {
    return metadata.offboard_txid;
  }

  if (metadata.chain_anchor) {
    return metadata.chain_anchor;
  }

  const candidateVtxo = [...movement.output_vtxos, ...movement.input_vtxos, ...movement.exited_vtxos]
    .find((id) => typeof id === "string" && id.length > 0);

  return candidateVtxo;
};

const normalizeDestinations = (destinations: BarkMovement["sent_to"]) =>
  destinations.map((destination) => ({
    destination: getMovementDestinationValue(destination) ?? "",
    amount_sat: destination.amount_sat,
  }));

const transformMovementToBoardingTransaction = (
  movement: BarkMovement,
): BoardingTransaction | null => {
  const type = isBoardMovement(movement)
    ? "onboarding"
    : isOffboardMovement(movement)
      ? "offboarding"
      : null;

  if (!type) {
    return null;
  }

  const metadata = parseMetadata(movement.metadata_json);
  const sentTo = normalizeDestinations(movement.sent_to);
  const receivedOn = normalizeDestinations(movement.received_on);
  const destination = type === "offboarding" ? sentTo[0]?.destination : undefined;

  return {
    id: `movement-${movement.id}`,
    movementId: movement.id,
    type,
    date: getMovementDateIso(movement),
    status: movement.status,
    amountSat: getBoardingAmount(movement),
    txid: getBoardingTxid(movement, metadata),
    chainAnchor: metadata.chain_anchor,
    onchainFeeSat: metadata.onchain_fee_sat,
    offchainFeeSat: movement.offchain_fee_sat,
    destination,
    subsystemName: getMovementSubsystemName(movement),
    subsystemKind: getMovementSubsystemKind(movement),
    metadataJson: movement.metadata_json,
    intendedBalanceSat: movement.intended_balance_sat,
    effectiveBalanceSat: movement.effective_balance_sat,
    sentTo,
    receivedOn,
    inputVtxos: movement.input_vtxos,
    outputVtxos: movement.output_vtxos,
    exitedVtxos: movement.exited_vtxos,
  };
};

const fetchBoardingTransactions = async (): Promise<BoardingTransaction[]> => {
  const movementsResult = await history();

  if (movementsResult.isErr()) {
    log.e("Failed to fetch boarding movements:", [movementsResult.error]);
    throw movementsResult.error;
  }

  return movementsResult.value
    .map(transformMovementToBoardingTransaction)
    .filter((transaction): transaction is BoardingTransaction => transaction !== null)
    .sort((a, b) => new Date(b.date).getTime() - new Date(a.date).getTime());
};

export const useBoardingTransactions = () => {
  return useQuery({
    queryKey: ["boarding-transactions"],
    queryFn: fetchBoardingTransactions,
    staleTime: 30 * 1000,
    refetchOnWindowFocus: true,
  });
};
