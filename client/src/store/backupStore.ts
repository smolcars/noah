import { create } from "zustand";
import { persist, createJSONStorage, StateStorage } from "zustand/middleware";
import { mmkv } from "~/lib/mmkv";
import logger from "~/lib/log";

const log = logger("backupStore");

type BackupStatus = "idle" | "in_progress" | "success" | "failed";

interface BackupState {
  backupPending: boolean;
  lastBackupAt: number | null;
  lastBackupAttemptAt: number | null;
  lastBackupStatus: BackupStatus;
  lastBackupError: string | null;
  lastBackupId: string | null;
  lastUploadedSnapshotSha256: string | null;
  markBackupPending: () => void;
  setBackupInProgress: () => void;
  setBackupSuccess: (
    snapshotSha256: string,
    backupId: string | null,
    uploaded: boolean,
    backupPending: boolean,
  ) => void;
  setBackupFailed: (error: string) => void;
  seedRestoredBackup: (snapshotSha256: string, backupId: string | null) => void;
  forgetUploadedBackup: (backupId: string) => boolean;
  reset: () => void;
}

const zustandStorage: StateStorage = {
  setItem: (name: string, value: string) => {
    try {
      return mmkv.set(name, value);
    } catch (error) {
      // Silently fail to prevent error loops and crashes
      log.e("Backup storage setItem failed:", [error]);
      return;
    }
  },
  getItem: (name: string) => {
    try {
      const value = mmkv.getString(name);
      return value ?? null;
    } catch (error) {
      // Silently fail and return null
      log.e("Backup storage getItem failed:", [error]);
      return null;
    }
  },
  removeItem: (name: string) => {
    try {
      return mmkv.remove(name);
    } catch (error) {
      // Silently fail
      log.e("Backup storage removeItem failed:", [error]);
      return;
    }
  },
};

const initialState = {
  backupPending: false,
  lastBackupAt: null,
  lastBackupAttemptAt: null,
  lastBackupStatus: "idle" as BackupStatus,
  lastBackupError: null,
  lastBackupId: null,
  lastUploadedSnapshotSha256: null,
};

export const useBackupStore = create<BackupState>()(
  persist(
    (set) => ({
      ...initialState,
      markBackupPending: () =>
        set({
          backupPending: true,
          lastBackupStatus: "idle",
          lastBackupError: null,
        }),
      setBackupInProgress: () =>
        set({
          backupPending: true,
          lastBackupStatus: "in_progress",
          lastBackupAttemptAt: Date.now(),
          lastBackupError: null,
        }),
      setBackupSuccess: (snapshotSha256, backupId, uploaded, backupPending) =>
        set((state) => ({
          backupPending,
          lastBackupStatus: "success",
          lastBackupAt: uploaded ? Date.now() : state.lastBackupAt,
          lastBackupError: null,
          lastBackupId: backupId ?? state.lastBackupId,
          lastUploadedSnapshotSha256: snapshotSha256,
        })),
      setBackupFailed: (error: string) =>
        set({
          backupPending: true,
          lastBackupStatus: "failed",
          lastBackupError: error,
        }),
      seedRestoredBackup: (snapshotSha256, backupId) =>
        set({
          backupPending: false,
          lastBackupAt: Date.now(),
          lastBackupStatus: "success",
          lastBackupError: null,
          lastBackupId: backupId,
          lastUploadedSnapshotSha256: snapshotSha256,
        }),
      forgetUploadedBackup: (backupId) => {
        let didForget = false;
        set((state) => {
          if (state.lastBackupId !== backupId) {
            return state;
          }
          didForget = true;
          return {
            backupPending: true,
            lastBackupId: null,
            lastUploadedSnapshotSha256: null,
            lastBackupStatus: "idle",
            lastBackupError: null,
          };
        });
        return didForget;
      },
      reset: () => set(initialState),
    }),
    {
      name: "backup-storage",
      storage: createJSONStorage(() => zustandStorage),
    },
  ),
);
