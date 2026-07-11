import RNFSTurbo from "react-native-fs-turbo";
import uuid from "react-native-uuid";
import {
  createWalletSnapshot,
  validateWalletSnapshot,
  type WalletSnapshotInfo,
} from "react-native-nitro-ark";
import {
  clearNativeMnemonic,
  decryptWalletBackup,
  downloadFile,
  encryptWalletSnapshot,
  finalizeWalletSnapshotInstall,
  installWalletSnapshot,
  restoreBackup as restoreBackupNative,
  rollbackWalletSnapshotInstall,
  storeNativeMnemonic,
  uploadFile,
} from "noah-tools";
import { err, ok, Result, ResultAsync } from "neverthrow";
import {
  completeBackupUpload,
  getBackupObjectDownloadForRestore,
  getDownloadUrlForRestore,
  initiateBackupUpload,
  listBackupObjectsForRestore,
  updateBackupSettings,
} from "./api";
import { clearMnemonic, getMnemonic, getStoredMnemonic, setMnemonic } from "./crypto";
import { closeWalletIfLoaded, loadWalletWithMnemonic } from "./walletApi";
import { useWalletStore } from "~/store/walletStore";
import { useBackupStore } from "~/store/backupStore";
import logger from "~/lib/log";
import ky from "ky";
import { ARK_DATA_PATH, CACHES_DIRECTORY_PATH, shouldUseUnifiedPush } from "~/constants";
import { APP_VARIANT } from "~/config";
import { redactSensitiveErrorMessage } from "~/lib/errorUtils";

const BACKUP_FORMAT_VERSION = 2;

type BackupManifestV2 = {
  formatVersion: 2;
  createdAt: string;
  snapshotSha256: string;
  network: string;
  walletFingerprint: string;
  serverPubkey: string | null;
  mailboxPubkey: string | null;
  barkSchemaVersion: number;
};

export type BackupOutcome = {
  backupId: string | null;
  snapshotSha256: string;
  uploaded: boolean;
};

export type EncryptedBackupFile = {
  path: string;
  sizeBytes: number;
  sha256: string;
};

type RestoreOutcome = {
  backupId: string | null;
  snapshotSha256: string | null;
  rollbackPath: string;
  usesSnapshotInstall: boolean;
};

const updateProgress = (step: string, progress: number) => {
  useWalletStore.getState().setRestoreProgress({ step, progress });
};

const log = logger("backupService");

const asError = (error: unknown): Error =>
  error instanceof Error ? error : new Error(`Backup operation failed: ${String(error)}`);

const isNotFoundError = (error: Error): boolean => {
  const status = (error as Error & { status?: unknown }).status;
  return status === 404;
};

const removePathIfPresent = async (path: string) => {
  try {
    if (RNFSTurbo.exists(path)) {
      await RNFSTurbo.unlink(path);
    }
  } catch (error) {
    log.w("Failed to clean up backup temporary path", [
      redactSensitiveErrorMessage(asError(error)),
    ]);
  }
};

const snapshotManifest = (snapshot: WalletSnapshotInfo): BackupManifestV2 => ({
  formatVersion: BACKUP_FORMAT_VERSION,
  createdAt: new Date().toISOString(),
  snapshotSha256: snapshot.sha256,
  network: snapshot.network,
  walletFingerprint: snapshot.walletFingerprint,
  serverPubkey: snapshot.serverPubkey ?? null,
  mailboxPubkey: snapshot.mailboxPubkey ?? null,
  barkSchemaVersion: snapshot.schemaVersion,
});

const parseBackupManifest = (manifestJson: string): Result<BackupManifestV2, Error> => {
  const parsed = Result.fromThrowable(
    () => JSON.parse(manifestJson) as unknown,
    (error) => new Error(`Backup manifest is invalid: ${String(error)}`),
  )();
  if (parsed.isErr()) {
    return err(parsed.error);
  }

  const value = parsed.value;
  if (typeof value !== "object" || value === null) {
    return err(new Error("Backup manifest is invalid"));
  }
  const manifest = value as Record<string, unknown>;
  if (
    manifest.formatVersion !== BACKUP_FORMAT_VERSION ||
    typeof manifest.createdAt !== "string" ||
    typeof manifest.snapshotSha256 !== "string" ||
    typeof manifest.network !== "string" ||
    typeof manifest.walletFingerprint !== "string" ||
    (typeof manifest.serverPubkey !== "string" && manifest.serverPubkey !== null) ||
    (typeof manifest.mailboxPubkey !== "string" && manifest.mailboxPubkey !== null) ||
    typeof manifest.barkSchemaVersion !== "number"
  ) {
    return err(new Error("Backup manifest is incomplete"));
  }
  return ok(manifest as BackupManifestV2);
};

