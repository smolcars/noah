import type { BarkMovement } from "react-native-nitro-ark";

type MovementDestinationLike = {
  destination: unknown;
};

export const BARK_SUBSYSTEM = {
  BOARD: {
    name: "bark.board",
    kind: "board",
  },
  OFFBOARD: {
    name: "bark.offboard",
    kind: "offboard",
  },
  SEND_ONCHAIN: {
    name: "bark.offboard",
    kind: "send_onchain",
  },
  ARKOOR_RECEIVE: {
    name: "bark.arkoor",
    kind: "receive",
  },
  ARKOOR_SEND: {
    name: "bark.arkoor",
    kind: "send",
  },
  ROUND_OFFBOARD: {
    name: "bark.round",
    kind: "offboard",
  },
  ROUND_SEND_ONCHAIN: {
    name: "bark.round",
    kind: "send_onchain",
  },
  EXIT_START: {
    name: "bark.exit",
    kind: "start",
  },
  LIGHTNING_RECEIVE: {
    name: "bark.lightning_receive",
    kind: "receive",
  },
  LIGHTNING_SEND: {
    name: "bark.lightning_send",
    kind: "send",
  },
} as const;

export type BarkSubsystem = (typeof BARK_SUBSYSTEM)[keyof typeof BARK_SUBSYSTEM];
export type BarkSubsystemName = BarkSubsystem["name"];
export type BarkSubsystemKind = BarkSubsystem["kind"];
export type BarkSubsystemId = `${BarkSubsystemName}:${BarkSubsystemKind}`;

type BarkMovementWithTypedSubsystem<
  Name extends BarkSubsystemName,
  Kind extends BarkSubsystemKind,
> = BarkMovement & {
  subsystem: BarkMovement["subsystem"] & {
    name: Name;
    kind: Kind;
  };
};

const toSubsystemId = (subsystem: BarkSubsystem): BarkSubsystemId =>
  `${subsystem.name}:${subsystem.kind}`;

const KNOWN_BARK_SUBSYSTEM_IDS = new Set<BarkSubsystemId>(
  Object.values(BARK_SUBSYSTEM).map(toSubsystemId),
);

export const getMovementSubsystemId = (movement: BarkMovement): BarkSubsystemId | undefined => {
  const subsystemName = movement.subsystem?.name?.toLowerCase();
  const subsystemKind = movement.subsystem?.kind?.toLowerCase();

  if (!subsystemName || !subsystemKind) {
    return undefined;
  }

  const subsystemId = `${subsystemName}:${subsystemKind}`;
  if (!KNOWN_BARK_SUBSYSTEM_IDS.has(subsystemId as BarkSubsystemId)) {
    return undefined;
  }

  return subsystemId as BarkSubsystemId;
};

export const getMovementSubsystemName = (movement: BarkMovement): BarkSubsystemName | undefined => {
  const subsystemId = getMovementSubsystemId(movement);
  if (!subsystemId) {
    return undefined;
  }

  return subsystemId.split(":")[0] as BarkSubsystemName;
};

export const getMovementSubsystemKind = (movement: BarkMovement): BarkSubsystemKind | undefined => {
  const subsystemId = getMovementSubsystemId(movement);
  if (!subsystemId) {
    return undefined;
  }

  return subsystemId.split(":")[1] as BarkSubsystemKind;
};

export const isArkReceiveMovement = (
  movement: BarkMovement | undefined,
): movement is BarkMovementWithTypedSubsystem<
  typeof BARK_SUBSYSTEM.ARKOOR_RECEIVE.name,
  typeof BARK_SUBSYSTEM.ARKOOR_RECEIVE.kind
> => {
  if (!movement) {
    return false;
  }

  return getMovementSubsystemId(movement) === toSubsystemId(BARK_SUBSYSTEM.ARKOOR_RECEIVE);
};

export const isLightningReceiveMovement = (
  movement: BarkMovement | undefined,
): movement is BarkMovementWithTypedSubsystem<
  typeof BARK_SUBSYSTEM.LIGHTNING_RECEIVE.name,
  typeof BARK_SUBSYSTEM.LIGHTNING_RECEIVE.kind
> => {
  if (!movement) {
    return false;
  }

  return getMovementSubsystemId(movement) === toSubsystemId(BARK_SUBSYSTEM.LIGHTNING_RECEIVE);
};

export const isFailedOrCanceledMovement = (
  movement: BarkMovement,
): movement is BarkMovement & { status: "failed" | "canceled" } =>
  movement.status === "failed" || movement.status === "canceled";

export const isBoardMovement = (
  movement: BarkMovement | undefined,
): movement is BarkMovementWithTypedSubsystem<
  typeof BARK_SUBSYSTEM.BOARD.name,
  typeof BARK_SUBSYSTEM.BOARD.kind
> => {
  if (!movement) {
    return false;
  }

  return getMovementSubsystemId(movement) === toSubsystemId(BARK_SUBSYSTEM.BOARD);
};

export const isOffboardMovement = (movement: BarkMovement | undefined): boolean => {
  if (!movement) {
    return false;
  }

  const subsystemId = getMovementSubsystemId(movement);
  return (
    subsystemId === toSubsystemId(BARK_SUBSYSTEM.OFFBOARD) ||
    subsystemId === toSubsystemId(BARK_SUBSYSTEM.ROUND_OFFBOARD)
  );
};

export const isSendOnchainMovement = (movement: BarkMovement | undefined): boolean => {
  if (!movement) {
    return false;
  }

  const subsystemId = getMovementSubsystemId(movement);
  return (
    subsystemId === toSubsystemId(BARK_SUBSYSTEM.SEND_ONCHAIN) ||
    subsystemId === toSubsystemId(BARK_SUBSYSTEM.ROUND_SEND_ONCHAIN)
  );
};

export const isBoardingHistoryMovement = (movement: BarkMovement | undefined): boolean =>
  isBoardMovement(movement) || isOffboardMovement(movement);

export const getMovementDestinationValue = (
  destination: MovementDestinationLike | undefined,
): string | undefined => {
  if (!destination) {
    return undefined;
  }

  if (typeof destination.destination === "string") {
    return destination.destination;
  }

  if (
    destination.destination &&
    typeof destination.destination === "object" &&
    "value" in destination.destination
  ) {
    const value = destination.destination.value;
    return typeof value === "string" ? value : undefined;
  }

  return undefined;
};
