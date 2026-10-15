ALTER TABLE workflow_executions ADD COLUMN trace_parent VARCHAR(55);
ALTER TABLE task_executions ADD COLUMN trace_parent VARCHAR(55);
CREATE TABLE audit_events (
 id UUID PRIMARY KEY, action VARCHAR(32) NOT NULL, actor VARCHAR(64) NOT NULL,
 workflow_id UUID REFERENCES workflow_executions(id), task_id UUID REFERENCES task_executions(id),
 created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX audit_created ON audit_events(created_at,id);
CREATE FUNCTION reject_audit_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Audit events are append only'; END;
$$;
CREATE TRIGGER immutable_audit BEFORE UPDATE OR DELETE ON audit_events FOR EACH ROW EXECUTE FUNCTION reject_audit_mutation();
