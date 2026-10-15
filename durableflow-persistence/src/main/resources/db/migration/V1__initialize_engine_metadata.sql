-- Foundation only: domain tables arrive with their owning feature phase.
CREATE TABLE engine_metadata (
    singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
INSERT INTO engine_metadata (singleton) VALUES (TRUE);
COMMENT ON TABLE engine_metadata IS 'Foundation marker; workflow coordination is added in later migrations';
