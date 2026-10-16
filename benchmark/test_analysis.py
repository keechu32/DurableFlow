import unittest
from analysis import percentile, summary, task_sample


class MeasurementTests(unittest.TestCase):
    def test_percentiles_preserve_tail_outlier(self):
        samples = list(range(1, 100)) + [100000]
        self.assertEqual(percentile(samples, 50), 50)
        self.assertEqual(percentile(samples, 99), 99)
        self.assertEqual(percentile(samples, 100), 100000)
        with self.assertRaises(ValueError):
            percentile([], 50)

    def test_timestamp_measurement_and_rate_denominators(self):
        row = {"id": "task", "status": "SUCCEEDED", "attempt": 2,
               "available_at": "2026-01-01T00:00:00Z", "started_at": "2026-01-01T00:00:01Z", "completed_at": "2026-01-01T00:00:01.250Z"}
        sample = task_sample(row)
        self.assertEqual(sample["scheduling_latency_ms"], 1000)
        self.assertEqual(sample["execution_overhead_ms"], 250)
        measured = summary([sample], 0.5, 2)
        self.assertEqual(measured["workflow_submissions_per_second"], 2)
        self.assertEqual(measured["task_completions_per_second"], 4)
        self.assertEqual(measured["end_to_end_tasks_per_second"], .5)
        self.assertEqual(measured["retried_task_rate"], 1)

    def test_failed_or_impossible_timings_are_rejected(self):
        for status, available in (("FAILED", "2026-01-01T00:00:00Z"), ("SUCCEEDED", "2026-01-01T00:00:03Z")):
            with self.assertRaises(ValueError):
                task_sample({"id": "task", "status": status, "attempt": 1, "available_at": available,
                             "started_at": "2026-01-01T00:00:01Z", "completed_at": "2026-01-01T00:00:02Z"})

class FailureArtifactTests(unittest.TestCase):
    def test_failed_submission_is_retained_next_to_successful_response(self):
        import json
        from pathlib import Path
        import tempfile
        from run import dispatch

        class Client:
            def __init__(self):
                self.calls = 0

            def submit(self, definition, payload):
                self.calls += 1
                if self.calls == 2:
                    raise RuntimeError("injected transport failure")
                return {"id": str(self.calls), "status": "PENDING"}

        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "requests.jsonl"
            rows = dispatch(Client(), {}, {}, 3, 1, path)
            persisted = [json.loads(line) for line in path.read_text().splitlines()]
            self.assertEqual(len(persisted), 3)
            self.assertEqual(sum(row["ok"] for row in persisted), 2)
            self.assertEqual(persisted[1]["error_type"], "RuntimeError")
            self.assertEqual(rows, persisted)


class ClientTransportTests(unittest.TestCase):
    def test_status_polling_reuses_one_connection(self):
        from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
        import threading
        from scripts.experiment import Client
        ports = []

        class Handler(BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"

            def do_GET(self):
                ports.append(self.client_address[1])
                body = b'{"ok":true}'
                self.send_response(200)
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, *args):
                pass

        server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        thread = threading.Thread(target=lambda: server.serve_forever(poll_interval=.01), daemon=True)
        thread.start()
        client = Client("http://127.0.0.1:" + str(server.server_port), {"DURABLEFLOW_OPERATOR_TOKEN": "test-only"})
        try:
            self.assertTrue(client.call("GET", "/status")["ok"])
            self.assertTrue(client.call("GET", "/status")["ok"])
            self.assertEqual(len(ports), 2)
            self.assertEqual(len(set(ports)), 1)
        finally:
            client.close_all()
            server.shutdown()
            server.server_close()
            thread.join(2)

    def test_dispatch_reuses_connections_across_warmup_batches(self):
        from concurrent.futures import ThreadPoolExecutor
        from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
        import tempfile
        import threading
        from pathlib import Path
        from run import dispatch
        from scripts.experiment import Client

        ports = []

        class Handler(BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"

            def do_POST(self):
                self.rfile.read(int(self.headers["Content-Length"]))
                ports.append(self.client_address[1])
                body = b'{"id":"workflow","status":"PENDING"}'
                self.send_response(200)
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, *args):
                pass

        server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        thread = threading.Thread(target=lambda: server.serve_forever(poll_interval=.01), daemon=True)
        thread.start()
        client = Client("http://127.0.0.1:" + str(server.server_port), {"DURABLEFLOW_OPERATOR_TOKEN": "test-only"})
        try:
            with tempfile.TemporaryDirectory() as directory, ThreadPoolExecutor(max_workers=1) as pool:
                path = Path(directory) / "warmup.jsonl"
                definition = {"definition_id": "definition", "version": 1}
                dispatch(client, definition, {}, 1, 1, path, pool)
                dispatch(client, definition, {}, 1, 1, path, pool)
            self.assertEqual(len(ports), 2)
            self.assertEqual(len(set(ports)), 1)
        finally:
            client.close_all()
            server.shutdown()
            server.server_close()
            thread.join(2)


if __name__ == "__main__":
    unittest.main()
