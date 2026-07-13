import { useQuery } from "@tanstack/react-query";
import { validateBitcoinAddress } from "bip-321";
import ky from "ky";
import { getEsploraApiBaseUrl } from "~/lib/esplora";
import { history, onchainTransactions } from "~/lib/paymentsApi";
import { loadWalletIfNeeded } from "~/lib/walletApi";
import { useWalletStore } from "~/store/walletStore";
import type { Transaction, PaymentTypes } from "~/types/transaction";
import type { BarkMovement, MovementStatus, OnchainTransactionInfo } from "react-native-nitro-ark";
import type { MovementKind } from "~/types/movement";
import { INCOMING_MOVEMENT_KINDS } from "~/types/movement";
import { getHistoricalBtcToFiatRate } from "~/hooks/useMarketData";
import logger from "~/lib/log";
import type { FiatCurrencyCode } from "~/lib/fiatCurrency";
import { useProfileStore } from "~/store/profileStore";
import { useEsploraStore } from "~/store/esploraStore";
import {
  BARK_SUBSYSTEM,
  type BarkSubsystemId,
  getMovementDestinationValue,
  getMovementSubsystemId,
  getMovementSubsystemKind,
  getMovementSubsystemName,
  isBoardingHistoryMovement,
} from "~/lib/barkMovement";

const log = logger("useTransactions");
const UNKNOWN_ONCHAIN_DATE_ISO = new Date(0).toISOString();

type EsploraTransactionResponse = {
  status?: {
    confirmed?: boolean;
    block_height?: number;
    block_hash?: string;
    block_time?: number;
  };
};

const SUBSYSTEM_KIND_TO_MOVEMENT_KIND: Partial<Record<BarkSubsystemId, MovementKind>> = {
  [`${BARK_SUBSYSTEM.BOARD.name}:${BARK_SUBSYSTEM.BOARD.kind}`]: "onboard",
  [`${BARK_SUBSYSTEM.OFFBOARD.name}:${BARK_SUBSYSTEM.OFFBOARD.kind}`]: "offboard",
  [`${BARK_SUBSYSTEM.SEND_ONCHAIN.name}:${BARK_SUBSYSTEM.SEND_ONCHAIN.kind}`]: "send-onchain",
  [`${BARK_SUBSYSTEM.ARKOOR_RECEIVE.name}:${BARK_SUBSYSTEM.ARKOOR_RECEIVE.kind}`]: "arkoor-receive",
  [`${BARK_SUBSYSTEM.ROUND_OFFBOARD.name}:${BARK_SUBSYSTEM.ROUND_OFFBOARD.kind}`]: "offboard",
  [`${BARK_SUBSYSTEM.ROUND_SEND_ONCHAIN.name}:${BARK_SUBSYSTEM.ROUND_SEND_ONCHAIN.kind}`]:
    "send-onchain",
  [`${BARK_SUBSYSTEM.EXIT_START.name}:${BARK_SUBSYSTEM.EXIT_START.kind}`]: "exit",
  [`${BARK_SUBSYSTEM.LIGHTNING_RECEIVE.name}:${BARK_SUBSYSTEM.LIGHTNING_RECEIVE.kind}`]:
    "lightning-receive",
};

const OUTGOING_SUBSYSTEM_KEYS = new Set<BarkSubsystemId>([
  `${BARK_SUBSYSTEM.OFFBOARD.name}:${BARK_SUBSYSTEM.OFFBOARD.kind}`,
  `${BARK_SUBSYSTEM.SEND_ONCHAIN.name}:${BARK_SUBSYSTEM.SEND_ONCHAIN.kind}`,
  `${BARK_SUBSYSTEM.ROUND_OFFBOARD.name}:${BARK_SUBSYSTEM.ROUND_OFFBOARD.kind}`,
  `${BARK_SUBSYSTEM.ROUND_SEND_ONCHAIN.name}:${BARK_SUBSYSTEM.ROUND_SEND_ONCHAIN.kind}`,
  `${BARK_SUBSYSTEM.ARKOOR_SEND.name}:${BARK_SUBSYSTEM.ARKOOR_SEND.kind}`,
  `${BARK_SUBSYSTEM.LIGHTNING_SEND.name}:${BARK_SUBSYSTEM.LIGHTNING_SEND.kind}`,
  `${BARK_SUBSYSTEM.EXIT_START.name}:${BARK_SUBSYSTEM.EXIT_START.kind}`,
]);

const INCOMING_MOVEMENT_KIND_SET = new Set<MovementKind>(INCOMING_MOVEMENT_KINDS);

