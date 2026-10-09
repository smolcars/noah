import { sourceText, type Translate } from "~/lib/i18n";
/**
 * Recurring payment executor.
 *
 * Trust model
 * -----------
 * - The spending policy (recipient, fixed amount, interval, limits) is stored
 *   only on the device and approved by the user when the schedule is created.
 * - Payments are always signed and sent by the local wallet. Nothing is
 *   pre-signed and no keys or policy details leave the device.
 * - The server only learns an opaque schedule id and the next due time, which
 *   it uses to send a silent `recurring_payment_due` push. A malicious or
 *   buggy server can at worst wake the device at the wrong time: the executor
 *   re-checks every schedule against its local policy before paying, so funds
 *   can only ever go to the approved recipient, for the approved amount, at
 *   most once per occurrence.
 *
 * Execution happens:
 * - when a `recurring_payment_due` push wakes the app in the background,
 * - on Android, from a periodic WorkManager job (`recurringBackgroundTask.ts`)
 *   that works without the server,
 * - whenever the app comes to the foreground,
 * and local notifications remind the user before a payment and nag them if a
 * payment is overdue (e.g. push was not delivered or the OS killed the task).
 */
import { Platform } from "react-native";
import * as Notifications from "expo-notifications";
import uuid from "react-native-uuid";
import { err, ok, Result, ResultAsync } from "neverthrow";

import logger from "~/lib/log";
import { syncRecurringPayments as syncRecurringPaymentsApi } from "~/lib/api";
import { fetchOffchainBalance, loadWalletIfNeeded } from "~/lib/walletApi";
import {
  estimateArkoorPaymentFee,
  estimateLightningSendFee,
  payLightningOffer,
  sendArkoorPayment,
} from "~/lib/paymentsApi";
import {
  readLightningPayment,
  resolveLightningAddressPaymentRoute,
  sendLightningAddressPayment,
  type LightningAddressPaymentRoute,
} from "~/hooks/usePayments";
import { formatBitcoinAmount } from "~/lib/bitcoinAmount";
import { isRecurringPaymentsSupported } from "~/constants";
import { createSerializedSync } from "~/lib/serializedSync";
import { normalizeLightningAddress, parseDestination } from "~/lib/sendUtils";
import { queryClient } from "~/queryClient";
import { useProfileStore } from "~/store/profileStore";
import { getRecurringPayments, useRecurringPaymentStore } from "~/store/recurringPaymentStore";
import {
  applyFailedRun,
  applyStaleInFlight,
  applySuccessfulRun,
  createRecurringPayment,
  isStillDueForPayment,
  isStillInFlightFor,
  markInFlight,
  MAX_RECURRING_PAYMENTS,
  mergeExecutionResult,
  OVERDUE_NAG_DELAY_MS,
  planRecurringExecution,
  REMINDER_LEAD_MS,
  resumeRecurringPayment,
  serverScheduleEntries,
  validateRecurringPaymentInput,
  type RecurringExecutionPlan,
  type RecurringInputError,
} from "~/lib/recurringSchedule";
import type {
  NewRecurringPaymentInput,
  RecurringDestinationType,
  RecurringPayment,
} from "~/types/recurringPayment";

const log = logger("recurringPayments");

const RECURRING_CHANNEL_ID = "recurring-payments";

export type RecurringExecutionTrigger = "push" | "background" | "foreground" | "manual";

export type RecurringExecutionSummary = {
  paid: number;
  failed: number;
  needsAttention: number;
};

class RetryableRecurringPaymentError extends Error {}

/**
 * Thrown right before a transfer would be submitted when the user paused,
 * cancelled or changed the schedule while the payment was being prepared.
 * No funds have moved when this is thrown.
 */
class RecurringPaymentStoppedError extends Error {}

const errorMessage = (error: unknown): string =>
  error instanceof Error ? error.message : String(error);

