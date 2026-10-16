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


def drain(client, ids, concurrency, executor=None):
    pending = set(ids)
    deadline = time.monotonic() + 300

    def poll(pool):
        while pending and time.monotonic() < deadline:
            states = list(pool.map(client.execution, list(pending)))
            for state in states:
                if state["status"] in {"FAILED", "CANCELLED"}:
                    raise AssertionError("Workflow " + state["id"] + " ended " + state["status"])
                if state["status"] == "SUCCEEDED":
                    pending.discard(state["id"])
            if pending:
                time.sleep(.1)

    if executor is None:
        with ThreadPoolExecutor(max_workers=concurrency) as pool:
            poll(pool)
    else:
        poll(executor)
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


def dispatch(client, definition, payload, count, concurrency, path, executor=None):
    def submit(_):
        began = time.monotonic()
        try:
            response = client.submit(definition, payload)
            return {"ok": True, "execution": {key: response[key] for key in ("id", "status", "created_at", "definition_id", "definition_version") if key in response}, "request_seconds": time.monotonic() - began}
        except Exception as error:
            return {"ok": False, "error_type": type(error).__name__, "message": str(error), "request_seconds": time.monotonic() - began}
    def run(pool):
        rows = []
        with path.open("a") as sink:
            for row in pool.map(submit, range(count)):
                sink.write(json.dumps(row) + "\n")
                rows.append(row)
        return rows

    if executor is not None:
        return run(executor)
    with ThreadPoolExecutor(max_workers=concurrency) as pool:
        return run(pool)


def profile(stack, client, workers, args, out):
    stack.down()
    stack.up(workers)
    metadata = machine(stack)
    (out / (str(workers) + "-workers-metadata.json")).write_text(json.dumps(metadata, indent=2) + "\n")
    payload = {"milliseconds": 0, "padding": "x" * args.payload_bytes}
    definition = client.define([{"id": "work", "type": "delay", "maxAttempts": 3, "timeoutSeconds": 60}])
    warm = []
    warm_start = time.monotonic()
    resources = Resources(stack)
    try:
        with ThreadPoolExecutor(max_workers=args.concurrency) as pool:
            while time.monotonic() - warm_start < args.warmup_seconds:
                responses = dispatch(client, definition, payload, args.concurrency, args.concurrency, out / (str(workers) + "-workers-warmup.jsonl"), pool)
                warm.extend(row["execution"]["id"] for row in responses if row["ok"])
                if not all(row["ok"] for row in responses):
                    raise AssertionError("Warm-up submission failed; raw responses retained")
            drain(client, warm, args.concurrency, pool)
            warm_elapsed = time.monotonic() - warm_start
            resources.thread.start()
            started = time.monotonic()
            try:
                responses = dispatch(client, definition, payload, args.samples, args.concurrency, out / (str(workers) + "-workers-requests.jsonl"), pool)
                executions = [row["execution"] for row in responses if row["ok"]]
                if not all(row["ok"] for row in responses):
                    raise AssertionError("Measured submission failed; every response retained")
                submission_seconds = time.monotonic() - started
                drain(client, [execution["id"] for execution in executions], args.concurrency, pool)
                elapsed = time.monotonic() - started
            finally:
                resources.close()
                (out / (str(workers) + "-workers-resources.json")).write_text(json.dumps(resources.samples, indent=2) + "\n")
    finally:
        client.close_all()
    try:
        with ThreadPoolExecutor(max_workers=args.concurrency) as pool:
            tasks = [task for group in pool.map(client.tasks, [execution["id"] for execution in executions]) for task in group]
    finally:
        client.close_all()
    raw = [{key: task[key] for key in ("id", "workflow_id", "status", "attempt", "available_at", "started_at", "completed_at", "error_category")} for task in tasks]
    (out / (str(workers) + "-workers-tasks.json")).write_text(json.dumps(raw, indent=2) + "\n")
    (out / (str(workers) + "-workers-resources.json")).write_text(json.dumps(resources.samples, indent=2) + "\n")
    if len(tasks) != args.samples:
        raise AssertionError("Expected " + str(args.samples) + " measured tasks; observed " + str(len(tasks)))
    task_states = [task["status"] for task in tasks]
    dead_letters = sum(status == "DEAD_LETTERED" for status in task_states)
    if dead_letters:
        raise AssertionError(str(dead_letters) + " measured tasks were dead-lettered")
    timings = [task_sample(task) for task in tasks]
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
    result["retried_tasks"] = sum(task["attempt"] > 1 for task in tasks)
    result["retried_task_rate"] = result["retried_tasks"] / len(tasks)
    result["dead_lettered_tasks"] = dead_letters
    result["dead_letter_rate"] = dead_letters / len(tasks)
    result["maximum_observed_database_connections"] = max((s.get("database_connections", 0) for s in resources.samples), default=0)
    postgres_cpu = [float(c["CPUPerc"].rstrip("%")) for sample in resources.samples for c in sample.get("containers", []) if "postgres" in c["Name"]]
    result["maximum_observed_postgres_cpu_percent"] = max(postgres_cpu, default=None)
    (out / (str(workers) + "-workers-timings.json")).write_text(json.dumps(timings, indent=2) + "\n")
    return result


