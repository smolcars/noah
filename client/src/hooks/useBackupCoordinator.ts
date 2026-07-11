import { useEffect, useState } from "react";
import { AppState } from "react-native";
import { subscribeWalletStateChanges, type WalletStateChangeEvent } from "react-native-nitro-ark";
import {
  cancelScheduledBackup,
  scheduleBackup,
  type BackupReason,
} from "~/lib/backupCoordinator";
import logger from "~/lib/log";
import { useServerStore } from "~/store/serverStore";
import { useWalletStore } from "~/store/walletStore";

const log = logger("useBackupCoordinator");

const scheduleBackupWhenIdle = (reason: BackupReason, immediate = false): boolean => {
  if (useWalletStore.getState().isBackgroundJobRunning) {
    log.d("Deferring automatic backup while a background job is running", [reason]);
    return false;
  }

  scheduleBackup(reason, { immediate });
  return true;
};

export const useBackupCoordinator = (isReady: boolean) => {
  const [subscriptionGeneration, setSubscriptionGeneration] = useState(0);
  const isBackupEnabled = useServerStore((state) => state.isBackupEnabled);
  const isInitialized = useWalletStore((state) => state.isInitialized);
  const isWalletLoaded = useWalletStore((state) => state.isWalletLoaded);
  const isWalletSuspended = useWalletStore((state) => state.isWalletSuspended);
  const isBackgroundJobRunning = useWalletStore((state) => state.isBackgroundJobRunning);
  const canCoordinateBackups =
    isReady &&
    isBackupEnabled &&
    isInitialized &&
    isWalletLoaded &&
    !isWalletSuspended &&
    !isBackgroundJobRunning;

  useEffect(() => {
    if (!canCoordinateBackups) {
      cancelScheduledBackup();
    }
  }, [canCoordinateBackups]);

  useEffect(() => {
    if (!canCoordinateBackups) {
      return;
    }

    try {
      const subscription = subscribeWalletStateChanges((event: WalletStateChangeEvent) => {
        if (event.reason === "databaseChanged") {
          scheduleBackupWhenIdle("database_changed");
          return;
        }

        if (event.reason === "resyncRequired") {
          if (scheduleBackupWhenIdle("resync_required", true)) {
            setSubscriptionGeneration((generation) => generation + 1);
          }
          return;
        }

        scheduleBackupWhenIdle("startup", true);
      });

      return () => {
        if (subscription.isActive()) {
          subscription.stop();
        }
      };
    } catch (error) {
      log.w("Failed to watch wallet state changes", [error]);
      scheduleBackupWhenIdle("startup", true);
    }
  }, [
    canCoordinateBackups,
    subscriptionGeneration,
  ]);

  useEffect(() => {
    if (!canCoordinateBackups) {
      return;
    }

    const appStateSubscription = AppState.addEventListener("change", (nextState) => {
      if (nextState === "active") {
        scheduleBackupWhenIdle("app_foreground", true);
      }
    });

    return () => appStateSubscription.remove();
  }, [canCoordinateBackups]);
};
