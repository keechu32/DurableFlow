#!/usr/bin/env python3
"""Submit the diamond DAG and wait for its durable terminal state."""
import json
import os
from pathlib import Path
import time
import urllib.request
import uuid

BASE = os.environ.get("DURABLEFLOW_URL", "http://localhost:8080")


def request(path, payload=None):
    headers = {"Content-Type": "application/json"}
    token = os.environ.get("DURABLEFLOW_API_TOKEN")
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(BASE + path, headers=headers,
                                 data=None if payload is None else json.dumps(payload).encode())
    with urllib.request.urlopen(req, timeout=10) as response:
        return json.load(response)


def main():
    spec = json.loads(Path(__file__).with_name("diamond.json").read_text())
    definition = request("/api/v1/workflow-definitions", spec)
    execution = request("/api/v1/workflow-executions", {
        "definitionId": definition["definition_id"], "version": definition["version"],
        "idempotencyKey": str(uuid.uuid4()), "input": {}})
    path = "/api/v1/workflow-executions/" + execution["id"]
    print("Execution:", execution["id"], flush=True)
    deadline = time.monotonic() + 60
    previous = None
    while time.monotonic() < deadline:
        execution = request(path)
        status = execution["status"]
        if status != previous:
            print("Status:", status, flush=True)
            previous = status
        if status in {"SUCCEEDED", "FAILED", "CANCELLED"}:
            print(json.dumps(request(path + "/tasks?limit=100"), indent=2))
            if status != "SUCCEEDED":
                raise SystemExit("Workflow did not succeed")
            return
        time.sleep(0.1)
    raise SystemExit("Workflow did not complete within 60 seconds")


if __name__ == "__main__":
    main()
