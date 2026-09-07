#!/usr/bin/env bash
set -euo pipefail

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}"

if [[ -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]]; then
  SDKMGR="$SDK/cmdline-tools/latest/bin/sdkmanager"
  AVDMGR="$SDK/cmdline-tools/latest/bin/avdmanager"
  EMULATOR="$SDK/emulator/emulator"
  ADB="$SDK/platform-tools/adb"
else
  SDKMGR="$SDK/cmdline-tools/latest/bin/sdkmanager.bat"
  AVDMGR="$SDK/cmdline-tools/latest/bin/avdmanager.bat"
  EMULATOR="$SDK/emulator/emulator.exe"
  ADB="$SDK/platform-tools/adb.exe"
fi
AVD_NAME="${AVD_NAME:-armbandbot_api35}"
IMAGE="${IMAGE:-system-images;android-35;google_apis;x86_64}"
DEVICE="${DEVICE:-pixel_2}"

if [[ ! -x "$SDKMGR" || ! -x "$AVDMGR" ]]; then
  echo "Android cmdline-tools not found under $SDK" >&2
  exit 2
fi

# Accept licenses and ensure emulator/image are present.
yes | "$SDKMGR" --licenses >/dev/null || true
"$SDKMGR" "emulator" "$IMAGE"

if ! "$EMULATOR" -list-avds | grep -Fxq "$AVD_NAME"; then
  printf 'no\n' | "$AVDMGR" create avd -n "$AVD_NAME" -k "$IMAGE" -d "$DEVICE" --force
fi

cat <<EOF
AVD ready: $AVD_NAME
SDK: $SDK
Emulator: $EMULATOR
ADB: $ADB

Boot manually/through CI with:
$EMULATOR -avd $AVD_NAME -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -no-snapshot -cores 2 -memory 3072
EOF