const determineMovementKind = (movement: BarkMovement): MovementKind | undefined => {
  const subsystemId = getMovementSubsystemId(movement);
  if (!subsystemId) {
    return undefined;
  }

  return SUBSYSTEM_KIND_TO_MOVEMENT_KIND[subsystemId];
};

const isOutgoingMovement = (movement: BarkMovement): boolean => {
  const subsystemId = getMovementSubsystemId(movement);

  if (!subsystemId) {
    if (movement.effective_balance_sat !== undefined && movement.effective_balance_sat < 0) {
      return true;
    }
    return false;
  }

  if (OUTGOING_SUBSYSTEM_KEYS.has(subsystemId)) {
    return true;
  }

  const movementKind = determineMovementKind(movement);
  if (movementKind && INCOMING_MOVEMENT_KIND_SET.has(movementKind)) {
    return false;
  }

  if (movement.effective_balance_sat !== undefined && movement.effective_balance_sat < 0) {
    return true;
  }

  return false;
};

const sumDestinationAmounts = (destinations: { amount_sat: number }[] | undefined): number => {
  if (!destinations || destinations.length === 0) {
    return 0;
  }
  return destinations.reduce((sum, dest) => sum + dest.amount_sat, 0);
};

const getMovementAmount = (movement: BarkMovement, isOutgoing: boolean): number => {
  const routedAmount = isOutgoing
    ? sumDestinationAmounts(movement.sent_to)
    : sumDestinationAmounts(movement.received_on);

  if (routedAmount > 0) {
    return routedAmount;
  }

  return Math.abs(movement.effective_balance_sat ?? 0);
};

const getMovementDateIso = (createdAt: string | undefined): string => {
  if (!createdAt) {
    return new Date().toISOString();
  }

  let parsed = new Date(createdAt);
  if (Number.isNaN(parsed.getTime()) && !createdAt.endsWith("Z")) {
    parsed = new Date(`${createdAt}Z`);
  }

  return Number.isNaN(parsed.getTime()) ? new Date().toISOString() : parsed.toISOString();
};

const getUniqueMovementId = (movement: BarkMovement, isOutgoing: boolean): string => {
  const candidateVtxos = isOutgoing
    ? [...movement.input_vtxos, ...movement.output_vtxos, ...movement.exited_vtxos]
    : [...movement.output_vtxos, ...movement.input_vtxos, ...movement.exited_vtxos];

  const vtxoId = candidateVtxos.find((id) => typeof id === "string" && id.length > 0);
  return vtxoId ?? `movement-${movement.id}`;
};

const hasBitcoinAddressDestination = (movement: BarkMovement, isOutgoing: boolean): boolean => {
  const destinations = isOutgoing ? movement.sent_to : movement.received_on;

  if (!destinations || destinations.length === 0) {
    return false;
  }

  return destinations.some((destination) => {
    const destinationValue = getMovementDestinationValue(destination);
    return destinationValue ? validateBitcoinAddress(destinationValue).valid : false;
  });
};

const determineTransactionType = (
  movement: BarkMovement,
  movementKind: MovementKind | undefined,
  isOutgoing: boolean,
): PaymentTypes => {
  if (movementKind === "lightning-receive") {
    return "Bolt11";
  }

  // Offboarding can currently surface as an Ark movement even when funds are headed to a
  // Bitcoin address. Prefer the user-facing Onchain label in that case.
  if (hasBitcoinAddressDestination(movement, isOutgoing)) {
    return "Onchain";
  }

  if (movementKind === "arkoor-receive") {
    return "Arkoor";
  }

  const subsystemName = getMovementSubsystemName(movement);
  const subsystemKind = getMovementSubsystemKind(movement);

  if (
    subsystemName === BARK_SUBSYSTEM.LIGHTNING_SEND.name &&
    subsystemKind === BARK_SUBSYSTEM.LIGHTNING_SEND.kind
  ) {
    return "Bolt11";
  }

  if (
    subsystemName === BARK_SUBSYSTEM.ARKOOR_SEND.name &&
    subsystemKind === BARK_SUBSYSTEM.ARKOOR_SEND.kind
  ) {
    return "Arkoor";
  }

  if (
    movementKind === "offboard" ||
    movementKind === "onboard" ||
    movementKind === "send-onchain" ||
    movementKind === "exit"
  ) {
    return "Onchain";
  }

  if (isOutgoing && movement.sent_to && movement.sent_to.length > 0) {
    return "Arkoor";
  }

  return "Onchain";
};

