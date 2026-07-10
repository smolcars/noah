CREATE TABLE backup_objects (
    backup_id UUID PRIMARY KEY,
    pubkey TEXT NOT NULL REFERENCES users(pubkey) ON DELETE CASCADE,
    object_key TEXT NOT NULL UNIQUE,
    format_version INTEGER NOT NULL,
    encrypted_size BIGINT NOT NULL,
    encrypted_sha256 TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'pending',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ,
    CONSTRAINT backup_objects_format_version_check CHECK (format_version >= 2),
    CONSTRAINT backup_objects_size_check CHECK (encrypted_size > 0),
    CONSTRAINT backup_objects_sha256_check CHECK (encrypted_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT backup_objects_status_check CHECK (status IN ('pending', 'completed'))
);

CREATE INDEX idx_backup_objects_pubkey_completed
    ON backup_objects(pubkey, completed_at DESC)
    WHERE status = 'completed';

CREATE INDEX idx_backup_objects_pending_created
    ON backup_objects(created_at)
    WHERE status = 'pending';
