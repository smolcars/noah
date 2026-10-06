/**
 * Pure scheduling and policy logic for recurring payments.
 *
 * Kept free of React Native / wallet imports so it can be unit tested with
 * `bun test` and reasoned about in isolation. All times are ms since epoch;
 * calendar math uses the device's local time zone so "the 1st of every month
 * at 09:00" stays at 09:00 across DST changes.
 */
import type {
  NewRecurringPaymentInput,
  RecurringDestinationType,
  RecurringInterval,
  RecurringPayment,
  RecurringPaymentRun,
} from "~/types/recurringPayment";

export const MAX_RECURRING_PAYMENTS = 50;
export const MAX_RECURRING_RUN_HISTORY = 24;
export const MAX_CUSTOM_INTERVAL_DAYS = 365;
/** An in-flight marker older than this means the app died mid-payment. */
export const STALE_IN_FLIGHT_MS = 10 * 60 * 1000;
export const REMINDER_LEAD_MS = 24 * 60 * 60 * 1000;
export const OVERDUE_NAG_DELAY_MS = 2 * 60 * 60 * 1000;
export const RETRY_BASE_DELAY_MS = 15 * 60 * 1000;
export const RETRY_MAX_DELAY_MS = 6 * 60 * 60 * 1000;

/** Delay before the next automatic attempt after `failures` retryable failures in a row. */
export const retryDelayMs = (failures: number): number =>
  Math.min(RETRY_BASE_DELAY_MS * 2 ** Math.max(0, Math.min(failures, 16) - 1), RETRY_MAX_DELAY_MS);

const RECURRING_DESTINATION_TYPES: readonly RecurringDestinationType[] = ["ark", "lnurl", "offer"];

export const isRecurringDestinationType = (value: unknown): value is RecurringDestinationType =>
  typeof value === "string" && (RECURRING_DESTINATION_TYPES as readonly string[]).includes(value);

const daysInMonth = (year: number, monthIndex: number): number =>
  new Date(year, monthIndex + 1, 0).getDate();

/** Returns the due time of occurrence `index` (0 = start). */
export const occurrenceAt = (
  startAt: number,
  interval: RecurringInterval,
  index: number,
): number => {
  const start = new Date(startAt);
  const steps = index * interval.every;

  if (interval.unit === "month") {
    const totalMonths = start.getMonth() + steps;
    const year = start.getFullYear() + Math.floor(totalMonths / 12);
    const month = ((totalMonths % 12) + 12) % 12;
    // Clamp e.g. Jan 31 -> Feb 28/29 without drifting later months.
    const day = Math.min(start.getDate(), daysInMonth(year, month));
    return new Date(
      year,
      month,
      day,
      start.getHours(),
      start.getMinutes(),
      start.getSeconds(),
      start.getMilliseconds(),
    ).getTime();
  }

  const days = interval.unit === "week" ? steps * 7 : steps;
  const next = new Date(startAt);
  next.setDate(next.getDate() + days);
  return next.getTime();
};

const isWithinLimits = (
  schedule: Pick<RecurringPayment, "startAt" | "interval" | "endAt" | "maxOccurrences">,
  index: number,
): boolean => {
  if (schedule.maxOccurrences !== null && index >= schedule.maxOccurrences) {
    return false;
  }
  if (
    schedule.endAt !== null &&
    occurrenceAt(schedule.startAt, schedule.interval, index) > schedule.endAt
  ) {
    return false;
  }
  return true;
};

/** Due time for `index`, or null if the schedule has ended by then. */
export const nextRunAtForIndex = (
  schedule: Pick<RecurringPayment, "startAt" | "interval" | "endAt" | "maxOccurrences">,
  index: number,
): number | null =>
  isWithinLimits(schedule, index) ? occurrenceAt(schedule.startAt, schedule.interval, index) : null;

export type RecurringInputError =
  | "label"
  | "amount"
  | "destination"
  | "interval"
  | "start"
  | "end"
  | "occurrences";

export const validateRecurringPaymentInput = (
  input: NewRecurringPaymentInput,
  now: number,
): RecurringInputError | null => {
  if (!input.label.trim() || input.label.length > 64) return "label";
  if (!Number.isSafeInteger(input.amountSat) || input.amountSat <= 0) return "amount";
  if (!input.destination.trim() || !isRecurringDestinationType(input.destinationType)) {
    return "destination";
  }
  const { unit, every } = input.interval;
  if (!Number.isInteger(every) || every < 1) return "interval";
  if (unit === "day" && every > MAX_CUSTOM_INTERVAL_DAYS) return "interval";
  if ((unit === "week" || unit === "month") && every > 12) return "interval";
  // Allow a small clock skew so "now" is a valid start.
  if (!Number.isFinite(input.startAt) || input.startAt < now - 5 * 60 * 1000) return "start";
  if (input.endAt !== null && (!Number.isFinite(input.endAt) || input.endAt < input.startAt)) {
    return "end";
  }
  if (
    input.maxOccurrences !== null &&
    (!Number.isInteger(input.maxOccurrences) || input.maxOccurrences < 1)
  ) {
    return "occurrences";
  }
  return null;
};

