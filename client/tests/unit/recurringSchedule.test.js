import { describe, expect, test } from "bun:test";

import {
  applyFailedRun,
  applyStaleInFlight,
  applySuccessfulRun,
  createRecurringPayment,
  describeInterval,
  isStillDueForPayment,
  markInFlight,
  mergeExecutionResult,
  nextRunAtForIndex,
  occurrenceAt,
  planRecurringExecution,
  resumeRecurringPayment,
  RETRY_BASE_DELAY_MS,
  RETRY_MAX_DELAY_MS,
  retryDelayMs,
  serverScheduleEntries,
  STALE_IN_FLIGHT_MS,
  validateRecurringPaymentInput,
} from "../../src/lib/recurringSchedule";

const local = (y, m, d, h = 9, min = 0) => new Date(y, m - 1, d, h, min, 0, 0).getTime();

const baseInput = (overrides = {}) => ({
  label: "Rent",
  destination: "landlord@example.com",
  destinationType: "lnurl",
  amountSat: 50_000,
  comment: "",
  interval: { unit: "month", every: 1 },
  startAt: local(2026, 1, 31),
  endAt: null,
  maxOccurrences: null,
  ...overrides,
});

describe("recurring payment calendar math", () => {
  test("monthly schedules clamp to month end without drifting", () => {
    const start = local(2026, 1, 31);
    const interval = { unit: "month", every: 1 };
    expect(new Date(occurrenceAt(start, interval, 1)).getDate()).toBe(28);
    expect(new Date(occurrenceAt(start, interval, 2)).getDate()).toBe(31);
    expect(new Date(occurrenceAt(start, interval, 3)).getDate()).toBe(30);
    expect(new Date(occurrenceAt(start, interval, 12)).getFullYear()).toBe(2027);
    expect(new Date(occurrenceAt(start, interval, 2)).getHours()).toBe(9);
  });

  test("weekly and custom day intervals keep the wall-clock time", () => {
    const start = local(2026, 3, 20, 8, 30);
    const weekly = occurrenceAt(start, { unit: "week", every: 1 }, 2);
    expect(new Date(weekly).getDate()).toBe(3);
    expect(new Date(weekly).getMonth()).toBe(3);
    expect(new Date(weekly).getHours()).toBe(8);
    const custom = occurrenceAt(start, { unit: "day", every: 10 }, 3);
    expect(new Date(custom).getDate()).toBe(19);
    expect(new Date(custom).getMinutes()).toBe(30);
  });

  test("end date and occurrence count stop the schedule", () => {
    const byCount = createRecurringPayment("a", baseInput({ maxOccurrences: 2 }), 0);
    expect(nextRunAtForIndex(byCount, 1)).not.toBeNull();
    expect(nextRunAtForIndex(byCount, 2)).toBeNull();

    const byDate = createRecurringPayment("b", baseInput({ endAt: local(2026, 3, 31) }), 0);
    expect(nextRunAtForIndex(byDate, 2)).toBe(local(2026, 3, 31));
    expect(nextRunAtForIndex(byDate, 3)).toBeNull();
  });

  test("describes intervals for the UI", () => {
    expect(describeInterval({ unit: "week", every: 1 })).toBe("Weekly");
    expect(describeInterval({ unit: "month", every: 1 })).toBe("Monthly");
    expect(describeInterval({ unit: "day", every: 14 })).toBe("Every 14 days");
  });
});

describe("recurring payment input validation", () => {
  const now = local(2026, 1, 1);
  test("accepts a valid schedule", () => {
    expect(validateRecurringPaymentInput(baseInput(), now)).toBeNull();
  });

  test("rejects unsafe or incomplete schedules", () => {
    expect(validateRecurringPaymentInput(baseInput({ amountSat: 0 }), now)).toBe("amount");
    expect(validateRecurringPaymentInput(baseInput({ amountSat: 1.5 }), now)).toBe("amount");
    expect(validateRecurringPaymentInput(baseInput({ destinationType: "lightning" }), now)).toBe(
      "destination",
    );
    expect(validateRecurringPaymentInput(baseInput({ destinationType: "onchain" }), now)).toBe(
      "destination",
    );
    expect(
      validateRecurringPaymentInput(baseInput({ interval: { unit: "day", every: 0 } }), now),
    ).toBe("interval");
    expect(validateRecurringPaymentInput(baseInput({ startAt: local(2025, 1, 1) }), now)).toBe(
      "start",
    );
    expect(validateRecurringPaymentInput(baseInput({ endAt: local(2026, 1, 2) }), now)).toBe(
      "end",
    );
    expect(validateRecurringPaymentInput(baseInput({ maxOccurrences: 0 }), now)).toBe(
      "occurrences",
    );
    expect(validateRecurringPaymentInput(baseInput({ label: "  " }), now)).toBe("label");
  });
});