const expectedNetwork = (): string => (APP_VARIANT === "mainnet" ? "bitcoin" : APP_VARIANT);

export class BackupService {
  async registerBackup() {
    const registerResult = await updateBackupSettings({ backup_enabled: true });
    if (registerResult.isErr()) {
      log.e("Failed to register backup:", [registerResult.error]);
    }
  }

  async performBackup(
    lastUploadedSnapshotSha256: string | null,
  ): Promise<Result<BackupOutcome, Error>> {
    const operationId = uuid.v4().toString();
    const snapshotPath = `${CACHES_DIRECTORY_PATH}/wallet-snapshot-${operationId}.sqlite`;
    const encryptedPath = `${CACHES_DIRECTORY_PATH}/wallet-backup-${operationId}.noahbackup`;

    try {
      const snapshotResult = await ResultAsync.fromPromise(
        createWalletSnapshot(snapshotPath),
        asError,
      );
      if (snapshotResult.isErr()) {
        return err(snapshotResult.error);
      }
      const snapshot = snapshotResult.value;
      if (snapshot.sha256 === lastUploadedSnapshotSha256) {
        return ok({ backupId: null, snapshotSha256: snapshot.sha256, uploaded: false });
      }

      const mnemonicResult = await getMnemonic();
      if (mnemonicResult.isErr()) {
        return err(mnemonicResult.error);
      }
      const encryptedResult = await ResultAsync.fromPromise(
        encryptWalletSnapshot(
          snapshot.path,
          JSON.stringify(snapshotManifest(snapshot)),
          encryptedPath,
          mnemonicResult.value,
        ),
        asError,
      );
      if (encryptedResult.isErr()) {
        return err(encryptedResult.error);
      }
      const encrypted = encryptedResult.value;

      const initiateResult = await initiateBackupUpload({
        format_version: BACKUP_FORMAT_VERSION,
        encrypted_size: encrypted.sizeBytes,
        encrypted_sha256: encrypted.sha256,
      });
      if (initiateResult.isErr()) {
        return err(initiateResult.error);
      }

      const uploadResult = await ResultAsync.fromPromise(
        uploadFile(
          initiateResult.value.upload_url,
          encrypted.path,
          {
            "Content-Length": encrypted.sizeBytes.toString(),
            "Content-Type": "application/octet-stream",
            "x-amz-checksum-sha256": initiateResult.value.checksum_sha256,
          },
          60,
        ),
        asError,
      );
      if (uploadResult.isErr()) {
        return err(uploadResult.error);
      }

      const completeResult = await completeBackupUpload({
        backup_id: initiateResult.value.backup_id,
      });
      if (completeResult.isErr()) {
        return err(completeResult.error);
      }

      return ok({
        backupId: initiateResult.value.backup_id,
        snapshotSha256: snapshot.sha256,
        uploaded: true,
      });
    } finally {
      await Promise.all([removePathIfPresent(snapshotPath), removePathIfPresent(encryptedPath)]);
    }
  }

  async createEncryptedBackupFile(
    destinationPath: string,
  ): Promise<Result<EncryptedBackupFile, Error>> {
    const operationId = uuid.v4().toString();
    const snapshotPath = `${CACHES_DIRECTORY_PATH}/wallet-export-${operationId}.sqlite`;

    try {
      const snapshotResult = await ResultAsync.fromPromise(
        createWalletSnapshot(snapshotPath),
        asError,
      );
      if (snapshotResult.isErr()) {
        return err(snapshotResult.error);
      }
      const mnemonicResult = await getMnemonic();
      if (mnemonicResult.isErr()) {
        return err(mnemonicResult.error);
      }

      return await ResultAsync.fromPromise(
        encryptWalletSnapshot(
          snapshotResult.value.path,
          JSON.stringify(snapshotManifest(snapshotResult.value)),
          destinationPath,
          mnemonicResult.value,
        ),
        asError,
      );
    } finally {
      await removePathIfPresent(snapshotPath);
    }
  }

