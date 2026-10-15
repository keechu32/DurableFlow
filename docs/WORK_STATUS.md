# Paused work checkpoint — 2026-10-15

The owner requested stopping and committing all current changes. Phases 0–4 were committed and pushed; Phase 5 remains incomplete. Phase 6 has not started. This checkpoint is local and does not mark Phase 5 complete.

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
