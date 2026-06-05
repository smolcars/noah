import { Platform } from "react-native";
import * as Device from "expo-device";
import * as Notifications from "expo-notifications";
import * as TaskManager from "expo-task-manager";
import Constants from "expo-constants";
import logger from "~/lib/log";
import { captureException } from "@sentry/react-native";
import {
  claimLightningReceivesTask,
  maintenanceTask,
  submitInvoiceTask,
  triggerBackupTask,
} from "./tasks";
import { registerPushToken, reportJobStatus, heartbeatResponse } from "~/lib/api";
import { err, ok, Result, ResultAsync } from "neverthrow";
import { NotificationData, ReportType } from "~/types/serverTypes";
import { useWalletStore } from "~/store/walletStore";
import { formatBip177 } from "./utils";
import { updateWidget } from "~/hooks/useWidget";
import { shouldUseUnifiedPush } from "~/constants";

const log = logger("pushNotifications");

export type PushPermissionStatus = {
  status: Notifications.PermissionStatus;
  isPhysicalDevice: boolean;
};

export type RegisterForPushResult =
  | { kind: "success"; pushToken: string; pushType: "expo" | "unified" }
  | { kind: "permission_denied"; permissionStatus: Notifications.PermissionStatus }
  | { kind: "device_not_supported" };

const BACKGROUND_NOTIFICATION_TASK = "BACKGROUND-NOTIFICATION-TASK";
const DEFAULT_NOTIFICATION_CHANNEL_ID = "default";
const KNOWN_NOTIFICATION_TYPES = new Set<string>([
  "maintenance",
  "lightning_invoice_request",
  "lightning_claim_request",
  "backup_trigger",
  "heartbeat",
]);

async function ensureDefaultNotificationChannel() {
  if (Platform.OS !== "android") {
    return;
  }

  await Notifications.setNotificationChannelAsync(DEFAULT_NOTIFICATION_CHANNEL_ID, {
    name: "default",
    importance: Notifications.AndroidImportance.MAX,
    vibrationPattern: [0, 250, 250, 250],
    lightColor: "#FF231F7C",
  });
}

async function scheduleLightningPaymentNotification(amountSat: number): Promise<string> {
  await ensureDefaultNotificationChannel();

  return Notifications.scheduleNotificationAsync({
    content: {
      title: "Lightning Payment Received! ⚡",
      body: `You received ${formatBip177(amountSat)}`,
      sound: "default",
      priority: Notifications.AndroidNotificationPriority.MAX,
      data: {
        notification_type: "lightning_payment_received",
      },
    },
    trigger: Platform.OS === "android" ? { channelId: DEFAULT_NOTIFICATION_CHANNEL_ID } : null,
  });
}

function asRecord(value: unknown): Record<string, unknown> | null {
  if (!value || typeof value !== "object") {
    return null;
  }

  return value as Record<string, unknown>;
}

function isKnownNotificationType(value: string): boolean {
  return KNOWN_NOTIFICATION_TYPES.has(value);
}

function extractNotificationPayload(payload: unknown): unknown {
  const record = asRecord(payload);
  if (!record) {
    return null;
  }

  if (
    typeof record.notification_type === "string" &&
    isKnownNotificationType(record.notification_type)
  ) {
    return record;
  }

  if ("body" in record) {
    return record.body;
  }

  if (typeof record.dataString === "string") {
    return record.dataString;
  }

  const nestedData = asRecord(record.data);
  if (nestedData) {
    if (
      typeof nestedData.notification_type === "string" &&
      isKnownNotificationType(nestedData.notification_type)
    ) {
      return nestedData;
    }

    if ("body" in nestedData) {
      return nestedData.body;
    }

    if (typeof nestedData.dataString === "string") {
      return nestedData.dataString;
    }

    const launchPayload = asRecord(nestedData.UIApplicationLaunchOptionsRemoteNotificationKey);
    if (launchPayload && "body" in launchPayload) {
      return launchPayload.body;
    }
  }

  const launchPayload = asRecord(record.UIApplicationLaunchOptionsRemoteNotificationKey);
  if (launchPayload && "body" in launchPayload) {
    return launchPayload.body;
  }

  return null;
}

function parseNotificationData(payload: unknown): Result<NotificationData | null, Error> {
  return Result.fromThrowable(
    () => {
      const rawPayload = extractNotificationPayload(payload);
      if (!rawPayload) {
        return null;
      }

      const parsed =
        typeof rawPayload === "string" ? (JSON.parse(rawPayload) as unknown) : rawPayload;
      const parsedRecord = asRecord(parsed);

      if (
        !parsedRecord ||
        typeof parsedRecord.notification_type !== "string" ||
        !KNOWN_NOTIFICATION_TYPES.has(parsedRecord.notification_type)
      ) {
        return null;
      }

      return parsedRecord as NotificationData;
    },
    (e) => new Error(`Failed to parse notification data: ${e}`),
  )();
}

