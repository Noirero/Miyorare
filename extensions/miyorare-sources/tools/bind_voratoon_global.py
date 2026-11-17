"""Bind the checked local VoraToon JAR into a logical Global pack."""

import hashlib
import shutil


def bind_voratoon_global(logical, candidate_dir, output):
    names = logical["sourceNames"]
    if (logical["packId"] != "miyorare-global" or len(logical["shards"]) != 1
            or logical["shards"][0]["provider"] != "GEKKOUSHI"
            or len(names) != len(set(names)) or "VORATOON" in names):
        raise SystemExit("Unexpected or duplicate VoraToon ownership")
    candidate = "miyorare-global-voratoon-candidate.jar"
    source = candidate_dir / candidate
    digest = hashlib.sha256(source.read_bytes()).hexdigest()
    if (candidate_dir / (candidate + ".sha256")).read_text(encoding="utf-8").split() != [digest, candidate]:
        raise SystemExit("VoraToon candidate checksum mismatch")
    asset = "miyorare-global-first-party.jar"
    target = output / asset
    shutil.copyfile(source, target)
    (output / (asset + ".sha256")).write_text(f"{digest}  {asset}\n", encoding="utf-8")
    logical["sourceNames"] = sorted([*names, "VORATOON"])
    logical["sourceCount"] = len(logical["sourceNames"])
    logical["deduplication"] = {
        "priority": ["MIYORARE", "GEKKOUSHI"],
        "runtimeKeyBased": True,
        "globalSingleOwner": True,
    }
    logical["shards"].append({
        "provider": "MIYORARE",
        "pluginId": "miyorare-global-first-party",
        "displayName": "Miyorare-Global / First-party",
        "assetName": asset,
        "sourceCount": 1,
        "sourceNames": ["VORATOON"],
        "upstreams": [],
        "sha256": digest,
        "size": target.stat().st_size,
    })
