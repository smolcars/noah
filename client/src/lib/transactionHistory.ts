import type { BarkMovement } from "react-native-nitro-ark";

import type { Transaction } from "~/types/transaction";

export type MovementMetadata = {
  offboardTxid?: string;
  onchainFeeSat?: number;
  chainAnchor?: string;
};

export const parseMovementMetadata = (metadataJson: string): MovementMetadata => {
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
      offboardTxid: typeof metadata.offboard_txid === "string" ? metadata.offboard_txid : undefined,
      onchainFeeSat:
        typeof metadata.onchain_fee_sat === "number" ? metadata.onchain_fee_sat : undefined,
      chainAnchor: typeof metadata.chain_anchor === "string" ? metadata.chain_anchor : undefined,
    };
  } catch {
    return {};
  }
};

export const getMovementTransactionId = (
  movement: BarkMovement,
  metadata: MovementMetadata,
  isOutgoing: boolean,
): string => {
  const chainAnchorTxid = metadata.chainAnchor?.replace(/:\d+$/, "");
  const metadataTxid = metadata.offboardTxid ?? chainAnchorTxid;
  if (metadataTxid) {
    return metadataTxid;
  }

  const candidateVtxos = isOutgoing
    ? [...movement.input_vtxos, ...movement.output_vtxos, ...movement.exited_vtxos]
    : [...movement.output_vtxos, ...movement.input_vtxos, ...movement.exited_vtxos];
  const vtxoId = candidateVtxos.find((id) => typeof id === "string" && id.length > 0);
  return vtxoId ?? `movement-${movement.id}`;
};

export const getBoardingMovementAmount = (movement: BarkMovement): number => {
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

const isBoardingTransaction = (transaction: Transaction): boolean =>
  transaction.movementKind === "onboard" || transaction.movementKind === "offboard";

export const mergeBoardingWithOnchainTransactions = (
  movementTransactions: Transaction[],
  onchainWalletTransactions: Transaction[],
): Transaction[] => {
  const onchainByTxid = new Map(
    onchainWalletTransactions
      .filter((transaction) => transaction.txid)
      .map((transaction) => [transaction.txid as string, transaction]),
  );
  const mergedOnchainIds = new Set<string>();

  const mergedMovements = movementTransactions.map((transaction) => {
    if (!isBoardingTransaction(transaction) || !transaction.txid) {
      return transaction;
    }

    const onchainTransaction = onchainByTxid.get(transaction.txid);
    if (!onchainTransaction) {
      return transaction;
    }

    mergedOnchainIds.add(onchainTransaction.id);
    return {
      ...transaction,
      txHex: onchainTransaction.txHex,
      btcPrice: transaction.btcPrice ?? onchainTransaction.btcPrice,
      balanceChangeSat: onchainTransaction.balanceChangeSat,
      hasOnchainFee: onchainTransaction.hasOnchainFee,
      onchainFeeSat: transaction.onchainFeeSat ?? onchainTransaction.onchainFeeSat,
      hasConfirmation: onchainTransaction.hasConfirmation,
      confirmationHeight: onchainTransaction.confirmationHeight,
      confirmationHash: onchainTransaction.confirmationHash,
      sortHeight: onchainTransaction.sortHeight,
    };
  });

  return [
    ...mergedMovements,
    ...onchainWalletTransactions.filter((transaction) => !mergedOnchainIds.has(transaction.id)),
  ];
};

export const getTransactionDisplayLabel = (transaction: Transaction): string => {
  if (transaction.movementKind === "onboard") {
    return "Board";
  }

  if (transaction.movementKind === "offboard") {
    return "Offboard";
  }

  if (transaction.type === "Bolt11" || transaction.type === "Lnurl") {
    return "Lightning";
  }

  if (transaction.type === "Arkoor") {
    return "Ark";
  }

  return transaction.type;
};

export const isInternalBoardingTransfer = (transaction: Transaction): boolean =>
  transaction.movementKind === "onboard";
