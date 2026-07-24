import { useEffect } from "react";
import { AppState } from "react-native";
import {
  cancelAndroidBackgroundSync,
  isAndroidBackgroundWalletJobRunning,
  scheduleAndroidBackgroundSync,
  storeNativeMnemonic,
} from "noah-tools";
import { PLATFORM } from "~/constants";
import { getMnemonic } from "~/lib/crypto";
import logger from "~/lib/log";
import { useWalletStore } from "~/store/walletStore";

const log = logger("useAndroidBackgroundSync");
const NATIVE_JOB_POLL_INTERVAL_MS = 250;

export function useAndroidBackgroundSync(isReady: boolean) {
  const isInitialized = useWalletStore((state) => state.isInitialized);
  const isWalletSuspended = useWalletStore((state) => state.isWalletSuspended);

  useEffect(() => {
    if (PLATFORM !== "android" || !isReady) {
      return;
    }

    if (!isInitialized || isWalletSuspended) {
      cancelAndroidBackgroundSync();
      return;
    }

    let isCancelled = false;

    void (async () => {
      const mnemonicResult = await getMnemonic();
      if (mnemonicResult.isErr()) {
        log.w("Unable to prepare Android background sync", [mnemonicResult.error]);
        return;
      }

      try {
        await storeNativeMnemonic(mnemonicResult.value);
        if (!isCancelled) {
          scheduleAndroidBackgroundSync();
          log.d("Android background sync scheduled");
        }
      } catch (error) {
        log.w("Failed to schedule Android background sync", [error]);
      }
    })();

    return () => {
      isCancelled = true;
    };
  }, [isInitialized, isReady, isWalletSuspended]);

  useEffect(() => {
    if (PLATFORM !== "android" || !isReady) {
      return;
    }

    let pollTimer: ReturnType<typeof setInterval> | undefined;
    const setNativeBackgroundJobRunning = useWalletStore.getState().setNativeBackgroundJobRunning;

    const refreshNativeJobStatus = () => {
      setNativeBackgroundJobRunning(isAndroidBackgroundWalletJobRunning());
    };

    const stopPolling = () => {
      if (pollTimer) {
        clearInterval(pollTimer);
        pollTimer = undefined;
      }
    };

    const startPolling = () => {
      refreshNativeJobStatus();
      if (!pollTimer) {
        pollTimer = setInterval(refreshNativeJobStatus, NATIVE_JOB_POLL_INTERVAL_MS);
      }
    };

    if (AppState.currentState === "active") {
      startPolling();
    } else {
      refreshNativeJobStatus();
    }

    const subscription = AppState.addEventListener("change", (nextState) => {
      if (nextState === "active") {
        startPolling();
      } else {
        stopPolling();
        refreshNativeJobStatus();
      }
    });

    return () => {
      stopPolling();
      subscription.remove();
      setNativeBackgroundJobRunning(false);
    };
  }, [isReady]);
}
