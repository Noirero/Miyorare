#!/usr/bin/env python3
from ci_metrics import summarize, markdown
runs=[
 {"name":"CI Fast","status":"completed","conclusion":"success","run_started_at":"2026-01-01T00:00:00Z","updated_at":"2026-01-01T00:01:00Z"},
 {"name":"CI Fast","status":"completed","conclusion":"failure","run_started_at":"2026-01-02T00:00:00Z","updated_at":"2026-01-02T00:03:00Z"},
 {"name":"Other","status":"completed","conclusion":"success","run_started_at":"2026-01-01T00:00:00Z","updated_at":"2026-01-01T00:10:00Z"},
]
s=summarize(runs)
assert s["CI Fast"]["samples"]==2
assert s["CI Fast"]["median_seconds"]==120.0
assert s["CI Fast"]["failure_rate"]==0.5
assert "CI Fast" in markdown(s)
print("ci metrics tests passed")
