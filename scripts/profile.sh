#!/usr/bin/env bash
#
# scripts/profile.sh
#
# Profiles Galva's startup CPU in release-like code: builds the demo app's `profiling` build type
# (R8-shrunk, not debuggable, profileable, readable names), starts it in fresh processes with and
# without Galva, and reports where the CPU went, charged to SDK methods. Traces are kept in
# app/build/reports/profile for Android Studio.
#
# Usage:
#     ./scripts/profile.sh                      # 5 runs with Galva, 5 without
#     ./scripts/profile.sh --runs 3 --modes sdk
#     ANDROID_SERIAL=emulator-5554 ./scripts/profile.sh
#
# Requires: Python 3, a device or emulator (Android 10+), and app/local.properties with
# GALVA_API_KEY: with no mock backend, the profiling build talks to the Development environment.
#

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PYTHON="$(command -v python3 || command -v python)"

echo "==> Building the profiling APK"
"$ROOT/gradlew" -p "$ROOT" :app:assembleProfiling -q

cd "$ROOT"
"$PYTHON" "$ROOT/scripts/profile.py" --apk "$ROOT/app/build/outputs/apk/profiling/app-profiling.apk" "$@"
