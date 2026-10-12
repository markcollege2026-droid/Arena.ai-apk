# Test report

**Status: pending the first CI run.** This file is filled in with the *actual* output of the workflow in
`.github/workflows/android.yml` - command, environment, counts, failures, and links - and is updated
after every run. Nothing below is inferred from reading the code: if a claim has no test behind it, it is
listed as untested instead of asserted.

## How to reproduce

```bash
./gradlew :app:testDebugUnitTest        # security core, JVM
./gradlew :app:assembleDebug :app:assembleRelease
./gradlew :app:connectedDebugAndroidTest # guards on a device/emulator (needs an emulator)
```

or watch the CI run for this commit: see the Actions tab of the repository.

## Contents (filled in after the first green run)

1. Environment: runner image, JDK, Android SDK/build-tools, Gradle, AGP, emulator API level.
2. Unit test results: number of tests, the URL case table size, pass/fail.
3. APK verification: `aapt2 dump badging` output excerpt (package id, versionCode/Name, minSdk/targetSdk,
   permissions, launchable activity, absence of `application-debuggable`), SHA-256 of the artifacts.
4. Instrumented test results: per-class outcome on the emulator.
5. Live smoke test: the URL the app ended up on, whether `evaluateJavascript` returned a value, and the
   captured diagnostics report from the device.
6. Screenshot captured from the emulator after the tests (`docs/evidence/app-screenshot.png`).
7. Anything that failed, was skipped, or is *not* covered by an automated test - stated plainly.
