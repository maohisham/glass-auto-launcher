#!/usr/bin/env bash
# Build glass-auto-launcher and install as launcher on a connected Android device.
# Usage: CAR_HOST=192.168.0.xxx ./build-and-install.sh
set -e

PKG="com.glass.auto"
APK="app/build/outputs/apk/release/app-release.apk"

echo "=== Building Glass Auto ==="
./gradlew assembleRelease

echo "=== Installing on ${CAR_HOST:-connected device} ==="
if [ -n "$CAR_HOST" ]; then
    adb connect "$CAR_HOST:5555"
fi

adb install -r "$APK"

echo "=== Setting as default Home ==="
adb shell cmd package set-home-activity "$PKG/.CarActivity"

echo "=== Done. Tap Home on the device to launch Glass Auto. ==="
