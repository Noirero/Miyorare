#!/usr/bin/env python3
"""Trusted, opt-in packaging overlay for an exact Miyorare debug source commit."""
import argparse
import hashlib
import json
import re
import subprocess
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

PACKAGE = "org.noirero.miyorare.validation"
LABEL = "Miyorare Validation"
ANDROID = "http://schemas.android.com/apk/res/android"
TOOLS = "http://schemas.android.com/tools"
ET.register_namespace("android", ANDROID)
ET.register_namespace("tools", TOOLS)
A = "{" + ANDROID + "}"
T = "{" + TOOLS + "}"
BLOCKED_PERMISSIONS = {
    "android.permission.MANAGE_EXTERNAL_STORAGE",
    "android.permission.READ_EXTERNAL_STORAGE",
    "android.permission.WRITE_EXTERNAL_STORAGE",
    "android.permission.READ_MEDIA_IMAGES",
    "android.permission.READ_MEDIA_VIDEO",
    "android.permission.READ_MEDIA_AUDIO",
    "android.permission.READ_MEDIA_VISUAL_USER_SELECTED",
    "android.permission.ACCESS_MEDIA_LOCATION",
    "android.permission.MANAGE_MEDIA",
    "android.permission.REQUEST_INSTALL_PACKAGES",
    "android.permission.REQUEST_DELETE_PACKAGES",
}
SHA = re.compile(r"[0-9a-f]{40}")
VERSION = re.compile(r"[0-9]+(?:\.[0-9]+){2}(?:[-+][A-Za-z0-9.-]+)?")
KOTLIN = Path("app/src/main/kotlin/org/koitharu/kotatsu")


def require(condition, message):
    if not condition:
        raise ValueError(message)


def git(root, *args):
    return subprocess.check_output(["git", "-C", str(root), *args])


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def once(text, old, new):
    require(text.count(old) == 1, "Unsupported source anchor: " + old[:100])
    return text.replace(old, new, 1)


def allocate(run_number, slot):
    require(run_number > 0 and slot in (0, 1), "Invalid build sequence")
    code = 1_000_000 + 2 * run_number + slot
    require(code <= 2_100_000_000, "Android versionCode exhausted")
    return code


def edit_gradle(text, code, version):
    require("applicationId = 'org.noirero.miyorare'" in text, "Unexpected base identity")
    require("buildConfigField 'boolean', 'SELF_UPDATE_SUPPORTED', 'false'" in text,
            "Expected debug self-update disabled by default")
    require(VERSION.fullmatch(version), "Unsupported version name")
    text, count = re.subn(r"(?m)^(\s*)versionCode = \d+$", lambda m: m[1] + f"versionCode = {code}", text)
    require(count == 1, "Expected exactly one versionCode")
    text, count = re.subn(r"(?m)^(\s*)versionName = '[^']+'$", lambda m: m[1] + f"versionName = '{version}'", text)
    require(count == 1, "Expected exactly one versionName")
    return once(text,
        "applicationIdSuffix = globalValidation ? '.debug.globalvalidation' : '.debug'",
        """if (project.findProperty('MIYORARE_VALIDATION') != 'true' || globalValidation) {
                throw new GradleException('Validation overlay requires exclusive MIYORARE_VALIDATION=true')
            }
            applicationIdSuffix = '.validation'
            signingConfig = null""")


def allowed_filter(node):
    actions = {e.get(A + "name") for e in node.findall("action")}
    categories = {e.get(A + "name") for e in node.findall("category")}
    if node.findall("data"):
        return False
    return (actions == {"android.intent.action.MAIN"} and
            categories == {"android.intent.category.LAUNCHER"}) or (
        bool(actions) and all(x and x.startswith("${applicationId}.action.") for x in actions)
        and not categories)


def read_xml(path):
    return ET.parse(path, parser=ET.XMLParser(target=ET.TreeBuilder(insert_comments=True)))


