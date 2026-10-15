ALTER TABLE task_executions ADD COLUMN attempt_limit INTEGER;
UPDATE task_executions SET attempt_limit=max_attempts;
ALTER TABLE task_executions ALTER COLUMN attempt_limit SET NOT NULL;
ALTER TABLE task_executions ADD CONSTRAINT valid_attempt_limit CHECK(attempt_limit>=max_attempts);
CREATE TABLE task_attempts (
 task_id UUID NOT NULL REFERENCES task_executions(id), attempt INTEGER NOT NULL CHECK(attempt>0), worker_id UUID,
 status VARCHAR(16) NOT NULL CHECK(status IN ('LEASED','RUNNING','SUCCEEDED','FAILED','EXPIRED','CANCELLED')),
 leased_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP, started_at TIMESTAMPTZ, completed_at TIMESTAMPTZ,
 output JSONB, error_category VARCHAR(64), PRIMARY KEY(task_id,attempt)
);
INSERT INTO task_attempts(task_id,attempt,worker_id,status,started_at,completed_at,output,error_category)
SELECT id,attempt,lease_owner,CASE WHEN status IN ('LEASED','RUNNING','SUCCEEDED') THEN status WHEN status IN ('FAILED','DEAD_LETTERED','RETRY_WAIT') THEN 'FAILED' ELSE 'CANCELLED' END,started_at,completed_at,output,error_category FROM task_executions WHERE attempt>0;
