# Prior paused work checkpoint — 2026-10-15

The owner requested stopping and committing all current changes. Phases 0–4 were committed and pushed; Phase 5 remained incomplete. Phase 6 had not started. This checkpoint did not mark Phase 5 complete.

## Preserved work

- Executable isolated Compose fault scenarios, benchmark driver, reusable HTTP client, machine/version metadata, raw observations and measurement-analysis tests.
- Worker instance metadata for targeting the actual lease owner, reusable local image tags, experimental Compose overrides and Python CI checks.
- Nine fault scenarios passed: worker kill, scheduler leader kill, PostgreSQL restart, worker network isolation, slow completion past lease expiry, concurrent duplicate submission, duplicate completion, retry exhaustion and graceful scaling.
- Latest full Maven verification passed: 49 Java tests, formatting and SpotBugs. Five Python tests passed, including connection reuse and failure-artifact preservation; Python compilation also passed.

Fault evidence: `fault-tests/results/2026-10-15T08-48-07.940433+00-00-3f2ebf/results.json`.

## Benchmark state

Both runs used 1,000 intended samples per worker profile, concurrency 16, 256 padding bytes and five seconds of warm-up. Neither reached a measured profile:

- `benchmark/raw/2026-10-15T08-50-22.878059+00-00-c4034d/`: failed during warm-up drain with socket allocation error (`Can't assign requested address`). Raw observations retained.
- The client was then changed to reuse HTTP connections, with a passing regression test.
- `benchmark/raw/2026-10-15T08-55-56.938767+00-00-66a30a/`: interrupted during warm-up drain at the owner’s request. Summary explicitly records interruption; experimental stack cleanup ran on exit.

No throughput or latency claims are supported yet. No BENCHMARKS.md has been generated.

## Resume

1. Review current tooling and raw failures; investigate why warm-up draining did not complete before interruption.
2. Run the complete 1/2/4/8-worker benchmark and recovery probe, preserving unsuccessful observations. Generate BENCHMARKS.md only after success.
3. Complete Phase 5 documentation, checklist and evidence review, then commit and push the completed phase as previously authorized.
4. Implement Phase 6 release preparation, complete audit and fresh-clone verification, then commit and push. Do not create cloud resources or spend money.

Ignored local credentials and runtime files are excluded from this checkpoint. The regular demonstration stack may still be running; benchmark cleanup targets only its own isolated project.

## Resume on Windows — 2026-10-16

The checkpoint is now pushed as `018d870` and checked out on `main`. Java 21 remains the project requirement; pre-existing uncommitted Java 25 edits were saved in the local Git stash `Preserve Java 25 runtime edits; project baseline is Java 21` and were not applied.

Observed environment: Docker Desktop 29.8.2, Linux containers, 12 Docker CPUs and 8,291,274,752 bytes of Docker memory; Compose 5.6.0; Python 3.11.9; host Temurin Java 17; no host Maven. The Docker Maven image in the repository Dockerfile provides Maven 3.9.16 and Java 21. Local `.env` and `.secrets/operator-token` were absent and generated with `scripts/init-env.py`; both are ignored. No existing containers were running when inspected.

`core.autocrlf=true` checked Java source out with CRLF, which made Spotless fail in Linux container builds. Java files were normalized to LF without changing their Git blob contents, and `.gitattributes` now enforces LF for Java across Windows checkouts.

The initial Maven verification attempted without cleaning found stale Java 25 class files in ignored `target/` directories (class file version 69). A clean Java 21 verification is required before interpreting build results.

## Phase 5 complete — 2026-10-16

The Phase 5 report and generated [`BENCHMARKS.md`](../BENCHMARKS.md) document a successful full 1/2/4/8-worker run (1,000 measured tasks per profile, concurrency 16, 256 padding bytes, five-second minimum warm-up), plus a worker-kill recovery probe. The run had zero retries, duplicate started attempts or dead letters. The eight-worker PostgreSQL CPU maximum was anomalous and is explicitly caveated. All nine fault scenarios passed in the Windows rerun. Raw failed, interrupted, anomalous and successful artifacts remain intact.

The fault and benchmark harnesses use unique disposable Compose projects and dynamically allocated loopback ports. Warm-up dispatch and drain share a fixed thread pool so thread-local HTTP connections persist; the client explicitly closes its connections. Warm-up drain is still costly and has a recorded range of 29.01–95.09 seconds. Java 21/Maven are provided by the repository Docker image, preserving the required Java 21 baseline. Details and actual verification are in [`Phase 5`](phases/phase-5.md) and [`VERIFICATION.md`](VERIFICATION.md).

Phase 6 is now active. Do not apply the preserved Java 25 stash or modify Java compatibility.
