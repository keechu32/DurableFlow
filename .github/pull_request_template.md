## Summary

Describe the user-visible or operational change and link the related issue.

## Design and safety

- [ ] API, scheduling, persistence, and execution boundaries remain explicit.
- [ ] Accepted work is committed before acknowledgement.
- [ ] Transaction boundaries and crash/retry behavior are described.
- [ ] Task execution remains at least once; side effects use stable idempotency keys.
- [ ] Lease fencing, capability filtering, retry, and cancellation semantics are preserved or explicitly changed.
- [ ] No shell execution, user scripts, dynamic loading, or unrestricted outbound HTTP handler was added.
- [ ] No secret, payload, credential, or personal data is logged or committed.

## Verification

- [ ] Relevant unit and integration tests pass.
- [ ] Formatting and static analysis pass.
- [ ] Docker Compose build/startup or an explained equivalent check passed.
- [ ] Documentation and examples reflect observed behavior.
- [ ] Failed or unavailable checks are explained; no results or performance claims are fabricated.
- [ ] New dependencies and migrations have a documented rationale.

## Operational notes

Describe deployment/configuration changes, data migration or rollback considerations, and known limitations.
