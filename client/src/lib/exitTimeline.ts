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
];

export const EXIT_STATE_LABELS: Record<ExitProgressState, string> = {
  Start: "Started",
  Processing: "Processing",
  AwaitingDelta: "Waiting",
  Claimable: "Claimable",
  ClaimInProgress: "Claiming",
  Claimed: "Claimed",
  VtxoAlreadySpent: "Already spent",
};

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

export const formatBlocksRemaining = (currentHeight?: number, targetHeight?: number) => {
  if (currentHeight === undefined || targetHeight === undefined) {
    return undefined;
  }
  const remaining = targetHeight - currentHeight;
  if (remaining <= 0) {
    return "Now";
  }
  return `${remaining} ${remaining === 1 ? "block" : "blocks"}`;
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

export const getProcessingTransactionSummary = (details?: ExitStateDetails) => {
  const transactions = details?.transactions ?? [];
  if (transactions.length === 0) {
    return undefined;
  }

  const confirmed = transactions.filter((tx) => tx.status.kind === "confirmed").length;
  const broadcast = transactions.filter((tx) => tx.status.kind === "broadcast-with-cpfp").length;
  return `${confirmed}/${transactions.length} confirmed, ${broadcast} broadcast`;
};

export const getExitBlockRows = ({
  state,
  details,
  currentBlockHeight,
}: {
  state: ExitProgressState;
  details?: ExitStateDetails;
  currentBlockHeight?: number;
}) => {
  const rows: ExitDetailRow[] = [];
  const addRow = (label: string, value?: string | number, txid?: string) => {
    if (value !== undefined && value !== "") {
      rows.push({ label, value: `${value}`, explorerUrl: txid ? getMempoolTxUrl(txid) : null });
    }
  };

  addRow("Synced tip", details?.tip_height);

  switch (state) {
    case "Processing":
      addRow("Transactions", getProcessingTransactionSummary(details));
      details?.transactions?.forEach((tx, index) => {
        addRow(`Exit tx ${index + 1}`, truncateMiddle(tx.txid, 10, 10), tx.txid);
        if (tx.status.child_txid) {
          addRow(
            `Child tx ${index + 1}`,
            truncateMiddle(tx.status.child_txid, 10, 10),
            tx.status.child_txid,
          );
        }
        if (tx.status.block) {
          addRow(`Confirmed ${index + 1}`, formatBlockRef(tx.status.block));
        }
      });
      break;
    case "AwaitingDelta": {
      const claimableHeight = getClaimableHeight(details);
      addRow("Confirmed block", formatBlockRef(details?.confirmed_block));
      addRow("Claimable at", claimableHeight);
      addRow("Remaining", formatBlocksRemaining(currentBlockHeight, claimableHeight));
      break;
    }
    case "Claimable":
      addRow("Claimable since", formatBlockRef(details?.claimable_since));
      addRow("Last scanned", formatBlockRef(details?.last_scanned_block));
      break;
    case "ClaimInProgress":
      addRow("Claimable since", formatBlockRef(details?.claimable_since));
      addRow(
        "Claim tx",
        details?.claim_txid ? truncateMiddle(details.claim_txid, 10, 10) : undefined,
        details?.claim_txid,
      );
      break;
    case "Claimed":
      addRow("Claimed block", formatBlockRef(details?.block));
      addRow(
        "Claim tx",
        details?.txid ? truncateMiddle(details.txid, 10, 10) : undefined,
        details?.txid,
      );
      break;
    case "VtxoAlreadySpent":
      addRow("Last scanned", formatBlockRef(details?.last_scanned_block));
      addRow("State detail", formatKind(details?.kind));
      break;
    default:
      addRow("State detail", formatKind(details?.kind));
      break;
  }

  return rows;
};

export const getExitStatusText = ({
  state,
  details,
  currentBlockHeight,
}: {
  state: ExitProgressState;
  details?: ExitStateDetails;
  currentBlockHeight?: number;
}) => {
  switch (state) {
    case "AwaitingDelta": {
      const claimableHeight = getClaimableHeight(details);
      const remaining = formatBlocksRemaining(currentBlockHeight, claimableHeight);
      return claimableHeight
        ? `Claimable at block ${claimableHeight}${remaining ? ` - ${remaining}` : ""}`
        : "Waiting for the timelock to mature";
    }
    case "Claimable":
      return details?.claimable_since
        ? `Claimable since block ${details.claimable_since.height}`
        : "Ready to claim";
    case "ClaimInProgress":
      return details?.claim_txid
        ? `Claim tx broadcast: ${truncateMiddle(details.claim_txid, 10, 10)}`
        : "Claim transaction is waiting for confirmation";
    case "Claimed":
      return details?.block
        ? `Claimed in block ${details.block.height}`
        : "Claim transaction confirmed";
    case "VtxoAlreadySpent":
      return "Exit VTXO was already spent";
    case "Processing": {
      const txSummary = getProcessingTransactionSummary(details);
      return txSummary
        ? `Exit transactions: ${txSummary}`
        : "Preparing or confirming exit transactions";
    }
    default:
      return "Exit has been registered";
  }
};

const getTimelineDescription = ({
  state,
  details,
  currentBlockHeight,
}: {
  state: ExitProgressState;
  details?: ExitStateDetails;
  currentBlockHeight?: number;
}) => {
  switch (state) {
    case "Start":
      return "Exit tracking was registered for this VTXO.";
    case "Processing":
      return (
        getProcessingTransactionSummary(details) ?? "Exit transactions were prepared and monitored."
      );
    case "AwaitingDelta": {
      const claimableHeight = getClaimableHeight(details);
      const remaining = formatBlocksRemaining(currentBlockHeight, claimableHeight);
      return claimableHeight
        ? `Exit transaction confirmed; funds become claimable at block ${claimableHeight}${remaining ? ` (${remaining})` : ""}.`
        : "Exit transaction confirmed; waiting for the timelock.";
    }
    case "Claimable":
      return details?.claimable_since
        ? `Funds became sweepable at block ${details.claimable_since.height}.`
        : "Funds are sweepable to an on-chain address.";
    case "ClaimInProgress":
      return details?.claim_txid
        ? `Final claim transaction ${truncateMiddle(details.claim_txid, 10, 10)} was broadcast.`
        : "Final claim transaction was broadcast and is waiting for confirmation.";
    case "Claimed":
      return details?.block
        ? `Funds were recovered on-chain in block ${details.block.height}.`
        : "Funds were recovered on-chain.";
    case "VtxoAlreadySpent":
      return "Exit tracking found that this VTXO was already spent.";
  }
};

const getDetailHeight = (details?: ExitStateDetails) =>
  getClaimableHeight(details) ?? details?.block?.height ?? details?.tip_height;

export const buildExitTimelineItems = ({
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
}) => {
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
      previous.description = getTimelineDescription({
        state,
        details: stateDetails,
        currentBlockHeight,
      });
      previous.details = getExitBlockRows({ state, details: stateDetails, currentBlockHeight });
      previous.isCurrent = index === states.length - 1;
      continue;
    }

    items.push({
      state,
      label: EXIT_STATE_LABELS[state],
      count: 1,
      startHeight: height,
      endHeight: height,
      description: getTimelineDescription({ state, details: stateDetails, currentBlockHeight }),
      details: getExitBlockRows({ state, details: stateDetails, currentBlockHeight }),
      isCurrent: index === states.length - 1,
    });
  }

  if (items.length > 0) {
    items[items.length - 1].isCurrent = true;
  }

  return items;
};