const formatAmount = (sats: number): string =>
  formatBitcoinAmount(sats, useProfileStore.getState().bitcoinAmountUnit);

// ---------------------------------------------------------------------------
// Local notifications
// ---------------------------------------------------------------------------

async function ensureRecurringChannel() {
  if (Platform.OS !== "android") return;
  await Notifications.setNotificationChannelAsync(RECURRING_CHANNEL_ID, {
    name: "Recurring payments",
    importance: Notifications.AndroidImportance.HIGH,
  });
}

const reminderId = (id: string) => `recurring-${id}-reminder`;
const overdueId = (id: string) => `recurring-${id}-overdue`;

async function cancelScheduledNotifications(id: string) {
  await Promise.allSettled([
    Notifications.cancelScheduledNotificationAsync(reminderId(id)),
    Notifications.cancelScheduledNotificationAsync(overdueId(id)),
  ]);
}

async function scheduleAt(identifier: string, at: number, title: string, body: string) {
  if (at <= Date.now()) return;
  await Notifications.scheduleNotificationAsync({
    identifier,
    content: {
      title,
      body,
      sound: "default",
      data: { notification_type: "recurring_payment_reminder" },
    },
    trigger: {
      type: Notifications.SchedulableTriggerInputTypes.DATE,
      date: new Date(at),
      ...(Platform.OS === "android" ? { channelId: RECURRING_CHANNEL_ID } : {}),
    },
  });
}

/** Re-creates the "upcoming" reminder and the "overdue" nag for a schedule. */
async function refreshScheduledNotifications(schedule: RecurringPayment) {
  const result = await ResultAsync.fromPromise(
    (async () => {
      await cancelScheduledNotifications(schedule.id);
      if (schedule.status !== "active" || schedule.nextRunAt === null) return;

      await ensureRecurringChannel();
      const amount = formatAmount(schedule.amountSat);
      await scheduleAt(
        reminderId(schedule.id),
        schedule.nextRunAt - REMINDER_LEAD_MS,
        "Upcoming recurring payment",
        `${amount} to ${schedule.label} will be sent tomorrow.`,
      );
      // Fires only if the payment hasn't executed (execution re-schedules it).
      await scheduleAt(
        overdueId(schedule.id),
        schedule.nextRunAt + OVERDUE_NAG_DELAY_MS,
        "Recurring payment waiting",
        `Open Noah to send ${amount} to ${schedule.label}.`,
      );
    })(),
    (e) => new Error(`Failed to schedule recurring payment notifications: ${errorMessage(e)}`),
  );
  if (result.isErr()) {
    log.w(result.error.message, [schedule.id]);
  }
}

async function notifyNow(title: string, body: string) {
  const result = await ResultAsync.fromPromise(
    (async () => {
      await ensureRecurringChannel();
      await Notifications.scheduleNotificationAsync({
        content: {
          title,
          body,
          sound: "default",
          data: { notification_type: "recurring_payment_result" },
        },
        trigger: Platform.OS === "android" ? { channelId: RECURRING_CHANNEL_ID } : null,
      });
    })(),
    (e) => e as Error,
  );
  if (result.isErr()) {
    log.w("Failed to show recurring payment notification", [result.error]);
  }
}

// ---------------------------------------------------------------------------
// Server wake-up schedule
// ---------------------------------------------------------------------------

/**
 * Pushes the opaque (id, due time) list of active schedules to the server.
 *
 * Each request replaces the server's whole list, so requests are sent one at a
 * time. If the local schedules change while a request is running, the latest
 * list is sent again afterwards, and `serverSyncPending` is only cleared once
 * the server has that latest list.
 */
