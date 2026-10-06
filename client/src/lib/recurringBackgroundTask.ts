/**
 * Android background execution for recurring payments.
 *
 * Registers a periodic WorkManager job (via expo-background-task) while there
 * are active recurring payments. Android wakes the app about once a day
 * (later with battery optimisation, Doze or no network), and
 * the job runs the same executor as the push and foreground paths. This works
 * without the server: the push is only a faster, more precise wake-up.
 *
 * The job runs the executor only if the app is not in the foreground; while
 * Noah is open the foreground runner already handles due payments.
 *
 * This module must be imported at app start (see `index.ts`) so the task is
 * defined before Android starts it in a headless JS context.
 */
import { AppState, Platform } from "react-native";
import * as BackgroundTask from "expo-background-task";
import * as TaskManager from "expo-task-manager";
import { ResultAsync } from "neverthrow";

import logger from "~/lib/log";
import { isRecurringPaymentsSupported } from "~/constants";
import { executeDueRecurringPayments } from "~/lib/recurringPayments";
import { getRecurringPayments } from "~/store/recurringPaymentStore";
import { useWalletStore } from "~/store/walletStore";

const log = logger("recurringBackgroundTask");

export const RECURRING_PAYMENTS_BACKGROUND_TASK = "recurring-payments-background-task";

/**
 * Once a day keeps the battery cost negligible. A payment that comes due while
 * Noah is closed is sent within about 24 hours (sooner via push or when the app
 * is opened), and the overdue notification still fires 2 hours after the due time.
 */
const MINIMUM_INTERVAL_MINUTES = 24 * 60;

const isSupported = Platform.OS === "android";

const errorMessage = (e: unknown) => (e instanceof Error ? e.message : String(e));

if (isSupported) {
  TaskManager.defineTask(RECURRING_PAYMENTS_BACKGROUND_TASK, async () => {
    if (AppState.currentState === "active" || !isRecurringPaymentsSupported()) {
      return BackgroundTask.BackgroundTaskResult.Success;
    }
    if (!getRecurringPayments().some((schedule) => schedule.status === "active")) {
      return BackgroundTask.BackgroundTaskResult.Success;
    }

    // Same coordination flag as the push background job: the foreground app
    // waits for it before starting its own wallet work.
    useWalletStore.getState().setBackgroundJobRunning(true);
    try {
      const summary = await executeDueRecurringPayments("background");
      if (summary.paid || summary.failed || summary.needsAttention) {
        log.i("Background recurring payment run", [summary]);
      }
      return BackgroundTask.BackgroundTaskResult.Success;
    } catch (e) {
      log.e("Background recurring payment run failed", [errorMessage(e)]);
      return BackgroundTask.BackgroundTaskResult.Failed;
    } finally {
      useWalletStore.getState().setBackgroundJobRunning(false);
    }
  });
}

/**
 * Registers the periodic job while at least one recurring payment is active,
 * and removes it otherwise so Noah doesn't wake up for nothing.
 */
export async function syncRecurringBackgroundTask(): Promise<void> {
  if (!isSupported) return;

  const hasActive =
    isRecurringPaymentsSupported() &&
    getRecurringPayments().some((schedule) => schedule.status === "active");

  const result = await ResultAsync.fromPromise(
    (async () => {
      const registered = await TaskManager.isTaskRegisteredAsync(
        RECURRING_PAYMENTS_BACKGROUND_TASK,
      );
      if (hasActive && !registered) {
        const status = await BackgroundTask.getStatusAsync();
        if (status !== BackgroundTask.BackgroundTaskStatus.Available) {
          log.w("Background tasks are restricted on this device");
          return;
        }
        await BackgroundTask.registerTaskAsync(RECURRING_PAYMENTS_BACKGROUND_TASK, {
          minimumInterval: MINIMUM_INTERVAL_MINUTES,
        });
        log.i("Registered recurring payments background task");
      } else if (!hasActive && registered) {
        await BackgroundTask.unregisterTaskAsync(RECURRING_PAYMENTS_BACKGROUND_TASK);
        log.i("Unregistered recurring payments background task");
      }
    })(),
    (e) => new Error(`Failed to update recurring payments background task: ${errorMessage(e)}`),
  );

  if (result.isErr()) {
    log.w(result.error.message);
  }
}

export const isRecurringBackgroundTaskSupported = isSupported;
