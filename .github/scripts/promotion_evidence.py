#!/usr/bin/env python3
import json
import sys

REQUIRED = ("verify-identity", "Fast", "Deep")

def validate_check_runs(payload):
    conclusions = {
        run.get("name"): run.get("conclusion")
        for run in payload.get("check_runs", [])
        if run.get("name") in REQUIRED
    }
    missing = [name for name in REQUIRED if conclusions.get(name) != "success"]
    if missing:
        detail = ", ".join(f"{name}={conclusions.get(name, 'missing')}" for name in missing)
        raise ValueError("Promotion evidence is incomplete: " + detail)

if __name__ == "__main__":
    try:
        validate_check_runs(json.load(sys.stdin))
    except (ValueError, json.JSONDecodeError) as error:
        raise SystemExit(str(error))
    print("Verified required beta checks on promotion evidence SHA")
