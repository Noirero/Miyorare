#!/usr/bin/env python3
"""Non-gating host-side watchdog around an exact-HEAD Android diagnostic.

Collect evidence *while the emulator is still alive*. The wrapped test owns the
exit code; this collector never converts a failure into a passing result.
"""
from __future__ import annotations

import argparse
import subprocess
import sys
import threading
import time
from pathlib import Path

APP_ID = "org.noirero.miyorare.debug"  # app/build.gradle: debug applicationId
WAIT_MARKER = "phase=wait_for_idle"
READY_MARKER = "phase=idle_ready"
DUMP_DELAY_SECONDS = 5.0
POLL_SECONDS = 2.0


def command_output(command: list[str], timeout: float = 3.0) -> str:
    try:
        result = subprocess.run(command, capture_output=True, text=True,
                                errors="replace", timeout=timeout, check=False)
        return f"exit={result.returncode} stdout={result.stdout.strip()!r} stderr={result.stderr.strip()!r}"
    except (OSError, subprocess.TimeoutExpired) as exc:
        return f"unavailable: {type(exc).__name__}: {exc}"


class PhaseState:
    def __init__(self) -> None:
        self.wait_at: float | None = None
        self.dumped = False
        self.phase = "none"

    def accept(self, chunk: str, now: float) -> None:
        for line in chunk.splitlines():
            if WAIT_MARKER in line and "mode=" in line:
                self.phase = line.strip()[-160:]
                self.wait_at = now
                self.dumped = False
            elif READY_MARKER in line and "mode=" in line:
                self.wait_at = None
                self.phase = "idle_ready"

    def needs_dump(self, now: float) -> bool:
        return (self.wait_at is not None and not self.dumped
                and now - self.wait_at >= DUMP_DELAY_SECONDS)


