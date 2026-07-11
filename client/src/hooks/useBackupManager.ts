import { useState } from "react";
import { Result } from "neverthrow";
import { listBackupObjects, deleteBackupObject, updateBackupSettings } from "../lib/api";

import { useServerStore } from "../store/serverStore";
import { useBackupStore } from "~/store/backupStore";
import logger from "~/lib/log";
import { cancelScheduledBackup, flushBackup, scheduleBackup } from "~/lib/backupCoordinator";
import type { BackupObjectInfo } from "~/types/serverTypes";

const log = logger("useBackupManager");

interface UseBackupManager {
  isBackupEnabled: boolean;
  setBackupEnabled: (enabled: boolean) => void;
  triggerBackup: () => Promise<Result<void, Error>>;
  listBackups: () => Promise<Result<BackupObjectInfo[], Error>>;
  deleteBackup: (backupId: string) => Promise<Result<void, Error>>;
  isLoading: boolean;
  backupsList: BackupObjectInfo[] | null;
}

export const useBackupManager = (): UseBackupManager => {
  const { isBackupEnabled, setBackupEnabled: setBackupEnabledStore } = useServerStore();
  const [isLoading, setIsLoading] = useState(false);
  const [backupsList, setBackupsList] = useState<BackupObjectInfo[] | null>(null);

  const setBackupEnabled = async (enabled: boolean) => {
    setIsLoading(true);
    setBackupEnabledStore(enabled);
    const updateResult = await updateBackupSettings({ backup_enabled: enabled });
    if (updateResult.isErr()) {
      log.e("Failed to update backup settings:", [updateResult.error]);
      // Revert the local state if the API call failed
      setBackupEnabledStore(!enabled);
    } else if (enabled) {
      scheduleBackup("startup", { immediate: true });
    } else {
      cancelScheduledBackup();
    }
    setIsLoading(false);
  };

  const triggerBackup = async (): Promise<Result<void, Error>> => {
    setIsLoading(true);

    const backupResult = await flushBackup("manual", { requireEnabled: false });

    if (backupResult.isErr()) {
      setIsLoading(false);
      return backupResult;
    }

    // Refresh the backups list after successful backup
    const refreshResult = await listBackupObjects();
    if (refreshResult.isOk()) {
      setBackupsList(refreshResult.value);
    }

    setIsLoading(false);
    return backupResult;
  };

  const listBackups = async (): Promise<Result<BackupObjectInfo[], Error>> => {
    setIsLoading(true);
    const result = await listBackupObjects();
    if (result.isOk()) {
      setBackupsList(result.value);
    }
    setIsLoading(false);
    return result;
  };

  const deleteBackup = async (backupId: string): Promise<Result<void, Error>> => {
    setIsLoading(true);
    const result = await deleteBackupObject({ backup_id: backupId });

    if (result.isOk()) {
      // Update the local backups list by removing the deleted backup
      setBackupsList((prev) =>
        prev ? prev.filter((backup) => backup.backup_id !== backupId) : null,
      );
      const deletedCurrentBackup = useBackupStore.getState().forgetUploadedBackup(backupId);
      if (deletedCurrentBackup && isBackupEnabled) {
        scheduleBackup("manual");
      }
    }

    setIsLoading(false);
    return result.map(() => undefined);
  };

  return {
    isBackupEnabled,
    setBackupEnabled,
    triggerBackup,
    listBackups,
    deleteBackup,
    isLoading,
    backupsList,
  };
};
