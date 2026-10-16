#!/usr/bin/env python3
"""Run failures only in the owned durableflow-faults Compose project."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import json
import os
from pathlib import Path
import subprocess
import sys
import time
import uuid

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from scripts.experiment import Client, ROOT, Stack, command, machine, maven_command, recovery_probe, task_state, utc, wait_for


def result_protocol():
    args = ["-B", "-ntp", "clean", "verify", "-Dit.test=RecoveryIT#duplicateSuccessIsIdempotentEvenAfterWorkflowCompletion"]
    if os.environ.get("DURABLEFLOW_MAVEN_REPO"):
        args.append("-Dmaven.repo.local=" + os.environ["DURABLEFLOW_MAVEN_REPO"])
    completed = subprocess.run(maven_command(args), cwd=ROOT, capture_output=True, text=True, timeout=600)
    if completed.returncode:
        raise AssertionError("Duplicate-result persistence protocol test failed: " + completed.stdout[-1200:])
    return {"test": "RecoveryIT.duplicateSuccessIsIdempotentEvenAfterWorkflowCompletion", "exit_code": 0, "observed": "16 equivalent reports accepted, conflicting output rejected, task version unchanged"}


def leader_failure(stack, client):
    leader = wait_for(stack.leader, description="scheduler leader")
    started = time.monotonic()
    command(["docker", "kill", "--signal", "KILL", leader])
    replacement = wait_for(lambda: (value if (value := stack.leader()) and value != leader else None), 30, "standby leadership")
    definition = client.define([{"id": "a", "type": "delay"}, {"id": "b", "type": "uppercase", "dependsOn": ["a"], "input": {"text": "after leader death"}}])
    execution = client.submit(definition, {"milliseconds": 100})["id"]
    final = client.terminal(execution)
    assert final["status"] == "SUCCEEDED"
    stack.compose("up", "-d", "--no-deps", "--no-recreate", "--scale", "scheduler=2", "scheduler")
    return {"status": final["status"], "workflow_id": execution, "replacement_container": replacement[:12], "failover_and_workflow_seconds": time.monotonic() - started}


def database_restart(stack, client):
    definition = client.define([{"id": "a", "type": "delay", "maxAttempts": 3}])
    execution = client.submit(definition, {"milliseconds": 5000})["id"]
    task_state(client, execution, "RUNNING")
    started = time.monotonic()
    stack.compose("restart", "postgres")
    final = client.terminal(execution)
    assert final["status"] == "SUCCEEDED"
    task = client.tasks(execution)[0]
    return {"workflow_id": execution, "status": final["status"], "attempt": task["attempt"], "recovery_seconds": time.monotonic() - started}


def duplicate_submission(stack, client):
    definition = client.define([{"id": "a", "type": "delay"}])
    key = "fault-duplicate-" + uuid.uuid4().hex
    with ThreadPoolExecutor(max_workers=16) as pool:
        results = list(pool.map(lambda _: client.submit(definition, {"milliseconds": 100}, key), range(32)))
    ids = {result["id"] for result in results}
    assert len(ids) == 1
    execution = next(iter(ids))
    assert client.terminal(execution)["status"] == "SUCCEEDED"
    tasks = client.tasks(execution)
    assert len(tasks) == 1
    return {"requests": 32, "concurrency": 16, "unique_execution_ids": len(ids), "task_count": len(tasks), "status": "SUCCEEDED"}


def exhaustion(stack, client):
    # Timeout a safe delay handler; no arbitrary failing script is needed.
    definition = client.define([{"id": "a", "type": "delay", "maxAttempts": 2, "timeoutSeconds": 1}])
    execution = client.submit(definition, {"milliseconds": 5000})["id"]
    final = client.terminal(execution)
    task = client.tasks(execution)[0]
    assert final["status"] == "FAILED" and task["status"] == "DEAD_LETTERED" and task["attempt"] == 2
    return {"workflow_id": execution, "status": final["status"], "task_status": task["status"], "attempts": client.attempts(task["id"])}


def scaling(stack, client):
    definition = client.define([{"id": "a", "type": "delay"}])
    executions = [client.submit(definition, {"milliseconds": 500})["id"] for _ in range(12)]
    wait_for(lambda: stack.sql("SELECT COUNT(*) FROM task_executions WHERE status='RUNNING'") != "0", description="running scaling work")
    stack.scale(1)
    stack.scale(4)
    finals = [client.terminal(execution) for execution in executions]
    assert all(execution["status"] == "SUCCEEDED" for execution in finals)
    attempts = [client.tasks(execution)[0]["attempt"] for execution in executions]
    stack.scale(3)
    return {"workflows": len(finals), "worker_counts": [3, 1, 4, 3], "all_succeeded": True, "maximum_attempt": max(attempts), "retried_workflows": sum(a>1 for a in attempts)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--keep-stack", action="store_true")
    parser.add_argument("--scenario", help="Run only the named scenario (default: all nine)")
    args = parser.parse_args()
    stack, client = Stack("faults"), Client()
    scenarios = [
        ("worker-kill", "A delay task is RUNNING with a valid lease", "SIGKILL its owning worker", "A new attempt succeeds and only one attempt has current ownership", lambda: recovery_probe(stack, client)),
        ("scheduler-kill", "Two schedulers compete for one advisory lock", "SIGKILL the elected scheduler", "Standby acquires leadership and unblocks a dependent task", lambda: leader_failure(stack, client)),
        ("postgres-restart", "A delay task is RUNNING", "Restart the isolated PostgreSQL container", "Durable workflow finishes after connectivity returns", lambda: database_restart(stack, client)),
        ("worker-network", "A worker owns a RUNNING delay task", "Disconnect the owning worker from its Docker network", "Healthy workers recover the expired lease; reconnect is safe", lambda: recovery_probe(stack, client, "network")),
        ("slow-completion", "A worker owns a RUNNING delay task", "Pause the worker past lease expiration until another attempt finishes", "Expired owner cannot overwrite the recovered result", lambda: recovery_probe(stack, client, "pause")),
        ("duplicate-submission", "One immutable task definition exists", "Send 32 equivalent submissions concurrently", "Exactly one durable execution and task set exists", lambda: duplicate_submission(stack, client)),
        ("duplicate-completion", "A successful attempt has committed", "Run 16 duplicate persistence-protocol completion reports", "Equivalent reports are idempotent; conflicting output cannot mutate state", result_protocol),
        ("retry-exhaustion", "A safe delay exceeds its configured one-second timeout", "Allow two attempts to exhaust", "Workflow FAILED; task DEAD_LETTERED at attempt two", lambda: exhaustion(stack, client)),
        ("graceful-scaling", "Twelve safe delay workflows are submitted", "Scale workers 3 to 1 to 4 to 3 gracefully", "Every workflow succeeds; any abandoned claim is recovered", lambda: scaling(stack, client)),
    ]
    if args.scenario:
        scenarios = [scenario for scenario in scenarios if scenario[0] == args.scenario]
        if not scenarios:
            parser.error("unknown scenario: " + args.scenario)
    out = ROOT / "fault-tests" / "results" / (utc().replace(":", "-") + "-" + uuid.uuid4().hex[:6])
    out.mkdir(parents=True)
    report = {"started_at": utc(), "status": "RUNNING", "scenarios": []}
    try:
        stack.down()
        stack.up()
        report["machine"] = machine(stack)
        for name, initial, failure, invariant, run in scenarios:
            print("Running", name, flush=True)
            started = time.monotonic()
            item = {"name": name, "initial_state": initial, "injected_failure": failure, "expected_invariant": invariant}
            try:
                item["observed_result"] = run()
                item["pass"] = True
            except Exception as error:
                item["observed_result"] = {"error_type": type(error).__name__, "message": str(error)}
                item["pass"] = False
            item["elapsed_seconds"] = time.monotonic() - started
            report["scenarios"].append(item)
            (out / "results.json").write_text(json.dumps(report, indent=2) + "\n")
            print(name, "PASS" if item["pass"] else "FAIL", flush=True)
        report["status"] = "PASSED" if all(item["pass"] for item in report["scenarios"]) else "FAILED"
    except KeyboardInterrupt:
        report["status"] = "INTERRUPTED"
        report["interruption_reason"] = "Fault run stopped before all scenarios completed."
        raise
    finally:
        report["completed_at"] = utc()
        (out / "results.json").write_text(json.dumps(report, indent=2) + "\n")
        if not args.keep_stack:
            try:
                stack.down()
            finally:
                client.close_all()
        else:
            client.close_all()
    print("Recorded", out.relative_to(ROOT), flush=True)
    if report["status"] != "PASSED":
        raise SystemExit(1)


if __name__ == "__main__":
    main()
