#!/usr/bin/env python3
import argparse, json, statistics
from datetime import datetime
from pathlib import Path

TRACKED = {"CI Fast", "CI Deep", "Android Runtime", "Beta Build", "Beta to Main Release Gate"}

def summarize(runs):
    grouped={}
    for run in runs:
        name=run.get("name")
        if name not in TRACKED or run.get("status") != "completed":
            continue
        created=run.get("run_started_at") or run.get("created_at")
        updated=run.get("updated_at")
        if not created or not updated:
            continue
        start=datetime.fromisoformat(created.replace("Z","+00:00"))
        end=datetime.fromisoformat(updated.replace("Z","+00:00"))
        seconds=max(0.0,(end-start).total_seconds())
        grouped.setdefault(name,[]).append((seconds,run.get("conclusion")))
    workflows={}
    recurring_failures=[]
    flaky_candidates=[]
    for name, values in sorted(grouped.items()):
        durations=[v[0] for v in values]
        conclusions=[v[1] for v in values]
        failures=sum(1 for c in conclusions if c not in ("success","skipped","neutral"))
        successes=sum(1 for c in conclusions if c == "success")
        workflows[name]={
            "samples":len(values),
            "median_seconds":round(statistics.median(durations),1),
            "failure_count":failures,
            "failure_rate":round(failures/len(values),4),
        }
        if failures:
            recurring_failures.append({"workflow":name,"failures":failures})
        if failures and successes:
            flaky_candidates.append(name)
    recurring_failures.sort(key=lambda x:(-x["failures"],x["workflow"]))
    return {
        "workflows": workflows,
        "top_recurring_failures": recurring_failures,
        "flaky_workflow_candidates": flaky_candidates,
        "cache_hit_miss": "not observable from workflow-run metadata; inspect Gradle action evidence when diagnosing cache regressions",
    }

def markdown(summary):
    lines=["# Miyorare CI metrics","","| Workflow | Samples | Median | Failures | Failure rate |","|---|---:|---:|---:|---:|"]
    for name,v in summary["workflows"].items():
        lines.append(f"| {name} | {v['samples']} | {v['median_seconds']}s | {v['failure_count']} | {v['failure_rate']:.1%} |")
    lines += ["","## Top recurring failures"]
    if summary["top_recurring_failures"]:
        lines += [f"- {x['workflow']}: {x['failures']}" for x in summary["top_recurring_failures"][:10]]
    else:
        lines.append("- None in sampled runs.")
    lines += ["","## Flaky candidates"]
    if summary["flaky_workflow_candidates"]:
        lines += [f"- {name}" for name in summary["flaky_workflow_candidates"]]
    else:
        lines.append("- None detected at workflow level in sampled runs.")
    lines += ["","## Cache visibility",f"- {summary['cache_hit_miss']}"]
    return "\n".join(lines)+"\n"

def main():
    p=argparse.ArgumentParser()
    p.add_argument("--input",type=Path,required=True)
    p.add_argument("--json-output",type=Path,required=True)
    p.add_argument("--md-output",type=Path,required=True)
    a=p.parse_args()
    payload=json.loads(a.input.read_text(encoding="utf-8"))
    result=summarize(payload.get("workflow_runs",[]))
    a.json_output.write_text(json.dumps(result,indent=2,sort_keys=True)+"\n",encoding="utf-8")
    a.md_output.write_text(markdown(result),encoding="utf-8")
if __name__=="__main__": main()
