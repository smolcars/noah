import type { MovementStatus } from "react-native-nitro-ark";

export const MOVEMENT_KIND_VALUES = [
  "arkoor-receive",
  "onboard",
  "offboard",
  "exit",
  "lightning-receive",
] as const;
export type MovementKind = (typeof MOVEMENT_KIND_VALUES)[number];

export const MOVEMENT_KIND_LABELS: Record<MovementKind, string> = {
  "arkoor-receive": "Ark Receive",
  onboard: "Board",
  offboard: "Offboard",
  exit: "Ark Exit",
  "lightning-receive": "Lightning Receive",
};

export const INCOMING_MOVEMENT_KINDS: MovementKind[] = [
  "arkoor-receive",
  "onboard",
  "lightning-receive",
];

export const MOVEMENT_STATUS_LABELS: Record<MovementStatus, string> = {
  pending: "Pending",
  successful: "Successful",
  failed: "Failed",
  canceled: "Canceled",
};

export const formatMovementKindLabel = (kind?: MovementKind): string | undefined => {
  if (!kind) {
    return undefined;
  }

  return MOVEMENT_KIND_LABELS[kind] ?? kind;
};

export const formatMovementStatusLabel = (status?: MovementStatus): string | undefined => {
  if (!status) {
    return undefined;
  }

  return MOVEMENT_STATUS_LABELS[status] ?? status;
};
