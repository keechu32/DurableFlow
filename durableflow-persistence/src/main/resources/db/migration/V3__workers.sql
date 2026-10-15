CREATE TABLE workers (
 id UUID PRIMARY KEY, supported_types TEXT[] NOT NULL, heartbeat_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 status VARCHAR(16) NOT NULL CHECK(status IN ('ONLINE','DRAINING','OFFLINE')), metadata JSONB NOT NULL
);
ALTER TABLE task_executions ADD CONSTRAINT lease_worker FOREIGN KEY(lease_owner) REFERENCES workers(id);
CREATE INDEX active_leases ON task_executions(lease_expires_at) WHERE status IN ('LEASED','RUNNING');