def publish(report, out):
    machine_info = report["profiles"][0]["metadata"]
    rows = []
    detail_rows = []
    for value in report["profiles"]:
        scheduling, overhead = value["scheduling_latency_ms"], value["execution_overhead_ms"]
        rows.append(f'| {value["workers"]} | {value["workflow_submissions_per_second"]:.2f} | {value["task_completions_per_second"]:.2f} | {scheduling["p50"]:.2f} / {scheduling["p95"]:.2f} / {scheduling["p99"]:.2f} | {overhead["p50"]:.2f} / {overhead["p95"]:.2f} / {overhead["p99"]:.2f} |')
        cpu = value["maximum_observed_postgres_cpu_percent"]
        detail_rows.append(f'| {value["workers"]} | {value["warmup_submissions"]} | {value["warmup_with_drain_seconds"]:.2f} | {value["retried_tasks"]} ({value["retried_task_rate"]:.2%}) | {value["additional_execution_attempts"]} | {value["dead_lettered_tasks"]} ({value["dead_letter_rate"]:.2%}) | {value["maximum_observed_database_connections"]} | {"unavailable" if cpu is None else f"{cpu:.2f}%"} |')
    max_postgres_cpu = max((p["maximum_observed_postgres_cpu_percent"] or 0 for p in report["profiles"]), default=0)
    cpu_note = ""
    if max_postgres_cpu > machine_info["docker_cpus"] * 100:
        cpu_note = f"\n\nDocker Desktop reported a sampled PostgreSQL peak of {max_postgres_cpu:.2f}%, above the nominal {machine_info['docker_cpus'] * 100:.0f}% scale for its {machine_info['docker_cpus']} CPUs. Preserve this raw observation, but treat the peak as anomalous rather than a reliable capacity measure."
    text = f'''# Benchmarks

Observed local results from `{out.relative_to(ROOT).as_posix()}`. Raw task timestamps, per-task timings, resource samples, image IDs and runtime/machine metadata are stored separately from this summary. These are exploratory single-run measurements, not production capacity claims.

## Reproduce

```sh
python scripts/init-env.py
docker compose build
python -m unittest discover -s benchmark -p 'test_*.py'
python benchmark/run.py --samples {report["parameters"]["samples"]} --concurrency {report["parameters"]["concurrency"]} --payload-bytes {report["parameters"]["payload_bytes"]} --warmup-seconds {report["parameters"]["warmup_seconds"]}
```

Requires Python 3, Docker Desktop or another Docker Engine using Linux containers, and Compose 2.24.4+. Java 21 and Maven are supplied by the application's Maven builder image; the Windows fault-test runner also has a Docker-based Maven fallback. Every run uses a unique disposable Compose project and dynamically selected loopback port, starts a fresh database for every worker count, and removes only its own volumes. Do not put real data in an experiment project. Successful, failed and interrupted raw runs remain in unique directories. BENCHMARKS.md is generated only after all four profiles and the recovery probe succeed.

## Environment and workload

- Host: {machine_info["os"]} (reported release {machine_info["os_release"]}), {machine_info["architecture"]}, {machine_info["host_cpu"]}; {machine_info["host_logical_cpus"]} logical CPUs and {machine_info["host_memory_bytes"]} bytes RAM.
- Docker Engine: {machine_info["docker_server_version"]}, {machine_info["docker_os_type"]}; Compose {machine_info["docker_compose_version"]}; {machine_info["docker_cpus"]} CPUs and {machine_info["docker_memory_bytes"]} bytes available to Docker. Other local containers running: {machine_info["other_local_containers_running"]}.
- Application runtime: {machine_info["java"].splitlines()[0]}; {machine_info["maven"].splitlines()[0]}; Spring Boot {machine_info["spring_boot_version"]}; project {machine_info["project_version"]}; PostgreSQL {machine_info["postgresql"]}. Commit, source fingerprint, image references/IDs and each container's memory limit are recorded per profile in the raw summary.
- {report["parameters"]["samples"]} single-task workflows per count; fixed client concurrency {report["parameters"]["concurrency"]}; delay handler with zero requested work and {report["parameters"]["payload_bytes"]} padding bytes. Exact JSON input size is recorded per profile.
- Warm-up dispatch lasts at least {report["parameters"]["warmup_seconds"]} seconds, then all warm-up workflows drain before timed submission. The same fixed thread pool is reused for warm-up dispatch and drain so its per-thread HTTP connections persist between batches. The regression test verifies connection reuse across dispatch calls.
- Database contents and JVM instances start fresh for every profile. Java application containers have 384 MiB memory limits; PostgreSQL has no Compose memory limit. Other containers follow the Compose limits. The benchmark's CPU/memory samples and PostgreSQL connection samples are stored separately.
- Tracing, metrics, audit/security filters and normal scheduling are enabled. Diagnostic polling adds measurable load. Host workload and the idle base demo were not disabled; resource samples disclose observed database CPU/connections.

## Results

| Workers | Workflow submissions/s | Task completions/s | Scheduling p50 / p95 / p99 (ms) | Execution overhead p50 / p95 / p99 (ms) |
| --- | --- | --- | --- | --- |
''' + "\n".join(rows) + f'''

| Workers | Warm-up workflows | Warm-up dispatch + drain (s) | Retried tasks (rate) | Additional started attempts | Dead letters (rate) | Max DB connections* | Max sampled PostgreSQL CPU |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
''' + "\n".join(detail_rows) + f'''

Percentiles use nearest rank over every successful measured task. Scheduling is database `started_at - available_at`. Execution overhead is database `completed_at - started_at` for the zero-delay handler: it includes handler dispatch, transactions, serialization and result persistence, plus the negligible zero-delay handler itself. Completion throughput uses the first measured task start through the last task completion. Submission throughput uses the first request dispatch through the last accepted response. End-to-end throughput (including scheduler terminal observation) is also in raw summaries. These denominators differ deliberately and must not be compared as identical rates.

Across all profiles there were {sum(p["additional_claim_attempts"] for p in report["profiles"])} additional claim attempts, {sum(p["additional_execution_attempts"] for p in report["profiles"])} additional started attempts, {sum(p["retried_tasks"] for p in report["profiles"])} retried tasks and {sum(p["dead_lettered_tasks"] for p in report["profiles"])} dead letters. A profile with any failed task cannot pass; tasks are not omitted from percentiles. *The connection count includes the benchmark's own sampling query. CPU is the maximum observed Docker stats sample, not a time-weighted average. Resource data contains sampled CPU/memory for all containers.''' + cpu_note + f'''

## Recovery measurement

With three workers, the runner killed the owner of a five-second delay task. The workflow succeeded on attempt {len(report["recovery"]["attempts"])}; lease recovery was observed after {report["recovery"]["lease_recovery_seconds"]:.3f} seconds and injection-to-terminal completion took **{report["recovery"]["recovery_seconds"]:.3f} seconds**. This includes lease expiry, backoff, rerunning the five-second handler and polling. It is not a universal failover SLA. The raw recovery record retains both attempts and worker IDs.

## Warm-up drain investigation

The prior socket-allocation failure and interrupted run are retained under `benchmark/raw/`; neither is treated as a successful measurement. Inspection found that dispatch created a new executor for every warm-up batch, so thread-local keep-alive connections could not be reused across batches. Dispatch and drain now share one fixed executor, and the client explicitly closes all thread-owned connections after each profile. The focused regression test passed. This complete run observed no submission errors. Warm-up still produced {min(p["warmup_submissions"] for p in report["profiles"])} to {max(p["warmup_submissions"] for p in report["profiles"])} submissions and took {min(p["warmup_with_drain_seconds"] for p in report["profiles"]):.2f} to {max(p["warmup_with_drain_seconds"] for p in report["profiles"]):.2f} seconds including drain; warm-up work is fully drained before measurement and can therefore be a significant part of setup time.

## Interpretation and limits

The engine uses short PostgreSQL transactions and one execution slot per worker. Completion throughput rose through four workers but fell at eight; eight workers also had higher scheduling and execution p99 values than four. Additional workers increase database contention as well as parallelism; horizontal scaling is not linear in this sample. PostgreSQL's reported maximum connection count rose with worker count. Polling, JSON persistence, telemetry and status queries are part of this workload, not removed to improve the numbers. Consider the tail latencies, connection counts and CPU samples before attributing scaling behavior to worker capacity alone.

This is one run, does not isolate bottlenecks, estimate confidence intervals or extrapolate to other machines. Repeat it on an otherwise idle host before making capacity decisions. Retained unsuccessful/anomalous runs remain under `benchmark/raw/`; never average them away silently. The database is single-node, payloads are small, handlers are safe and near-zero work, and no external side-effect service is benchmarked.
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
    except KeyboardInterrupt:
        report["status"] = "INTERRUPTED"
        report["interruption_reason"] = "Benchmark stopped before all worker profiles and recovery completed."
        raise
    except Exception as error:
        report["status"] = "FAILED"
        report["error"] = {"type": type(error).__name__, "message": str(error)}
        raise
    finally:
        report["completed_at"] = utc()
        (out / "summary.json").write_text(json.dumps(report, indent=2) + "\n")
        try:
            stack.down()
        except Exception as error:
            report["cleanup_error"] = {"type": type(error).__name__, "message": str(error)}
            if report["status"] == "PASSED":
                report["status"] = "FAILED"
            (out / "summary.json").write_text(json.dumps(report, indent=2) + "\n")
        finally:
            client.close_all()
    if report["status"] != "PASSED":
        raise SystemExit(1)
    publish(report, out)
    print("Recorded", out.relative_to(ROOT), flush=True)


if __name__ == "__main__":
    main()