def isolate_manifest(tree, debug):
    root = tree.getroot()
    for node in list(root):
        if node.tag in ("uses-permission", "uses-permission-sdk-23") and node.get(A + "name") in BLOCKED_PERMISSIONS:
            root.remove(node)
    app = root.find("application")
    require(app is not None, "Missing application")
    if debug:
        # Remove permissions contributed by lower-priority manifests too.
        for permission in sorted(BLOCKED_PERMISSIONS):
            for tag in ("uses-permission", "uses-permission-sdk-23"):
                ET.SubElement(root, tag, {A + "name": permission, T + "node": "remove"})
    else:
        app.set(A + "label", LABEL)
        app.set(A + "allowBackup", "false")
        app.set(A + "requestLegacyExternalStorage", "false")
        for key in ("backupAgent", "dataExtractionRules", "fullBackupContent", "fullBackupOnly", "restoreAnyVersion"):
            app.attrib.pop(A + key, None)
        for provider in app.findall("provider"):
            if provider.get(A + "name") == "rikka.shizuku.ShizukuProvider":
                provider.set(A + "enabled", "false")
                provider.set(T + "replace", "android:enabled")
    for component in list(app):
        if component.tag not in ("activity", "activity-alias"):
            continue
        removed = False
        for intent in component.findall("intent-filter"):
            if not allowed_filter(intent):
                component.remove(intent)
                removed = True
        if removed and not component.findall("intent-filter"):
            component.set(A + "exported", "false")
    return tree


def own_storage(root):
    """Limit configured manga roots to the app's own directories in the validation overlay."""
    prefs = root / KOTLIN / "core/prefs/AppSettings.kt"
    store = root / KOTLIN / "download/domain/DownloadDestinationStore.kt"
    helper = root / KOTLIN / "core/prefs/ValidationStoragePolicy.kt"
    require(not helper.exists(), "Validation helper already exists")
    p = prefs.read_text()
    p = once(p, "class AppSettings @Inject constructor(@ApplicationContext context: Context) {",
             "class AppSettings @Inject constructor(@ApplicationContext private val context: Context) {")
    p = once(p, "return set.mapNotNullToSet { File(it).takeIfReadable() }",
             "return set.mapNotNullToSet { File(it).takeIfReadable()?.takeIf { file -> ValidationStoragePolicy.owns(context, file) } }")
    p = once(p, "val set = value.mapToSet { it.absolutePath }",
             "require(value.all { ValidationStoragePolicy.owns(context, it) }) { \"Validation requires app-owned storage\" }\n\t\t\tval set = value.mapToSet { it.absolutePath }")
    p = once(p, "set(value) = synchronized(downloadDestinationMutationLock) {\n\t\t\tprefs.edit {",
             "set(value) = synchronized(downloadDestinationMutationLock) {\n\t\t\trequire(value == null || ValidationStoragePolicy.owns(context, value)) { \"Validation requires app-owned storage\" }\n\t\t\tprefs.edit {")
    s = store.read_text()
    s = once(s, "import org.koitharu.kotatsu.core.prefs.AppSettings\n",
             "import org.koitharu.kotatsu.core.prefs.AppSettings\nimport org.koitharu.kotatsu.core.prefs.ValidationStoragePolicy\n")
    s = once(s, 'prefs.getString(AppSettings.KEY_LOCAL_STORAGE, null)?.let(::File)',
             'prefs.getString(AppSettings.KEY_LOCAL_STORAGE, null)?.let(::File)?.takeIf { ValidationStoragePolicy.owns(context, it) }')
    s = once(s, 'prefs.getString(KEY_PRIVATE_DOWNLOAD_ROOT, null)?.let(::File)',
             'prefs.getString(KEY_PRIVATE_DOWNLOAD_ROOT, null)?.let(::File)?.takeIf { ValidationStoragePolicy.owns(context, it) }')
    s = once(s, "fun setRoot(space: FavouriteSpace, root: File?) {",
             "fun setRoot(space: FavouriteSpace, root: File?) {\n\t\trequire(root == null || ValidationStoragePolicy.owns(context, root)) { \"Validation requires app-owned storage\" }")
    s = once(s, 'prefs.getStringSet(legacyKey(space), emptySet()).orEmpty().map(::File)',
             'prefs.getStringSet(legacyKey(space), emptySet()).orEmpty().map(::File).filter { ValidationStoragePolicy.owns(context, it) }')
    prefs.write_text(p)
    store.write_text(s)
    helper.write_text("""package org.koitharu.kotatsu.core.prefs

import android.content.Context
import java.io.File

/** Validation-only policy, never packaged in Main or Beta. */
internal object ValidationStoragePolicy {
    fun owns(context: Context, file: File): Boolean = runCatching {
        val path = file.canonicalFile.toPath()
        val roots = listOf(context.filesDir) + context.getExternalFilesDirs(null).filterNotNull()
        roots.any { path.startsWith(it.canonicalFile.toPath()) }
    }.getOrDefault(false)
}
""")
    return [prefs, store, helper]


