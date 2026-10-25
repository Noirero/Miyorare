#!/usr/bin/env bash
set -euo pipefail

if (( $# == 0 )); then
    echo "Usage: $0 <runtime command and arguments>" >&2
    exit 2
fi

runtime_report_dir=app/build/reports/android-runtime-diagnostics
mkdir -p "$runtime_report_dir"
git rev-parse HEAD > "$runtime_report_dir/commit.txt"

# Stream before instrumentation: after an emulator/ADB loss, pulling logcat is too late.
adb logcat -b all -v threadtime > "$runtime_report_dir/device-logcat.txt" 2>&1 &
runtime_logcat_pid=$!
(
    while true; do
        date -u
        free -m
        ps -eo pid,ppid,comm,rss --sort=-rss | head -18 || true
        if [[ -r /sys/fs/cgroup/memory.events ]]; then
            cat /sys/fs/cgroup/memory.events
        fi
        sleep 5
    done
) > "$runtime_report_dir/host-resources.txt" 2>&1 &
runtime_monitor_pid=$!

collect_runtime_diagnostics() {
    # Diagnostics must never replace the wrapped test command's exit status.
    set +e
    kill "$runtime_logcat_pid" "$runtime_monitor_pid" 2>/dev/null
    wait "$runtime_logcat_pid" "$runtime_monitor_pid" 2>/dev/null
    printf '%s\n' "$runtime_exit_status" > "$runtime_report_dir/command-exit-status.txt"
    timeout 10s adb devices -l > "$runtime_report_dir/adb-devices.txt" 2>&1
    timeout 10s adb shell cat /proc/meminfo > "$runtime_report_dir/device-memory.txt" 2>&1
    timeout 10s sudo -n dmesg --ctime > "$runtime_report_dir/host-kernel.txt" 2>&1
}
trap 'runtime_exit_status=$?; collect_runtime_diagnostics' EXIT

runtime_exit_status=0
"$@" || runtime_exit_status=$?
exit "$runtime_exit_status"
