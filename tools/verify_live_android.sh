#!/usr/bin/env bash
# The API 37.2 image exposes RenderNode.setBackdropRenderEffect to normal applications.
set -euo pipefail
sdk_bin="$ANDROID_HOME/cmdline-tools/latest/bin"
image="system-images;android-37.2;google_apis_ps16k;x86_64"
export JAVA_HOME="${JAVA_HOME_21_X64:-$JAVA_HOME}"
"$sdk_bin/sdkmanager" --install emulator "$image"
export ANDROID_USER_HOME="$RUNNER_TEMP/liquidshade-android"
export ANDROID_AVD_HOME="$ANDROID_USER_HOME/avd"
mkdir -p "$ANDROID_AVD_HOME"
profile_dir="$ANDROID_AVD_HOME/liquidshade_live.avd"
echo no | "$sdk_bin/avdmanager" create avd --force -n liquidshade_live -k "$image" --device pixel_4 --path "$profile_dir"
test -s "$profile_dir/config.ini"
# Emulator and command-line tools have used different default profile directories on hosted Linux.
cat > "$ANDROID_AVD_HOME/liquidshade_live.ini" <<EOF
avd.ini.encoding=UTF-8
path=$profile_dir
target=android-37.2
EOF
"$ANDROID_HOME/emulator/emulator" -avd liquidshade_live -no-window -gpu swiftshader -noaudio -no-boot-anim -no-snapshot -cores 2 -memory 3072 > "$RUNNER_TEMP/liquidshade-emulator.log" 2>&1 &
emulator_pid=$!
trap 'adb emu kill >/dev/null 2>&1 || true' EXIT
for attempt in $(seq 1 150); do
  if ! kill -0 "$emulator_pid" 2>/dev/null; then tail -50 "$RUNNER_TEMP/liquidshade-emulator.log"; exit 1; fi
  boot=$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)
  if [[ "$boot" == 1 ]]; then break; fi
  sleep 3
done
test "$boot" = 1
bash tools/verify_android.sh true