def prepare(args):
    root = args.root.resolve()
    out = args.out.resolve()
    require(not out.is_relative_to(root), "Output must be outside candidate checkout")
    require(SHA.fullmatch(args.sha), "Invalid source SHA")
    require(git(root, "rev-parse", "HEAD").decode().strip() == args.sha, "Source HEAD mismatch")
    require(not git(root, "status", "--porcelain", "--untracked-files=all").strip(), "Candidate checkout is not clean")
    code = allocate(args.run_number, args.slot)
    gradle = root / "app/build.gradle"
    original = gradle.read_text()
    versions = re.findall(r"(?m)^\s*versionName = '([^']+)'$", original)
    require(len(versions) == 1, "Ambiguous versionName")
    version = args.version_name or versions[0]
    require(args.slot == 0 or not args.version_name, "Candidate versionName must be preserved")
    gradle.write_text(edit_gradle(original, code, version))
    paths = [gradle]
    for kind in ("main", "debug"):
        path = root / f"app/src/{kind}/AndroidManifest.xml"
        tree = isolate_manifest(read_xml(path), kind == "debug")
        tree.write(path, encoding="utf-8", xml_declaration=True)
        paths.append(path)
    label = root / "app/src/debug/res/values/strings.xml"
    label.write_text(once(label.read_text(), ">Miyorare Debug</string>", ">Miyorare Validation</string>"))
    paths.append(label)
    paths.extend(own_storage(root))
    # Explicitly record the generated helper in the patch without committing.
    git(root, "add", "-N", "--", *(str(p.relative_to(root)) for p in paths))
    out.mkdir(parents=True, exist_ok=False)
    patch = out / "overlay.patch"
    patch.write_bytes(git(root, "diff", "--binary", "HEAD", "--"))
    require(patch.stat().st_size > 0, "Empty overlay")
    metadata = {
        "schemaVersion": 1, "pr": args.pr, "sourceSha": args.sha,
        "workflowSha": args.workflow_sha, "runId": args.run_id, "runNumber": args.run_number,
        "runAttempt": args.run_attempt, "slot": args.slot, "applicationId": PACKAGE,
        "variant": "debug-validation", "versionCode": code, "versionName": version,
        "overlaySha256": digest(patch), "overlayFiles": [str(p.relative_to(root)) for p in paths],
    }
    (out / "build.json").write_text(json.dumps(metadata, indent=2, sort_keys=True) + "\n")
    print(f"Prepared {args.sha} slot={args.slot} version={version} code={code}")


