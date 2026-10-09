import { sourceText, type Translate } from "~/lib/i18n";
import type { MovementStatus } from "react-native-nitro-ark";

export const MOVEMENT_KIND_VALUES = [
  "arkoor-receive",
  "onboard",
  "offboard",
  "send-onchain",
  "exit",
  "lightning-receive",
] as const;
export type MovementKind = (typeof MOVEMENT_KIND_VALUES)[number];

export const INCOMING_MOVEMENT_KINDS: MovementKind[] = [
  "arkoor-receive",
  "onboard",
  "lightning-receive",
];

export const formatMovementKindLabel = (
  kind?: MovementKind,
  gt: Translate = sourceText,
): string | undefined => {
  const MOVEMENT_KIND_LABELS: Record<MovementKind, string> = {
    "arkoor-receive": gt("Ark Receive"),
    onboard: gt("Board"),
    offboard: gt("Offboard"),
    "send-onchain": gt("Onchain Send"),
    exit: gt("Ark Exit"),
    "lightning-receive": gt("Lightning Receive"),
  };
  if (!kind) {
    return undefined;
  }

  return MOVEMENT_KIND_LABELS[kind] ?? kind;
};

export const formatMovementStatusLabel = (
  status?: MovementStatus,
  gt: Translate = sourceText,
): string | undefined => {
  const MOVEMENT_STATUS_LABELS: Record<MovementStatus, string> = {
    pending: gt("Pending"),
    successful: gt("Successful"),
    failed: gt("Failed"),
    canceled: gt("Canceled"),
  };
  if (!status) {
    return undefined;
  }

  return MOVEMENT_STATUS_LABELS[status] ?? status;
};
