#!/usr/bin/env bash
set -euxo pipefail

mkdir -p emulator-logs

PACKAGE="${TARGET_PACKAGE:-com.vayro.app}"

for variant in online offline; do
  apk="app/build/outputs/apk/${variant}/release/app-${variant}-release.apk"
  test -f "$apk"

  adb logcat -c -b all
  adb uninstall com.vayro.app || true
  adb uninstall "$PACKAGE" || true
  adb install "$apk"

  adb shell am force-stop "$PACKAGE"
  adb shell monkey -p "$PACKAGE" 1
  sleep 15

  adb shell dumpsys activity activities > "emulator-logs/${variant}-activities.txt"
  adb shell dumpsys package "$PACKAGE" > "emulator-logs/${variant}-package.txt"
  adb shell pidof "$PACKAGE" > "emulator-logs/${variant}-pid.txt" || true
  adb logcat -d -b all -v threadtime > "emulator-logs/${variant}-logcat.txt"

  if ! grep -qE "ResumedActivity:.*${PACKAGE}/.*MainActivity|topResumedActivity=.*${PACKAGE}/.*MainActivity" "emulator-logs/${variant}-activities.txt"; then
    echo "MainActivity is not resumed after launch: ${variant}"
    tail -n 300 "emulator-logs/${variant}-activities.txt"
    tail -n 300 "emulator-logs/${variant}-logcat.txt"
    exit 1
  fi

  if grep -qE "AndroidRuntime: Process: ${PACKAGE}, PID:" "emulator-logs/${variant}-logcat.txt"; then
    echo "Fatal Android runtime exception detected in ${PACKAGE}: ${variant}"
    grep -nE "AndroidRuntime: Process: ${PACKAGE}, PID:" "emulator-logs/${variant}-logcat.txt" || true
    tail -n 300 "emulator-logs/${variant}-logcat.txt"
    exit 1
  fi

  echo "Smoke test passed for ${variant}: MainActivity resumed."
done