describe("recurring payment execution policy", () => {
  const schedule = createRecurringPayment("rent", baseInput(), local(2026, 1, 1));

  test("does nothing before the first due time or when not active", () => {
    expect(planRecurringExecution(schedule, local(2026, 1, 30))).toEqual({ kind: "idle" });
    expect(
      planRecurringExecution({ ...schedule, status: "paused" }, local(2026, 2, 5)),
    ).toEqual({ kind: "idle" });
  });

  test("pays exactly once for the latest due occurrence and skips missed ones", () => {
    const plan = planRecurringExecution(schedule, local(2026, 4, 5));
    expect(plan.kind).toBe("pay");
    expect(plan.occurrenceIndex).toBe(2);
    expect(plan.skippedIndices).toEqual([0, 1]);

    const next = applySuccessfulRun(schedule, plan, local(2026, 4, 5));
    expect(next.occurrencesPaid).toBe(1);
    expect(next.nextOccurrenceIndex).toBe(3);
    expect(next.nextRunAt).toBe(local(2026, 4, 30));
    expect(next.runs.map((r) => r.status)).toEqual(["success", "skipped", "skipped"]);
    expect(planRecurringExecution(next, local(2026, 4, 6))).toEqual({ kind: "idle" });
  });

  test("completes after the last allowed occurrence", () => {
    const once = createRecurringPayment("once", baseInput({ maxOccurrences: 1 }), 0);
    const plan = planRecurringExecution(once, local(2026, 2, 1));
    const next = applySuccessfulRun(once, plan, local(2026, 2, 1));
    expect(next.status).toBe("completed");
    expect(next.nextRunAt).toBeNull();
    expect(serverScheduleEntries([next])).toEqual([]);
  });

  test("retryable failures keep the occurrence due; ambiguous ones stop the schedule", () => {
    const at = local(2026, 2, 1);
    const plan = planRecurringExecution(schedule, at);

    const retry = applyFailedRun(schedule, plan, "Insufficient balance", true, at);
    expect(retry.status).toBe("active");
    expect(retry.nextOccurrenceIndex).toBe(0);
    expect(planRecurringExecution(retry, at + RETRY_BASE_DELAY_MS).kind).toBe("pay");

    const stopped = applyFailedRun(schedule, plan, "timeout", false, at);
    expect(stopped.status).toBe("needs_attention");
    expect(planRecurringExecution(stopped, at + 1000)).toEqual({ kind: "idle" });
  });

  test("automatic retries back off; a manual run retries immediately", () => {
    const at = local(2026, 2, 1);
    const plan = planRecurringExecution(schedule, at);
    const first = applyFailedRun(schedule, plan, "Insufficient balance", true, at);

    expect(planRecurringExecution(first, at + 60_000)).toEqual({ kind: "idle" });
    expect(planRecurringExecution(first, at + 60_000, { ignoreRetryBackoff: true }).kind).toBe(
      "pay",
    );

    const second = applyFailedRun(first, plan, "Insufficient balance", true, at);
    expect(second.retryNotBefore).toBe(at + 2 * RETRY_BASE_DELAY_MS);
    expect(retryDelayMs(1)).toBe(RETRY_BASE_DELAY_MS);
    expect(retryDelayMs(50)).toBe(RETRY_MAX_DELAY_MS);

    const paid = applySuccessfulRun(second, plan, at + 3 * RETRY_BASE_DELAY_MS);
    expect(paid.retryNotBefore).toBeNull();
    expect(paid.consecutiveFailures).toBe(0);
  });

  test("an interrupted payment is never retried automatically", () => {
    const at = local(2026, 2, 1);
    const inFlight = markInFlight(schedule, 0, at);
    expect(planRecurringExecution(inFlight, at + 1000)).toEqual({ kind: "idle" });
    expect(planRecurringExecution(inFlight, at + STALE_IN_FLIGHT_MS + 1)).toEqual({
      kind: "stale_in_flight",
    });
    expect(applyStaleInFlight(inFlight, at).status).toBe("needs_attention");
  });

  test("resuming skips occurrences that passed while paused", () => {
    const paused = { ...schedule, status: "paused" };
    const resumed = resumeRecurringPayment(paused, local(2026, 3, 5));
    expect(resumed.status).toBe("active");
    expect(resumed.nextOccurrenceIndex).toBe(2);
    expect(planRecurringExecution(resumed, local(2026, 3, 6))).toEqual({ kind: "idle" });
  });

  test("resuming during a retry wait still skips the overdue payment", () => {
    // 09:00 payment fails and a retry is set for 09:15.
    const dueAt = local(2026, 1, 31, 9, 0);
    const plan = planRecurringExecution(schedule, dueAt);
    const failed = applyFailedRun(schedule, plan, "Insufficient balance", true, dueAt);
    expect(failed.retryNotBefore).toBe(dueAt + RETRY_BASE_DELAY_MS);

    // Paused at 09:01, resumed at 09:02.
    const paused = { ...failed, status: "paused" };
    const resumeAt = local(2026, 1, 31, 9, 2);
    const resumed = resumeRecurringPayment(paused, resumeAt);

    expect(resumed.status).toBe("active");
    expect(resumed.retryNotBefore).toBeNull();
    expect(resumed.nextOccurrenceIndex).toBe(1);
    expect(resumed.nextRunAt).toBe(local(2026, 2, 28, 9, 0));
    // The runner must not immediately attempt the skipped 09:00 payment.
    expect(planRecurringExecution(resumed, resumeAt)).toEqual({ kind: "idle" });
    expect(planRecurringExecution(resumed, resumeAt, { ignoreRetryBackoff: true })).toEqual({
      kind: "idle",
    });
  });

  test("only active schedules are shared with the server, without amounts or recipients", () => {
    const entries = serverScheduleEntries([schedule, { ...schedule, id: "p", status: "paused" }]);
    expect(entries).toEqual([
      { schedule_id: "rent", next_run_at: Math.floor(schedule.startAt / 1000) },
    ]);
    expect(Object.keys(entries[0])).toEqual(["schedule_id", "next_run_at"]);
  });
});

