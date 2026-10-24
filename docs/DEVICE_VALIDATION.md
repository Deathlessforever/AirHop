# AirHop Android device validation checklist

This checklist separates automated checks from real-device evidence. A green unit-test
or build job does **not** prove that Bluetooth relaying, Wi-Fi Aware, background
services, location, audio capture, or disaster alerts work on a particular phone.

## 1. Record the test environment

For every run, record:

- Phone model and Android version
- AirHop commit SHA and APK build variant
- Whether the phone has Google Play services and whether permissions were granted
- Battery-saver state, Bluetooth/Wi-Fi state, and whether the app was freshly installed
- Exact reproduction steps and expected versus observed behavior

## 2. Install and launch

1. Build with `./gradlew test --console=plain`.
2. Build the debug APK with `./gradlew assembleDebug --console=plain`.
3. Install with `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
4. Launch the app and exercise each visible button once, waiting for the UI to settle.
5. Repeat after force-stop and relaunch; then repeat after a device reboot.

If the app closes or a button crashes, capture logs immediately:

```bash
adb logcat -c
adb shell monkey -p com.team.vocalink -c android.intent.category.LAUNCHER 1
adb logcat -d -v threadtime > airhop-logcat.txt
```

If the installed application ID differs from `com.team.vocalink`, use the actual
application ID from `app/build.gradle.kts`. Remove personal identifiers from logs
before sharing them publicly.

## 3. Two-device offline test

Use two physical Android phones with SIM/mobile data disabled. Do not claim a successful
mesh test from a single-device simulation.

- Confirm the app starts without internet access.
- Confirm required runtime permissions are requested and denial is handled gracefully.
- Send a harmless test message and record sender/receiver timestamps.
- Test duplicate packets, expired TTL, malformed frames, and a relay node between phones.
- Repeat with screen off, battery saver enabled, and after Bluetooth is toggled off/on.
- Record whether Wi-Fi Aware is supported on both devices before testing that fallback.

For every run, record the observed peer count, packet identifier, hop count, delivery
result, and any logcat exception. A UI counter alone is not proof that a packet reached
another phone.

## 4. Safety and evidence rules

- Never use emergency services as a test recipient; use clearly labelled test messages.
- Do not describe a test as offline unless mobile data, Wi-Fi internet access, and SIM
  connectivity were actually disabled for that run.
- Do not describe multi-hop as verified until a third physical device successfully relays
  a packet between two endpoints.
- Keep raw logs and screenshots with the commit SHA so failures can be reproduced.
- Mark untested device/OS combinations as **not tested**, rather than assuming compatibility.

## Current status

This file is a test procedure, not a test report. No physical-device results are asserted
by adding this checklist; actual results must be recorded after running the steps.
