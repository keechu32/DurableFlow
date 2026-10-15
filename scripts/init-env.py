#!/usr/bin/env python3
"""Generate ignored development secrets without printing their values."""
from pathlib import Path
import os
import secrets

root = Path(__file__).resolve().parents[1]
path = root / ".env"
values = {}
if path.exists():
    for line in path.read_text().splitlines():
        if "=" in line and not line.lstrip().startswith("#"):
            key, value = line.split("=", 1)
            values[key] = value
values.setdefault("DURABLEFLOW_DB_USER", "durableflow")
for key in ("DURABLEFLOW_DB_PASSWORD", "DURABLEFLOW_OPERATOR_TOKEN", "DURABLEFLOW_ADMIN_TOKEN"):
    values.setdefault(key, secrets.token_hex(32))
path.touch(mode=0o600, exist_ok=True)
path.chmod(0o600)
path.write_text("".join(key + "=" + value + "\n" for key, value in values.items()))
directory = root / ".secrets"
directory.mkdir(mode=0o700, exist_ok=True)
directory.chmod(0o700)
secret = directory / "operator-token"
secret.write_text(values["DURABLEFLOW_OPERATOR_TOKEN"])
# The private parent directory protects this host file; Prometheus needs read access
# when Compose binds the individual file into its own container.
secret.chmod(0o644)
print("Local .env and .secrets/operator-token are ready (ignored by Git and Docker builds).")
