/**
 * Recurring (scheduled) payments.
 *
 * A recurring payment is a spending policy the user approves once: a fixed
 * amount, a fixed recipient and a fixed interval. The policy lives only on the
 * device and is executed by the wallet itself. The Noah server only receives an
 * opaque schedule id and the next due time so it can wake the device with a
 * silent push; it never learns the amount or recipient and cannot move funds.
 */

/** Destinations that can be paid repeatedly without a new request each time. */
export type RecurringDestinationType = "ark" | "lnurl" | "offer";

export type RecurringIntervalUnit = "day" | "week" | "month";

export type RecurringInterval = {
  unit: RecurringIntervalUnit;
  /** Pay every `every` units, e.g. `{ unit: "day", every: 14 }` is bi-weekly. */
  every: number;
};

export type RecurringPaymentStatus =
  /** Scheduled and will execute automatically when due. */
  | "active"
  /** Paused by the user. */
  | "paused"
  /** Stopped automatically after an ambiguous failure; the user must review. */
  | "needs_attention"
  /** End date or number of occurrences reached. */
  | "completed";

export type RecurringPaymentRunStatus = "success" | "failed" | "skipped";

export type RecurringPaymentRun = {
  occurrenceIndex: number;
  scheduledFor: number;
  attemptedAt: number;
  status: RecurringPaymentRunStatus;
  amountSat: number;
  error?: string;
};

export type RecurringPaymentInFlight = {
  occurrenceIndex: number;
  startedAt: number;
};

export type RecurringPayment = {
  id: string;
  label: string;
  destination: string;
  destinationType: RecurringDestinationType;
  /** Hard per-occurrence limit: automation never pays more than this. */
  amountSat: number;
  comment: string;
  interval: RecurringInterval;
  /** First occurrence (ms since epoch, local wall-clock time is preserved). */
  startAt: number;
  /** Optional last allowed occurrence time (inclusive, ms since epoch). */
  endAt: number | null;
  /** Optional total number of occurrences (including skipped ones). */
  maxOccurrences: number | null;
  status: RecurringPaymentStatus;
  /** Index of the next occurrence to pay (0 = `startAt`). */
  nextOccurrenceIndex: number;
  /** Due time of `nextOccurrenceIndex`, or null when completed. */
  nextRunAt: number | null;
  occurrencesPaid: number;
  consecutiveFailures: number;
  lastError: string | null;
  inFlight: RecurringPaymentInFlight | null;
  runs: RecurringPaymentRun[];
  createdAt: number;
  updatedAt: number;
};

export type NewRecurringPaymentInput = {
  label: string;
  destination: string;
  destinationType: RecurringDestinationType;
  amountSat: number;
  comment: string;
  interval: RecurringInterval;
  startAt: number;
  endAt: number | null;
  maxOccurrences: number | null;
};
