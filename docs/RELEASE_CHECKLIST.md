# Release checklist

Use this checklist for a proposed public release. A checked item requires current evidence; a merge or green build from an old commit is not enough.

## Source and governance

- [ ] Review the exact tagged commit, clean worktree, license, contributors and third-party notices.
- [ ] Verify MIT license applicability to project-owned code and preserve dependency licenses.
- [ ] Confirm README quick start, examples, architecture, delivery semantics, roadmap and known limitations match the tagged code.
- [ ] Confirm bug, feature, and design-proposal forms and pull-request guidance are active and contain no sensitive data.
- [ ] Check public repository description/topics and release notes for unsupported claims.

## Correctness and security

- [ ] Run full Maven verification with Java 21, Maven 3.9.x and Docker-backed Testcontainers.
- [ ] Run Python tooling tests, syntax checks, fault scenarios and applicable Compose smoke checks.
- [ ] Confirm CI passes on the exact candidate commit, including dependency/secret scanning.
- [ ] Review new and resolved dependency advisories; do not add silent vulnerability exceptions.
- [ ] Search the candidate tree and generated artifacts for credentials, personal information, local configuration, build output, and accidental datasets.
- [ ] Confirm authentication, input bounds, redacted errors, no payload logging, and documented trust boundaries.
- [ ] Review migration compatibility and recovery/failure-window coverage.

## Reproducibility and operations

- [ ] Simulate setup from a clean clone using only documented prerequisites and initialization.
- [ ] Confirm experiment cleanup is scoped to unique disposable Compose projects and volumes.
- [ ] Verify health/readiness, OpenAPI, metrics, traces, backup/restore expectations, and troubleshooting instructions.
- [ ] Preserve raw benchmark and fault evidence; regenerate summaries only from successful raw runs.
- [ ] Treat anomalous results as anomalies and avoid production-capacity or exactly-once claims.

## Candidate and publication

- [ ] Choose a version and create release notes tied to the reviewed commit.
- [ ] Confirm tag, artifacts and source archive correspond to the same commit.
- [ ] Obtain maintainer approval for publishing; this checklist does not authorize a release or deployment.
- [ ] After publication, verify download integrity, links and the documented installation path.
- [ ] Record known issues and next roadmap items.