describe("recurring payment changes while a payment is in progress", () => {
  const schedule = createRecurringPayment("rent", baseInput(), local(2026, 1, 1));
  const at = local(2026, 2, 1);
  const plan = planRecurringExecution(schedule, at);

  test("sends only if the latest stored schedule still plans the same payment", () => {
    expect(isStillDueForPayment(schedule, plan, at + 1000)).toBe(true);
  });

  test("does not send after a pause, cancel or wallet deletion during the checks", () => {
    expect(isStillDueForPayment({ ...schedule, status: "paused" }, plan, at + 1000)).toBe(false);
    expect(isStillDueForPayment(undefined, plan, at + 1000)).toBe(false);
  });

  test("does not send after a pause and resume moved past the occurrence", () => {
    const resumed = resumeRecurringPayment({ ...schedule, status: "paused" }, at + 1000);
    expect(resumed.status).toBe("active");
    expect(isStillDueForPayment(resumed, plan, at + 2000)).toBe(false);
  });

  test("does not send if another run already marked the occurrence in flight", () => {
    const inFlight = markInFlight(schedule, plan.occurrenceIndex, at);
    expect(isStillDueForPayment(inFlight, plan, at + 1000)).toBe(false);
  });

  test("a payment that finishes after a pause records the run but stays paused", () => {
    const inFlight = markInFlight(schedule, plan.occurrenceIndex, at);
    const pausedDuringSend = { ...inFlight, status: "paused" };
    const result = applySuccessfulRun(inFlight, plan, at + 5000);
    expect(result.status).toBe("active");

    const merged = mergeExecutionResult(pausedDuringSend, result);
    expect(merged.status).toBe("paused");
    expect(merged.inFlight).toBeNull();
    expect(merged.occurrencesPaid).toBe(1);
    expect(merged.nextOccurrenceIndex).toBe(1);
    expect(merged.runs[0].status).toBe("success");
    expect(planRecurringExecution(merged, local(2026, 3, 1))).toEqual({ kind: "idle" });
    expect(serverScheduleEntries([merged])).toEqual([]);
  });

  test("a payment that finishes after a cancel does not re-create the schedule", () => {
    const result = applySuccessfulRun(markInFlight(schedule, 0, at), plan, at + 5000);
    expect(mergeExecutionResult(undefined, result)).toBeNull();
  });

  test("a failure after a pause keeps it stopped, and needs-attention wins", () => {
    const paused = { ...schedule, status: "paused" };
    const retryable = applyFailedRun(schedule, plan, "Insufficient balance", true, at);
    expect(mergeExecutionResult(paused, retryable).status).toBe("paused");

    const ambiguous = applyFailedRun(schedule, plan, "timeout", false, at);
    expect(mergeExecutionResult(paused, ambiguous).status).toBe("needs_attention");
  });

  test("results for a schedule that stayed active are saved unchanged", () => {
    const result = applySuccessfulRun(schedule, plan, at);
    expect(mergeExecutionResult(schedule, result)).toEqual(result);
  });
});
