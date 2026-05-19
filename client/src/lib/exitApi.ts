import * as NitroArk from "react-native-nitro-ark";
import { err, ok, Result, ResultAsync } from "neverthrow";
import logger from "~/lib/log";
import type {
  ExitProgressStatusResult,
  ExitStateDetails,
  ExitStatusResult,
  ExitVtxoResult,
} from "react-native-nitro-ark";

const log = logger("exitApi");

export type ExitClaimResult = {
  txid: string;
  txHex: string;
};

const summarizeVtxoId = (vtxoId: string) => vtxoId;

const summarizeBlockRef = (block?: { height: number; hash: string }) =>
  block ? { height: block.height, hash: block.hash } : undefined;

const summarizeStateDetails = (details: ExitStateDetails) => ({
  kind: details.kind,
  tip_height: details.tip_height,
  transaction_count: details.transactions?.length ?? 0,
  transaction_statuses: details.transactions?.map((tx) => ({
    txid: tx.txid,
    status: tx.status.kind,
    child_txid: tx.status.child_txid,
    block: summarizeBlockRef(tx.status.block),
  })),
  confirmed_block: summarizeBlockRef(details.confirmed_block),
  claimable_height: details.claimable_height,
  claimable_since: summarizeBlockRef(details.claimable_since),
  last_scanned_block: summarizeBlockRef(details.last_scanned_block),
  claim_txid: details.claim_txid,
  txid: details.txid,
  block: summarizeBlockRef(details.block),
});

const summarizeExit = (exit: ExitVtxoResult) => ({
  vtxo_id: summarizeVtxoId(exit.vtxo_id),
  amount_sat: exit.amount_sat,
  state: exit.state,
  state_details: summarizeStateDetails(exit.state_details),
  is_claimable: exit.is_claimable,
  is_initialized: exit.is_initialized,
  txids: exit.txids,
  history: exit.history,
  history_details: exit.history_details.map(summarizeStateDetails),
});

const summarizeProgress = (progress: ExitProgressStatusResult) => ({
  vtxo_id: summarizeVtxoId(progress.vtxo_id),
  state: progress.state,
  state_details: summarizeStateDetails(progress.state_details),
  error: progress.error,
});

export const startExitForEntireWallet = async (): Promise<Result<void, Error>> => {
  log.i("Starting exit for entire wallet");
  const result = await ResultAsync.fromPromise(
    NitroArk.startExitForEntireWallet(),
    (e) => e as Error,
  );

  if (result.isErr()) {
    log.e("Failed to start exit for entire wallet", [result.error]);
  } else {
    log.i("Started exit for entire wallet");
  }
  return result;
};

export const startExitForVtxos = async (vtxoIds: string[]): Promise<Result<void, Error>> => {
  log.i("Starting exit for selected VTXOs", [{ vtxo_ids: vtxoIds }]);
  const result = await ResultAsync.fromPromise(NitroArk.startExitForVtxos(vtxoIds), (e) => e as Error);

  if (result.isErr()) {
    log.e("Failed to start exit for selected VTXOs", [{ vtxo_ids: vtxoIds }, result.error]);
  } else {
    log.i("Started exit for selected VTXOs", [{ vtxo_ids: vtxoIds }]);
  }
  return result;
};

export const syncExit = async (): Promise<Result<void, Error>> => {
  log.d("Syncing exits with progress allowed");
  const result = await ResultAsync.fromPromise(NitroArk.syncExit(), (e) => e as Error);

  if (result.isErr()) {
    log.e("Failed to sync exits", [result.error]);
  } else {
    log.d("Synced exits");
  }
  return result;
};

export const syncNoProgress = async (): Promise<Result<void, Error>> => {
  log.d("Syncing exits without progress");
  const result = await ResultAsync.fromPromise(NitroArk.syncNoProgress(), (e) => e as Error);

  if (result.isErr()) {
    log.e("Failed to sync exits without progress", [result.error]);
  } else {
    log.d("Synced exits without progress");
  }
  return result;
};

export const progressExits = async (
  feeRateSatPerKvb?: number,
): Promise<Result<ExitProgressStatusResult[], Error>> => {
  log.i("Progressing exits", [{ fee_rate_sat_per_kvb: feeRateSatPerKvb }]);

  const syncResult = await syncNoProgress();
  if (syncResult.isErr()) {
    log.e("Failed to sync exits before progressing", [
      { fee_rate_sat_per_kvb: feeRateSatPerKvb },
      syncResult.error,
    ]);
    return err(syncResult.error);
  }

  const result = await ResultAsync.fromPromise(
    NitroArk.progressExits(feeRateSatPerKvb),
    (e) => e as Error,
  );

  if (result.isErr()) {
    log.e("Failed to progress exits", [{ fee_rate_sat_per_kvb: feeRateSatPerKvb }, result.error]);
    return result;
  }

  const progress = result.value.map(summarizeProgress);
  const failures = progress.filter((item) => item.error);
  log.i("Exit progress result", [{ count: progress.length, progress }]);

  if (failures.length > 0) {
    log.w("Some exits failed to progress", [{ failures }]);
  }

  return result;
};

export const getExitVtxos = async (): Promise<Result<ExitVtxoResult[], Error>> => {
  const result = await ResultAsync.fromPromise(NitroArk.getExitVtxos(), (e) => e as Error);

  if (result.isErr()) {
    log.e("Failed to get exit VTXOs", [result.error]);
  } else {
    log.d("Loaded exit VTXOs", [
      { count: result.value.length, exits: result.value.map(summarizeExit) },
    ]);
  }
  return result;
};

