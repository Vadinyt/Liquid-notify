#!/usr/bin/env bash
# The API 37.2 image exposes RenderNode.setBackdropRenderEffect to normal applications.
set -euo pipefail
sdk_bin="$ANDROID_HOME/cmdline-tools/latest/bin"
image="system-images;android-37.2;google_apis_ps16k;x86_64"
export JAVA_HOME="${JAVA_HOME_21_X64:-$JAVA_HOME}"
"$sdk_bin/sdkmanager" --install emulator "$image"
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
export ANDROID_AVD_HOME="$HOME/.android/avd"
export ANDROID_SERIAL=emulator-5554
adb version
adb start-server
adb devices -l
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
"$ANDROID_HOME/emulator/emulator" -port 5554 -avd liquidshade_live -no-window -gpu swiftshader -noaudio -no-boot-anim -no-snapshot -cores 2 -memory 2048 > "$RUNNER_TEMP/liquidshade-emulator.log" 2>&1 &
emulator_pid=$!
trap 'adb -s "$ANDROID_SERIAL" emu kill >/dev/null 2>&1 || kill "$emulator_pid" 2>/dev/null || true' EXIT
for attempt in $(seq 1 80); do
  if ! kill -0 "$emulator_pid" 2>/dev/null; then break; fi
  boot=$(timeout 5 adb -s "$ANDROID_SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)
  if [[ "$boot" == 1 ]]; then
    bash tools/verify_android.sh true
    echo "booted=true" >> "$GITHUB_OUTPUT"
    exit 0
  fi
  if (( attempt % 12 == 0 )); then
    adb devices -l
    tail -5 "$RUNNER_TEMP/liquidshade-emulator.log"
  fi
  sleep 4
done
adb devices -l
tail -55 "$RUNNER_TEMP/liquidshade-emulator.log"
echo "::warning::The API 37.2 emulator did not boot. Running the baseline Android pixel suite."
echo "booted=false" >> "$GITHUB_OUTPUT"