async function handleNotificationData(notificationData: NotificationData) {
  switch (notificationData.notification_type) {
    case "maintenance": {
      const result = await maintenanceTask();
      // Also perform a sync after maintenance
      await handleTaskCompletion("maintenance", result, notificationData.notification_k1);

      // Refresh widget after maintenance
      await updateWidget();
      break;
    }

    case "lightning_invoice_request": {
      log.i("Received lightning invoice request", [notificationData]);
      const invoiceResult = await submitInvoiceTask(
        notificationData.transaction_id,
        notificationData.amount,
      );
      if (invoiceResult.isErr()) {
        throw invoiceResult.error;
      }
      break;
    }

    case "lightning_claim_request": {
      log.i("Received lightning claim request", [notificationData]);
      log.i("Claiming pending lightning receives", [notificationData.payment_hash]);
      const claimResult = await claimLightningReceivesTask();
      if (claimResult.isErr()) {
        throw claimResult.error;
      }
      log.i("Successfully claimed pending lightning receives", [notificationData.payment_hash]);

      const notificationId = await scheduleLightningPaymentNotification(
        notificationData.amount_sat,
      );
      log.i("Local notification scheduled for lightning payment", [
        notificationId,
        notificationData.payment_hash,
        notificationData.amount_sat,
      ]);

      await updateWidget();
      break;
    }

    case "backup_trigger": {
      const result = await triggerBackupTask();
      await handleTaskCompletion("backup", result, notificationData.notification_k1);
      log.d("Backup task completed");
      break;
    }

    case "heartbeat": {
      log.i("Received heartbeat notification", [notificationData]);
      const heartbeatResult = await heartbeatResponse({
        notification_id: notificationData.notification_id,
      });

      if (heartbeatResult.isErr()) {
        log.w("Failed to respond to heartbeat", [heartbeatResult.error]);
      } else {
        log.d("Successfully responded to heartbeat", [notificationData.notification_id]);
      }
      break;
    }

    default: {
      const _exhaustiveCheck: never = notificationData;
      log.w("Unknown notification type received", [_exhaustiveCheck]);
    }
  }
}

/**
 * Reports job completion status to the server.
 * Called after each background task completes or fails.
 */
async function handleTaskCompletion(
  report_type: ReportType,
  result: Result<void, Error>,
  notification_k1: string,
) {
  if (result.isErr()) {
    log.w(`Failed to trigger ${report_type} task, reporting failure`);
    const jobStatusResult = await reportJobStatus({
      report_type,
      status: "failure",
      error_message: result.error.message,
      notification_k1,
    });

    if (jobStatusResult.isErr()) {
      log.w("Failed to report job status", [jobStatusResult.error]);
    }
    throw result.error;
  }

  const jobStatusResult = await reportJobStatus({
    report_type,
    status: "success",
    error_message: null,
    notification_k1,
  });

  if (jobStatusResult.isErr()) {
    log.i("Failed to report job status", [jobStatusResult.error]);
    return;
  }

  log.d(`Triggered ${report_type} task, successfully`);
}

TaskManager.defineTask<Notifications.NotificationTaskPayload>(
  BACKGROUND_NOTIFICATION_TASK,
  async ({ data, error }) => {
    /**
     * BACKGROUND JOB COORDINATION:
     *
     * Set flag to true at the start of any background job. This signals to the
     * foreground app (HomeScreen) that wallet operations are in progress.
     *
     * If the user opens the app while this is true, the app will wait for this
     * flag to clear before attempting its own wallet operations, preventing
     * concurrent access conflicts that cause the app to hang.
     *
     * The flag is cleared in the finally block below to ensure it's always reset,
     * even if the task fails. If the task crashes before finally executes, the
     * timestamp-based stale flag detection will clear it after 60 seconds.
     */
    useWalletStore.getState().setBackgroundJobRunning(true);

    try {
      log.i("[Background Job] dataReceived", [data, typeof data]);
      if (error) {
        log.e("[Background Job] error", [error]);
        captureException(error);
        return;
      }

      const notificationDataResult = parseNotificationData(data);

      if (notificationDataResult.isErr()) {
        captureException(notificationDataResult.error);
        log.e("[Background Job] error", [notificationDataResult.error]);
        return;
      }

      const notificationData = notificationDataResult.value;

      if (!notificationData) {
        log.w("[Background Job] No data or type received", [notificationData]);
        return;
      }

      const taskResult = await ResultAsync.fromPromise(
        handleNotificationData(notificationData),
        (e) =>
          new Error(
            `Failed to handle background notification: ${e instanceof Error ? e.message : String(e)}`,
          ),
      );

      if (taskResult.isErr()) {
        captureException(taskResult.error);
        log.e("[Background Job] error", [taskResult.error]);
      }
    } finally {
      /**
       * Always clear the background job flag, even if an error occurred.
       *
       * This ensures the foreground app doesn't wait indefinitely. The finally
       * block executes even if there are errors in the try block, ensuring
       * proper cleanup.
       *
       * Note: In catastrophic failures (OS kills task, out of memory, etc.),
       * the finally block may not execute. In those cases, the timestamp-based
       * stale flag detection in walletStore.clearStaleBackgroundJobFlag() will
       * clean up after 60 seconds.
       */
      useWalletStore.getState().setBackgroundJobRunning(false);
      log.d("[Background Job] Completed, flag cleared");
    }
  },
);