export const createRecurringPayment = (
  id: string,
  input: NewRecurringPaymentInput,
  now: number,
): RecurringPayment => ({
  id,
  label: input.label.trim(),
  destination: input.destination.trim(),
  destinationType: input.destinationType,
  amountSat: input.amountSat,
  comment: input.comment,
  interval: input.interval,
  startAt: input.startAt,
  endAt: input.endAt,
  maxOccurrences: input.maxOccurrences,
  status: "active",
  nextOccurrenceIndex: 0,
  nextRunAt: input.startAt,
  occurrencesPaid: 0,
  consecutiveFailures: 0,
  lastError: null,
  inFlight: null,
  runs: [],
  createdAt: now,
  updatedAt: now,
});

export type RecurringExecutionPlan =
  | { kind: "idle" }
  | { kind: "stale_in_flight" }
  | { kind: "complete" }
  | {
      kind: "pay";
      occurrenceIndex: number;
      scheduledFor: number;
      /** Older missed occurrences that will be skipped (never paid in bulk). */
      skippedIndices: number[];
    };

/**
 * Decides what the executor may do for a schedule right now.
 *
 * Policy guarantees:
 * - Only `active` schedules are ever paid.
 * - At most one payment per execution, for the most recent due occurrence.
 *   Occurrences missed while the phone was off are skipped, never batched,
 *   so a long outage can't drain the wallet.
 * - A payment that was started but never recorded (app killed mid-send) is
 *   never retried automatically.
 */
export const planRecurringExecution = (
  schedule: RecurringPayment,
  now: number,
  options: { ignoreRetryBackoff?: boolean } = {},
): RecurringExecutionPlan => {
  if (schedule.status !== "active") return { kind: "idle" };

  if (schedule.inFlight) {
    return now - schedule.inFlight.startedAt > STALE_IN_FLIGHT_MS
      ? { kind: "stale_in_flight" }
      : { kind: "idle" };
  }

  const first = nextRunAtForIndex(schedule, schedule.nextOccurrenceIndex);
  if (first === null) return { kind: "complete" };
  if (first > now) return { kind: "idle" };
  if (!options.ignoreRetryBackoff && schedule.retryNotBefore && schedule.retryNotBefore > now) {
    return { kind: "idle" };
  }

  let latest = schedule.nextOccurrenceIndex;
  // Walk forward to the most recent occurrence that is due and within limits.
  // Bounded: daily schedules over 10 years are < 4k iterations.
  for (let i = 0; i < 5000; i += 1) {
    const candidate = latest + 1;
    const at = nextRunAtForIndex(schedule, candidate);
    if (at === null || at > now) break;
    latest = candidate;
  }

  const skippedIndices: number[] = [];
  for (let i = schedule.nextOccurrenceIndex; i < latest; i += 1) skippedIndices.push(i);

  return {
    kind: "pay",
    occurrenceIndex: latest,
    scheduledFor: occurrenceAt(schedule.startAt, schedule.interval, latest),
    skippedIndices,
  };
};

const appendRuns = (
  runs: RecurringPaymentRun[],
  additions: RecurringPaymentRun[],
): RecurringPaymentRun[] => [...additions.reverse(), ...runs].slice(0, MAX_RECURRING_RUN_HISTORY);

const withNextOccurrence = (schedule: RecurringPayment, nextIndex: number): RecurringPayment => {
  const nextRunAt = nextRunAtForIndex(schedule, nextIndex);
  return {
    ...schedule,
    nextOccurrenceIndex: nextIndex,
    nextRunAt,
    status: nextRunAt === null ? "completed" : schedule.status,
  };
};

export const markInFlight = (
  schedule: RecurringPayment,
  occurrenceIndex: number,
  now: number,
): RecurringPayment => ({
  ...schedule,
  inFlight: { occurrenceIndex, startedAt: now },
  updatedAt: now,
});

export const applySuccessfulRun = (
  schedule: RecurringPayment,
  plan: Extract<RecurringExecutionPlan, { kind: "pay" }>,
  now: number,
): RecurringPayment => {
  const skipped: RecurringPaymentRun[] = plan.skippedIndices.map((index) => ({
    occurrenceIndex: index,
    scheduledFor: occurrenceAt(schedule.startAt, schedule.interval, index),
    attemptedAt: now,
    status: "skipped",
    amountSat: schedule.amountSat,
  }));
  const paid: RecurringPaymentRun = {
    occurrenceIndex: plan.occurrenceIndex,
    scheduledFor: plan.scheduledFor,
    attemptedAt: now,
    status: "success",
    amountSat: schedule.amountSat,
  };

  return withNextOccurrence(
    {
      ...schedule,
      inFlight: null,
      occurrencesPaid: schedule.occurrencesPaid + 1,
      consecutiveFailures: 0,
      lastError: null,
      retryNotBefore: null,
      runs: appendRuns(schedule.runs, [...skipped, paid]),
      updatedAt: now,
    },
    plan.occurrenceIndex + 1,
  );
};

