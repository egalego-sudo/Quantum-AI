#!/usr/bin/env bash
set -Eeuo pipefail

ADB_DEVICE="${ANDROID_SERIAL:-emulator-5554}"
DIAG_DIR="${RUNNER_TEMP:-${TMPDIR:-/tmp}}"

adb wait-for-device
adb devices -l

device_state="$(adb -s "$ADB_DEVICE" get-state)"
if [[ "$device_state" != "device" ]]; then
  echo "Expected $ADB_DEVICE state=device, got: $device_state" >&2
  adb devices -l >&2
  exit 1
fi

boot_completed=""
for attempt in $(seq 1 60); do
  boot_completed="$(adb -s "$ADB_DEVICE" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
  if [[ "$boot_completed" == "1" ]]; then
    break
  fi
  sleep 2
done

if [[ "$boot_completed" != "1" ]]; then
  echo "Emulator did not report sys.boot_completed=1." >&2
  adb devices -l >&2
  adb -s "$ADB_DEVICE" shell getprop >&2 || true
  exit 1
fi

echo "Emulator ready: ADB state=$device_state, sys.boot_completed=$boot_completed"

test_status=0
gradle --no-daemon connectedDebugAndroidTest --stacktrace || test_status=$?

if [[ "$test_status" -ne 0 ]]; then
  echo "===== Android instrumentation failure logcat ====="
  adb -s "$ADB_DEVICE" logcat -d -v threadtime > "$DIAG_DIR/quantum-emulator-logcat.txt" || true
  adb -s "$ADB_DEVICE" logcat -d -v threadtime -s AndroidRuntime:E ActivityManager:E Instrumentation:E TestRunner:E '*:S' || true
  adb -s "$ADB_DEVICE" shell dumpsys activity processes > "$DIAG_DIR/quantum-emulator-processes.txt" || true
  echo "Diagnostic files saved under $DIAG_DIR for artifact upload."
fi

exit "$test_status"