def manifest_check(root, code, version):
    require(root.get("package") == PACKAGE, "Wrong APK applicationId")
    require(root.get(A + "versionCode") == str(code), "Wrong versionCode")
    require(root.get(A + "versionName") == version, "Wrong versionName")
    app = root.find("application")
    require(app is not None and app.get(A + "allowBackup") == "false", "Backup must be disabled")
    require(app.get(A + "label") == LABEL, "Wrong Validation label")
    require(not app.get(A + "backupAgent"), "Unexpected backup agent")
    require(app.get(A + "debuggable") == "true", "Expected debug build")
    require(app.get(A + "requestLegacyExternalStorage") != "true", "Legacy storage enabled")
    for node in root:
        if isinstance(node.tag, str) and node.tag.startswith("uses-permission"):
            require(node.get(A + "name") not in BLOCKED_PERMISSIONS, "Forbidden permission")
    launchers = []
    for node in app:
        if node.tag == "provider":
            authority = node.get(A + "authorities", "")
            require(all(a.startswith(PACKAGE + ".") for a in authority.split(";")), "Unexpected provider authority: " + authority)
            if node.get(A + "name") == "rikka.shizuku.ShizukuProvider":
                require(node.get(A + "enabled") == "false", "Shizuku must be disabled")
        if node.tag in ("activity", "activity-alias"):
            for intent in node.findall("intent-filter"):
                # Disabled GlobalValidationActivity is an existing debug-only component.
                if node.get(A + "enabled") == "false":
                    continue
                actions = {e.get(A + "name") for e in intent.findall("action")}
                categories = {e.get(A + "name") for e in intent.findall("category")}
                require(not intent.findall("data"), "External activity data filter remains")
                if actions == {"android.intent.action.MAIN"} and categories == {"android.intent.category.LAUNCHER"}:
                    launchers.append(node.get(A + "name"))
                else:
                    require(bool(actions) and all(x and x.startswith(PACKAGE + ".action.") for x in actions)
                            and not categories, "Unexpected activity intent filter")
    require(launchers == ["org.koitharu.kotatsu.main.ui.MainActivity"], "Expected one MainActivity launcher")


def verify(args):
    directory = args.directory
    metadata = json.loads((directory / "build.json").read_text())
    require(metadata["sourceSha"] == args.sha and metadata["workflowSha"] == args.workflow_sha, "Source/workflow mismatch")
    require(metadata["pr"] == args.pr and metadata["runId"] == args.run_id and metadata["runAttempt"] == args.run_attempt,
            "Run identity mismatch")
    require(metadata["runNumber"] == args.run_number and metadata["slot"] == args.slot, "Build sequence mismatch")
    require(metadata["applicationId"] == PACKAGE and metadata["variant"] == "debug-validation", "Wrong metadata identity")
    require(metadata["versionCode"] == allocate(args.run_number, args.slot), "Invalid allocated version")
    require(VERSION.fullmatch(metadata["versionName"]), "Invalid metadata version name")
    require(metadata["overlaySha256"] == digest(directory / "overlay.patch"), "Overlay digest mismatch")
    apk = directory / args.apk
    xml = subprocess.check_output([args.apkanalyzer, "manifest", "print", str(apk)])
    manifest_check(ET.fromstring(xml), metadata["versionCode"], metadata["versionName"])
    if args.expected_version:
        require(metadata["versionName"] == args.expected_version, "Unexpected source version")
    if not args.certificate:
        actual = digest(apk)
        if args.record_unsigned:
            metadata["unsignedApkSha256"] = actual
            (directory / "build.json").write_text(json.dumps(metadata, indent=2, sort_keys=True) + "\n")
        else:
            require(metadata["unsignedApkSha256"] == actual, "Unsigned APK digest mismatch")
    if args.certificate:
        output = subprocess.check_output([args.apksigner, "verify", "--verbose", "--print-certs", str(apk)], text=True)
        fingerprints = re.findall(r"(?m)^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]+)\s*$", output)
        expected = args.certificate.replace(":", "").lower()
        require(re.fullmatch(r"[0-9a-f]{64}", expected) and [f.lower() for f in fingerprints] == [expected],
                "Signing certificate mismatch")
        metadata["signingCertificateSha256"] = expected
        metadata["apkSha256"] = digest(apk)
        metadata["apkName"] = apk.name
        (directory / "build.json").write_text(json.dumps(metadata, indent=2, sort_keys=True) + "\n")
    print(f"Verified {apk.name} source={args.sha} code={metadata['versionCode']}")