export const syncRecurringPaymentsWithServer = createSerializedSync({
  // Users without wake-up support get an empty list so the server never pushes.
  getPayload: () =>
    isRecurringPaymentsSupported() ? serverScheduleEntries(getRecurringPayments()) : [],
  send: async (schedules) => {
    const result = await syncRecurringPaymentsApi({ schedules });
    if (result.isErr()) {
      log.w("Failed to sync recurring payment schedule with server", [result.error]);
      return err(result.error);
    }
    return ok(undefined);
  },
  onSettled: (pending) => useRecurringPaymentStore.getState().setServerSyncPending(pending),
});

async function persist(schedule: RecurringPayment) {
  useRecurringPaymentStore.getState().upsertSchedule(schedule);
  await refreshScheduledNotifications(schedule);
}

/**
 * Saves the outcome of an execution without undoing a pause or cancel the user
 * made while it was running. Returns the saved schedule, or null if the
 * schedule was removed in the meantime (it is not re-created).
 */
async function persistExecutionResult(result: RecurringPayment): Promise<RecurringPayment | null> {
  const merged = mergeExecutionResult(getSchedule(result.id), result);
  if (!merged) {
    await cancelScheduledNotifications(result.id);
    return null;
  }
  await persist(merged);
  return merged;
}

// ---------------------------------------------------------------------------
// Destinations
// ---------------------------------------------------------------------------

export type RecurringDestination = {
  destination: string;
  destinationType: RecurringDestinationType;
};

/**
 * Accepts only reusable destinations: Ark addresses, Lightning addresses and
 * BOLT12 offers (the pull/subscription-style rail). BOLT11 invoices are single
 * use and on-chain sends carry variable miner fees, so they are rejected.
 */
export const resolveRecurringDestination = (
  raw: string,
  gt: Translate = sourceText,
): Result<RecurringDestination, string> => {
  const trimmed = raw.trim();
  if (!trimmed) return err(gt("Enter a recipient"));

  const parsed = parseDestination(trimmed, gt);
  if (parsed.error) return err(parsed.error);

  switch (parsed.destinationType) {
    case "ark":
      return ok({ destination: trimmed, destinationType: "ark" });
    case "lnurl":
      return ok({
        destination: normalizeLightningAddress(trimmed.replace(/^lightning:/i, "")),
        destinationType: "lnurl",
      });
    case "offer":
      return ok({ destination: trimmed.replace(/^lightning:/i, ""), destinationType: "offer" });
    case "bip321":
      if (parsed.bip321?.arkAddress) {
        return ok({ destination: parsed.bip321.arkAddress, destinationType: "ark" });
      }
      if (parsed.bip321?.offer) {
        return ok({ destination: parsed.bip321.offer, destinationType: "offer" });
      }
      return err(gt("This payment request has no reusable Ark address or Lightning offer"));
    case "lightning":
      return err(gt("Lightning invoices can only be paid once. Use a Lightning address or offer"));
    case "onchain":
      return err(gt("On-chain addresses are not supported for recurring payments yet"));
    default:
      return err(gt("Unsupported recipient"));
  }
};

// ---------------------------------------------------------------------------
// User actions
// ---------------------------------------------------------------------------

export type CreateRecurringPaymentError =
  | { kind: "invalid"; field: RecurringInputError }
  | { kind: "limit" }
  | { kind: "unsupported" };

export async function createRecurringPaymentSchedule(
  input: NewRecurringPaymentInput,
): Promise<Result<RecurringPayment, CreateRecurringPaymentError>> {
  if (!isRecurringPaymentsSupported()) {
    return err({ kind: "unsupported" });
  }
  const now = Date.now();
  const invalidField = validateRecurringPaymentInput(input, now);
  if (invalidField) {
    return err({ kind: "invalid", field: invalidField });
  }
  if (getRecurringPayments().length >= MAX_RECURRING_PAYMENTS) {
    return err({ kind: "limit" });
  }

  const schedule = createRecurringPayment(uuid.v4().toString(), input, now);
  await persist(schedule);
  await syncRecurringPaymentsWithServer();
  log.i("Recurring payment created", [schedule.id, schedule.destinationType]);
  return ok(schedule);
}

