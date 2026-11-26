#!/usr/bin/env bash
# Measures whether the app's main WebView and sidebar are fully scrollable at one screen size.
# Invoked from the emulator runner; it must be a single command because that action executes each
# line of its `script:` input in a separate shell.
set -u
SCREEN="${1:?usage: responsive-probe.sh WxH}"
EVIDENCE="docs/evidence/responsive"
mkdir -p "$EVIDENCE"
set -x
adb shell wm size "$SCREEN" 2>&1 | tee "$EVIDENCE/wm-size.txt"
adb shell wm density 320 2>&1 | tee "$EVIDENCE/wm-density.txt"
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 0
adb logcat -c || true
set -o pipefail
./gradlew --no-daemon :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=app.arenawebview.locked.web.ResponsiveScrollTest \
  --stacktrace 2>&1 | tee "$EVIDENCE/gradle-$SCREEN.log"
GRADLE_EXIT="${PIPESTATUS[0]}"
adb logcat -d -s ArenaWebViewLocked:V > "$EVIDENCE/logcat-$SCREEN.txt" || true
echo "=== responsive probe at $SCREEN ==="
grep 'responsive probe' "$EVIDENCE/logcat-$SCREEN.txt" || true
echo "gradle exit: $GRADLE_EXIT"
exit "$GRADLE_EXIT"