const transformMovementToTransaction = async (
  movement: BarkMovement,
  currency: FiatCurrencyCode,
): Promise<Transaction> => {
  const movementKind = determineMovementKind(movement);
  const isOutgoing = isOutgoingMovement(movement);
  const direction = isOutgoing ? "outgoing" : "incoming";

  const createdAt =
    (movement as { time?: { created_at?: string } }).time?.created_at ?? movement.created_at;
  const dateIso = getMovementDateIso(createdAt);
  const amount = getMovementAmount(movement, isOutgoing);
  const txid = getUniqueMovementId(movement, isOutgoing);
  const transactionType = determineTransactionType(movement, movementKind, isOutgoing);

  let btcPrice: number | undefined;
  const btcPriceResult = await getHistoricalBtcToFiatRate(dateIso, currency);
  if (btcPriceResult.isOk()) {
    btcPrice = btcPriceResult.value;
  }

  const destinationEntry = isOutgoing
    ? movement.sent_to?.[0]?.destination
    : movement.received_on?.[0]?.destination;
  const destination = getMovementDestinationValue({ destination: destinationEntry });

  return {
    id: `movement-${movement.id}`,
    txid,
    amount,
    date: dateIso,
    sortTimestamp: new Date(dateIso).getTime(),
    direction,
    type: transactionType,
    source: "ark",
    btcPrice,
    description: "",
    destination: destination ?? "",
    movementId: movement.id,
    movementStatus: movement.status as MovementStatus,
    movementKind,
    subsystemName: getMovementSubsystemName(movement),
    subsystemKind: getMovementSubsystemKind(movement),
    metadataJson: movement.metadata_json,
    intendedBalanceSat: movement.intended_balance_sat,
    effectiveBalanceSat: movement.effective_balance_sat,
    offchainFeeSat: movement.offchain_fee_sat,
    sentTo: movement.sent_to.map((destination) => ({
      destination: getMovementDestinationValue(destination) ?? "",
      amount_sat: destination.amount_sat,
    })),
    receivedOn: movement.received_on.map((destination) => ({
      destination: getMovementDestinationValue(destination) ?? "",
      amount_sat: destination.amount_sat,
    })),
    inputVtxos: movement.input_vtxos,
    outputVtxos: movement.output_vtxos,
    exitedVtxos: movement.exited_vtxos,
  };
};

const shouldIncludeOnchainTransaction = (transaction: OnchainTransactionInfo): boolean => {
  return transaction.balance_change_sat !== 0;
};

const getOnchainTransactionLabel = (transaction: OnchainTransactionInfo): string => {
  if (transaction.has_confirmation) {
    return `Confirmed at block ${transaction.confirmation_height}`;
  }

  return "Unconfirmed";
};

const getOnchainTransactionBlockTime = async (
  transaction: OnchainTransactionInfo,
): Promise<number | undefined> => {
  if (!transaction.has_confirmation) {
    return undefined;
  }

  const esploraBaseUrl = getEsploraApiBaseUrl();
  if (!esploraBaseUrl) {
    return undefined;
  }

  try {
    const response = await ky
      .get(`${esploraBaseUrl}/tx/${transaction.txid}`)
      .json<EsploraTransactionResponse>();

    if (!response.status?.confirmed || typeof response.status.block_time !== "number") {
      return undefined;
    }

    return response.status.block_time;
  } catch (error) {
    log.w("Failed to fetch onchain transaction timestamp", [transaction.txid, error]);
    return undefined;
  }
};

const transformOnchainTransaction = async (
  transaction: OnchainTransactionInfo,
  currency: FiatCurrencyCode,
): Promise<Transaction> => {
  const isOutgoing = transaction.balance_change_sat < 0;
  const blockTime = await getOnchainTransactionBlockTime(transaction);
  const date = blockTime ? new Date(blockTime * 1000).toISOString() : UNKNOWN_ONCHAIN_DATE_ISO;

  let btcPrice: number | undefined;
  if (blockTime) {
    const btcPriceResult = await getHistoricalBtcToFiatRate(date, currency);
    if (btcPriceResult.isOk()) {
      btcPrice = btcPriceResult.value;
    }
  }

  return {
    id: `onchain-wallet-${transaction.txid}`,
    txid: transaction.txid,
    txHex: transaction.tx_hex,
    amount: Math.abs(transaction.balance_change_sat),
    date,
    dateLabel: blockTime ? undefined : getOnchainTransactionLabel(transaction),
    sortTimestamp: blockTime ? blockTime * 1000 : undefined,
    sortHeight: transaction.has_confirmation ? transaction.confirmation_height : undefined,
    direction: isOutgoing ? "outgoing" : "incoming",
    type: "Onchain",
    source: "onchain-wallet",
    btcPrice,
    description: "",
    balanceChangeSat: transaction.balance_change_sat,
    hasOnchainFee: transaction.has_onchain_fee,
    onchainFeeSat: transaction.has_onchain_fee ? transaction.onchain_fee_sat : undefined,
    hasConfirmation: transaction.has_confirmation,
    confirmationHeight: transaction.has_confirmation ? transaction.confirmation_height : undefined,
    confirmationHash: transaction.has_confirmation ? transaction.confirmation_hash : undefined,
  };
};