  private async restoreV2BackupObject(
    mnemonic: string,
    backupId: string,
    accessToken: string,
  ): Promise<Result<RestoreOutcome, Error>> {
    const downloadUrlResult = await getBackupObjectDownloadForRestore({
      accessToken,
      backupId,
    });
    if (downloadUrlResult.isErr()) {
      return err(downloadUrlResult.error);
    }

    const operationId = uuid.v4().toString();
    const encryptedPath = `${CACHES_DIRECTORY_PATH}/wallet-restore-${operationId}.noahbackup`;
    const restoreDirectory = `${CACHES_DIRECTORY_PATH}/wallet-restore-${operationId}`;
    try {
      const downloadResult = await ResultAsync.fromPromise(
        downloadFile(downloadUrlResult.value.download_url, encryptedPath, {}, 60),
        asError,
      );
      if (downloadResult.isErr()) {
        return err(downloadResult.error);
      }

      const decryptedResult = await ResultAsync.fromPromise(
        decryptWalletBackup(encryptedPath, restoreDirectory, mnemonic),
        asError,
      );
      if (decryptedResult.isErr()) {
        return err(decryptedResult.error);
      }
      const manifestResult = parseBackupManifest(decryptedResult.value.manifestJson);
      if (manifestResult.isErr()) {
        return err(manifestResult.error);
      }
      const manifest = manifestResult.value;
      if (manifest.network !== expectedNetwork()) {
        return err(new Error("Backup belongs to a different Bitcoin network"));
      }

      const validationResult = await ResultAsync.fromPromise(
        validateWalletSnapshot(decryptedResult.value.snapshotPath, {
          network: manifest.network,
          walletFingerprint: manifest.walletFingerprint,
          ...(manifest.serverPubkey ? { serverPubkey: manifest.serverPubkey } : {}),
        }),
        asError,
      );
      if (validationResult.isErr()) {
        return err(validationResult.error);
      }
      const validated = validationResult.value;
      if (
        validated.sha256 !== manifest.snapshotSha256 ||
        validated.schemaVersion !== manifest.barkSchemaVersion ||
        (validated.serverPubkey ?? null) !== manifest.serverPubkey ||
        (validated.mailboxPubkey ?? null) !== manifest.mailboxPubkey
      ) {
        return err(new Error("Backup identity validation failed"));
      }

      const installResult = await ResultAsync.fromPromise(
        installWalletSnapshot(decryptedResult.value.snapshotPath, ARK_DATA_PATH),
        asError,
      );
      if (installResult.isErr()) {
        return err(installResult.error);
      }

      return ok({
        backupId: downloadUrlResult.value.backup.backup_id,
        snapshotSha256: manifest.snapshotSha256,
        rollbackPath: installResult.value,
        usesSnapshotInstall: true,
      });
    } finally {
      await Promise.all([
        removePathIfPresent(encryptedPath),
        removePathIfPresent(restoreDirectory),
      ]);
    }
  }

  private async restoreLegacyBackup(mnemonic: string): Promise<Result<RestoreOutcome, Error>> {
    const downloadUrlResult = await getDownloadUrlForRestore({ mnemonic });
    if (downloadUrlResult.isErr()) {
      return err(downloadUrlResult.error);
    }
    const responseResult = await ResultAsync.fromPromise(
      ky.get(downloadUrlResult.value.download_url).text(),
      asError,
    );
    if (responseResult.isErr()) {
      return err(responseResult.error);
    }
    const restoreResult = await ResultAsync.fromPromise(
      restoreBackupNative(responseResult.value.trim(), mnemonic),
      asError,
    );
    if (restoreResult.isErr()) {
      return err(restoreResult.error);
    }
    return ok({
      backupId: null,
      snapshotSha256: null,
      rollbackPath: "",
      usesSnapshotInstall: false,
    });
  }

  async restoreBackup(mnemonic: string): Promise<Result<RestoreOutcome, Error>> {
    const backupsResult = await listBackupObjectsForRestore({ mnemonic });
    if (backupsResult.isErr()) {
      if (isNotFoundError(backupsResult.error)) {
        return this.restoreLegacyBackup(mnemonic);
      }
      return err(backupsResult.error);
    }
    if (backupsResult.value.backups.length === 0) {
      return this.restoreLegacyBackup(mnemonic);
    }

    let latestError: Error | null = null;
    for (const backup of backupsResult.value.backups) {
      if (backup.format_version !== BACKUP_FORMAT_VERSION) {
        latestError ??= new Error(`Unsupported backup format version ${backup.format_version}`);
        continue;
      }

      const restoreResult = await this.restoreV2BackupObject(
        mnemonic,
        backup.backup_id,
        backupsResult.value.accessToken,
      );
      if (restoreResult.isOk()) {
        return restoreResult;
      }
      latestError ??= restoreResult.error;
      log.w("Retained wallet backup failed validation, trying an older backup", [
        redactSensitiveErrorMessage(restoreResult.error),
      ]);
    }

    return err(latestError ?? new Error("No supported wallet backup was found"));
  }
}

