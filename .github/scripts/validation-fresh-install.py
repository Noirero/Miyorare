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
import xml.etree.ElementTree as ET
from pathlib import Path

PACKAGE = "org.noirero.miyorare.validation"
MAIN = "org.koitharu.kotatsu.main.ui.MainActivity"
ONBOARDING = "org.koitharu.kotatsu.main.ui.welcome.OnboardingActivity"


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
        except (subprocess.CalledProcessError, subprocess.TimeoutExpired, ET.ParseError):
            pass
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
    adb("shell", "uiautomator", "dump", path, timeout=30)
    root = ET.fromstring(adb("shell", "cat", path))
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


def smoke(apk):
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
            smoke(signed)
            require(hashlib.sha256(source.read_bytes()).hexdigest() == expected, "Unsigned artifact modified")


if __name__ == "__main__":
    main()