export const listClaimable = async (): Promise<Result<ExitVtxoResult[], Error>> => {
  const result = await ResultAsync.fromPromise(NitroArk.listClaimable(), (e) => e as Error);

  if (result.isErr()) {
    log.e("Failed to list claimable exits", [result.error]);
  } else {
    log.d("Loaded claimable exits", [
      { count: result.value.length, exits: result.value.map(summarizeExit) },
    ]);
  }
  return result;
};

export const getExitStatus = async (
  vtxoId: string,
  includeHistory = true,
  includeTransactions = false,
): Promise<Result<ExitStatusResult | undefined, Error>> => {
  const result = await ResultAsync.fromPromise(
    NitroArk.getExitStatus(vtxoId, includeHistory, includeTransactions),
    (e) => e as Error,
  );

  if (result.isErr()) {
    log.e("Failed to get exit status", [
      { vtxo_id: summarizeVtxoId(vtxoId), includeHistory, includeTransactions },
      result.error,
    ]);
  } else {
    log.d("Loaded exit status", [
      {
        vtxo_id: summarizeVtxoId(vtxoId),
        state: result.value?.state,
        state_details: result.value?.state_details
          ? summarizeStateDetails(result.value.state_details)
          : undefined,
        history: result.value?.history,
        history_details: result.value?.history_details.map(summarizeStateDetails),
        transaction_count: result.value?.transactions.length ?? 0,
      },
    ]);
  }
  return result;
};

export const hasPendingExits = async (): Promise<Result<boolean, Error>> => {
  const result = await ResultAsync.fromPromise(NitroArk.hasPendingExits(), (e) => e as Error);

  if (result.isErr()) {
    log.e("Failed to check pending exits", [result.error]);
  } else {
    log.d("Checked pending exits", [{ has_pending: result.value }]);
  }
  return result;
};

export const pendingExitTotal = async (): Promise<Result<number, Error>> => {
  const result = await ResultAsync.fromPromise(NitroArk.pendingExitTotal(), (e) => e as Error);

  if (result.isErr()) {
    log.e("Failed to get pending exit total", [result.error]);
  } else {
    log.d("Loaded pending exit total", [{ amount_sat: result.value }]);
  }
  return result;
};

export const allClaimableAtHeight = async (): Promise<Result<number | undefined, Error>> => {
  const result = await ResultAsync.fromPromise(NitroArk.allClaimableAtHeight(), (e) => e as Error);

  if (result.isErr()) {
    log.e("Failed to get all-claimable height", [result.error]);
  } else {
    log.d("Loaded all-claimable height", [{ height: result.value }]);
  }
  return result;
};

export const drainExits = async (
  vtxoIds: string[],
  destinationAddress: string,
  feeRateSatPerKvb?: number,
): Promise<Result<string, Error>> => {
  log.i("Building exit claim transaction PSBT", [
    { vtxo_ids: vtxoIds, fee_rate_sat_per_kvb: feeRateSatPerKvb },
  ]);
  const result = await ResultAsync.fromPromise(
    NitroArk.drainExits(vtxoIds, destinationAddress, feeRateSatPerKvb),
    (e) => e as Error,
  );

  if (result.isErr()) {
    log.e("Failed to build exit claim transaction PSBT", [
      { vtxo_ids: vtxoIds, fee_rate_sat_per_kvb: feeRateSatPerKvb },
      result.error,
    ]);
  } else {
    log.i("Built exit claim transaction PSBT", [{ vtxo_ids: vtxoIds }]);
  }
  return result;
};

export const extractTransaction = async (psbt: string): Promise<Result<string, Error>> => {
  log.d("Extracting claim transaction from PSBT");
  const result = await ResultAsync.fromPromise(NitroArk.extractTransaction(psbt), (e) => e as Error);

  if (result.isErr()) {
    log.e("Failed to extract claim transaction from PSBT", [result.error]);
  } else {
    log.d("Extracted claim transaction from PSBT", [{ tx_hex_length: result.value.length }]);
  }
  return result;
};

export const broadcastTransaction = async (txHex: string): Promise<Result<string, Error>> => {
  log.i("Broadcasting claim transaction", [{ tx_hex_length: txHex.length }]);
  const result = await ResultAsync.fromPromise(NitroArk.broadcastTransaction(txHex), (e) => e as Error);

  if (result.isErr()) {
    log.e("Failed to broadcast claim transaction", [result.error]);
  } else {
    log.i("Broadcasted claim transaction", [{ txid: result.value }]);
  }
  return result;
};

export const claimExits = async ({
  vtxoIds,
  destinationAddress,
  feeRateSatPerKvb,
}: {
  vtxoIds: string[];
  destinationAddress: string;
  feeRateSatPerKvb?: number;
}): Promise<Result<ExitClaimResult, Error>> => {
  if (vtxoIds.length === 0) {
    log.w("Claim exits called with no VTXOs");
    return err(new Error("No claimable exits selected."));
  }

  log.i("Claiming exits", [{ vtxo_ids: vtxoIds, fee_rate_sat_per_kvb: feeRateSatPerKvb }]);

  const psbtResult = await drainExits(vtxoIds, destinationAddress, feeRateSatPerKvb);
  if (psbtResult.isErr()) {
    return err(psbtResult.error);
  }

  const txHexResult = await extractTransaction(psbtResult.value);
  if (txHexResult.isErr()) {
    return err(txHexResult.error);
  }

  const txidResult = await broadcastTransaction(txHexResult.value);
  if (txidResult.isErr()) {
    return err(txidResult.error);
  }

  const syncResult = await syncExit();
  if (syncResult.isErr()) {
    log.w("Claim transaction broadcasted but exit sync failed", [syncResult.error]);
  }

  return ok({
    txid: txidResult.value,
    txHex: txHexResult.value,
  });
};
