#!/usr/bin/env bash
# Provisions the arm64 executable that RuntimeFeasibilityTest needs.
#
# The test has to exec a *real* arm64 Bionic binary from nativeLibraryDir to
# measure what an app is permitted to run. Rather than commit one - a vendor
# system binary is not ours to redistribute, and a downloaded one would drag a
# third party into the build - it is copied off the connected device, which by
# definition has a binary that runs on it.
#
# The result is git-ignored. Run this once before ./gradlew connectedDebugAndroidTest.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
DEST="$ROOT/apps/android/app/src/debug/jniLibs/arm64-v8a"
export MSYS_NO_PATHCONV=1

if [ "$(adb devices | tail -n +2 | grep -c 'device$')" -eq 0 ]; then
  echo "ENV: no device connected; cannot provision the exec fixture."
  exit 2
fi

abi="$(adb shell getprop ro.product.cpu.abi | tr -d '\r')"
if [ "$abi" != "arm64-v8a" ]; then
  echo "ENV: connected device is $abi, not arm64-v8a."
  exit 2
fi

mkdir -p "$DEST"
adb pull /system/bin/sh "$DEST/libspikesh.so" | tail -1
echo "provisioned $DEST/libspikesh.so from the device"
