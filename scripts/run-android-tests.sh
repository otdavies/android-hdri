#!/usr/bin/env bash
# Test exactly the candidates that will be published. No Gradle or rebuild on the device runner.
set -uo pipefail
cd "$(dirname "$0")/.."
mkdir -p verification
collect() {
  timeout --kill-after=2s 8 adb logcat -d >verification/logcat.txt 2>&1 || true
  timeout --kill-after=2s 10 adb pull /sdcard/Android/data/io.github.otdavies.hdri.preview/files/verification/. verification/ >/dev/null 2>&1 || true
}
trap collect EXIT
device_adb() { timeout --kill-after=3s 60 adb "$@"; }
device_adb install -r candidate/sphere-0.1.0-preview.apk || exit $?
# Reinstall the exact binary to exercise update compatibility without destructive uninstall.
device_adb install -r candidate/sphere-0.1.0-preview.apk || exit $?
device_adb install -r -t candidate/hdri-tests.apk || exit $?
device_adb shell pm clear io.github.otdavies.hdri.preview || exit $?
device_adb logcat -c
timeout --foreground --kill-after=5s 660 adb shell am instrument -w -r -e timeout_msec 360000 \
  io.github.otdavies.hdri.preview.test/androidx.test.runner.AndroidJUnitRunner | tee verification/instrumentation.txt
result=${PIPESTATUS[0]}
[[ "$result" == 0 ]] || exit "$result"
python3 - <<'PY'
from pathlib import Path
import re,sys
text=Path('verification/instrumentation.txt').read_text()
ok=re.search(r'OK \((\d+) tests?\)',text)
failed=any(s in text for s in ['FAILURES!!!','INSTRUMENTATION_FAILED','Process crashed','INSTRUMENTATION_STATUS_CODE: -2'])
if not ok or int(ok.group(1))<30 or failed:
    print('The thirty required installed-APK tests did not all pass.',file=sys.stderr)
    sys.exit(1)
print(f'Verified {ok.group(1)} tests against the release APK.')
PY
