#!/usr/bin/env bash
# Build and launch dumbtv home on the Android TV preview emulator only.
#   ./launcher/preview.sh
# Set PREVIEW_SERIAL only when the emulator uses a different emulator-* serial.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

SERIAL=${PREVIEW_SERIAL:-emulator-5556}
[[ $SERIAL == emulator-* ]] || {
  echo "error: preview target must be an emulator-* serial, got $SERIAL" >&2
  exit 2
}

if [[ -x "$PWD/../platform-tools/adb.exe" ]]; then
  ADB=${ADB:-$PWD/../platform-tools/adb.exe}
else
  ADB=${ADB:-adb}
fi
adb_() { MSYS_NO_PATHCONV=1 "$ADB" -s "$SERIAL" "$@"; }

state=$(adb_ get-state 2>/dev/null || true)
[[ $state == device ]] || {
  echo "error: $SERIAL is not online; start the DumbTV_TV_API36 emulator first" >&2
  exit 1
}

./build.sh
adb_ install -r build/dumbtv-home.apk
media=/sdcard/Android/media/tv.dumbtv.home
adb_ shell mkdir -p "$media"
adb_ push home.conf "$media/home.conf" >/dev/null
adb_ shell am start -n tv.dumbtv.home/.Home
echo "preview running on $SERIAL"
