#!/usr/bin/env python3
"""Fresh-install UI smoke against the actual Validation APK on a disposable emulator."""
import hashlib
import json
import os
import re
import secrets
import subprocess
import sys
import tempfile
import time
import traceback
import xml.etree.ElementTree as ET
from pathlib import Path

PACKAGE = "org.noirero.miyorare.validation"
MAIN = "org.koitharu.kotatsu.main.ui.MainActivity"
ONBOARDING = "org.koitharu.kotatsu.main.ui.welcome.OnboardingActivity"
POLL_EVENTS = []
LAST_HIERARCHY = None


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def run(*args, timeout=90, env=None):
    result = subprocess.run(args, text=True, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, timeout=timeout, env=env)
    if result.returncode:
        raise subprocess.CalledProcessError(result.returncode, args, result.stdout)
    return result.stdout


def adb(*args, timeout=90):
    return run("adb", *args, timeout=timeout)


def assert_no_crash():
    crashes = adb("logcat", "-b", "crash", "-d")
    require(not re.search(r"(?m)\bProcess: " + re.escape(PACKAGE) + r"(?:,|\s|$)", crashes),
            "Validation application crashed:\n" + crashes)


def wait_for(description, predicate, seconds=90):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        assert_no_crash()
        try:
            value = predicate()
            if value is not None and value is not False:
                return value
        except (subprocess.CalledProcessError, subprocess.TimeoutExpired, ET.ParseError) as exc:
            event = {"time": time.time(), "wait": description, "exception": repr(exc),
                     "output": str(getattr(exc, "output", None))}
            POLL_EVENTS.append(event)
            print("UI poll exception:", json.dumps(event), flush=True)
        time.sleep(2)
    raise AssertionError("Timed out: " + description)


def resumed(activity):
    output = adb("shell", "dumpsys", "activity", "activities")
    return any(PACKAGE in line and activity in line
               for line in output.splitlines()
               if "mResumedActivity" in line or "topResumedActivity" in line)


def nodes():
    path = "/sdcard/validation-ui.xml"
    adb("shell", "rm", "-f", path)
    global LAST_HIERARCHY
    output = adb("shell", "uiautomator", "dump", path, timeout=30)
    POLL_EVENTS.append({"time": time.time(), "uiautomator": output})
    raw = adb("shell", "cat", path)
    LAST_HIERARCHY = (time.time(), raw)
    root = ET.fromstring(raw)
    result = [node for node in root.iter("node") if node.get("package") == PACKAGE]
    require(not any(node.get("text") == "Install extensions" for node in result),
            "Unsupported installer control visible")
    return result


def find_text(label):
    return next((node for node in nodes() if node.get("text") == label), None)


