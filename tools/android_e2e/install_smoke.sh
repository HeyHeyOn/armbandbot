#!/usr/bin/env bash
set -euo pipefail

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}"
if [[ -x "$SDK/platform-tools/adb" ]]; then
  ADB="$SDK/platform-tools/adb"
else
  ADB="$SDK/platform-tools/adb.exe"
fi
APK="${1:-app/build/outputs/apk/release/app-release.apk}"
PKG="com.heyheyon.armbandbot"
ACTIVITY="com.heyheyon.armbandbot/.MainActivity"

if [[ ! -f "$APK" ]]; then
  echo "APK not found: $APK" >&2
  exit 2
fi

"$ADB" wait-for-device
for i in $(seq 1 180); do
  boot=$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)
  if [[ "$boot" == "1" ]]; then
    break
  fi
  sleep 2
done
if [[ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)" != "1" ]]; then
  echo "Device did not boot" >&2
  "$ADB" devices >&2
  exit 3
fi

"$ADB" install -r "$APK"
# Android 13+ notification permission; ignore on older images.
"$ADB" shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true
"$ADB" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null
sleep 5
"$ADB" shell pidof "$PKG" >/dev/null
"$ADB" logcat -d -t 300 | grep -E "BotService|AndroidRuntime|FATAL EXCEPTION|$PKG" || true

echo "APP_SMOKE_OK package=$PKG apk=$APK"
