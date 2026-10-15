"""Shared standard-library tooling for isolated, disposable local experiments."""
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import hashlib
import http.client
import io
import threading
import urllib.parse
import json
import os
from pathlib import Path
import platform
import subprocess
import time
import urllib.error
import urllib.request
import uuid
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
TERMINAL = {"SUCCEEDED", "FAILED", "CANCELLED"}


def utc():
    return datetime.now(timezone.utc).isoformat()


def command(args, timeout=180, check=True):
    result = subprocess.run(args, cwd=ROOT, capture_output=True, text=True, timeout=timeout)
    if check and result.returncode:
        raise RuntimeError("Command failed: " + " ".join(args[:5]) + "\n" + result.stderr[-1500:])
    return result.stdout.strip()


def wait_for(predicate, seconds=90, description="condition"):
    deadline = time.monotonic() + seconds
    last_error = None
    while time.monotonic() < deadline:
        try:
            value = predicate()
            if value:
                return value
        except (urllib.error.URLError, TimeoutError, ConnectionError, RuntimeError) as error:
            last_error = type(error).__name__
        time.sleep(0.1)
    raise AssertionError(description + " did not become true; last error=" + str(last_error))


def settings():
    result = {}
    for line in (ROOT / ".env").read_text().splitlines():
        if "=" in line and not line.lstrip().startswith("#"):
            key, value = line.split("=", 1)
            result[key] = value.strip('\"\'')
    for key in tuple(result):
        if key in os.environ:
            result[key] = os.environ[key]
    return result


class Client:
    def __init__(self, url=None, tokens=None):
        self.base = url or "http://127.0.0.1:" + os.environ.get("DURABLEFLOW_EXPERIMENT_PORT", "18080")
        self.tokens = settings() if tokens is None else tokens
        self.target = urllib.parse.urlsplit(self.base)
        if self.target.scheme not in {"http", "https"} or self.target.username or self.target.password:
            raise ValueError("A credential-free HTTP(S) experiment URL is required")
        self.local = threading.local()

    def close(self):
        connection = getattr(self.local, "connection", None)
        if connection is not None:
            connection.close()
        self.local.connection = None

    def call(self, method, path, body=None, admin=False):
        key = "DURABLEFLOW_ADMIN_TOKEN" if admin else "DURABLEFLOW_OPERATOR_TOKEN"
        data = None if body is None else json.dumps(body, separators=(",", ":")).encode()
        connection = getattr(self.local, "connection", None)
        if connection is None or time.monotonic() - getattr(self.local, "used", 0) > 3:
            self.close()
            factory = http.client.HTTPSConnection if self.target.scheme == "https" else http.client.HTTPConnection
            connection = factory(self.target.hostname, self.target.port, timeout=10)
            self.local.connection = connection
        try:
            connection.request(method, self.target.path.rstrip("/") + path, body=data,
                               headers={"Content-Type": "application/json", "Authorization": "Bearer " + self.tokens[key]})
            response = connection.getresponse()
            raw = response.read()
            self.local.used = time.monotonic()
            if response.status >= 400:
                raise urllib.error.HTTPError(self.base + path, response.status, response.reason, response.headers, io.BytesIO(raw))
            return json.loads(raw)
        except (OSError, http.client.HTTPException) as error:
            self.close()
            raise urllib.error.URLError(error) from error

    def define(self, tasks, name=None):
        return self.call("POST", "/api/v1/workflow-definitions", {"name": name or "experiment-" + uuid.uuid4().hex, "tasks": tasks})

    def submit(self, definition, payload=None, key=None):
        return self.call("POST", "/api/v1/workflow-executions", {"definitionId": definition["definition_id"], "version": definition["version"], "idempotencyKey": key or uuid.uuid4().hex, "input": payload or {}})

    def execution(self, execution):
        return self.call("GET", "/api/v1/workflow-executions/" + str(execution))

    def tasks(self, execution):
        return self.call("GET", "/api/v1/workflow-executions/" + str(execution) + "/tasks?limit=100")

    def attempts(self, task):
        return self.call("GET", "/api/v1/task-executions/" + str(task) + "/attempts?limit=100")

    def terminal(self, execution, seconds=90):
        return wait_for(lambda: (value if (value := self.execution(execution))["status"] in TERMINAL else None), seconds, "workflow terminal status")


