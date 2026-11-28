-- A key the app keeps so it alone can read the payer data its LNURL-pay payment pushes carry
-- (docs/lnurl_pay.md). NULL for apps that don't register one.
ALTER TABLE push_tokens
    ADD COLUMN notification_key BYTEA
        CHECK (notification_key IS NULL OR octet_length(notification_key) = 32);
