#!/usr/bin/env bash
#
# scripts/perf.sh
#
# Runs the performance suite (SdkFootprintTest): starts the demo app in fresh processes WITH Galva
# and WITHOUT it (baseline), measures memory / launch / CPU, and writes a before/after report to
# app/build/reports/perf-report.md. Exits non-zero if a gating budget is breached (the memory
# delta), like the iOS SDK's scripts/perf.sh.
#
# Usage:
#     ./scripts/perf.sh
#     ANDROID_SERIAL=emulator-5554 ./scripts/perf.sh    # pick the device when several are attached
#
# Requires: a device or emulator, and app/local.properties with GALVA_API_KEY (any value).
#

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPORT="$ROOT/app/build/reports/perf-report.md"

if ! command -v adb >/dev/null 2>&1; then
    echo "error: adb not found on PATH" >&2
    exit 2
fi

echo "==> Running the performance suite"
set +e
"$ROOT/gradlew" -p "$ROOT" :app:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=io.galva.sample.e2e.SdkFootprintTest \
    -Pandroid.testInstrumentationRunnerArguments.galvaPerf=true
STATUS=$?
set -e

# Extract the latest before/after report the test logged between sentinel markers (the device log
# is not cleared, so earlier runs may still be there).
mkdir -p "$(dirname "$REPORT")"
adb logcat -d -v raw -s GalvaPerf:I | awk '
    /===GALVA-PERF-REPORT-BEGIN===/ { inside = 1; report = ""; next }
    /===GALVA-PERF-REPORT-END===/   { inside = 0; last = report; next }
    inside { sub(/^ $/, ""); report = report $0 "\n" }
    END { printf "%s", last }
' > "$REPORT"

if [ -s "$REPORT" ]; then
    echo
    cat "$REPORT"
    echo
    echo "==> Report: $REPORT"
else
    echo "warning: no performance report found in logcat" >&2
fi

exit $STATUS
