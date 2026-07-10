import { err, ok, type Result } from "neverthrow";
import { BackupService } from "~/lib/backupService";
import { redactSensitiveErrorMessage } from "~/lib/errorUtils";
import logger from "~/lib/log";
import { useBackupStore } from "~/store/backupStore";
import { useServerStore } from "~/store/serverStore";
import { useWalletStore } from "~/store/walletStore";

const BACKUP_DEBOUNCE_MS = 5_000;

export type BackupReason =
  | "app_foreground"
  | "database_changed"
  | "manual"
  | "push"
  | "resync_required"
  | "startup";

type BackupRequestOptions = {
  immediate?: boolean;
  requireEnabled?: boolean;
};

const log = logger("backupCoordinator");

let requestedGeneration = 0;
let completedGeneration = 0;
let debounceTimer: ReturnType<typeof setTimeout> | null = null;
let backupInFlight: Promise<Result<void, Error>> | null = null;

const clearDebounceTimer = () => {
  if (debounceTimer) {
    clearTimeout(debounceTimer);
    debounceTimer = null;
  }
};

const canBackup = (requireEnabled: boolean): boolean => {
  const wallet = useWalletStore.getState();
  if (!wallet.isInitialized || !wallet.isWalletLoaded || wallet.isWalletSuspended) {
    return false;
  }
  return !requireEnabled || useServerStore.getState().isBackupEnabled;
};

const scheduleTrailingBackup = () => {
  if (debounceTimer) {
    return;
  }
  debounceTimer = setTimeout(() => {
    debounceTimer = null;
    void runBackupPass();
  }, BACKUP_DEBOUNCE_MS);
};

const performBackupPass = async (): Promise<Result<void, Error>> => {
  const passGeneration = requestedGeneration;
  const backupStore = useBackupStore.getState();
  backupStore.setBackupInProgress();

  const service = new BackupService();
  const result = await service.performBackup(backupStore.lastUploadedSnapshotSha256);
  if (result.isErr()) {
    const safeMessage = redactSensitiveErrorMessage(result.error);
    useBackupStore.getState().setBackupFailed(safeMessage);
    log.w("Backup failed", [safeMessage]);
    return err(result.error);
  }

  completedGeneration = Math.max(completedGeneration, passGeneration);
  const hasNewerRequest = requestedGeneration > completedGeneration;
  useBackupStore
    .getState()
    .setBackupSuccess(
      result.value.snapshotSha256,
      result.value.backupId,
      result.value.uploaded,
      hasNewerRequest,
    );
  log.d(result.value.uploaded ? "Backup uploaded" : "Backup already current");

  if (hasNewerRequest) {
    scheduleTrailingBackup();
  }
  return ok(undefined);
};

async function runBackupPass(): Promise<Result<void, Error>> {
  if (backupInFlight) {
    return backupInFlight;
  }

  backupInFlight = performBackupPass();
  try {
    return await backupInFlight;
  } finally {
    backupInFlight = null;
  }
}

export const scheduleBackup = (reason: BackupReason, options: BackupRequestOptions = {}): void => {
  const requireEnabled = options.requireEnabled ?? true;
  if (!canBackup(requireEnabled)) {
    log.d("Skipping backup request", [reason]);
    return;
  }

  requestedGeneration += 1;
  useBackupStore.getState().markBackupPending();
  log.d("Backup requested", [reason, requestedGeneration]);

  if (options.immediate) {
    clearDebounceTimer();
    void runBackupPass();
    return;
  }
  scheduleTrailingBackup();
};

export const cancelScheduledBackup = (): void => {
  clearDebounceTimer();
};

export const flushBackup = async (
  reason: BackupReason,
  options: Omit<BackupRequestOptions, "immediate"> = {},
): Promise<Result<void, Error>> => {
  const requireEnabled = options.requireEnabled ?? true;
  if (!canBackup(requireEnabled)) {
    return err(new Error("Wallet is not ready for backup"));
  }

  requestedGeneration += 1;
  const targetGeneration = requestedGeneration;
  useBackupStore.getState().markBackupPending();
  clearDebounceTimer();
  log.d("Immediate backup requested", [reason, targetGeneration]);

  let result = await runBackupPass();
  while (result.isOk() && completedGeneration < targetGeneration) {
    result = await runBackupPass();
  }
  return result;
};
