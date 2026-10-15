#!/bin/bash
# Runs the Manager's instrumented tests (manager/app/src/androidTest) on the device over adb.
#
#   1. build both APKs (Windows: K: drive, docs/CAM_CHANGES.md section 5):
#        Push-Location K:\manager; & K:\manager\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest
#   2. scripts/device_android_test.sh [test class]     e.g. cam.su.kernel.CamRootServiceTest
#
# Never use Gradle's connectedAndroidTest instead: it uninstalls the APKs after the run, and
# uninstalling the Manager drops its data (settings, account, library state). This installs over
# the Manager with `install -r` (debug builds are signed with the release key, root survives) and
# leaves both APKs installed.
#
# The root tests (CamRootServiceTest, CamFileServiceTest) only read, or work inside
# /data/local/tmp/cam_androidtest; they are skipped when the Manager has no root.
set -euo pipefail
export MSYS_NO_PATHCONV=1

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ADB="${ADB:-$(command -v adb || echo "${LOCALAPPDATA:-}/Android/Sdk/platform-tools/adb.exe")}"
PKG="${KSU_PACKAGE_NAME:-cam.su.kernel}"
OUT="$ROOT/manager/app/build/outputs/apk"

newest() {
  local best=""
  for f in "$@"; do
    [ -f "$f" ] && { [ -z "$best" ] || [ "$f" -nt "$best" ]; } && best="$f"
  done
  echo "$best"
}
APP="$(newest "$OUT"/debug/*-debug.apk)"
TEST="$(newest "$OUT"/androidTest/debug/*-debug-androidTest.apk)"
[ -n "$APP" ] && [ -n "$TEST" ] || {
  echo "error: build first: gradlew :app:assembleDebug :app:assembleDebugAndroidTest" >&2
  exit 1
}

winpath() { if command -v cygpath >/dev/null; then cygpath -m "$1"; else echo "$1"; fi; }
echo "== installing $(basename "$APP") and $(basename "$TEST")"
"$ADB" install -r "$(winpath "$APP")" | tail -1
"$ADB" install -r -t "$(winpath "$TEST")" | tail -1

args=()
[ -n "${1:-}" ] && args=(-e class "$1")
log="$(mktemp)"
trap 'rm -f "$log"' EXIT
"$ADB" shell am instrument -w -r "${args[@]}" "$PKG.test/androidx.test.runner.AndroidJUnitRunner" | tr -d '\r' > "$log"

# -r prints raw status blocks; summarize them
awk '
  /^INSTRUMENTATION_STATUS: class=/ { cls = substr($0, index($0, "=") + 1) }
  /^INSTRUMENTATION_STATUS: test=/ { test = substr($0, index($0, "=") + 1) }
  /^INSTRUMENTATION_STATUS: stack=/ { stack = substr($0, index($0, "=") + 1); grab = 1; next }
  grab && /^INSTRUMENTATION_STATUS/ { grab = 0 }
  grab && stack_lines < 6 { stack = stack "\n      " $0; stack_lines++ }
  /^INSTRUMENTATION_STATUS_CODE: / {
    code = $2
    if (code == 1) { stack = ""; stack_lines = 0; next }
    name = cls "#" test
    if (code == 0) { print "PASS " name; pass++ }
    else if (code == -3) { print "SKIP " name; skip++ }
    else if (code == -4) { print "SKIP " name " (assumption)"; skip++ }
    else { print "FAIL " name "\n      " stack; fail++ }
    stack = ""; stack_lines = 0
  }
  /^INSTRUMENTATION_RESULT: shortMsg=/ { crash = $0 }
  END {
    if (crash != "") print crash
    printf "== %d passed, %d failed, %d skipped\n", pass, fail, skip
    exit (fail > 0 || crash != "" || pass == 0) ? 1 : 0
  }
' "$log"
