import { useEffect } from "react";
import { AppState } from "react-native";
import { reconcileLnurlPayReceiveMetadata } from "~/lib/lnurlPayReceiveMetadataService";
import logger from "~/lib/log";
import { useWalletStore } from "~/store/walletStore";

const log = logger("useLnurlPayReceiveMetadata");

const reconcile = async () => {
  // An existing AppState listener can fire before React cleans up the effect after a task starts.
  if (
    AppState.currentState !== "active" ||
    useWalletStore.getState().isBackgroundJobRunning
  ) {
    return;
  }

  const result = await reconcileLnurlPayReceiveMetadata();
  if (result.isErr()) {
    log.w("Failed to reconcile LNURL-pay receive metadata", [result.error]);
  }
};

export const useLnurlPayReceiveMetadata = (isReady: boolean): void => {
  const { isInitialized, isWalletLoaded, isWalletSuspended, isBackgroundJobRunning } =
    useWalletStore();
  const enabled =
    isReady &&
    isInitialized &&
    isWalletLoaded &&
    !isWalletSuspended &&
    !isBackgroundJobRunning;

  useEffect(() => {
    if (!enabled) {
      return;
    }

    void reconcile();

    const subscription = AppState.addEventListener("change", (nextState) => {
      if (nextState === "active") {
        void reconcile();
      }
    });

    return () => subscription.remove();
  }, [enabled]);
};
