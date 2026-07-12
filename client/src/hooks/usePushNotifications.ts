import { useEffect } from "react";
import { useServerStore } from "~/store/serverStore";
import logger from "~/lib/log";
import { loadWalletIfNeeded } from "~/lib/walletApi";
import { BackupService } from "~/lib/backupService";
import { registerPushNotificationsForServer } from "~/lib/server";

const log = logger("usePushNotifications");

export const usePushNotifications = (isReady: boolean) => {
  const { isRegisteredWithServer, isBackupEnabled } = useServerStore();

  useEffect(() => {
    const register = async () => {
      if (!isReady || !isRegisteredWithServer) {
        return;
      }

      await loadWalletIfNeeded();

      const registerResult = await registerPushNotificationsForServer();
      if (registerResult.isErr()) {
        log.w("Failed to register for push notifications", [registerResult.error]);
        return;
      }

      log.d("Push notification registration flow completed");

      // If backup is enabled, then register with server for backup
      log.d("Is backup enabled?", [isBackupEnabled]);
      if (isBackupEnabled) {
        const backupService = new BackupService();
        backupService.registerBackup();
      }
    };

    register();
  }, [isRegisteredWithServer, isReady]);
};
