import { sourceText, type Translate } from "~/lib/i18n";
import type {
  ExitProgressState,
  ExitStateDetails,
  ExitStatusResult,
  ExitVtxoResult,
} from "react-native-nitro-ark";
import { getMempoolTxUrl } from "~/constants";

export const EXIT_STATE_ORDER: ExitProgressState[] = [
  "Start",
  "Processing",
  "AwaitingDelta",
  "Claimable",
  "ClaimInProgress",
  "Claimed",
  "VtxoAlreadySpent",
  "Canceled",
];

export const getExitStateLabels = (
  gt: Translate = sourceText,
): Record<ExitProgressState, string> => ({
  Start: gt("Started"),
  Processing: gt("Processing"),
  AwaitingDelta: gt("Waiting"),
  Claimable: gt("Claimable"),
  ClaimInProgress: gt("Claiming"),
  Claimed: gt("Claimed"),
  VtxoAlreadySpent: gt("Already spent"),
  Canceled: gt("Canceled"),
});

export type ExitDetailRow = {
  label: string;
  value: string;
  explorerUrl?: string | null;
};

export type ExitTimelineItem = {
  state: ExitProgressState;
  label: string;
  count: number;
  startHeight?: number;
  endHeight?: number;
  description: string;
  details: ExitDetailRow[];
  isCurrent: boolean;
};

export const truncateMiddle = (value: string, prefix = 8, suffix = 8) => {
  if (value.length <= prefix + suffix + 3) {
    return value;
  }
  return `${value.slice(0, prefix)}...${value.slice(-suffix)}`;
};

export const formatBlockRef = (block?: { height: number; hash: string }) =>
  block ? `${block.height} (${truncateMiddle(block.hash, 6, 6)})` : undefined;

export const formatBlocksRemaining = (
  currentHeight?: number,
  targetHeight?: number,
  gt: Translate = sourceText,
) => {
  if (currentHeight === undefined || targetHeight === undefined) {
    return undefined;
  }
  const remaining = targetHeight - currentHeight;
  if (remaining <= 0) {
    return gt("Now");
  }
  return remaining === 1
    ? gt("{count} block", { count: remaining })
    : gt("{count} blocks", { count: remaining });
};

export const formatKind = (kind?: string) =>
  kind
    ? kind
        .split("-")
        .map((part) => `${part.slice(0, 1).toUpperCase()}${part.slice(1)}`)
        .join(" ")
    : undefined;

export const getClaimableHeight = (details?: ExitStateDetails) =>
  details?.claimable_height ?? details?.claimable_since?.height;

export const isClaimableExit = (exit: ExitVtxoResult, status?: ExitStatusResult) => {
  const state = status?.state ?? exit.state;
  const kind = status?.state_details.kind ?? exit.state_details.kind;
  return exit.is_claimable || state === "Claimable" || kind === "claimable";
};

const CANCELABLE_EXIT_TX_STATUS_KINDS = new Set([
  "verify-inputs",
  "awaiting-input-confirmation",
  "awaiting-cpfp-broadcast",
]);

export const isCancelableExit = (state: ExitProgressState, details?: ExitStateDetails) => {
  if (state === "Start") {
    return true;
  }
  if (state !== "Processing") {
    return false;
  }

  const finalTransaction = details?.transactions?.at(-1);
  return !finalTransaction || CANCELABLE_EXIT_TX_STATUS_KINDS.has(finalTransaction.status.kind);
};

export const getProcessingTransactionSummary = (
  details?: ExitStateDetails,
  gt: Translate = sourceText,
) => {
  const transactions = details?.transactions ?? [];
  if (transactions.length === 0) {
    return undefined;
  }

  const confirmed = transactions.filter((tx) => tx.status.kind === "confirmed").length;
  const broadcast = transactions.filter((tx) => tx.status.kind === "broadcast-with-cpfp").length;
  return gt("{value1}/{value2} confirmed, {value3} broadcast", {
    value1: confirmed,
    value2: transactions.length,
    value3: broadcast,
  });
};