class ContractTest(unittest.TestCase):
    def test_version_sequence(self):
        self.assertLess(allocate(1, 0), allocate(1, 1))
        self.assertLess(allocate(1, 1), allocate(2, 0))
        with self.assertRaises(ValueError):
            allocate(2_100_000_000, 1)

    def test_gradle_opt_in(self):
        source = """applicationId = 'org.noirero.miyorare'
buildConfigField 'boolean', 'SELF_UPDATE_SUPPORTED', 'false'
versionCode = 75
versionName = '1.2.0'
applicationIdSuffix = globalValidation ? '.debug.globalvalidation' : '.debug'
"""
        result = edit_gradle(source, 1000003, "1.2.0")
        self.assertIn("applicationIdSuffix = '.validation'", result)
        self.assertIn("signingConfig = null", result)
        self.assertIn("MIYORARE_VALIDATION", result)
        with self.assertRaises(ValueError):
            edit_gradle(source + source, 1000003, "1.2.0")

    def test_manifest_verification_rejects_wrong_identity_and_permission(self):
        root = ET.fromstring(f'''<manifest xmlns:android="{ANDROID}" package="{PACKAGE}"
            android:versionCode="1000003" android:versionName="1.2.0">
            <application android:label="{LABEL}" android:allowBackup="false" android:debuggable="true">
                <activity android:name="org.koitharu.kotatsu.main.ui.MainActivity">
                    <intent-filter><action android:name="android.intent.action.MAIN"/>
                    <category android:name="android.intent.category.LAUNCHER"/></intent-filter>
                </activity>
            </application></manifest>''')
        manifest_check(root, 1000003, "1.2.0")
        with self.assertRaises(ValueError):
            manifest_check(root, 1000004, "1.2.0")
        ET.SubElement(root, "uses-permission", {A + "name": "android.permission.MANAGE_EXTERNAL_STORAGE"})
        with self.assertRaises(ValueError):
            manifest_check(root, 1000003, "1.2.0")

    def test_manifest_isolation(self):
        root = ET.fromstring(f'''<manifest xmlns:android="{ANDROID}">
          <uses-permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE"/>
          <application android:allowBackup="true" android:backupAgent="Agent">
            <activity android:name="Main"><intent-filter><action android:name="android.intent.action.MAIN"/>
              <category android:name="android.intent.category.LAUNCHER"/></intent-filter></activity>
            <activity android:name="Callback" android:exported="true"><intent-filter>
              <action android:name="android.intent.action.VIEW"/><data android:scheme="miyorare"/>
            </intent-filter></activity>
          </application>
        </manifest>''')
        isolate_manifest(ET.ElementTree(root), False)
        self.assertIsNone(root.find("uses-permission"))
        app = root.find("application")
        self.assertEqual(app.get(A + "allowBackup"), "false")
        self.assertEqual(app.get(A + "label"), LABEL)
        self.assertIsNone(app.get(A + "backupAgent"))
        self.assertIsNotNone(app[0].find("intent-filter"))
        self.assertIsNone(app[1].find("intent-filter"))
        self.assertEqual(app[1].get(A + "exported"), "false")


def main():
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("self-test")
    for name in ("prepare", "verify"):
        p = sub.add_parser(name)
        p.add_argument("--sha", required=True)
        p.add_argument("--workflow-sha", required=True)
        p.add_argument("--pr", type=int, required=True)
        p.add_argument("--run-id", type=int, required=True)
        p.add_argument("--run-number", type=int, required=True)
        p.add_argument("--run-attempt", type=int, required=True)
        p.add_argument("--slot", type=int, choices=(0, 1), required=True)
        if name == "prepare":
            p.add_argument("--root", type=Path, required=True)
            p.add_argument("--out", type=Path, required=True)
            p.add_argument("--version-name")
        else:
            p.add_argument("--directory", type=Path, required=True)
            p.add_argument("--apk", required=True)
            p.add_argument("--apkanalyzer", required=True)
            p.add_argument("--apksigner")
            p.add_argument("--certificate")
            p.add_argument("--expected-version")
            p.add_argument("--record-unsigned", action="store_true")
    args = parser.parse_args()
    if args.command == "self-test":
        result = unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(ContractTest))
        raise SystemExit(0 if result.wasSuccessful() else 1)
    (prepare if args.command == "prepare" else verify)(args)


if __name__ == "__main__":
    main()
