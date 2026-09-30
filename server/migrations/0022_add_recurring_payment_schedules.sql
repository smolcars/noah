-- Wake-up schedule for client-side recurring payments.
--
-- The server intentionally stores no amount, recipient, or spending policy.
-- Recurring payments are signed and executed by the wallet on the device; the
-- server only knows an opaque schedule id and when the device should be woken
-- up (via silent push) to execute its own locally stored, user-approved policy.
CREATE TABLE recurring_payment_schedules (
    pubkey TEXT NOT NULL REFERENCES users(pubkey) ON DELETE CASCADE,
    schedule_id TEXT NOT NULL CHECK (schedule_id ~ '^[A-Za-z0-9_-]{1,64}$'),
    next_run_at TIMESTAMPTZ NOT NULL,
    last_notified_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (pubkey, schedule_id)
);

CREATE INDEX recurring_payment_schedules_next_run_at_idx
    ON recurring_payment_schedules (next_run_at);