const getSchedule = (id: string): RecurringPayment | undefined =>
  useRecurringPaymentStore.getState().schedules[id];

export async function pauseRecurringPayment(id: string): Promise<void> {
  const schedule = getSchedule(id);
  if (!schedule || schedule.status === "completed") return;
  await persist({ ...schedule, status: "paused", updatedAt: Date.now() });
  await syncRecurringPaymentsWithServer();
}

export async function resumeRecurringPaymentById(id: string): Promise<void> {
  const schedule = getSchedule(id);
  if (!schedule || schedule.status === "completed") return;
  await persist(resumeRecurringPayment(schedule, Date.now()));
  await syncRecurringPaymentsWithServer();
}

export async function cancelRecurringPayment(id: string): Promise<void> {
  await cancelScheduledNotifications(id);
  useRecurringPaymentStore.getState().removeSchedule(id);
  await syncRecurringPaymentsWithServer();
}

/**
 * Removes every local schedule and its reminders. Called when the wallet is
 * deleted so old schedules can never spend from a new or restored wallet.
 * The server records are removed by deregistration.
 */
export async function clearAllRecurringPayments(): Promise<void> {
  const ids = Object.keys(useRecurringPaymentStore.getState().schedules);
  useRecurringPaymentStore.getState().reset();
  await Promise.allSettled(ids.map((id) => cancelScheduledNotifications(id)));
}

// ---------------------------------------------------------------------------
// Execution
// ---------------------------------------------------------------------------

async function ensureSpendable(amountSat: number, feeSat: number) {
  const balance = await fetchOffchainBalance();
  if (balance.isErr()) {
    throw new RetryableRecurringPaymentError(`Could not read balance: ${balance.error.message}`);
  }
  if (balance.value.spendable < amountSat + feeSat) {
    throw new RetryableRecurringPaymentError(
      `Insufficient balance: ${formatAmount(amountSat + feeSat)} needed, ${formatAmount(
        balance.value.spendable,
      )} spendable`,
    );
  }
}

/**
 * Everything that can fail *before* funds could move. Throws
 * `RetryableRecurringPaymentError` so the occurrence stays due.
 */
async function preflight(
  schedule: RecurringPayment,
): Promise<{ lightningAddressRoute?: LightningAddressPaymentRoute }> {
  const loadResult = await loadWalletIfNeeded();
  if (loadResult.isErr()) {
    throw new RetryableRecurringPaymentError(`Wallet unavailable: ${loadResult.error.message}`);
  }

  if (schedule.destinationType === "lnurl") {
    let route: LightningAddressPaymentRoute;
    try {
      route = await resolveLightningAddressPaymentRoute(schedule.destination);
    } catch (e) {
      throw new RetryableRecurringPaymentError(
        `Could not reach ${schedule.destination}: ${errorMessage(e)}`,
      );
    }
    const amountMsat = schedule.amountSat * 1000;
    if (amountMsat < route.minSendableMsat || amountMsat > route.maxSendableMsat) {
      throw new RetryableRecurringPaymentError(
        "The recipient no longer accepts this amount. Update or cancel the recurring payment.",
      );
    }
    const fee =
      route.method === "ark"
        ? await estimateArkoorPaymentFee(schedule.amountSat)
        : await estimateLightningSendFee(schedule.amountSat);
    await ensureSpendable(schedule.amountSat, fee.isOk() ? fee.value.fee_sat : 0);
    return { lightningAddressRoute: route };
  }

  const fee =
    schedule.destinationType === "ark"
      ? await estimateArkoorPaymentFee(schedule.amountSat)
      : await estimateLightningSendFee(schedule.amountSat);
  await ensureSpendable(schedule.amountSat, fee.isOk() ? fee.value.fee_sat : 0);
  return {};
}

