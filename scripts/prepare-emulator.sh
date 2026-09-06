#!/usr/bin/env bash
# Called once per cache miss, before device tests. No app or mutable AVD data enters this cache.
set -euo pipefail
sdk_dir="${ANDROID_HOME:-/usr/local/lib/android/sdk}"
if [[ ! -x "$sdk_dir/emulator/emulator" || ! -f "$sdk_dir/system-images/android-36/default/x86_64/package.xml" ]]; then
  { yes || true; } | "$sdk_dir/cmdline-tools/latest/bin/sdkmanager" --licenses >/dev/null
  "$sdk_dir/cmdline-tools/latest/bin/sdkmanager" --install emulator platform-tools 'system-images;android-36;default;x86_64'
fi
# Even the version probe must use the headless binary. The GUI binary links
# PulseAudio/Qt host libraries that a fresh Actions runner need not provide.
"$sdk_dir/emulator/emulator" -no-window -version | sed -n '1p'

