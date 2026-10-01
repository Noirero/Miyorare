#!/usr/bin/env python3
import argparse, json
from pathlib import Path

NAMES = ("cold-unit", "warm-unit", "cold-preview", "warm-preview", "compile")

def build_summary(input_dir: Path, label: str, workers: int, heap: str):
    measurements = {}
    for name in NAMES:
        path = input_dir / f"{name}.resource.json"
        if not path.exists():
            continue
        measurements[name] = json.loads(path.read_text(encoding="utf-8"))
    return {"label": label, "workers": workers, "heap": heap, "measurements": measurements}

def main():
    p=argparse.ArgumentParser()
    p.add_argument("--input", type=Path, required=True)
    p.add_argument("--label", required=True)
    p.add_argument("--workers", type=int, required=True)
    p.add_argument("--heap", required=True)
    p.add_argument("--output", type=Path, required=True)
    a=p.parse_args()
    result=build_summary(a.input,a.label,a.workers,a.heap)
    a.output.write_text(json.dumps(result,indent=2,sort_keys=True)+"\n",encoding="utf-8")
    print(json.dumps(result,indent=2,sort_keys=True))
if __name__=="__main__": main()