export const restoreWallet = async (mnemonic: string): Promise<Result<void, Error>> => {
  let restoreOutcome: RestoreOutcome | null = null;
  let previousMnemonic: string | null = null;
  let walletOpened = false;
  let keychainMnemonicMayHaveChanged = false;
  let nativeMnemonicMayHaveChanged = false;
  const rollbackSnapshotInstall = async () => {
    if (!restoreOutcome?.usesSnapshotInstall) {
      return;
    }
    await rollbackWalletSnapshotInstall(ARK_DATA_PATH, restoreOutcome.rollbackPath);
    restoreOutcome = null;
  };

  const rollbackRestore = async () => {
    let rollbackError: Error | null = null;
    const rememberRollbackError = (error: Error) => {
      rollbackError ??= error;
    };

    if (walletOpened) {
      const closeResult = await closeWalletIfLoaded();
      if (closeResult.isErr()) {
        rememberRollbackError(closeResult.error);
      } else if (!closeResult.value) {
        rememberRollbackError(new Error("Failed to close restored wallet before rollback"));
      }
      walletOpened = false;
    }

    if (keychainMnemonicMayHaveChanged) {
      const restoreMnemonicResult = previousMnemonic
        ? await setMnemonic(previousMnemonic)
        : await clearMnemonic();
      if (restoreMnemonicResult.isErr()) {
        rememberRollbackError(restoreMnemonicResult.error);
      }
      keychainMnemonicMayHaveChanged = false;
    }

    if (nativeMnemonicMayHaveChanged && shouldUseUnifiedPush()) {
      const nativeRestoreResult = await ResultAsync.fromPromise(
        previousMnemonic
          ? storeNativeMnemonic(previousMnemonic)
          : clearNativeMnemonic(),
        asError,
      );
      if (nativeRestoreResult.isErr()) {
        rememberRollbackError(nativeRestoreResult.error);
      }
      nativeMnemonicMayHaveChanged = false;
    }

    const snapshotRollbackResult = await ResultAsync.fromPromise(
      rollbackSnapshotInstall(),
      asError,
    );
    if (snapshotRollbackResult.isErr()) {
      rememberRollbackError(snapshotRollbackResult.error);
    }

    if (rollbackError) {
      throw rollbackError;
    }
  };

  const failRestore = async (error: Error): Promise<Result<void, Error>> => {
    const rollbackResult = await ResultAsync.fromPromise(rollbackRestore(), asError);
    if (rollbackResult.isErr()) {
      log.e("Failed to roll back wallet restore", [
        redactSensitiveErrorMessage(rollbackResult.error),
      ]);
      return err(new Error("Wallet restore failed and could not be rolled back safely"));
    }
    return err(error);
  };

  try {
    updateProgress("Starting restore...", 0);
    const previousMnemonicResult = await getStoredMnemonic();
    if (previousMnemonicResult.isErr()) {
      return err(previousMnemonicResult.error);
    }
    previousMnemonic = previousMnemonicResult.value;

    const backupService = new BackupService();
    updateProgress("Fetching and validating backup...", 55);
    const restoreResult = await backupService.restoreBackup(mnemonic);
    if (restoreResult.isErr()) {
      return err(restoreResult.error);
    }
    restoreOutcome = restoreResult.value;

    updateProgress("Loading wallet...", 90);
    const loadWalletResult = await loadWalletWithMnemonic(mnemonic);
    if (loadWalletResult.isErr()) {
      return failRestore(loadWalletResult.error);
    }
    walletOpened = true;

    updateProgress("Finalizing...", 95);
    keychainMnemonicMayHaveChanged = true;
    const setMnemonicResult = await setMnemonic(mnemonic);
    if (setMnemonicResult.isErr()) {
      return failRestore(setMnemonicResult.error);
    }

    if (shouldUseUnifiedPush()) {
      nativeMnemonicMayHaveChanged = true;
      const storeNativeResult = await ResultAsync.fromPromise(
        storeNativeMnemonic(mnemonic),
        asError,
      );
      if (storeNativeResult.isErr()) {
        return failRestore(storeNativeResult.error);
      }
    }

    keychainMnemonicMayHaveChanged = false;
    nativeMnemonicMayHaveChanged = false;
    walletOpened = false;
    if (restoreOutcome.usesSnapshotInstall) {
      const rollbackPath = restoreOutcome.rollbackPath;
      restoreOutcome.usesSnapshotInstall = false;
      const finalizeResult = await ResultAsync.fromPromise(
        finalizeWalletSnapshotInstall(rollbackPath),
        asError,
      );
      if (finalizeResult.isErr()) {
        log.w("Failed to clean up wallet restore rollback data", [
          redactSensitiveErrorMessage(finalizeResult.error),
        ]);
      }
    }

    if (restoreOutcome.snapshotSha256) {
      useBackupStore
        .getState()
        .seedRestoredBackup(restoreOutcome.snapshotSha256, restoreOutcome.backupId);
    }
    updateProgress("Complete", 100);
    useWalletStore.setState({ isInitialized: true, isWalletLoaded: true, restoreProgress: null });
    return ok(undefined);
  } catch (error) {
    return failRestore(asError(error));
  } finally {
    useWalletStore.getState().setRestoreProgress(null);
  }
};
