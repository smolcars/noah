import { err, ok, type Result } from "neverthrow";
import { BackupService } from "~/lib/backupService";
import { redactSensitiveErrorMessage } from "~/lib/errorUtils";
import logger from "~/lib/log";
import { useBackupStore } from "~/store/backupStore";
import { useServerStore } from "~/store/serverStore";
import { useWalletStore } from "~/store/walletStore";

const BACKUP_DEBOUNCE_MS = 5_000;
const BACKUP_RETRY_INITIAL_MS = 15_000;
const BACKUP_RETRY_MAX_MS = 15 * 60_000;

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
let retryTimer: ReturnType<typeof setTimeout> | null = null;
let retryAttempt = 0;
let pendingRequireEnabled = true;
let backupInFlight: Promise<Result<void, Error>> | null = null;

const clearDebounceTimer = () => {
  if (debounceTimer) {
    clearTimeout(debounceTimer);
    debounceTimer = null;
  }
};

const clearRetryTimer = () => {
  if (retryTimer) {
    clearTimeout(retryTimer);
    retryTimer = null;
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

const scheduleBackupRetry = () => {
  if (retryTimer || debounceTimer || !canBackup(pendingRequireEnabled)) {
    return;
  }

  const baseDelay = Math.min(
    BACKUP_RETRY_INITIAL_MS * 2 ** retryAttempt,
    BACKUP_RETRY_MAX_MS,
  );
  const delay = Math.min(
    Math.round(baseDelay * (0.8 + Math.random() * 0.4)),
    BACKUP_RETRY_MAX_MS,
  );
  retryAttempt = Math.min(retryAttempt + 1, 10);
  retryTimer = setTimeout(() => {
    retryTimer = null;
    void runBackupPass();
  }, delay);
  log.d("Backup retry scheduled", [delay]);
};

const performBackupPass = async (
  passGeneration: number,
  requireEnabled: boolean,
): Promise<Result<void, Error>> => {
  const backupStore = useBackupStore.getState();
  backupStore.setBackupInProgress();

  const service = new BackupService();
  const result = await service.performBackup(backupStore.lastUploadedSnapshotSha256);
  if (result.isErr()) {
    const safeMessage = redactSensitiveErrorMessage(result.error);
    useBackupStore.getState().setBackupFailed(safeMessage);
    log.w("Backup failed", [safeMessage]);
    pendingRequireEnabled = pendingRequireEnabled && requireEnabled;
    scheduleBackupRetry();
    return err(result.error);
  }

  retryAttempt = 0;
  clearRetryTimer();
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

  const requireEnabled = pendingRequireEnabled;
  if (!canBackup(requireEnabled)) {
    log.d("Skipping backup pass because the wallet is not eligible");
    return err(new Error("Wallet is not ready for backup"));
  }

  const passGeneration = requestedGeneration;
  pendingRequireEnabled = true;
  backupInFlight = performBackupPass(passGeneration, requireEnabled);
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
  pendingRequireEnabled = pendingRequireEnabled && requireEnabled;
  useBackupStore.getState().markBackupPending();
  log.d("Backup requested", [reason, requestedGeneration]);

  if (options.immediate) {
    clearDebounceTimer();
    clearRetryTimer();
    void runBackupPass();
    return;
  }
  clearRetryTimer();
  scheduleTrailingBackup();
};

export const cancelScheduledBackup = (): void => {
  clearDebounceTimer();
  clearRetryTimer();
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
  pendingRequireEnabled = pendingRequireEnabled && requireEnabled;
  const targetGeneration = requestedGeneration;
  useBackupStore.getState().markBackupPending();
  clearDebounceTimer();
  clearRetryTimer();
  log.d("Immediate backup requested", [reason, targetGeneration]);

  let result = await runBackupPass();
  while (result.isOk() && completedGeneration < targetGeneration) {
    result = await runBackupPass();
  }
  return result;
};
