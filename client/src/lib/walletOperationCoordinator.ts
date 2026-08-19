import {
  isAndroidBackgroundWalletJobRunning,
  releaseAndroidBackgroundWalletJob,
  tryAcquireAndroidBackgroundWalletJob,
} from "noah-tools";
import { PLATFORM } from "~/constants";
import logger from "~/lib/log";
import { useWalletStore } from "~/store/walletStore";
import { createForegroundWalletOperationRunner } from "~/lib/walletOperationCoordinatorCore";

const log = logger("walletOperationCoordinator");
const CHECK_INTERVAL_MS = 100;
let foregroundWalletJobSequence = 0;

const waitForJavaScriptBackgroundJob = async () => {
  useWalletStore.getState().clearStaleBackgroundJobFlag();

  let didLogWait = false;
  while (useWalletStore.getState().isBackgroundJobRunning) {
    if (!didLogWait) {
      log.i("Waiting for a JavaScript background wallet job");
      didLogWait = true;
    }
    await new Promise((resolve) => setTimeout(resolve, CHECK_INTERVAL_MS));
    useWalletStore.getState().clearStaleBackgroundJobFlag();
  }
};

export function isBackgroundWalletJobRunning(): boolean {
  const state = useWalletStore.getState();
  return (
    state.isBackgroundJobRunning ||
    (PLATFORM === "android" && isAndroidBackgroundWalletJobRunning())
  );
}

let didLogNativeWait = false;
const runAndroidForegroundWalletOperation = createForegroundWalletOperationRunner({
  tryAcquire: (owner) => {
    const acquired = tryAcquireAndroidBackgroundWalletJob(owner);
    if (!acquired && !didLogNativeWait) {
      log.i("Waiting for an Android background wallet job");
      didLogNativeWait = true;
    }
    if (acquired) {
      didLogNativeWait = false;
    }
    return acquired;
  },
  release: releaseAndroidBackgroundWalletJob,
  waitForBackgroundJob: waitForJavaScriptBackgroundJob,
  waitBeforeRetry: () => new Promise((resolve) => setTimeout(resolve, CHECK_INTERVAL_MS)),
  createOwner: () => {
    foregroundWalletJobSequence += 1;
    return `foreground:${Date.now()}:${foregroundWalletJobSequence}`;
  },
});

/**
 * Runs a foreground wallet operation while holding the same native lease used by
 * WorkManager and push jobs. Background work can no longer start between a status
 * check and the foreground operation itself.
 */
export async function runForegroundWalletOperation<T>(operation: () => Promise<T>): Promise<T> {
  if (PLATFORM !== "android") {
    await waitForJavaScriptBackgroundJob();
    return operation();
  }

  return runAndroidForegroundWalletOperation(operation);
}
