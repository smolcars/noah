import { useCallback, useEffect } from "react";
import { AppState } from "react-native";
import { useWalletStore } from "~/store/walletStore";
import logger from "~/lib/log";
import { runForegroundWalletOperation } from "~/lib/walletOperationCoordinator";

const log = logger("useBackgroundJobCoordination");

/**
 * Hook to coordinate background jobs and prevent race conditions with foreground operations.
 *
 * ## The Problem
 * When a push notification triggers background wallet operations (maintenance, sync),
 * and the user opens the app simultaneously, both try to access wallet resources concurrently.
 * This causes operations to conflict, resulting in the app hanging in a loading state.
 *
 * ## How It Works
 *
 * ### Normal Flow (No Background Job):
 * 1. User opens app
 * 2. Hook checks: isBackgroundJobRunning = false
 * 3. Wallet loads immediately
 *
 * ### Background Job Running:
 * 1. Push notification or WorkManager acquires the native wallet lease
 * 2. User opens app while background work is running
 * 3. Foreground work waits, then atomically acquires the same lease
 * 4. The callback runs only after background work releases the wallet
 *
 * ### Stale Flag Protection:
 * If background job crashes and finally block never executes:
 * 1. Background job starts → flag = true, timestamp = recorded
 * 2. Job crashes → finally never runs → flag stays true
 * 3. [60 seconds pass]
 * 4. User opens app → triggers clearStaleBackgroundJobFlag()
 * 5. Check: Date.now() - timestamp > 60000ms?
 * 6. YES → log warning, set flag = false
 * 7. The callback can acquire the native lease.
 *
 * ### Multiple Safety Checks:
 * - Before every operation: clearStaleBackgroundJobFlag()
 * - When app comes to foreground: clearStaleBackgroundJobFlag()
 * - Native lease acquisition closes the race between checking status and using the wallet
 *
 * @returns {Function} safelyExecuteWhenReady - Wrapper function that waits for background jobs
 *                                               before executing the provided callback
 */
export const useBackgroundJobCoordination = () => {
  const { clearStaleBackgroundJobFlag } = useWalletStore();
  const isBackgroundJobRunning = useWalletStore((state) => state.isBackgroundJobRunning);
  const isNativeBackgroundJobRunning = useWalletStore(
    (state) => state.isNativeBackgroundJobRunning,
  );

  /**
   * Executes a callback function only after ensuring no background jobs are running.
   * Includes stale JavaScript flag detection and native lease acquisition.
   *
   * @param callback - Async function to execute once it's safe
   * @returns Promise that resolves when callback completes
   */
  const safelyExecuteWhenReady = useCallback(
    async <T>(callback: () => Promise<T>): Promise<T> => {
      clearStaleBackgroundJobFlag();
      return runForegroundWalletOperation(callback);
    },
    [clearStaleBackgroundJobFlag],
  );

  /**
   * Clear stale background job flags when app comes to foreground.
   * This catches cases where background jobs crashed and the flag was never cleared.
   */
  useEffect(() => {
    const subscription = AppState.addEventListener("change", (nextAppState) => {
      if (nextAppState === "active") {
        log.d("App came to foreground, checking for stale background job flags");
        clearStaleBackgroundJobFlag();
      }
    });

    return () => subscription.remove();
  }, [clearStaleBackgroundJobFlag]);

  return {
    safelyExecuteWhenReady,
    isBackgroundJobRunning: isBackgroundJobRunning || isNativeBackgroundJobRunning,
  };
};
