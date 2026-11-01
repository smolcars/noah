-- NULL means the token's own format decides the route: Expo or UnifiedPush.
ALTER TABLE push_tokens
    ADD COLUMN provider TEXT CHECK (provider IN ('apns', 'fcm')),
    ADD COLUMN apns_sandbox BOOLEAN NOT NULL DEFAULT FALSE;
