#!/usr/bin/env python3
from ci_metrics import summarize, markdown
runs=[
 {"name":"CI Fast","status":"completed","conclusion":"success","run_started_at":"2026-01-01T00:00:00Z","updated_at":"2026-01-01T00:01:00Z"},
 {"name":"CI Fast","status":"completed","conclusion":"failure","run_started_at":"2026-01-02T00:00:00Z","updated_at":"2026-01-02T00:03:00Z"},
 {"name":"Other","status":"completed","conclusion":"success","run_started_at":"2026-01-01T00:00:00Z","updated_at":"2026-01-01T00:10:00Z"},
]
s=summarize(runs)
assert s["workflows"]["CI Fast"]["samples"]==2
assert s["workflows"]["CI Fast"]["median_seconds"]==120.0
assert s["workflows"]["CI Fast"]["failure_rate"]==0.5
assert s["top_recurring_failures"][0]=={"workflow":"CI Fast","failures":1}
assert s["flaky_workflow_candidates"]==["CI Fast"]
assert "CI Fast" in markdown(s)
print("ci metrics tests passed")