Notifications.registerTaskAsync(BACKGROUND_NOTIFICATION_TASK);

Notifications.addNotificationReceivedListener((notification) => {
  const notificationDataResult = parseNotificationData(notification.request.content.data);
  if (notificationDataResult.isErr()) {
    captureException(notificationDataResult.error);
    log.e("[Foreground Notification] error", [notificationDataResult.error]);
    return;
  }

  const notificationData = notificationDataResult.value;
  if (!notificationData) {
    return;
  }

  void (async () => {
    useWalletStore.getState().setBackgroundJobRunning(true);

    try {
      await handleNotificationData(notificationData);
    } catch (e) {
      const error =
        e instanceof Error
          ? e
          : new Error(`Failed to handle foreground notification: ${String(e)}`);
      captureException(error);
      log.e("[Foreground Notification] error", [error]);
    } finally {
      useWalletStore.getState().setBackgroundJobRunning(false);
    }
  })();
});

Notifications.setNotificationHandler({
  handleNotification: async () => ({
    shouldShowAlert: true,
    shouldPlaySound: true,
    shouldSetBadge: true,
    shouldShowBanner: true,
    shouldShowList: true,
  }),
});

export async function getPushPermissionStatus(): Promise<Result<PushPermissionStatus, Error>> {
  if (!Device.isDevice) {
    // Simulators and emulators cannot receive push notifications; skip gating in that scenario.
    return ok({ status: Notifications.PermissionStatus.DENIED, isPhysicalDevice: false });
  }

  const permissionResult = await ResultAsync.fromPromise(
    Notifications.getPermissionsAsync(),
    (e) => e as Error,
  );

  if (permissionResult.isErr()) {
    return err(permissionResult.error);
  }

  return ok({ status: permissionResult.value.status, isPhysicalDevice: true });
}

export async function registerForPushNotificationsAsync(): Promise<
  Result<RegisterForPushResult, Error>
> {
  await ensureDefaultNotificationChannel();

  // If the device is not a physical device, return a non-supported status
  if (!Device.isDevice) {
    return ok({ kind: "device_not_supported" });
  }

  // Request permissions for all devices first
  const { status: existingStatus } = await Notifications.getPermissionsAsync();
  let finalStatus = existingStatus;
  if (existingStatus !== "granted") {
    const { status } = await Notifications.requestPermissionsAsync();
    finalStatus = status;
  }
  if (finalStatus !== "granted") {
    return ok({ kind: "permission_denied", permissionStatus: finalStatus });
  }

  // Prefer UnifiedPush endpoint on Android real device without Play Services.
  if (shouldUseUnifiedPush()) {
    try {
      const { getUnifiedPushEndpoint } = await import("noah-tools");
      const unifiedEndpoint = getUnifiedPushEndpoint();
      if (unifiedEndpoint) {
        return ok({ kind: "success", pushToken: unifiedEndpoint, pushType: "unified" });
      }
    } catch (e) {
      log.w("UnifiedPush endpoint lookup failed", [e]);
    }
  }
  const projectId = Constants?.expoConfig?.extra?.eas?.projectId ?? Constants?.easConfig?.projectId;
  if (!projectId) {
    return err(new Error("Project ID not found"));
  }
  const nativePushTokenResult = await ResultAsync.fromPromise(
    Notifications.getDevicePushTokenAsync(),
    (e) => e as Error,
  );

  if (nativePushTokenResult.isErr()) {
    log.w("Failed to get native push token", [nativePushTokenResult.error]);
    return err(nativePushTokenResult.error);
  }

  const pushTokenResult = await ResultAsync.fromPromise(
    Notifications.getExpoPushTokenAsync({
      projectId,
    }),
    (e) => e as Error,
  );

  if (pushTokenResult.isErr()) {
    return err(pushTokenResult.error);
  }

  const pushTokenString = pushTokenResult.value.data;
  return ok({ kind: "success", pushToken: pushTokenString, pushType: "expo" });
}

export async function registerPushTokenWithServer(pushToken: string): Promise<Result<void, Error>> {
  const result = await registerPushToken({ push_token: pushToken });

  if (result.isErr()) {
    return err(result.error);
  }

  return ok(undefined);
}

export async function registerUnifiedPushTokenWithServer(
  pushEndpoint: string,
): Promise<Result<void, Error>> {
  // Reuse same API payload; server should treat endpoint as token for UnifiedPush
  return registerPushTokenWithServer(pushEndpoint);
}