class HostWatchdog:
    def __init__(self, output: Path) -> None:
        self.output = output
        self.stopping = threading.Event()
        self.state = PhaseState()
        self.offset = 0
        self.partial_line = ""
        self.thread: threading.Thread | None = None
        self.logcat: subprocess.Popen[bytes] | None = None
        self.logcat_stream = None

    def start(self) -> None:
        self.output.mkdir(parents=True, exist_ok=True)
        self.logcat_stream = (self.output / "device-logcat-stream.txt").open("wb")
        try:
            self.logcat = subprocess.Popen(
                ["adb", "logcat", "-b", "all", "-v", "threadtime"],
                stdout=self.logcat_stream, stderr=subprocess.STDOUT,
            )
        except OSError as exc:
            (self.output / "logcat-start-error.txt").write_text(str(exc))
        self.thread = threading.Thread(target=self.monitor, name="android-host-watchdog", daemon=True)
        self.thread.start()

    def read_new_logcat(self, now: float) -> None:
        log_path = self.output / "device-logcat-stream.txt"
        if not log_path.exists():
            return
        with log_path.open("rb") as stream:
            stream.seek(self.offset)
            chunk = stream.read()
            self.offset = stream.tell()
        if chunk:
            combined = self.partial_line + chunk.decode("utf-8", errors="replace")
            lines = combined.split("\n")
            self.partial_line = lines.pop()
            if lines:
                self.state.accept("\n".join(lines), now)

    def capture_thread_dump(self, log) -> None:
        self.state.dumped = True  # At most one SIGQUIT per stalled initial idle gate.
        log.write(f"thread_dump_trigger phase={self.state.phase!r}\n")
        try:
            proc = subprocess.run(["adb", "shell", "pidof", "-s", APP_ID],
                                  capture_output=True, text=True, timeout=4, check=False)
            pid = proc.stdout.strip()
            if proc.returncode or not pid.isdecimal():
                log.write(f"thread_dump_unavailable pidof_rc={proc.returncode} "
                          f"stdout={pid!r} stderr={proc.stderr.strip()!r}\n")
                return
            # SIGQUIT asks ART to write Java stacks (often under /data/anr).
            # Native debuggerd output is saved separately while ADB is alive.
            log.write(f"java_dump_request pid={pid} "
                      f"{command_output(['adb', 'shell', 'kill', '-3', pid], timeout=4)}\n")
            try:
                native = subprocess.run(["adb", "shell", "debuggerd", "-b", pid],
                                        capture_output=True, text=True, errors="replace",
                                        timeout=6, check=False)
                (self.output / "app-native-backtrace.txt").write_text(
                    f"exit={native.returncode}\n{native.stdout}\n{native.stderr}", encoding="utf-8")
                log.write(f"native_backtrace exit={native.returncode}\n")
            except (OSError, subprocess.TimeoutExpired) as exc:
                (self.output / "app-native-backtrace-error.txt").write_text(str(exc))
                log.write(f"native_backtrace_unavailable {type(exc).__name__}\n")
            log.write("anr_directory " + command_output(["adb", "shell", "ls", "-lt", "/data/anr"], timeout=3) + "\n")
            # Some emulator images permit shell to read ART's generated traces;
            # preserve them immediately if accessible, before QEMU disconnects.
            try:
                latest = subprocess.run(
                    ["adb", "shell", "sh", "-c", "ls -t /data/anr/anr_* 2>/dev/null | head -n 1"],
                    capture_output=True, text=True, timeout=3, check=False,
                ).stdout.strip()
                if latest.startswith("/data/anr/anr_") and "\n" not in latest and " " not in latest:
                    trace = subprocess.run(["adb", "exec-out", "cat", latest],
                                           capture_output=True, timeout=5, check=False)
                    if trace.returncode == 0 and trace.stdout:
                        (self.output / "app-java-anr.txt").write_bytes(trace.stdout)
                        log.write(f"java_anr_captured path={latest!r} bytes={len(trace.stdout)}\n")
                    else:
                        log.write(f"java_anr_unavailable path={latest!r} exit={trace.returncode} "
                                  f"stderr={trace.stderr.decode(errors='replace')[:300]!r}\n")
            except (OSError, subprocess.TimeoutExpired) as exc:
                log.write(f"java_anr_unavailable {type(exc).__name__}: {exc}\n")
        except (OSError, subprocess.TimeoutExpired) as exc:
            log.write(f"thread_dump_unavailable {type(exc).__name__}: {exc}\n")
        log.flush()

    def host_snapshot(self, log, now: float) -> None:
        stamp = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
        log.write(f"\n[{stamp}] monotonic={now:.3f} idle={self.state.phase!r}\n")
        # qemu-system may disappear before Android/ADB can report any crash.
        # Only host process metadata: no command arguments or runner secrets.
        try:
            result = subprocess.run(["ps", "-eo", "pid,ppid,stat,rss,etime,comm"],
                                    capture_output=True, text=True, timeout=3, check=False)
            for line in result.stdout.splitlines():
                if any(name in line.lower() for name in ("qemu", "emulator", "adb")):
                    log.write(f"process {line.strip()}\n")
            if result.returncode:
                log.write(f"ps_failed exit={result.returncode}\n")
        except (OSError, subprocess.TimeoutExpired) as exc:
            log.write(f"ps_unavailable {type(exc).__name__}: {exc}\n")
        log.write(f"adb_state {command_output(['adb', 'get-state'], timeout=3)}\n")
        for name in ("/sys/fs/cgroup/memory.events", "/sys/fs/cgroup/memory.current"):
            try:
                log.write(f"{name}: {Path(name).read_text().strip()!r}\n")
            except OSError as exc:
                log.write(f"{name}: unavailable {exc}\n")
        log.flush()

    def monitor(self) -> None:
        try:
            with (self.output / "host-timeline.txt").open("w", encoding="utf-8") as log:
                log.write("watchdog_start: host QEMU/ADB sampling + live logcat; "
                          "SIGQUIT after 5s at History wait_for_idle; emulator stderr in Actions job log\n")
                while not self.stopping.is_set():
                    now = time.monotonic()
                    self.read_new_logcat(now)
                    self.host_snapshot(log, now)
                    if self.state.needs_dump(time.monotonic()):
                        self.capture_thread_dump(log)
                    self.stopping.wait(POLL_SECONDS)
                self.read_new_logcat(time.monotonic())
                log.write("watchdog_stop\n")
        except Exception as exc:  # Diagnostics failure cannot mask test exit status.
            (self.output / "watchdog-error.txt").write_text(f"{type(exc).__name__}: {exc}\n")

    def stop(self) -> None:
        self.stopping.set()
        if self.thread is not None:
            self.thread.join(timeout=20)
            if self.thread.is_alive():
                (self.output / "watchdog-stop-timeout.txt").write_text("monitor did not finish in 20s\n")
        if self.logcat is not None and self.logcat.poll() is None:
            self.logcat.terminate()
            try:
                self.logcat.wait(timeout=4)
            except subprocess.TimeoutExpired:
                self.logcat.kill()
                self.logcat.wait(timeout=4)
        if self.logcat_stream is not None:
            self.logcat_stream.close()


def self_test() -> None:
    state = PhaseState()
    state.accept("I/HistoryScrollBench: mode=grid phase=wait_for_idle\n", 100.0)
    assert not state.needs_dump(104.9)
    assert state.needs_dump(105.0)
    state.dumped = True
    assert not state.needs_dump(107.0)
    state.accept("I/HistoryScrollBench: mode=grid phase=idle_ready\n", 108.0)
    assert not state.needs_dump(120.0)
    state.accept("I/HistoryScrollBench: mode=cover phase=wait_for_idle\n", 121.0)
    assert state.needs_dump(126.0)
    # The wrapper must propagate the diagnostic runner's failure unchanged.
    assert subprocess.run([sys.executable, "-c", "raise SystemExit(7)"], check=False).returncode == 7
    print("Host watchdog phase/exit-code contract: PASS")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    if args.self_test:
        self_test()
        return 0
    if not args.command or args.command[0] != "--" or len(args.command) < 2:
        parser.error("Expected -- <diagnostic command> [...]")
    watchdog = HostWatchdog(Path("diagnostics-evidence/host-watchdog"))
    watchdog.start()
    try:
        try:
            return subprocess.run(args.command[1:], check=False).returncode
        except OSError as exc:
            print(f"Could not execute diagnostic command: {exc}", file=sys.stderr)
            return 127
    finally:
        watchdog.stop()


if __name__ == "__main__":
    sys.exit(main())
