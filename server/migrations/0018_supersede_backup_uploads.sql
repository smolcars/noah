ALTER TABLE backup_objects
    DROP CONSTRAINT backup_objects_status_check;

ALTER TABLE backup_objects
    ADD COLUMN superseded_at TIMESTAMPTZ,
    ADD CONSTRAINT backup_objects_status_check
        CHECK (status IN ('pending', 'completed', 'superseded'));

CREATE INDEX idx_backup_objects_superseded_at
    ON backup_objects(superseded_at)
    WHERE status = 'superseded';