/**
 * Records a failure.
 *
 * `retryable` failures happened before any funds could have moved (e.g. the
 * balance pre-check). They keep the occurrence due so the next wake-up retries.
 * Non-retryable failures are ambiguous (the payment may or may not have gone
 * through), so the schedule is stopped until the user reviews it.
 */
export const applyFailedRun = (
  schedule: RecurringPayment,
  plan: Extract<RecurringExecutionPlan, { kind: "pay" }>,
  error: string,
  retryable: boolean,
  now: number,
): RecurringPayment => ({
  ...schedule,
  inFlight: null,
  status: retryable ? schedule.status : "needs_attention",
  consecutiveFailures: schedule.consecutiveFailures + 1,
  lastError: error,
  retryNotBefore: retryable ? now + retryDelayMs(schedule.consecutiveFailures + 1) : null,
  runs: appendRuns(schedule.runs, [
    {
      occurrenceIndex: plan.occurrenceIndex,
      scheduledFor: plan.scheduledFor,
      attemptedAt: now,
      status: "failed",
      amountSat: schedule.amountSat,
      error,
    },
  ]),
  updatedAt: now,
});

export const applyStaleInFlight = (schedule: RecurringPayment, now: number): RecurringPayment => ({
  ...schedule,
  inFlight: null,
  status: "needs_attention",
  lastError:
    "Noah was interrupted while sending this payment. Check your transaction history before resuming.",
  updatedAt: now,
});

/**
 * Re-checks a schedule right before funds move. The executor plans a payment,
 * then runs slow checks (wallet, balance, recipient). If the user paused,
 * resumed, cancelled or deleted the schedule in the meantime, the latest stored
 * copy no longer plans the same occurrence and nothing may be sent.
 */
export const isStillDueForPayment = (
  latest: RecurringPayment | undefined,
  plan: Extract<RecurringExecutionPlan, { kind: "pay" }>,
  now: number,
): latest is RecurringPayment => {
  if (!latest) return false;
  // The original plan already respected (or deliberately ignored) the backoff.
  const current = planRecurringExecution(latest, now, { ignoreRetryBackoff: true });
  return current.kind === "pay" && current.occurrenceIndex === plan.occurrenceIndex;
};

/**
 * Combines the result of an execution with the latest stored copy of the
 * schedule, so a pause or cancel made while a payment was in progress wins.
 *
 * - Removed (cancelled or wallet deleted): returns null, the schedule must not
 *   be re-created.
 * - No longer active (e.g. paused): the run is recorded, but the result can't
 *   turn the schedule back on. A stronger stop from the result
 *   (`needs_attention` / `completed`) is kept.
 */
export const mergeExecutionResult = (
  latest: RecurringPayment | undefined,
  result: RecurringPayment,
): RecurringPayment | null => {
  if (!latest) return null;
  if (latest.status === "active") return result;
  return { ...result, status: result.status === "active" ? latest.status : result.status };
};

/**
 * Resumes a paused or needs-attention schedule. Occurrences that passed while
 * it was not active are skipped so resuming never triggers a burst of payments.
 */
export const resumeRecurringPayment = (
  schedule: RecurringPayment,
  now: number,
): RecurringPayment => {
  let index = schedule.nextOccurrenceIndex;
  const current = nextRunAtForIndex(schedule, index);
  if (current !== null && current <= now) {
    // Ignore the retry backoff: resuming must always skip to the next future
    // occurrence, even if a recent failure set `retryNotBefore`.
    const plan = planRecurringExecution({ ...schedule, status: "active", inFlight: null }, now, {
      ignoreRetryBackoff: true,
    });
    if (plan.kind === "pay") {
      index = plan.occurrenceIndex + 1;
    }
  }
  return withNextOccurrence(
    {
      ...schedule,
      status: "active",
      inFlight: null,
      lastError: null,
      retryNotBefore: null,
      consecutiveFailures: 0,
      updatedAt: now,
    },
    index,
  );
};

export const describeInterval = (interval: RecurringInterval): string => {
  const { unit, every } = interval;
  if (every === 1) {
    return unit === "day" ? "Daily" : unit === "week" ? "Weekly" : "Monthly";
  }
  return `Every ${every} ${unit}s`;
};

/** Schedules the server should wake the device for. */
export const serverScheduleEntries = (
  schedules: readonly RecurringPayment[],
): { schedule_id: string; next_run_at: number }[] =>
  schedules
    .filter((s) => s.status === "active" && s.nextRunAt !== null)
    .map((s) => ({ schedule_id: s.id, next_run_at: Math.floor((s.nextRunAt as number) / 1000) }));
