import { err, ok, type Result } from "neverthrow";
import type { BarkMovement } from "react-native-nitro-ark";
import { acknowledgeLnurlPayReceiveMetadata, listLnurlPayReceiveMetadata } from "~/lib/api";
import { scheduleBackup } from "~/lib/backupCoordinator";
import {
  buildLnurlPayReceiveMetadataPatch,
  doesPersistedLnurlPayReceiveMetadataMatch,
  findLightningReceiveMovement,
  parseLnurlPayReceiveMetadata,
  type PersistedLnurlPayReceiveMetadata,
} from "~/lib/lnurlPayReceiveMetadata";
import { history, updateHistoryMetadata } from "~/lib/paymentsApi";
import logger from "~/lib/log";
import { queryClient } from "~/queryClient";
import type { LnurlPayReceiveMetadata } from "~/types/serverTypes";

const log = logger("lnurlPayReceiveMetadataService");

export type ReconciledLnurlPayReceiveMetadata = {
  record: LnurlPayReceiveMetadata;
  movementId: number;
};

export type LnurlPayReceiveMetadataReconciliation = {
  reconciled: ReconciledLnurlPayReceiveMetadata[];
  deferredCount: number;
};

let reconciliationQueue: Promise<void> = Promise.resolve();

const persistedMetadataMatchesRecord = (
  movement: BarkMovement,
  record: LnurlPayReceiveMetadata,
): boolean =>
  doesPersistedLnurlPayReceiveMetadataMatch(parseLnurlPayReceiveMetadata(movement.metadata_json), {
    payer_data: record.payer_data,
    comment: record.comment,
  });

const runReconciliationPass = async (): Promise<
  Result<LnurlPayReceiveMetadataReconciliation, Error>
> => {
  const listResult = await listLnurlPayReceiveMetadata();
  if (listResult.isErr()) {
    return err(listResult.error);
  }

  const records = listResult.value.items;
  if (records.length === 0) {
    return ok({ reconciled: [], deferredCount: 0 });
  }

  const historyResult = await history();
  if (historyResult.isErr()) {
    return err(historyResult.error);
  }

  const candidates: ReconciledLnurlPayReceiveMetadata[] = [];
  let wroteMetadata = false;

  for (const record of records) {
    const movement = findLightningReceiveMovement(historyResult.value, record.payment_hash);
    if (!movement) {
      continue;
    }

    if (!persistedMetadataMatchesRecord(movement, record)) {
      const patch = buildLnurlPayReceiveMetadataPatch({
        payer_data: record.payer_data,
        comment: record.comment,
      });
      const updateResult = await updateHistoryMetadata(movement.id, JSON.stringify(patch));
      if (updateResult.isErr()) {
        log.w("Failed to persist LNURL-pay receive metadata", [movement.id, updateResult.error]);
        continue;
      }
      wroteMetadata = true;
    }

    candidates.push({ record, movementId: movement.id });
  }

  if (candidates.length === 0) {
    return ok({ reconciled: [], deferredCount: records.length });
  }

  const verificationResult = wroteMetadata ? await history() : historyResult;
  if (verificationResult.isErr()) {
    return err(verificationResult.error);
  }

  const reconciled = candidates.filter(({ record, movementId }) => {
    const movement = verificationResult.value.find((candidate) => candidate.id === movementId);
    return movement ? persistedMetadataMatchesRecord(movement, record) : false;
  });

  if (reconciled.length === 0) {
    return ok({ reconciled: [], deferredCount: records.length });
  }

  // Schedule even when the metadata was written by an interrupted earlier pass. This keeps
  // acknowledgement behind a fresh backup request across the write/schedule crash window.
  scheduleBackup("database_changed");
  await queryClient.invalidateQueries({ queryKey: ["transactions"] });

  const ackResult = await acknowledgeLnurlPayReceiveMetadata({
    ids: reconciled.map(({ record }) => record.id),
  });
  if (ackResult.isErr()) {
    return err(ackResult.error);
  }

  return ok({
    reconciled,
    deferredCount: records.length - reconciled.length,
  });
};

export const reconcileLnurlPayReceiveMetadata = (): Promise<
  Result<LnurlPayReceiveMetadataReconciliation, Error>
> => {
  const pass = reconciliationQueue.then(runReconciliationPass, runReconciliationPass);
  reconciliationQueue = pass.then(
    () => undefined,
    () => undefined,
  );
  return pass;
};

export const getPersistedLnurlPayReceiveMetadata = async (
  paymentHash: string,
): Promise<Result<PersistedLnurlPayReceiveMetadata | undefined, Error>> => {
  const historyResult = await history();
  if (historyResult.isErr()) {
    return err(historyResult.error);
  }

  const movement = findLightningReceiveMovement(historyResult.value, paymentHash);
  return ok(movement ? parseLnurlPayReceiveMetadata(movement.metadata_json) : undefined);
};