/**
 * The only place a recurring payment moves funds. Uses the stored policy only.
 *
 * `beforeSubmit` runs after every asynchronous step (history snapshot, address
 * validation, ...) and immediately before the native send, with no `await` in
 * between. It throws `RecurringPaymentStoppedError` if the user stopped the
 * schedule meanwhile, so nothing is submitted.
 */
async function sendPayment(
  schedule: RecurringPayment,
  lightningAddressRoute: LightningAddressPaymentRoute | undefined,
  beforeSubmit: () => void,
): Promise<void> {
  switch (schedule.destinationType) {
    case "ark": {
      const result = await sendArkoorPayment(
        schedule.destination,
        schedule.amountSat,
        beforeSubmit,
      );
      if (result.isErr()) throw result.error;
      return;
    }
    case "offer": {
      beforeSubmit();
      await readLightningPayment(payLightningOffer(schedule.destination, schedule.amountSat));
      return;
    }
    case "lnurl": {
      if (!lightningAddressRoute) {
        throw new Error("Missing lightning address route");
      }
      await sendLightningAddressPayment(
        lightningAddressRoute,
        schedule.destination,
        schedule.amountSat,
        schedule.comment || null,
        beforeSubmit,
      );
      return;
    }
    default: {
      const _exhaustive: never = schedule.destinationType;
      throw new Error(`Unsupported recurring destination: ${String(_exhaustive)}`);
    }
  }
}

/**
 * Removes our in-flight marker after a payment was stopped before submitting,
 * so a later resume isn't mistaken for an interrupted payment. Keeps the
 * user's pause; a removed schedule is not re-created.
 */
async function clearInFlight(id: string, occurrenceIndex: number) {
  const current = getSchedule(id);
  if (!current) {
    await cancelScheduledNotifications(id);
    return;
  }
  if (current.inFlight?.occurrenceIndex !== occurrenceIndex) return;
  await persist({ ...current, inFlight: null, updatedAt: Date.now() });
}

/** Returns the updated schedule, or null if nothing was attempted. */
async function executeOne(
  schedule: RecurringPayment,
  plan: Extract<RecurringExecutionPlan, { kind: "pay" }>,
): Promise<RecurringPayment | null> {
  let lightningAddressRoute: LightningAddressPaymentRoute | undefined;
  try {
    ({ lightningAddressRoute } = await preflight(schedule));
  } catch (e) {
    const retryable = e instanceof RetryableRecurringPaymentError;
    return applyFailedRun(schedule, plan, errorMessage(e), retryable, Date.now());
  }

  // The checks above take time. If the user paused, resumed, cancelled or
  // deleted the schedule meanwhile, the stored copy no longer plans this
  // occurrence: send nothing and leave it as the user set it.
  const latest = getSchedule(schedule.id);
  if (!isStillDueForPayment(latest, plan, Date.now())) {
    log.i("Recurring payment changed during checks, not sending", [schedule.id]);
    return null;
  }

  // Persist the in-flight marker *before* sending so a crash mid-payment can
  // never lead to an automatic second attempt.
  const inFlight = markInFlight(latest, plan.occurrenceIndex, Date.now());
  useRecurringPaymentStore.getState().upsertSchedule(inFlight);

  // Sending still awaits (history snapshot, address validation) before the
  // transfer is submitted, so check once more right before submitting.
  const beforeSubmit = () => {
    if (!isStillInFlightFor(getSchedule(schedule.id), plan.occurrenceIndex)) {
      throw new RecurringPaymentStoppedError("Recurring payment was stopped before sending");
    }
  };

  try {
    await sendPayment(inFlight, lightningAddressRoute, beforeSubmit);
  } catch (e) {
    if (e instanceof RecurringPaymentStoppedError) {
      log.i("Recurring payment stopped before submitting, not sending", [schedule.id]);
      await clearInFlight(schedule.id, plan.occurrenceIndex);
      return null;
    }
    // Ambiguous: the payment may have partially gone through.
    return applyFailedRun(inFlight, plan, errorMessage(e), false, Date.now());
  }

  return applySuccessfulRun(inFlight, plan, Date.now());
}