class Stack:
    def __init__(self, kind):
        if kind not in {"faults", "benchmark"}:
            raise ValueError("Experiments must use an owned disposable project")
        self.kind = kind
        self.project = "durableflow-" + kind
        self.prefix = ["docker", "compose", "-p", self.project, "-f", "docker-compose.yml", "-f", "ops/experiments.compose.yml"]
        if kind == "faults":
            self.prefix += ["-f", "fault-tests/compose.yml"]

    def compose(self, *args, timeout=240):
        return command(self.prefix + list(args), timeout)

    def up(self, workers=3):
        self.compose("up", "-d", "--no-build", "--wait", "--wait-timeout", "180", "--scale", "worker=" + str(workers))

    def down(self):
        self.compose("down", "--volumes", "--remove-orphans")

    def ids(self, service):
        return self.compose("ps", "-q", service).splitlines()

    def scale(self, workers):
        self.compose("up", "-d", "--no-deps", "--no-recreate", "--scale", "worker=" + str(workers), "worker")
        wait_for(lambda: len(self.ids("worker")) == workers and all(command(["docker", "inspect", "--format", "{{.State.Health.Status}}", item]) == "healthy" for item in self.ids("worker")), 90, "healthy worker count")

    def sql(self, query):
        return self.compose("exec", "-T", "postgres", "sh", "-c", 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -At -c "$1"', "sh", query)

    def owner_container(self, worker):
        worker = str(uuid.UUID(str(worker)))
        instance = self.sql("SELECT metadata->>'instance' FROM workers WHERE id='" + worker + "'")
        for container in self.ids("worker"):
            if command(["docker", "inspect", "--format", "{{.Config.Hostname}}", container]) == instance:
                return container
        raise AssertionError("No live container matches the lease owner")

    def leader(self):
        address = self.sql("SELECT host(a.client_addr) FROM pg_locks l JOIN pg_stat_activity a ON a.pid=l.pid WHERE l.locktype='advisory' AND l.objid=741938251 AND l.granted")
        for container in self.ids("scheduler"):
            info = json.loads(command(["docker", "inspect", container]))[0]
            if address in [network["IPAddress"] for network in info["NetworkSettings"]["Networks"].values()]:
                return container
        return None


def task_state(client, execution, wanted):
    def find():
        return next((task for task in client.tasks(execution) if task["status"] == wanted), None)
    return wait_for(find, 90, "task status " + wanted)


def recovery_probe(stack, client, mode="kill"):
    definition = client.define([{"id": "probe", "type": "delay", "maxAttempts": 3, "timeoutSeconds": 60}])
    execution = client.submit(definition, {"milliseconds": 5000})["id"]
    task = task_state(client, execution, "RUNNING")
    container = stack.owner_container(task["lease_owner"])
    began = time.monotonic()
    paused = False
    disconnected = False
    network = None
    try:
        if mode == "kill":
            command(["docker", "kill", "--signal", "KILL", container])
        elif mode == "pause":
            command(["docker", "pause", container]); paused = True
        elif mode == "network":
            info = json.loads(command(["docker", "inspect", container]))[0]
            network = next(iter(info["NetworkSettings"]["Networks"]))
            command(["docker", "network", "disconnect", network, container]); disconnected = True
        else:
            raise ValueError("Invalid recovery injection")
        wait_for(lambda: next((t for t in client.tasks(execution) if t["attempt"] > task["attempt"] and t["status"] in {"RUNNING", "SUCCEEDED"}), None), 90, "replacement execution attempt")
        lease_recovery_seconds = time.monotonic() - began
        final = client.terminal(execution)
        recovery_seconds = time.monotonic() - began
        assert final["status"] == "SUCCEEDED", final["status"]
        current = client.tasks(execution)[0]
        attempts = client.attempts(current["id"])
        assert current["attempt"] >= 2, "Failure did not force a new attempt"
        assert len([a for a in attempts if a["status"] in {"LEASED", "RUNNING"}]) <= 1
        stale_status = None
        if paused:
            command(["docker", "unpause", container]); paused = False
            def stale_report():
                for line in command(["docker", "logs", "--tail", "50", container]).splitlines():
                    try:
                        event = json.loads(line)
                    except json.JSONDecodeError:
                        continue
                    if event.get("task_id") == current["id"] and event.get("status") in {"STALE", "ABANDONED"}:
                        return event["status"]
                return None
            stale_status = wait_for(stale_report, 20, "expired owner result rejection")
            assert client.tasks(execution)[0]["version"] == current["version"]
        return {"lease_recovery_seconds": lease_recovery_seconds, "expired_owner_status": stale_status, "workflow_id": execution, "task_id": current["id"], "status": final["status"], "attempts": attempts, "recovery_seconds": recovery_seconds, "injection": mode}
    finally:
        if paused:
            command(["docker", "unpause", container], check=False)
        if disconnected:
            command(["docker", "network", "connect", network, container], check=False)
        stack.scale(3)


def machine(stack):
    def optional(args):
        try:
            return command(args, 20)
        except (RuntimeError, FileNotFoundError):
            return "unavailable"
    engine = json.loads(command(["docker", "info", "--format", "{{json .}}"] ))
    project = ET.parse(ROOT / "pom.xml").getroot()
    source_hash = hashlib.sha256()
    for path in [ROOT / "pom.xml", *sorted(ROOT.glob("durableflow-*/pom.xml")), *sorted(ROOT.glob("durableflow-*/src/main/**/*"))]:
        if path.is_file():
            source_hash.update(str(path.relative_to(ROOT)).encode()); source_hash.update(path.read_bytes())
    return {"recorded_at": utc(), "os": platform.system(), "os_release": platform.release(), "architecture": platform.machine(), "host_cpu": optional(["sysctl", "-n", "machdep.cpu.brand_string"]), "host_logical_cpus": optional(["sysctl", "-n", "hw.ncpu"]), "host_memory_bytes": optional(["sysctl", "-n", "hw.memsize"]), "docker_cpus": engine["NCPU"], "docker_memory_bytes": engine["MemTotal"], "docker_server_version": engine["ServerVersion"], "java": stack.compose("exec", "-T", "api", "java", "-version") or optional(["docker", "exec", stack.ids("api")[0], "java", "--version"]), "postgresql": stack.sql("SHOW server_version"), "project_version": project.find("{http://maven.apache.org/POM/4.0.0}version").text, "git_commit": optional(["git", "rev-parse", "HEAD"]), "git_dirty": bool(command(["git", "status", "--porcelain"])), "engine_source_sha256": source_hash.hexdigest(), "image_ids": {role: command(["docker", "inspect", "--format", "{{.Image}}", stack.ids(role)[0]]) for role in ("api", "scheduler", "worker", "postgres")}, "other_local_containers_running": len(command(["docker", "ps", "-q"]).splitlines()) - len(stack.compose("ps", "-q").splitlines())}
