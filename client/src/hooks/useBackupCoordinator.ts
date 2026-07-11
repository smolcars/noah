import { useEffect, useState } from "react";
import { AppState } from "react-native";
import { subscribeWalletStateChanges, type WalletStateChangeEvent } from "react-native-nitro-ark";
import { cancelScheduledBackup, scheduleBackup } from "~/lib/backupCoordinator";
import logger from "~/lib/log";
import { useServerStore } from "~/store/serverStore";
import { useWalletStore } from "~/store/walletStore";

const log = logger("useBackupCoordinator");

export const useBackupCoordinator = (isReady: boolean) => {
  const [subscriptionGeneration, setSubscriptionGeneration] = useState(0);
  const isBackupEnabled = useServerStore((state) => state.isBackupEnabled);
  const isInitialized = useWalletStore((state) => state.isInitialized);
  const isWalletLoaded = useWalletStore((state) => state.isWalletLoaded);
  const isWalletSuspended = useWalletStore((state) => state.isWalletSuspended);
  const canCoordinateBackups =
    isReady && isBackupEnabled && isInitialized && isWalletLoaded && !isWalletSuspended;

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
          scheduleBackup("database_changed");
          return;
        }

        if (event.reason === "resyncRequired") {
          scheduleBackup("resync_required", { immediate: true });
          setSubscriptionGeneration((generation) => generation + 1);
          return;
        }

        scheduleBackup("startup", { immediate: true });
      });

      return () => {
        if (subscription.isActive()) {
          subscription.stop();
        }
      };
    } catch (error) {
      log.w("Failed to watch wallet state changes", [error]);
      scheduleBackup("startup", { immediate: true });
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
        scheduleBackup("app_foreground", { immediate: true });
      }
    });

    return () => appStateSubscription.remove();
  }, [canCoordinateBackups]);
};
