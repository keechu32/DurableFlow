#!/usr/bin/env python3
"""Measure an isolated engine; publish BENCHMARKS.md only after every profile succeeds."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import json
from pathlib import Path
import sys
import threading
import time
import uuid

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from scripts.experiment import Client, ROOT, Stack, command, machine, recovery_probe, utc
from analysis import summary, task_sample


def drain(client, ids, concurrency):
    pending = set(ids)
    deadline = time.monotonic() + 300
    with ThreadPoolExecutor(max_workers=concurrency) as pool:
        while pending and time.monotonic() < deadline:
            states = list(pool.map(client.execution, list(pending)))
            for state in states:
                if state["status"] in {"SUCCEEDED", "FAILED", "CANCELLED"}:
                    pending.discard(state["id"])
            if pending:
                time.sleep(.1)
    if pending:
        raise AssertionError(str(len(pending)) + " workflows did not terminate within 300 seconds")


class Resources:
    def __init__(self, stack):
        self.stack, self.samples, self.stop = stack, [], threading.Event()
        self.thread = threading.Thread(target=self.run, daemon=True)

    def run(self):
        while not self.stop.is_set():
            sample = {"at": utc()}
            try:
                ids = self.stack.compose("ps", "-q").splitlines()
                sample["containers"] = [json.loads(line) for line in command(["docker", "stats", "--no-stream", "--format", "{{json .}}"] + ids, 30).splitlines()]
                sample["database_connections"] = int(self.stack.sql("SELECT COUNT(*) FROM pg_stat_activity WHERE datname=current_database()"))
            except Exception as error:
                sample["error"] = type(error).__name__ + ": " + str(error)
            self.samples.append(sample)
            self.stop.wait(.5)

    def close(self):
        self.stop.set()
        self.thread.join(35)
        if self.thread.is_alive():
            raise AssertionError("Resource monitor did not stop")


def dispatch(client, definition, payload, count, concurrency, path):
    def submit(_):
        began = time.monotonic()
        try:
            response = client.submit(definition, payload)
            return {"ok": True, "execution": {key: response[key] for key in ("id", "status", "created_at", "definition_id", "definition_version") if key in response}, "request_seconds": time.monotonic() - began}
        except Exception as error:
            return {"ok": False, "error_type": type(error).__name__, "message": str(error), "request_seconds": time.monotonic() - began}
    rows = []
    with ThreadPoolExecutor(max_workers=concurrency) as pool, path.open("a") as sink:
        for row in pool.map(submit, range(count)):
            sink.write(json.dumps(row) + "\n")
            rows.append(row)
    return rows


def profile(stack, client, workers, args, out):
    stack.down()
    stack.up(workers)
    metadata = machine(stack)
    (out / (str(workers) + "-workers-metadata.json")).write_text(json.dumps(metadata, indent=2) + "\n")
    payload = {"milliseconds": 0, "padding": "x" * args.payload_bytes}
    definition = client.define([{"id": "work", "type": "delay", "maxAttempts": 3, "timeoutSeconds": 60}])
    warm = []
    warm_start = time.monotonic()
    while time.monotonic() - warm_start < args.warmup_seconds:
        responses = dispatch(client, definition, payload, args.concurrency, args.concurrency, out / (str(workers) + "-workers-warmup.jsonl"))
        warm.extend(row["execution"]["id"] for row in responses if row["ok"])
        if not all(row["ok"] for row in responses):
            raise AssertionError("Warm-up submission failed; raw responses retained")
    drain(client, warm, args.concurrency)
    warm_elapsed = time.monotonic() - warm_start
    resources = Resources(stack)
    resources.thread.start()
    started = time.monotonic()
    try:
        responses = dispatch(client, definition, payload, args.samples, args.concurrency, out / (str(workers) + "-workers-requests.jsonl"))
        executions = [row["execution"] for row in responses if row["ok"]]
        if not all(row["ok"] for row in responses):
            raise AssertionError("Measured submission failed; every response retained")
        submission_seconds = time.monotonic() - started
        drain(client, [execution["id"] for execution in executions], args.concurrency)
        elapsed = time.monotonic() - started
    finally:
        resources.close()
        (out / (str(workers) + "-workers-resources.json")).write_text(json.dumps(resources.samples, indent=2) + "\n")
    with ThreadPoolExecutor(max_workers=args.concurrency) as pool:
        tasks = [task for group in pool.map(client.tasks, [execution["id"] for execution in executions]) for task in group]
    raw = [{key: task[key] for key in ("id", "workflow_id", "status", "attempt", "available_at", "started_at", "completed_at", "error_category")} for task in tasks]
    (out / (str(workers) + "-workers-tasks.json")).write_text(json.dumps(raw, indent=2) + "\n")
    (out / (str(workers) + "-workers-resources.json")).write_text(json.dumps(resources.samples, indent=2) + "\n")
    timings = [task_sample(task) for task in tasks]
    assert len(timings) == args.samples
    result = summary(timings, submission_seconds, elapsed)
    result.update({"workers": workers, "warmup_submissions": len(warm), "warmup_dispatch_seconds": args.warmup_seconds,
                   "warmup_with_drain_seconds": warm_elapsed, "input_json_bytes": len(json.dumps(payload, separators=(",", ":")).encode()),
                   "metadata": metadata, "resource_samples": len(resources.samples)})
    duplicate_executions = 0
    for task in tasks:
        if task["attempt"] > 1:
            attempts = client.attempts(task["id"])
            duplicate_executions += max(0, sum(a["started_at"] is not None for a in attempts) - 1)
    result["additional_execution_attempts"] = duplicate_executions
    result["maximum_observed_database_connections"] = max((s.get("database_connections", 0) for s in resources.samples), default=0)
    postgres_cpu = [float(c["CPUPerc"].rstrip("%")) for sample in resources.samples for c in sample.get("containers", []) if "postgres" in c["Name"]]
    result["maximum_observed_postgres_cpu_percent"] = max(postgres_cpu, default=None)
    (out / (str(workers) + "-workers-timings.json")).write_text(json.dumps(timings, indent=2) + "\n")
    return result


def publish(report, out):
    machine_info = report["profiles"][0]["metadata"]
    rows = []
    for value in report["profiles"]:
        scheduling, overhead = value["scheduling_latency_ms"], value["execution_overhead_ms"]
        rows.append(f'| {value["workers"]} | {value["workflow_submissions_per_second"]:.2f} | {value["task_completions_per_second"]:.2f} | {scheduling["p50"]:.2f} / {scheduling["p95"]:.2f} / {scheduling["p99"]:.2f} | {overhead["p50"]:.2f} / {overhead["p95"]:.2f} / {overhead["p99"]:.2f} |')
    text = f'''# Benchmarks

Observed local results from `{out.relative_to(ROOT)}`. Raw task timestamps, per-task timings, resource samples, image IDs and runtime/machine metadata are stored separately from this summary. These are exploratory single-run measurements, not production capacity claims.

## Reproduce

```sh
python3 scripts/init-env.py
docker compose build
python3 -m unittest discover -s benchmark -p 'test_*.py'
python3 benchmark/run.py --samples {report["parameters"]["samples"]} --concurrency {report["parameters"]["concurrency"]} --payload-bytes {report["parameters"]["payload_bytes"]} --warmup-seconds {report["parameters"]["warmup_seconds"]}
```

Requires Java 21/Maven, Docker/Compose 2.24.4+ and Python 3. The runner uses the disposable `durableflow-benchmark` project on port 18080, creates a fresh database for each worker count, and removes only that project's volumes. Do not put real data in experiment projects. All runs, including failures, get a unique raw-results directory. BENCHMARKS.md is generated only after a successful complete run.

## Environment and workload

- Host: {machine_info["os"]} {machine_info["os_release"]}, {machine_info["architecture"]}, {machine_info["host_cpu"]}; {machine_info["host_logical_cpus"]} logical CPUs and {machine_info["host_memory_bytes"]} bytes RAM.
- Docker VM: {machine_info["docker_cpus"]} CPUs, {machine_info["docker_memory_bytes"]} bytes RAM. Other local containers running: {machine_info["other_local_containers_running"]}.
- Runtime versions, commit, source fingerprint and image IDs: see every profile's metadata in the raw summary. PostgreSQL {machine_info["postgresql"]}; project {machine_info["project_version"]}.
- {report["parameters"]["samples"]} single-task workflows per count; fixed client concurrency {report["parameters"]["concurrency"]}; delay handler with zero requested work and {report["parameters"]["payload_bytes"]} padding bytes. Exact JSON input size is recorded per profile.
- Warm-up dispatch lasts at least {report["parameters"]["warmup_seconds"]} seconds, then all warm-up workflows drain before timed submission. Database contents and JVM instances start fresh for every profile.
- Tracing, metrics, audit/security filters and normal scheduling are enabled. Diagnostic polling adds measurable load. Host workload and the idle base demo were not disabled; resource samples disclose observed database CPU/connections.

## Results

| Workers | Workflow submissions/s | Task completions/s | Scheduling p50 / p95 / p99 (ms) | Execution overhead p50 / p95 / p99 (ms) |
| --- | --- | --- | --- | --- |
''' + "\n".join(rows) + f'''

Percentiles use nearest rank over every successful measured task. Scheduling is database `started_at - available_at`. Execution overhead is database `completed_at - started_at` for the zero-delay handler: it includes handler dispatch, transactions, serialization and result persistence, plus the negligible zero-delay handler itself. Completion throughput uses the first measured task start through the last task completion. Submission throughput uses the first request dispatch through the last accepted response. End-to-end throughput (including scheduler terminal observation) is also in raw summaries. These denominators differ deliberately and must not be compared as identical rates.

Additional claim attempts: {sum(p["additional_claim_attempts"] for p in report["profiles"])}. Additional started execution attempts: {sum(p["additional_execution_attempts"] for p in report["profiles"])}. Retries/dead letters are recorded per profile; unsuccessful tasks would fail the run rather than disappear from percentiles. Resource data includes every sampled container's CPU/memory and PostgreSQL connection counts (including the monitor connection).

## Recovery measurement

With three workers, the runner killed the owner of a five-second delay task. The workflow succeeded on attempt {len(report["recovery"]["attempts"])}; injection-to-observed completion took **{report["recovery"]["recovery_seconds"]:.3f} seconds**. This includes lease expiry, backoff, rerunning the five-second handler and polling. It is not a claim that every deployment recovers in that time. The raw recovery record retains attempt history.

## Interpretation and limits

The engine uses short PostgreSQL transactions and one execution slot per worker. More workers increase database contention as well as parallelism; horizontal scaling is not guaranteed to be linear. The scheduler scans active workflows and waits for its poll interval between passes. Polling, JSON persistence, telemetry and status queries are part of this workload, not removed to improve the numbers. Compare the measured tail latencies and PostgreSQL CPU/connection samples before attributing any scaling change to worker capacity alone.

This run does not isolate each bottleneck causally, estimate confidence intervals or extrapolate to other machines. Repeat it on an otherwise idle host before making capacity decisions. Retained unsuccessful/anomalous runs remain under `benchmark/raw/`; never average them away silently. The database is single-node, payloads are small, handlers are safe and near-zero work, and no external side-effect service is benchmarked.
'''
    (ROOT / "BENCHMARKS.md").write_text(text)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--samples", type=int, default=1000)
    parser.add_argument("--concurrency", type=int, default=16)
    parser.add_argument("--payload-bytes", type=int, default=256)
    parser.add_argument("--warmup-seconds", type=float, default=5)
    args = parser.parse_args()
    if args.samples < 100 or not 1 <= args.concurrency <= 64 or not 0 <= args.payload_bytes <= 65536 or args.warmup_seconds <= 0:
        parser.error("Require >=100 samples, concurrency 1–64, payload 0–65536 and positive warm-up")
    out = ROOT / "benchmark" / "raw" / (utc().replace(":", "-") + "-" + uuid.uuid4().hex[:6])
    out.mkdir(parents=True)
    report = {"started_at": utc(), "parameters": vars(args), "profiles": [], "status": "RUNNING"}
    stack, client = Stack("benchmark"), Client()
    try:
        for workers in (1, 2, 4, 8):
            print("Measuring", workers, "workers", flush=True)
            result = profile(stack, client, workers, args, out)
            report["profiles"].append(result)
            (out / "summary.json").write_text(json.dumps(report, indent=2) + "\n")
            print(workers, "workers:", round(result["task_completions_per_second"], 2), "tasks/s", flush=True)
        stack.scale(3)
        report["recovery"] = recovery_probe(stack, client)
        report["status"] = "PASSED"
    except Exception as error:
        report["status"] = "FAILED"
        report["error"] = {"type": type(error).__name__, "message": str(error)}
        raise
    finally:
        report["completed_at"] = utc()
        (out / "summary.json").write_text(json.dumps(report, indent=2) + "\n")
        stack.down()
    publish(report, out)
    print("Recorded", out.relative_to(ROOT), flush=True)


if __name__ == "__main__":
    main()
