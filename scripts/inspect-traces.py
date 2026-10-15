#!/usr/bin/env python3
"""Print trace IDs and service/span names, without payloads or credentials."""
import json
import sys
from collections import defaultdict

traces = defaultdict(set)
with open(sys.argv[1]) as source:
    for line in source:
        if not line.strip():
            continue
        for resource in json.loads(line).get("resourceSpans", []):
            attributes = resource.get("resource", {}).get("attributes", [])
            service = next((a["value"].get("stringValue", "") for a in attributes
                            if a["key"] == "service.name"), "unknown")
            for scope in resource.get("scopeSpans", []):
                for span in scope.get("spans", []):
                    traces[span["traceId"]].add((service, span["name"]))
for trace_id, spans in traces.items():
    print(trace_id, ", ".join(service + ":" + name for service, name in sorted(spans)))
