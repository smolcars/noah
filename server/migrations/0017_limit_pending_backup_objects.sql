CREATE UNIQUE INDEX idx_backup_objects_one_pending_per_pubkey
    ON backup_objects(pubkey)
    WHERE status = 'pending';
