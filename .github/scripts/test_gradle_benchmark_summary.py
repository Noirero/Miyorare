#!/usr/bin/env python3
import json, tempfile
from pathlib import Path
from gradle_benchmark_summary import build_summary

with tempfile.TemporaryDirectory() as td:
    p=Path(td)
    (p/"cold-unit.resource.json").write_text('{"elapsed_seconds":12.5,"max_rss_kb":1234}',encoding="utf-8")
    out=build_summary(p,"4w-6g",4,"6g")
    assert out["workers"] == 4
    assert out["heap"] == "6g"
    assert out["measurements"]["cold-unit"]["elapsed_seconds"] == 12.5
print("gradle benchmark summary tests passed")