let executionInFlight: Promise<RecurringExecutionSummary> | null = null;

async function runDueRecurringPayments(
  trigger: RecurringExecutionTrigger,
): Promise<RecurringExecutionSummary> {
  const summary: RecurringExecutionSummary = { paid: 0, failed: 0, needsAttention: 0 };
  if (!isRecurringPaymentsSupported()) return summary;

  const scheduleIds = getRecurringPayments().map((s) => s.id);
  let changed = false;

  for (const id of scheduleIds) {
    // Re-read every time: earlier iterations await, and the user may have
    // paused or cancelled this schedule in the meantime.
    const schedule = getSchedule(id);
    if (!schedule) continue;
    // A manual run retries immediately; automatic runs respect the retry backoff.
    const plan = planRecurringExecution(schedule, Date.now(), {
      ignoreRetryBackoff: trigger === "manual",
    });

    if (plan.kind === "idle") continue;

    if (plan.kind === "complete") {
      await persist({ ...schedule, status: "completed", nextRunAt: null, updatedAt: Date.now() });
      changed = true;
      continue;
    }

    if (plan.kind === "stale_in_flight") {
      const updated = applyStaleInFlight(schedule, Date.now());
      await persist(updated);
      changed = true;
      summary.needsAttention += 1;
      await notifyNow(
        "Recurring payment needs your attention",
        `A payment to ${schedule.label} was interrupted. Check your history, then resume it.`,
      );
      continue;
    }

    log.i("Executing recurring payment", [schedule.id, trigger, plan.occurrenceIndex]);
    const result = await executeOne(schedule, plan);
    if (!result) continue;
    // `updated` is null when the schedule was cancelled or deleted meanwhile.
    const updated = await persistExecutionResult(result);
    changed = true;

    const amount = formatAmount(schedule.amountSat);
    const lastRun = result.runs[0];
    if (lastRun?.status === "success" && lastRun.occurrenceIndex === plan.occurrenceIndex) {
      // Always report money that moved, even if the schedule was stopped meanwhile.
      summary.paid += 1;
      await notifyNow("Recurring payment sent", `${amount} sent to ${schedule.label}.`);
    } else if (result.status === "needs_attention") {
      summary.needsAttention += 1;
      await notifyNow(
        "Recurring payment needs your attention",
        `Sending ${amount} to ${schedule.label} failed: ${result.lastError ?? "unknown error"}. It has been paused.`,
      );
    } else {
      summary.failed += 1;
      // Only notify on the first failure (or a manual run) to avoid a
      // notification on every automatic retry, and never for a schedule the
      // user stopped meanwhile.
      if (updated?.status !== "active") continue;
      if (result.consecutiveFailures > 1 && trigger !== "manual") continue;
      await notifyNow(
        "Recurring payment will retry",
        `${amount} to ${schedule.label} could not be sent yet: ${result.lastError ?? "unknown error"}`,
      );
    }
  }

  if (changed || useRecurringPaymentStore.getState().serverSyncPending) {
    await syncRecurringPaymentsWithServer();
  }

  if (summary.paid > 0) {
    await queryClient.invalidateQueries({ queryKey: ["balance"] });
    await queryClient.invalidateQueries({ queryKey: ["transactions"] });
  }

  return summary;
}

/**
 * Executes every due recurring payment at most once. Safe to call from
 * multiple entry points; concurrent calls share the same run.
 */
export function executeDueRecurringPayments(
  trigger: RecurringExecutionTrigger,
): Promise<RecurringExecutionSummary> {
  if (!executionInFlight) {
    executionInFlight = runDueRecurringPayments(trigger).finally(() => {
      executionInFlight = null;
    });
  }
  return executionInFlight;
}

export const hasRecurringPayments = (): boolean => getRecurringPayments().length > 0;
