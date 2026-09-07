set shell := ["bash", "-eu", "-o", "pipefail", "-c"]
set default-list := true

default_avd := "light-phone-iii-api34"
tool_apk := "tool/build/outputs/apk/debug/tool-debug.apk"
emulator_apk := "sdk/emulator/build/outputs/apk/debug/emulator-debug.apk"
tool_id := "com.thelightphone.lightimessage"
serial := env("ANDROID_SERIAL", "")
adb := if serial == "" { "adb" } else { "adb -s " + serial }

# Interactive CLI bootstrap wizard
[group('setup')]
bootstrap:
  ./scripts/bootstrap-dev-env-wizard.sh

# Run all checks expected in CI
[group('check')]
ci-check:
  ./gradlew check --stacktrace

# Lint tool module
[group('check')]
tool-lint:
  ./gradlew :tool:lint --stacktrace

# Run tool module unit tests
[group('check')]
tool-test:
  ./gradlew :tool:test --stacktrace

# Build Light Page APK
[group('build')]
tool-build:
  ./gradlew :tool:assembleDebug --stacktrace

# Build LightOS emulator APK
[group('build')]
emulator-build:
  ./gradlew :sdk:emulator:assembleDebug --stacktrace

# Start emulator with writable system
[group('emulator')]
emulator-start avd=default_avd:
  emulator -avd {{avd}} -writable-system

# Start emulator in background with log file
[group('emulator')]
emulator-start-bg avd=default_avd:
  nohup emulator -avd {{avd}} -writable-system >/tmp/light-emulator.log 2>&1 &
  echo "emulator log: /tmp/light-emulator.log"

# Wait for configured adb target
[group('emulator')]
wait-device:
  {{adb}} wait-for-device

# Prepare writable system partition
[group('emulator')]
prepare-system:
  {{adb}} root
  {{adb}} remount || (echo "remount failed: try 'adb disable-verity && adb reboot' then rerun" && false)

# Install Light Page APK on emulator/device
[group('tool')]
tool-install apk=tool_apk:
  {{adb}} install -r {{apk}}

# Launch Light Page from launcher category
[group('tool')]
tool-launch tool=tool_id:
  {{adb}} shell monkey -p {{tool}} -c android.intent.category.LAUNCHER 1

# Install LightOS emulator as privileged system app
[group('emulator')]
system-emulator-install apk=emulator_apk:
  {{adb}} shell mkdir -p /system/priv-app/LightOSEmulator
  {{adb}} push {{apk}} /system/priv-app/LightOSEmulator/LightOSEmulator.apk
  {{adb}} reboot

# Rebuild and reinstall emulator app without full priv-app flow
[group('emulator')]
system-emulator-reinstall:
  ./gradlew :sdk:emulator:assembleDebug --stacktrace
  {{adb}} install -r {{emulator_apk}}

# Verify privileged install and test-keys image
[group('emulator')]
system-emulator-verify:
  {{adb}} shell pm path com.thelightphone.sdk.emulator
  {{adb}} shell dumpsys package com.thelightphone.sdk.emulator | grep "uid=1000"
  {{adb}} shell getprop ro.build.description | grep "test-keys"

# Set LightOS emulator as launcher
[group('emulator')]
set-launcher:
  {{adb}} shell cmd package set-home-activity com.thelightphone.sdk.emulator/.MainActivity

# Disable Android transition animations
[group('emulator')]
disable-animations:
  {{adb}} shell settings put global window_animation_scale 0
  {{adb}} shell settings put global transition_animation_scale 0
  {{adb}} shell settings put global animator_duration_scale 0

# Bring emulator up and remounted
[group('flow')]
emulator-setup avd=default_avd: (emulator-start-bg avd) wait-device prepare-system

# Fast local quality gate
[group('flow')]
dev-check: tool-lint tool-test tool-build
