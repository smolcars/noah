import type { MovementStatus } from "react-native-nitro-ark";
import type { BarkSubsystemKind, BarkSubsystemName } from "~/lib/barkMovement";
import type { MovementDestination } from "~/types/transaction";

export type BoardingTransactionType = "onboarding" | "offboarding";

export type BoardingTransaction = {
  id: string;
  movementId: number;
  type: BoardingTransactionType;
  date: string;
  status: MovementStatus;
  amountSat: number;
  txid?: string;
  chainAnchor?: string;
  onchainFeeSat?: number;
  offchainFeeSat?: number;
  destination?: string;
  subsystemName?: BarkSubsystemName;
  subsystemKind?: BarkSubsystemKind;
  metadataJson?: string;
  intendedBalanceSat?: number;
  effectiveBalanceSat?: number;
  sentTo: MovementDestination[];
  receivedOn: MovementDestination[];
  inputVtxos: string[];
  outputVtxos: string[];
  exitedVtxos: string[];
};
