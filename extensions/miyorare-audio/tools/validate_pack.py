#!/usr/bin/env python3
import json
import sys
from pathlib import Path

ALLOWED_AUTH = {"none", "optional", "required"}
ALLOWED_CAPABILITIES = {
    "catalog",
    "search",
    "detail",
    "playback",
    "download",
    "subtitles",
}


def fail(message: str) -> None:
    raise SystemExit(message)


def validate(path: Path) -> None:
    data = json.loads(path.read_text(encoding="utf-8"))
    if data.get("schemaVersion") != 1:
        fail("unsupported audio pack schemaVersion")
    if data.get("packId") != "miyorare-audio":
        fail("unexpected audio pack id")
    if data.get("mediaType") != "audio":
        fail("audio pack mediaType must be audio")

    consumer = data.get("consumer")
    if not isinstance(consumer, dict):
        fail("consumer object is required")
    if consumer.get("repository") != "Noirero/Hiraukan":
        fail("audio pack consumer must be Noirero/Hiraukan")
    if consumer.get("extensionSchemaVersion") != 1:
        fail("unsupported Hiraukan extension schema version")

    extensions = data.get("extensions")
    if not isinstance(extensions, list) or not extensions:
        fail("audio pack must contain at least one extension")

    seen: set[str] = set()
    for item in extensions:
        if not isinstance(item, dict):
            fail("extension entries must be objects")
        extension_id = item.get("id")
        if not isinstance(extension_id, str) or not extension_id:
            fail("extension id is required")
        if extension_id in seen:
            fail(f"duplicate extension id: {extension_id}")
        seen.add(extension_id)
        if item.get("type") != "audio":
            fail(f"{extension_id}: type must be audio")
        if item.get("auth") not in ALLOWED_AUTH:
            fail(f"{extension_id}: unsupported auth mode")
        capabilities = item.get("capabilities")
        if not isinstance(capabilities, list) or not capabilities:
            fail(f"{extension_id}: capabilities are required")
        unknown = set(capabilities) - ALLOWED_CAPABILITIES
        if unknown:
            fail(f"{extension_id}: unsupported capabilities: {sorted(unknown)}")
        delivery = item.get("delivery")
        if not isinstance(delivery, dict):
            fail(f"{extension_id}: delivery is required")
        if delivery.get("kind") != "builtin":
            fail(f"{extension_id}: schema v1 only allows builtin delivery")
        if delivery.get("runtimeId") != extension_id:
            fail(f"{extension_id}: runtimeId must equal extension id")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        fail("usage: validate_pack.py <pack.json>")
    validate(Path(sys.argv[1]))
