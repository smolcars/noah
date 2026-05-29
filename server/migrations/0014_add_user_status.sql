ALTER TABLE users
ADD COLUMN status TEXT NOT NULL DEFAULT 'active'
    CHECK (status IN ('active', 'inactive', 'deregistered'));

ALTER TABLE users
ADD COLUMN status_changed_at TIMESTAMPTZ NOT NULL DEFAULT now();

CREATE INDEX idx_users_status ON users(status);
