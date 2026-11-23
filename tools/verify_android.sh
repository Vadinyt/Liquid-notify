#!/usr/bin/env bash
set -euo pipefail
adb shell settings put global animator_duration_scale 1
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
result=$(adb shell am instrument -w dev.vadinyt.liquidshade.test/dev.vadinyt.liquidshade.GlassInstrumentation)
echo "$result"
if ! grep -q 'LIQUIDSHADE_DEVICE_PASS' <<< "$result"; then
  adb logcat -d -s LiquidShadeTest:E AndroidRuntime:E
  exit 1
fi