const compareTransactions = (a: Transaction, b: Transaction): number => {
  const aIsOnchainWallet = a.source === "onchain-wallet";
  const bIsOnchainWallet = b.source === "onchain-wallet";
  const aIsUnconfirmedOnchainWallet = aIsOnchainWallet && !a.hasConfirmation;
  const bIsUnconfirmedOnchainWallet = bIsOnchainWallet && !b.hasConfirmation;
  const aHasReliableTimestamp = typeof a.sortTimestamp === "number" && a.sortTimestamp > 0;
  const bHasReliableTimestamp = typeof b.sortTimestamp === "number" && b.sortTimestamp > 0;

  if (aIsUnconfirmedOnchainWallet !== bIsUnconfirmedOnchainWallet) {
    return aIsUnconfirmedOnchainWallet ? -1 : 1;
  }

  if (aHasReliableTimestamp && bHasReliableTimestamp) {
    return (b.sortTimestamp ?? 0) - (a.sortTimestamp ?? 0);
  }

  if (aIsOnchainWallet && bIsOnchainWallet) {
    return (b.sortHeight ?? 0) - (a.sortHeight ?? 0);
  }

  if (aHasReliableTimestamp !== bHasReliableTimestamp) {
    return aHasReliableTimestamp ? -1 : 1;
  }

  const aSortTimestamp = a.sortTimestamp ?? new Date(a.date).getTime();
  const bSortTimestamp = b.sortTimestamp ?? new Date(b.date).getTime();
  return bSortTimestamp - aSortTimestamp;
};

const shouldIncludeMovement = (movement: BarkMovement): boolean => {
  if (movement.status === "failed") {
    return false;
  }

  if (isBoardingHistoryMovement(movement)) {
    return false;
  }

  const hasAmount =
    (movement.intended_balance_sat !== undefined && movement.intended_balance_sat !== 0) ||
    (movement.effective_balance_sat !== undefined && movement.effective_balance_sat !== 0);

  return hasAmount;
};

const fetchAndTransformTransactions = async (
  currency: FiatCurrencyCode,
): Promise<Transaction[]> => {
  const loadWalletResult = await loadWalletIfNeeded();
  if (loadWalletResult.isErr()) {
    throw loadWalletResult.error;
  }

  if (!loadWalletResult.value) {
    return [];
  }

  const [movementsResult, onchainTransactionsResult] = await Promise.all([
    history(),
    onchainTransactions(),
  ]);

  if (movementsResult.isErr()) {
    log.e("Failed to fetch movements:", [movementsResult.error]);
    throw movementsResult.error;
  }

  if (onchainTransactionsResult.isErr()) {
    log.e("Failed to fetch onchain wallet transactions:", [onchainTransactionsResult.error]);
    throw onchainTransactionsResult.error;
  }

  const movements = movementsResult.value.filter(shouldIncludeMovement);
  const walletTransactions = onchainTransactionsResult.value.filter(
    shouldIncludeOnchainTransaction,
  );

  if (movements.length === 0 && walletTransactions.length === 0) {
    return [];
  }

  const movementTransactions = await Promise.all(
    movements.map((movement) => transformMovementToTransaction(movement, currency)),
  );
  const onchainWalletTransactions = await Promise.all(
    walletTransactions.map((transaction) => transformOnchainTransaction(transaction, currency)),
  );

  return [...movementTransactions, ...onchainWalletTransactions].sort(compareTransactions);
};

export const useTransactions = (options?: { enabled?: boolean }) => {
  const { isInitialized, isWalletLoaded, isWalletSuspended } = useWalletStore();
  const preferredCurrency = useProfileStore((state) => state.preferredCurrency);
  const endpointOverride = useEsploraStore((state) => state.endpointOverride);
  const enabled =
    (options?.enabled ?? true) && isInitialized && isWalletLoaded && !isWalletSuspended;

  return useQuery({
    queryKey: ["transactions", preferredCurrency, endpointOverride],
    queryFn: () => fetchAndTransformTransactions(preferredCurrency),
    enabled,
    staleTime: 30 * 1000,
    refetchOnWindowFocus: true,
    retry: false,
  });
};

export const useTransaction = (transactionId: string) => {
  const { data: transactions, ...rest } = useTransactions();

  const transaction = transactions?.find((t) => t.id === transactionId);

  return {
    data: transaction,
    ...rest,
  };
};
