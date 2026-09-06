#!/usr/bin/env bash
# Reuse the cached SDK directly: no repeated SDK updates, Gradle startup, or mutable snapshots.
set -euo pipefail
cd "$(dirname "$0")/.."
sdk_dir="${ANDROID_HOME:-/usr/local/lib/android/sdk}"
export PATH="$sdk_dir/platform-tools:$sdk_dir/emulator:$PATH"
export ANDROID_SERIAL=emulator-5554
# avdmanager and emulator otherwise use different defaults on hosted runners.
export ANDROID_AVD_HOME="${RUNNER_TEMP:-/tmp}/hdri-avd"
mkdir -p "$ANDROID_AVD_HOME"
mkdir -p verification
printf 'no\n' | "$sdk_dir/cmdline-tools/latest/bin/avdmanager" create avd --force -n hdri-ci \
  --package 'system-images;android-36;default;x86_64' --device pixel_2 --path "$ANDROID_AVD_HOME/hdri-ci.avd"
test -s "$ANDROID_AVD_HOME/hdri-ci.ini" || { echo 'AVD registration is missing; refusing to launch.' >&2; exit 1; }
available=$(df -Pk "$ANDROID_AVD_HOME" | awk 'NR == 2 {print $4}')
test "$available" -ge 10485760 || { echo 'Need 10 GB free after SDK caching, before creating emulator data.' >&2; exit 1; }
timeout --kill-after=2s 15 adb start-server
emulator -avd hdri-ci -port 5554 -no-window -gpu swiftshader_indirect -noaudio \
  -no-boot-anim -no-snapshot -no-metrics -camera-back emulated -cores 2 -memory 3072 -skin 720x1280 \
  >verification/emulator.txt 2>&1 &
emulator_pid=$!
finish() {
  test_status=$?
  trap - EXIT
  timeout --kill-after=2s 8 adb logcat -d >verification/logcat.txt 2>&1 || true
  kill "$emulator_pid" 2>/dev/null || true
  exit "$test_status"
}
trap finish EXIT
started=$SECONDS
while [[ "$(timeout --kill-after=2s 5 adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" != 1 ]]; do
  if ! kill -0 "$emulator_pid" 2>/dev/null; then
    tail -n 100 verification/emulator.txt
    echo 'Emulator process exited; failing immediately instead of waiting for the boot timeout.' >&2
    exit 1
  fi
  if (( SECONDS - started >= 180 )); then
    tail -n 100 verification/emulator.txt
    echo 'Emulator did not boot within three minutes.' >&2
    exit 1
  fi
  sleep 2
done
echo 'Emulator booted; installing the retained APKs and starting tests.'
timeout --kill-after=2s 15 adb shell wm density 320
timeout --kill-after=2s 15 adb shell input keyevent 82
timeout --kill-after=2s 15 adb shell settings put global window_animation_scale 0
timeout --kill-after=2s 15 adb shell settings put global transition_animation_scale 0
timeout --kill-after=2s 15 adb shell settings put global animator_duration_scale 0
timeout --kill-after=2s 15 adb shell settings put secure spell_checker_enabled 0
# The first-boot Quickstep launcher can ANR under the two-core native benchmark and cover screenshots.
# Tests launch Luma Sphere directly; disable only the disposable emulator's unrelated home app.
# App crash/ANR dialogs and instrumentation timeouts remain enabled.
timeout --kill-after=2s 15 adb shell pm disable-user --user 0 com.android.launcher3
./scripts/run-android-tests.sh

