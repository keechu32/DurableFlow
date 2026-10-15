"""Explicit nearest-rank and database-clock measurement definitions."""
from datetime import datetime
import math


def percentile(values, percent):
    if not values or not 0 <= percent <= 100:
        raise ValueError("Nonempty samples and percentile in [0,100] are required")
    values = sorted(values)
    return values[max(0, math.ceil(percent / 100 * len(values)) - 1)]


def timestamp(value):
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if parsed.tzinfo is None:
        raise ValueError("UTC/offset timestamp required")
    return parsed.timestamp()


def task_sample(task):
    if task["status"] != "SUCCEEDED":
        raise ValueError("Unsuccessful task cannot be a successful timing sample")
    started, completed, available = (timestamp(task[name]) for name in ("started_at", "completed_at", "available_at"))
    if completed < started or started < available:
        raise ValueError("Invalid persisted timestamp order")
    return {"task_id": task["id"], "status": task["status"], "attempt": task["attempt"],
            "available_at": task["available_at"], "started_at": task["started_at"], "completed_at": task["completed_at"],
            "scheduling_latency_ms": (started - available) * 1000,
            "execution_overhead_ms": (completed - started) * 1000}


def summary(samples, submission_seconds, elapsed_seconds):
    if not samples or submission_seconds <= 0 or elapsed_seconds <= 0:
        raise ValueError("Positive measurement windows required")
    window = max(timestamp(s["completed_at"]) for s in samples) - min(timestamp(s["started_at"]) for s in samples)
    if window <= 0:
        raise ValueError("Completion window must be positive")
    result = {"workflows": len(samples), "submission_seconds": submission_seconds, "elapsed_seconds": elapsed_seconds,
              "workflow_submissions_per_second": len(samples) / submission_seconds,
              "task_completions_per_second": len(samples) / window,
              "end_to_end_tasks_per_second": len(samples) / elapsed_seconds,
              "additional_claim_attempts": sum(max(0, s["attempt"] - 1) for s in samples),
              "retried_task_rate": sum(s["attempt"] > 1 for s in samples) / len(samples),
              "dead_letter_rate": sum(s["status"] == "DEAD_LETTERED" for s in samples) / len(samples)}
    for name in ("scheduling_latency_ms", "execution_overhead_ms"):
        result[name] = {"p" + str(p): percentile([s[name] for s in samples], p) for p in (50, 95, 99)}
    return result
