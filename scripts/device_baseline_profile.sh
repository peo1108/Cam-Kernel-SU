#!/bin/bash
# Makes the Manager's Baseline Profile on the device (manager/baselineprofile), or measures cold
# start of a release APK.
#
#   scripts/device_baseline_profile.sh
#       build first (Windows: K: drive, docs/CAM_CHANGES.md section 5):
#         gradlew :app:assembleNonMinifiedRelease :baselineprofile:assembleNonMinifiedRelease
#       writes manager/app/src/release/generated/baselineProfiles/baseline-prof.txt (about 8 minutes:
#       the rule repeats the journey until the profile is stable)
#   scripts/device_baseline_profile.sh --benchmark [release.apk]
#       default: the newest app/build/outputs/apk/release APK (gradlew :app:assembleRelease).
#       Prints cold start in the two states an OTA install goes through (rootInstallScript,
#       AppCompile): "verify" is about the first launch after the update, "profile" every launch
#       after AppCompile compiled it against what ProfileInstaller put down.
#
# Never Gradle's generateBaselineProfile or connectedAndroidTest instead: both uninstall the APKs
# after the run, and uninstalling the Manager drops its data. This installs over the Manager with
# `install -r` (these builds are signed with the release key, root survives) and leaves it there;
# install a normal build afterwards (the profile build is four times the size). Both modes reset
# the Manager's compiled code and end by compiling it with speed-profile again.
#
# Keep the screen on and unlocked. Macrobenchmark's own startup benchmark cannot run on ZUI: it
# talks to ProfileInstallReceiver after force-stopping the app, and ZUI's auto-start manager drops
# broadcasts to a stopped app (result=0), so --benchmark only messages the Manager while it runs.
set -euo pipefail
export MSYS_NO_PATHCONV=1

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ADB="${ADB:-$(command -v adb || echo "${LOCALAPPDATA:-}/Android/Sdk/platform-tools/adb.exe")}"
PKG="${KSU_PACKAGE_NAME:-cam.su.kernel}"
TEST_PKG=cam.su.kernel.baselineprofile
DEVICE_OUT="/sdcard/Android/media/$TEST_PKG/additional_test_output"
SAVE="$ROOT/manager/app/src/release/generated/baselineProfiles"
RUNS=8

newest() {
  local best=""
  for f in "$@"; do
    [ -f "$f" ] && { [ -z "$best" ] || [ "$f" -nt "$best" ]; } && best="$f"
  done
  echo "$best"
}
winpath() { if command -v cygpath >/dev/null; then cygpath -m "$1"; else echo "$1"; fi; }
adb_install() { "$ADB" install -r "$@" | tail -1; }

# cold start (am start -W: first frame) of $2, $RUNS times; the first launch only fills caches
measure() {
  local times=() t i
  for i in $(seq 0 "$RUNS"); do
    t="$("$ADB" shell am start -W -S -n "$2" | tr -d '\r' | sed -n 's/^TotalTime: //p')"
    [ "$i" -gt 0 ] && times+=("$t")
    "$ADB" shell input keyevent KEYCODE_HOME
    sleep 2
  done
  printf '%s\n' "${times[@]}" | sort -n | awk -v state="$1" '{ t[NR] = $1 } END {
    printf "%-8s median %5d ms   min %5d   max %5d   (%d launches)\n", state, t[int((NR + 1) / 2)], t[1], t[NR], NR }'
}

if [ "${1:-}" = "--benchmark" ]; then
  APK="${2:-$(newest "$ROOT"/manager/app/build/outputs/apk/release/*-release.apk)}"
  [ -f "$APK" ] || { echo "error: build first: gradlew :app:assembleRelease" >&2; exit 1; }
  ACTIVITY="$("$ADB" shell cmd package resolve-activity --brief -c android.intent.category.LAUNCHER "$PKG" | tr -d '\r' | tail -1)"
  echo "== $(basename "$APK"), APK profile $(unzip -p "$APK" assets/dexopt/baseline.prof | wc -c) bytes"
  adb_install "$(winpath "$APK")"

  "$ADB" shell cmd package compile -m verify -f "$PKG" > /dev/null
  measure verify "$ACTIVITY"

  # what the first launch after an OTA install sets up: no profile, ProfileInstaller writes the
  # APK's (asked for here while the app runs, see the header), AppCompile compiles against it
  "$ADB" shell cmd package compile --reset "$PKG" > /dev/null
  "$ADB" shell am start -W -n "$ACTIVITY" > /dev/null
  sleep 3
  "$ADB" shell am broadcast -a androidx.profileinstaller.action.INSTALL_PROFILE \
    "$PKG/androidx.profileinstaller.ProfileInstallReceiver" | tr -d '\r' | tail -1 | sed 's/^/   ProfileInstaller: /'
  sleep 17
  "$ADB" shell am force-stop "$PKG"
  "$ADB" shell cmd package compile -m speed-profile -f "$PKG" > /dev/null
  measure profile "$ACTIVITY"
  exit 0
fi

APP="$(newest "$ROOT"/manager/app/build/outputs/apk/nonMinifiedRelease/*-nonMinifiedRelease.apk)"
TEST="$(newest "$ROOT"/manager/baselineprofile/build/outputs/apk/nonMinifiedRelease/*-nonMinifiedRelease.apk)"
[ -n "$APP" ] && [ -n "$TEST" ] || {
  echo "error: build first: gradlew :app:assembleNonMinifiedRelease :baselineprofile:assembleNonMinifiedRelease" >&2
  exit 1
}
echo "== installing $(basename "$APP") and $(basename "$TEST")"
adb_install "$(winpath "$APP")"
adb_install -t "$(winpath "$TEST")"
# ZUI (Lenovo) hides installed apps from the test app behind its own app-op, whatever
# QUERY_ALL_PACKAGES says ("queryIntentActivitiesInternal: not allow"); other ROMs lack the op
"$ADB" shell appops set "$TEST_PKG" GET_INSTALLED_APP allow 2>/dev/null || true
"$ADB" shell rm -rf "$DEVICE_OUT"

log="$(mktemp)"
tmp="$(mktemp -d)"
trap 'rm -rf "$log" "$tmp"' EXIT
"$ADB" shell am instrument -w -r -e class "$TEST_PKG.BaselineProfileGenerator" \
  -e androidx.benchmark.enabledRules BaselineProfile -e targetAppId "$PKG" \
  -e additionalTestOutputDir "$DEVICE_OUT" \
  "$TEST_PKG/androidx.test.runner.AndroidJUnitRunner" | tr -d '\r' > "$log" || true
"$ADB" shell cmd package compile -m speed-profile -f "$PKG" | tail -1

if grep -q "INSTRUMENTATION_STATUS_CODE: -2\|INSTRUMENTATION_RESULT: shortMsg\|FAILURES!!!" "$log"; then
  grep -m1 -A8 "^INSTRUMENTATION_STATUS: stack=\|shortMsg" "$log" >&2
  echo "== failed; nothing saved" >&2
  exit 1
fi

"$ADB" pull "$DEVICE_OUT" "$(winpath "$tmp")" > /dev/null 2>&1
profile="$tmp/additional_test_output/BaselineProfileGenerator_launchAndTabs-baseline-prof.txt"
[ -f "$profile" ] || { ls -R "$tmp" >&2; echo "error: no profile pulled" >&2; exit 1; }
mkdir -p "$SAVE"
cp "$profile" "$SAVE/baseline-prof.txt"
echo "== saved $(wc -l < "$SAVE/baseline-prof.txt") rules to ${SAVE#"$ROOT"/}/baseline-prof.txt"
