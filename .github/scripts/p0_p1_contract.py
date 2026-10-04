#!/usr/bin/env python3
"""Verify audited P0/P1 execution ownership locally; never skip on contract drift."""

from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]

# Exact audited configurations, including events, filters, properties, env,
# candidate checkout, toolchain, devices, policy and evidence. See the Stage 6
# matrix before updating a pin; even harmless drift requires an explicit audit.
AUDITED_FILES = {
    ".github/workflows/ci-deep.yml": "0ffceba96f7aa7276d5ba11940ea9501077322028656f2ced07a4f8c7881355c",
    ".github/scripts/ci_deep_paths.py": "1de72ee76ca96630aff882223ad75dea2a4a601aca7bed230b8a2102601e5bc7",
    ".github/scripts/test_ci_deep_paths.py": "15768c9aa2af127aabbdb731384ceb965c473d423f03dd79cd4dfde13e4fe362",
    ".github/workflows/android-runtime.yml": "25776987e55cf6212a122a792868ef953dbe13343ea7d9914699b3ced064e2e3",
    ".github/scripts/android_runtime_paths.py": "3d938f2c572b78dc4ae7b041d7bbd539c0da3439b4b2bb0ad93a13efef4b4d6c",
    ".github/scripts/test_android_runtime_paths.py": "fb649cc8982ac37c439f9ba09b83a7627c1edf9cef61142d465bb3d0e78df934",
    ".github/workflows/p0-p1-acceptance.yml": "e032d8cb9dc28a43d625b2ed59384288daa434bfd80e3d77717ced70b5f5e57f",
}

JVM_CLASSES = (
    "org.koitharu.kotatsu.local.data.output.LocalArchiveFinalizationRegressionTest",
    "org.koitharu.kotatsu.local.DownloadDeletionRegressionTest",
    "org.koitharu.kotatsu.settings.sources.ExtensionInstallerMethodDialogRegressionTest",
    "org.koitharu.kotatsu.local.data.LocalStackSafetyRegressionTest",
    "org.koitharu.kotatsu.favourites.ui.categories.select.FavouriteCategoryBatchingRegressionTest",
    "org.koitharu.kotatsu.performance.RuntimeLagHardeningRegressionTest",
)
RUNTIME_CLASSES = (
    "org.koitharu.kotatsu.core.db.ChapterPersistenceRegressionTest",
    "org.koitharu.kotatsu.alternatives.domain.ProfileMigrationPersistenceRegressionTest",
    "org.koitharu.kotatsu.backup.local.LocalBackupIdentityTest",
    "org.koitharu.kotatsu.settings.backup.AppBackupAgentTest",
)
LOCAL_LIBRARY_CLASS = "org.koitharu.kotatsu.local.library.SmartLocalLibraryRuntimeTest"
FAVOURITES_CLASS = "org.koitharu.kotatsu.favourites.ui.FavouritesGoldenVisualTest"


class ContractError(ValueError):
    """A failed validation, never an instruction to skip execution."""


def verify_contract(root: Path = ROOT) -> None:
    for relative, expected in AUDITED_FILES.items():
        actual = hashlib.sha256((root / relative).read_bytes()).hexdigest()
        if actual != expected:
            raise ContractError(f"Ownership contract changed: {relative}; re-audit coverage before updating its fingerprint")
    for source_set, classes in (("test", JVM_CLASSES), ("androidTest", (*RUNTIME_CLASSES, LOCAL_LIBRARY_CLASS, FAVOURITES_CLASS))):
        for class_name in classes:
            source = root / f"app/src/{source_set}/kotlin/{class_name.replace('.', '/')}.kt"
            text = source.read_text(encoding="utf-8")
            package, name = class_name.rsplit(".", 1)
            if not re.search(rf"^package {re.escape(package)}\s*$", text, re.MULTILINE):
                raise ContractError(f"Missing audited package: {source}")
            if not re.search(rf"\bclass {name}\b", text) or "@Test" not in text or "@Ignore" in text:
                raise ContractError(f"Missing/disabled audited acceptance class: {source}")


def load_policy(root: Path, name: str):
    spec = importlib.util.spec_from_file_location(name, root / f".github/scripts/{name}.py")
    if spec is None or spec.loader is None:
        raise ContractError(f"Cannot load audited policy: {name}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def ownership(paths: list[str], labels: str = "", event: str = "pull_request", root: Path = ROOT) -> dict:
    # Pins are checked before policy evaluation. This is a report of existing
    # owners, not a new heavy-work router or proof that another run has passed.
    verify_contract(root)
    if event not in ("pull_request", "workflow_dispatch"):
        raise ContractError(f"Unsupported acceptance event: {event}")
    deep = load_policy(root, "ci_deep_paths")
    runtime = load_policy(root, "android_runtime_paths")
    runtime_required, reason = runtime.policy_requires_android_runtime(paths, runtime.parse_labels(labels))
    return {
        "ci_deep_required": event == "workflow_dispatch" or deep.requires_deep(paths),
        "android_runtime_required": event == "workflow_dispatch" or runtime_required,
        "runtime_policy": "manual Android Runtime validates full suite" if event == "workflow_dispatch" else reason,
        "p0_ownership_validation": True,
        "p0_jvm_execution": False,
        "p0_favourites_capture": event == "workflow_dispatch",
    }


def git(root: Path, *args: str) -> bytes:
    return subprocess.run(["git", *args], cwd=root, check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE).stdout


def candidate_paths(root: Path, event: str, base: str, head: str) -> list[str]:
    if not re.fullmatch(r"[0-9a-f]{40}", head):
        raise ContractError("Missing/invalid exact candidate SHA")
    if git(root, "rev-parse", "HEAD").decode().strip() != head:
        raise ContractError("Checkout differs from exact candidate SHA")
    if event == "workflow_dispatch":
        return []
    if event != "pull_request" or not re.fullmatch(r"[0-9a-f]{40}", base):
        raise ContractError("Missing/invalid PR base or unsupported event")
    git(root, "rev-parse", "--verify", f"{base}^{{commit}}")
    # Both sides of renames are relevant, and every commit in the PR matters.
    raw = git(root, "diff", "--no-renames", "--name-only", "-z", base, head)
    return [path.decode("utf-8", errors="strict") for path in raw.split(b"\0") if path]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--event", required=True)
    parser.add_argument("--base", default="")
    parser.add_argument("--head", required=True)
    parser.add_argument("--labels", default="")
    args = parser.parse_args()
    try:
        paths = candidate_paths(ROOT, args.event, args.base, args.head)
        report = ownership(paths, args.labels, args.event)
        print(json.dumps({"candidate": args.head, "base": args.base, "changed_paths": paths, **report}, indent=2))
    except (ContractError, OSError, UnicodeError, subprocess.CalledProcessError, ImportError, SyntaxError) as error:
        print(f"P0/P1 ownership validation failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