def click(label):
    node = wait_for(label, lambda: find_text(label))
    bounds = [int(v) for v in re.findall(r"\d+", node.get("bounds", ""))]
    require(len(bounds) == 4 and bounds[2] > bounds[0] and bounds[3] > bounds[1],
            "Invalid visible bounds for " + label)
    adb("shell", "input", "tap", str((bounds[0] + bounds[2]) // 2),
        str((bounds[1] + bounds[3]) // 2))


def reach_permissions():
    width, height = map(int, re.findall(r"(\d+)x(\d+)", adb("shell", "wm", "size"))[-1])
    for _ in range(8):
        snapshot = wait_for("onboarding UI", lambda: nodes() or None)
        if any(node.get("text") == "Notifications" for node in snapshot):
            return
        adb("shell", "input", "swipe", str(width // 2), str(height * 3 // 4),
            str(width // 2), str(height // 3), "450")
        time.sleep(1)
    raise AssertionError("Notifications row not found on storage/permissions page")


def installed():
    return "package:" + PACKAGE in adb("shell", "pm", "list", "packages", PACKAGE).splitlines()


def capture_failure(directory, failure):
    """Best-effort read-only emulator diagnostics; never change the test verdict."""
    directory.mkdir(parents=True, exist_ok=True)
    (directory / "failure.txt").write_text(failure)
    (directory / "poll-events.json").write_text(json.dumps(POLL_EVENTS, indent=2) + "\n")
    if LAST_HIERARCHY is not None:
        observed_at, raw = LAST_HIERARCHY
        (directory / "last-observed-ui.xml").write_text(raw)
        (directory / "last-observed-ui-time.txt").write_text(str(observed_at) + "\n")
    captures = []

    def capture(name, *args, timeout=20):
        event = {"file": name, "command": list(args), "time": time.time()}
        try:
            result = subprocess.run(["adb", *args], stdout=subprocess.PIPE,
                                    stderr=subprocess.PIPE, timeout=timeout)
            event["returncode"] = result.returncode
            event["stderr"] = result.stderr.decode(errors="replace")
            if name.endswith(".png") and not result.stdout.startswith(b"\x89PNG\r\n\x1a\n"):
                event["error"] = "No PNG returned"
                event["stdout"] = result.stdout.decode(errors="replace")
            else:
                (directory / name).write_bytes(result.stdout)
        except Exception as exc:
            event["exception"] = repr(exc)
        captures.append(event)

    # Capture the screen and focused window before querying a new hierarchy.
    capture("screen.png", "exec-out", "screencap", "-p")
    capture("window.txt", "shell", "dumpsys", "window")
    capture("activity.txt", "shell", "dumpsys", "activity", "activities")
    capture("crash.txt", "logcat", "-b", "crash", "-d")
    capture("activity-events.txt", "logcat", "-b", "events", "-d",
            "-s", "am_anr", "am_crash", "am_proc_died", "wm_on_resume_called")
    path = "/sdcard/validation-failure-ui.xml"
    capture("ui-remove.txt", "shell", "rm", "-f", path)
    capture("ui-dump.txt", "shell", "uiautomator", "dump", path, timeout=30)
    capture("failure-ui.xml", "shell", "cat", path)
    (directory / "capture-status.json").write_text(json.dumps(captures, indent=2) + "\n")
    print("Failure diagnostics:", directory, json.dumps(captures), flush=True)


def smoke(apk, diagnostic_dir):
    global LAST_HIERARCHY
    POLL_EVENTS.clear()
    LAST_HIERARCHY = None
    require(not installed(), "Expected a genuinely fresh installation")
    adb("logcat", "-b", "crash", "-c")
    require("Success" in adb("install", str(apk), timeout=180), "APK install failed")
    try:
        print(adb("shell", "am", "start", "-W", "-n", PACKAGE + "/" + MAIN), flush=True)
        wait_for("fresh-install onboarding resumed", lambda: resumed(ONBOARDING))
        click("Continue")
        wait_for("storage and permissions page", lambda: find_text("Storage & permissions"))
        reach_permissions()
        click("Continue")
        wait_for("sync page", lambda: find_text("Sync & restore"))
        click("Continue")
        wait_for("finish page", lambda: find_text("Ready to read"))
        click("Done")
        wait_for("main activity after completing onboarding", lambda: resumed(MAIN))

        def persisted():
            prefs = adb("shell", "run-as", PACKAGE, "cat",
                        "shared_prefs/" + PACKAGE + "_preferences.xml")
            root = ET.fromstring(prefs)
            return any(n.get("name") == "onboarding_completed" and n.get("value") == "true"
                       for n in root.findall("boolean"))

        wait_for("onboarding completion persisted", persisted)
        adb("shell", "am", "force-stop", PACKAGE)
        adb("shell", "am", "start", "-W", "-n", PACKAGE + "/" + MAIN)
        wait_for("main activity after restart", lambda: resumed(MAIN))
        require(not resumed(ONBOARDING), "Completed onboarding returned on restart")
        assert_no_crash()
        print("PASS: fresh install, permission page, onboarding completion and restart", flush=True)
    except Exception:
        try:
            capture_failure(diagnostic_dir, traceback.format_exc())
        except Exception as diagnostic_error:
            print("Diagnostic capture failed:", repr(diagnostic_error), flush=True)
        print(adb("logcat", "-b", "crash", "-d"), flush=True)
        raise
    finally:
        require("Success" in adb("uninstall", PACKAGE), "Emulator cleanup failed")


def main():
    require(len(sys.argv) > 1, "Provide unsigned artifact directories")
    require(adb("shell", "getprop", "ro.kernel.qemu").strip() == "1", "Disposable emulator required")
    require(adb("shell", "getprop", "ro.build.version.sdk").strip() == "36", "Android 16 required")
    sdk = Path(os.environ["ANDROID_HOME"]) / "build-tools"
    tools = sorted((p for p in sdk.iterdir() if p.is_dir()),
                   key=lambda p: tuple(int(v) for v in re.findall(r"\d+", p.name)))[-1]
    with tempfile.TemporaryDirectory(prefix="validation-smoke-") as tmp:
        tmp = Path(tmp)
        env = dict(os.environ, PROBE_PASSWORD=secrets.token_urlsafe(24))
        run("keytool", "-genkeypair", "-alias", "probe", "-keyalg", "RSA",
            "-keysize", "3072", "-validity", "1", "-dname", "CN=Validation smoke",
            "-storetype", "PKCS12", "-keystore", str(tmp / "probe.p12"),
            "-storepass:env", "PROBE_PASSWORD", "-keypass:env", "PROBE_PASSWORD", env=env)
        for arg in sys.argv[1:]:
            directory = Path(arg)
            source = directory / "unsigned.apk"
            metadata = json.loads((directory / "build.json").read_text())
            expected = metadata["unsignedApkSha256"]
            require(metadata["applicationId"] == PACKAGE, "Wrong test APK identity")
            require(hashlib.sha256(source.read_bytes()).hexdigest() == expected, "Unsigned digest mismatch")
            print("Testing source", metadata["sourceSha"], "version", metadata["versionName"],
                  "code", metadata["versionCode"], flush=True)
            aligned, signed = tmp / "aligned.apk", tmp / "signed.apk"
            run(str(tools / "zipalign"), "-f", "-P", "16", "4", str(source), str(aligned))
            run(str(tools / "apksigner"), "sign", "--ks", str(tmp / "probe.p12"),
                "--ks-key-alias", "probe", "--ks-pass", "env:PROBE_PASSWORD",
                "--key-pass", "env:PROBE_PASSWORD", "--out", str(signed), str(aligned), env=env)
            run(str(tools / "apksigner"), "verify", str(signed))
            diagnostic_dir = Path(os.environ["RUNNER_TEMP"]) / "validation-fresh-install-diagnostics" / directory.name
            smoke(signed, diagnostic_dir)
            require(hashlib.sha256(source.read_bytes()).hexdigest() == expected, "Unsigned artifact modified")
            print("PASS: unsigned digest unchanged", expected, flush=True)


if __name__ == "__main__":
    main()