export const getExitBlockRows = (
  {
    state,
    details,
    currentBlockHeight,
  }: {
    state: ExitProgressState;
    details?: ExitStateDetails;
    currentBlockHeight?: number;
  },
  gt: Translate = sourceText,
) => {
  const rows: ExitDetailRow[] = [];
  const addRow = (label: string, value?: string | number, txid?: string) => {
    if (value !== undefined && value !== "") {
      rows.push({ label, value: `${value}`, explorerUrl: txid ? getMempoolTxUrl(txid) : null });
    }
  };

  addRow(gt("Synced tip"), details?.tip_height);

  switch (state) {
    case "Processing":
      addRow(gt("Transactions"), getProcessingTransactionSummary(details, gt));
      details?.transactions?.forEach((tx, index) => {
        addRow(
          gt("Exit tx {value1}", { value1: index + 1 }),
          truncateMiddle(tx.txid, 10, 10),
          tx.txid,
        );
        if (tx.status.child_txid) {
          addRow(
            gt("Child tx {value1}", { value1: index + 1 }),
            truncateMiddle(tx.status.child_txid, 10, 10),
            tx.status.child_txid,
          );
        }
        if (tx.status.block) {
          addRow(gt("Confirmed {value1}", { value1: index + 1 }), formatBlockRef(tx.status.block));
        }
      });
      break;
    case "AwaitingDelta": {
      const claimableHeight = getClaimableHeight(details);
      addRow(gt("Confirmed block"), formatBlockRef(details?.confirmed_block));
      addRow(gt("Claimable at"), claimableHeight);
      addRow(gt("Remaining"), formatBlocksRemaining(currentBlockHeight, claimableHeight, gt));
      break;
    }
    case "Claimable":
      addRow(gt("Claimable since"), formatBlockRef(details?.claimable_since));
      addRow(gt("Last scanned"), formatBlockRef(details?.last_scanned_block));
      break;
    case "ClaimInProgress":
      addRow(gt("Claimable since"), formatBlockRef(details?.claimable_since));
      addRow(
        gt("Claim tx"),
        details?.claim_txid ? truncateMiddle(details.claim_txid, 10, 10) : undefined,
        details?.claim_txid,
      );
      break;
    case "Claimed":
      addRow(gt("Claimed block"), formatBlockRef(details?.block));
      addRow(
        gt("Claim tx"),
        details?.txid ? truncateMiddle(details.txid, 10, 10) : undefined,
        details?.txid,
      );
      break;
    case "VtxoAlreadySpent":
      addRow(gt("Last scanned"), formatBlockRef(details?.last_scanned_block));
      addRow(gt("State detail"), formatKind(details?.kind));
      break;
    default:
      addRow(gt("State detail"), formatKind(details?.kind));
      break;
  }

  return rows;
};

export const getExitStatusText = (
  {
    state,
    details,
    currentBlockHeight,
  }: {
    state: ExitProgressState;
    details?: ExitStateDetails;
    currentBlockHeight?: number;
  },
  gt: Translate = sourceText,
) => {
  switch (state) {
    case "AwaitingDelta": {
      const claimableHeight = getClaimableHeight(details);
      const remaining = formatBlocksRemaining(currentBlockHeight, claimableHeight, gt);
      return claimableHeight
        ? gt("Claimable at block {value1}{value2}", {
            value1: claimableHeight,
            value2: remaining ? ` - ${remaining}` : "",
          })
        : gt("Waiting for the timelock to mature");
    }
    case "Claimable":
      return details?.claimable_since
        ? gt("Claimable since block {value1}", { value1: details.claimable_since.height })
        : gt("Ready to claim");
    case "ClaimInProgress":
      return details?.claim_txid
        ? gt("Claim tx broadcast: {value1}", { value1: truncateMiddle(details.claim_txid, 10, 10) })
        : gt("Claim transaction is waiting for confirmation");
    case "Claimed":
      return details?.block
        ? gt("Claimed in block {value1}", { value1: details.block.height })
        : gt("Claim transaction confirmed");
    case "VtxoAlreadySpent":
      return gt("Exit VTXO was already spent");
    case "Canceled":
      return gt("Exit processing was canceled");
    case "Processing": {
      const txSummary = getProcessingTransactionSummary(details, gt);
      return txSummary
        ? gt("Exit transactions: {value1}", { value1: txSummary })
        : gt("Preparing or confirming exit transactions");
    }
    default:
      return gt("Exit has been registered");
  }
};

