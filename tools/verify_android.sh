#!/usr/bin/env bash
set -euo pipefail
adb shell settings put global animator_duration_scale 1
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
result=$(adb shell am instrument -w -e requireLive "${1:-false}" dev.vadinyt.liquidshade.test/dev.vadinyt.liquidshade.GlassInstrumentation)
echo "$result"
if ! grep -q 'LIQUIDSHADE_DEVICE_PASS' <<< "$result"; then
  adb logcat -d -s LiquidShadeTest:E AndroidRuntime:E
  exit 1
fi

mkdir -p dist
adb exec-out run-as dev.vadinyt.liquidshade cat cache/liquidshade-native.png > dist/LiquidShade-1.7.0-native.png
test -s dist/LiquidShade-1.7.0-native.png
