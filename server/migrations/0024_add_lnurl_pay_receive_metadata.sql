-- LUD-12/18 data a payer sent with an LNURL-pay callback that barkd forwards, held encrypted until
-- the recipient's app fetches and acknowledges it. See docs/lnurl_pay.md.
CREATE TABLE lnurl_pay_receive_metadata (
    id UUID PRIMARY KEY,
    recipient_pubkey TEXT NOT NULL REFERENCES users(pubkey) ON DELETE CASCADE,
    state TEXT NOT NULL CHECK (state IN ('awaiting_invoice', 'awaiting_payment', 'ready')),
    payment_hash_lookup BYTEA,
    encrypted_payload BYTEA NOT NULL,
    nonce BYTEA NOT NULL CHECK (octet_length(nonce) = 24),
    request_expires_at TIMESTAMPTZ,
    invoice_expires_at TIMESTAMPTZ,
    correlation_expires_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    ready_at TIMESTAMPTZ,
    correlation_checked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (
        (state = 'awaiting_invoice' AND request_expires_at IS NOT NULL)
        OR state <> 'awaiting_invoice'
    ),
    CHECK ((state = 'ready' AND ready_at IS NOT NULL) OR state <> 'ready'),
    CHECK (correlation_expires_at <= expires_at)
);

CREATE INDEX idx_lnurl_pay_receive_metadata_recipient_ready
    ON lnurl_pay_receive_metadata(recipient_pubkey, created_at, id)
    WHERE state = 'ready';

CREATE UNIQUE INDEX idx_lnurl_pay_receive_metadata_payment_hash_unique
    ON lnurl_pay_receive_metadata(recipient_pubkey, payment_hash_lookup)
    WHERE payment_hash_lookup IS NOT NULL;

CREATE INDEX idx_lnurl_pay_receive_metadata_pending
    ON lnurl_pay_receive_metadata(
        recipient_pubkey,
        correlation_checked_at,
        created_at DESC,
        id DESC
    )
    WHERE state = 'awaiting_payment';

CREATE INDEX idx_lnurl_pay_receive_metadata_expiry
    ON lnurl_pay_receive_metadata(expires_at);

CREATE INDEX idx_lnurl_pay_receive_metadata_correlation_expiry
    ON lnurl_pay_receive_metadata(correlation_expires_at)
    WHERE state <> 'ready';

-- One mailbox Arkoor delivery, kept (encrypted) until it is matched to a record or found to match
-- none, so a failed barkd lookup can be retried without advancing the mailbox checkpoint.
CREATE TABLE lnurl_pay_receive_arkoor_observations (
    id UUID PRIMARY KEY,
    recipient_pubkey TEXT NOT NULL REFERENCES users(pubkey) ON DELETE CASCADE,
    mailbox_id TEXT NOT NULL,
    mailbox_checkpoint BIGINT NOT NULL CHECK (mailbox_checkpoint >= 0),
    metadata_id UUID REFERENCES lnurl_pay_receive_metadata(id) ON DELETE CASCADE,
    correlation_scan_started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    correlation_matched_metadata_id UUID REFERENCES lnurl_pay_receive_metadata(id) ON DELETE SET NULL,
    correlation_requires_retry BOOLEAN NOT NULL DEFAULT FALSE,
    encrypted_payload BYTEA NOT NULL,
    nonce BYTEA NOT NULL CHECK (octet_length(nonce) = 24),
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (recipient_pubkey, mailbox_id, mailbox_checkpoint)
);

CREATE INDEX idx_lnurl_pay_receive_arkoor_observations_unmatched
    ON lnurl_pay_receive_arkoor_observations(recipient_pubkey, created_at)
    WHERE metadata_id IS NULL;

CREATE INDEX idx_lnurl_pay_receive_arkoor_observations_expiry
    ON lnurl_pay_receive_arkoor_observations(expires_at);
