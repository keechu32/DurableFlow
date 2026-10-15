CREATE TABLE workflow_definitions (
 id UUID PRIMARY KEY, name VARCHAR(128) NOT NULL UNIQUE, created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE definition_versions (
 definition_id UUID NOT NULL REFERENCES workflow_definitions(id), version INTEGER NOT NULL CHECK(version>0),
 spec JSONB NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(definition_id, version)
);
CREATE TABLE workflow_executions (
 id UUID PRIMARY KEY, definition_id UUID NOT NULL, definition_version INTEGER NOT NULL,
 idempotency_key VARCHAR(128) NOT NULL UNIQUE, request JSONB NOT NULL, input JSONB NOT NULL,
 status VARCHAR(24) NOT NULL CHECK(status IN ('PENDING','RUNNING','SUCCEEDED','FAILED','CANCELLING','CANCELLED')),
 created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP, started_at TIMESTAMPTZ, completed_at TIMESTAMPTZ,
 cancellation_reason VARCHAR(256), cancelled_at TIMESTAMPTZ, version BIGINT NOT NULL DEFAULT 0,
 FOREIGN KEY(definition_id,definition_version) REFERENCES definition_versions(definition_id,version)
);
CREATE TABLE task_executions (
 id UUID PRIMARY KEY, workflow_id UUID NOT NULL REFERENCES workflow_executions(id), task_definition_id VARCHAR(128) NOT NULL,
 type VARCHAR(64) NOT NULL, status VARCHAR(24) NOT NULL CHECK(status IN ('BLOCKED','READY','LEASED','RUNNING','RETRY_WAIT','SUCCEEDED','FAILED','DEAD_LETTERED','CANCELLED')),
 attempt INTEGER NOT NULL DEFAULT 0 CHECK(attempt>=0), max_attempts INTEGER NOT NULL CHECK(max_attempts BETWEEN 1 AND 20),
 timeout_seconds INTEGER NOT NULL CHECK(timeout_seconds BETWEEN 1 AND 3600), available_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 lease_owner UUID, lease_expires_at TIMESTAMPTZ, heartbeat_at TIMESTAMPTZ,
 input JSONB NOT NULL, output JSONB, error_category VARCHAR(64), error_message VARCHAR(256),
 created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP, started_at TIMESTAMPTZ, completed_at TIMESTAMPTZ,
 version BIGINT NOT NULL DEFAULT 0, UNIQUE(workflow_id,task_definition_id)
);
CREATE TABLE task_dependencies (
 task_id UUID NOT NULL REFERENCES task_executions(id), prerequisite_id UUID NOT NULL REFERENCES task_executions(id),
 PRIMARY KEY(task_id,prerequisite_id), CHECK(task_id<>prerequisite_id)
);
CREATE INDEX task_queue ON task_executions(available_at,created_at,id) WHERE status='READY';
CREATE INDEX tasks_by_workflow ON task_executions(workflow_id,created_at,id);
CREATE INDEX dependency_prerequisites ON task_dependencies(prerequisite_id);