const getTimelineDescription = (
  {
    state,
    details,
    currentBlockHeight,
  }: {
    state: ExitProgressState;
    details?: ExitStateDetails;
    currentBlockHeight?: number;
  },
  gt: Translate = sourceText,
) => {
  switch (state) {
    case "Start":
      return gt("Exit tracking was registered for this VTXO.");
    case "Processing":
      return (
        getProcessingTransactionSummary(details, gt) ??
        gt("Exit transactions were prepared and monitored.")
      );
    case "AwaitingDelta": {
      const claimableHeight = getClaimableHeight(details);
      const remaining = formatBlocksRemaining(currentBlockHeight, claimableHeight, gt);
      return claimableHeight
        ? gt("Exit transaction confirmed; funds become claimable at block {value1}{value2}.", {
            value1: claimableHeight,
            value2: remaining ? ` (${remaining})` : "",
          })
        : gt("Exit transaction confirmed; waiting for the timelock.");
    }
    case "Claimable":
      return details?.claimable_since
        ? gt("Funds became sweepable at block {value1}.", {
            value1: details.claimable_since.height,
          })
        : gt("Funds are sweepable to an on-chain address.");
    case "ClaimInProgress":
      return details?.claim_txid
        ? gt("Final claim transaction {value1} was broadcast.", {
            value1: truncateMiddle(details.claim_txid, 10, 10),
          })
        : gt("Final claim transaction was broadcast and is waiting for confirmation.");
    case "Claimed":
      return details?.block
        ? gt("Funds were recovered on-chain in block {value1}.", { value1: details.block.height })
        : gt("Funds were recovered on-chain.");
    case "VtxoAlreadySpent":
      return gt("Exit tracking found that this VTXO was already spent.");
    case "Canceled":
      return gt("Exit processing was canceled before completion.");
  }
};

const getDetailHeight = (details?: ExitStateDetails) =>
  getClaimableHeight(details) ?? details?.block?.height ?? details?.tip_height;

export const buildExitTimelineItems = (
  {
    history,
    historyDetails,
    currentState,
    currentDetails,
    currentBlockHeight,
  }: {
    history?: ExitProgressState[];
    historyDetails?: ExitStateDetails[];
    currentState: ExitProgressState;
    currentDetails?: ExitStateDetails;
    currentBlockHeight?: number;
  },
  gt: Translate = sourceText,
) => {
  const states =
    history && history.length > 0 && history.at(-1) !== currentState
      ? [...history, currentState]
      : history && history.length > 0
        ? [...history]
        : [currentState];
  const details: (ExitStateDetails | undefined)[] = [...(historyDetails ?? [])];
  if (details.length < states.length) {
    details.push(currentDetails);
  } else if (details.length === states.length) {
    details[details.length - 1] = currentDetails ?? details[details.length - 1];
  }

  const items: ExitTimelineItem[] = [];
  for (let index = 0; index < states.length; index += 1) {
    const state = states[index];
    const stateDetails = details[index];
    const previous = items.at(-1);
    const height = getDetailHeight(stateDetails);

    if (previous?.state === state) {
      previous.count += 1;
      previous.endHeight = height ?? previous.endHeight;
      previous.description = getTimelineDescription(
        {
          state,
          details: stateDetails,
          currentBlockHeight,
        },
        gt,
      );
      previous.details = getExitBlockRows({ state, details: stateDetails, currentBlockHeight }, gt);
      previous.isCurrent = index === states.length - 1;
      continue;
    }

    items.push({
      state,
      label: getExitStateLabels(gt)[state],
      count: 1,
      startHeight: height,
      endHeight: height,
      description: getTimelineDescription({ state, details: stateDetails, currentBlockHeight }, gt),
      details: getExitBlockRows({ state, details: stateDetails, currentBlockHeight }, gt),
      isCurrent: index === states.length - 1,
    });
  }

  if (items.length > 0) {
    items[items.length - 1].isCurrent = true;
  }

  return items;
};
