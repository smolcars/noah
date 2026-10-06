import { useEffect } from "react";
import { AppState } from "react-native";

import logger from "~/lib/log";
import { isRecurringPaymentsSupported } from "~/constants";
import { syncRecurringBackgroundTask } from "~/lib/recurringBackgroundTask";
import {
  executeDueRecurringPayments,
  hasRecurringPayments,
  syncRecurringPaymentsWithServer,
} from "~/lib/recurringPayments";
import { useRecurringPaymentStore } from "~/store/recurringPaymentStore";
import { useServerStore } from "~/store/serverStore";
import { useWalletStore } from "~/store/walletStore";

const log = logger("useRecurringPaymentsRunner");

/** Checks for due recurring payments while the app is open, and on every foreground. */
const FOREGROUND_CHECK_INTERVAL_MS = 60_000;

export const useRecurringPaymentsRunner = (isReady: boolean) => {
  const isWalletLoaded = useWalletStore((state) => state.isWalletLoaded);
  const isWalletSuspended = useWalletStore((state) => state.isWalletSuspended);
  const isBackgroundJobRunning = useWalletStore((state) => state.isBackgroundJobRunning);
  const isRegisteredWithServer = useServerStore((state) => state.isRegisteredWithServer);
  const hasSchedules = useRecurringPaymentStore(
    (state) => Object.keys(state.schedules).length > 0 || state.serverSyncPending,
  );

  const activeScheduleCount = useRecurringPaymentStore(
    (state) => Object.values(state.schedules).filter((s) => s.status === "active").length,
  );

  // Keep the Android WorkManager job registered only while something is active.
  useEffect(() => {
    if (!isReady) return;
    void syncRecurringBackgroundTask();
  }, [isReady, activeScheduleCount]);

  const canRun =
    isRecurringPaymentsSupported() &&
    isReady &&
    isWalletLoaded &&
    !isWalletSuspended &&
    !isBackgroundJobRunning &&
    hasSchedules;

  useEffect(() => {
    if (!canRun) return;

    const run = () => {
      if (!hasRecurringPayments() && !useRecurringPaymentStore.getState().serverSyncPending) {
        return;
      }
      executeDueRecurringPayments("foreground")
        .then((summary) => {
          if (summary.paid || summary.failed || summary.needsAttention) {
            log.i("Foreground recurring payment run", [summary]);
          }
        })
        .catch((e) => log.e("Foreground recurring payment run failed", [e]));
    };

    run();
    const interval = setInterval(run, FOREGROUND_CHECK_INTERVAL_MS);
    const subscription = AppState.addEventListener("change", (state) => {
      if (state === "active") run();
    });

    return () => {
      clearInterval(interval);
      subscription.remove();
    };
  }, [canRun]);

  // Retry a failed server sync once registration is available.
  useEffect(() => {
    if (!isReady || !isRegisteredWithServer) return;
    if (useRecurringPaymentStore.getState().serverSyncPending) {
      void syncRecurringPaymentsWithServer();
    }
  }, [isReady, isRegisteredWithServer]);
};
