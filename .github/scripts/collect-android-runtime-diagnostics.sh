#!/usr/bin/env bash
set -euo pipefail

if (( $# == 0 )); then
    echo "Usage: $0 <runtime command and arguments>" >&2
    exit 2
fi

runtime_report_dir=app/build/reports/android-runtime-diagnostics
mkdir -p "$runtime_report_dir"
git rev-parse HEAD > "$runtime_report_dir/commit.txt"

start_runtime_logcat() {
    local output_file=$1
    adb logcat -b all -v threadtime > "$output_file" 2>&1 &
    runtime_logcat_pid=$!
}

stop_runtime_logcat() {
    set +e
    kill "$runtime_logcat_pid" 2>/dev/null
    wait "$runtime_logcat_pid" 2>/dev/null
    set -e
}

# Stream before instrumentation: after an emulator/ADB loss, pulling logcat is too late.
runtime_logcat_pid=0
start_runtime_logcat "$runtime_report_dir/device-logcat.txt"
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

run_runtime_command() {
    local output_file=$1
    shift
    set +e
    "$@" 2>&1 | tee "$output_file"
    local status=${PIPESTATUS[0]}
    set -e
    return "$status"
}

is_incomplete_offline_run() {
    local output_file=$1
    grep -Fq "device offline" "$output_file" &&
        grep -Fq "Test run failed to complete. Expected" "$output_file" &&
        grep -Fq "received" "$output_file"
}

wait_for_android_device() {
    local deadline=$((SECONDS + 120))
    while (( SECONDS < deadline )); do
        if [[ "$(adb get-state 2>/dev/null || true)" == "device" ]] && adb shell true >/dev/null 2>&1; then
            return 0
        fi
        sleep 2
    done
    return 1
}

collect_runtime_diagnostics() {
    # Diagnostics must never replace the wrapped test command's exit status.
    set +e
    kill "$runtime_logcat_pid" "$runtime_monitor_pid" 2>/dev/null
    wait "$runtime_logcat_pid" "$runtime_monitor_pid" 2>/dev/null
    printf '%s\n' "$runtime_exit_status" > "$runtime_report_dir/command-exit-status.txt"
    timeout 10s adb devices -l > "$runtime_report_dir/adb-devices.txt" 2>&1
    timeout 10s adb shell cat /proc/meminfo > "$runtime_report_dir/device-memory.txt" 2>&1
    # Synthetic UI captures survive AGP uninstall via MediaStore Downloads.
    timeout 20s adb pull /sdcard/Download/miyorare-category-sets-ui "$runtime_report_dir/category-sets-ui"
    timeout 10s sudo -n dmesg --ctime > "$runtime_report_dir/host-kernel.txt" 2>&1
}
trap 'runtime_exit_status=$?; collect_runtime_diagnostics' EXIT

runtime_exit_status=0
if run_runtime_command "$runtime_report_dir/runtime-command-attempt-1.txt" "$@"; then
    runtime_exit_status=0
else
    runtime_exit_status=$?
fi

# A real assertion must remain red. Retry only the exact infrastructure failure observed in CI:
# Android transport went offline and instrumentation reported an incomplete test count.
if (( runtime_exit_status != 0 )) && is_incomplete_offline_run "$runtime_report_dir/runtime-command-attempt-1.txt"; then
    printf '%s\n' "Detected incomplete Android run caused by device offline; retrying the exact runtime command once." \
        | tee "$runtime_report_dir/retry.txt"
    if wait_for_android_device; then
        stop_runtime_logcat
        start_runtime_logcat "$runtime_report_dir/device-logcat-retry.txt"
        if run_runtime_command "$runtime_report_dir/runtime-command-attempt-2.txt" "$@"; then
            runtime_exit_status=0
        else
            runtime_exit_status=$?
        fi
    else
        printf '%s\n' "Android device did not recover; keeping the original failure." \
            | tee -a "$runtime_report_dir/retry.txt"
    fi
fi

exit "$runtime_exit_status"
