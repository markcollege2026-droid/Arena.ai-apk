# Raw CI evidence

Verbatim excerpts from the GitHub Actions run, kept in the repository so the numbers in
[TEST_REPORT.md](../TEST_REPORT.md) can be checked without trusting the summary. Nothing here is
paraphrased.

* **Workflow:** `.github/workflows/android.yml` ("Android CI")
* **Run:** https://github.com/markcollege2026-droid/Arena.ai-apk/actions/runs/38287613538
* **Commit:** `8697d6c3567a93e09f28a4d5ffc15b43e28a7b42`
* **Runner:** `ubuntu-24.04` (image `20261004.327.1`), region `westus3`

## 1. Job `unit-tests` — passed

```
> Found known Gradle Wrapper JAR files:
  2db75c40782f5e8ba1fc278a5574bab070adccb2d21ca5a6e5ed840888448046 gradle/wrapper/gradle-wrapper.jar

> Task :app:compileDebugJavaWithJavac
> Task :app:compileDebugUnitTestJavaWithJavac
> Task :app:testDebugUnitTest

BUILD SUCCESSFUL

1640 tests completed, 0 failed
```

(The wrapper-jar hash above is the value pinned in `gradle/wrapper/gradle-wrapper.jar.sha256` and
checked by the workflow; the same digest is also validated by `gradle/actions/wrapper-validation`.)

The previous run of the same workflow (`b3853fe`, run 38287200419) failed with 4 failures, which were
diagnosed from the log and fixed in `8697d6c`:

```
ManifestPolicyTest > networkSecurityConfigIsHttpsOnlyWithSystemAnchors FAILED
    org.junit.ComparisonFailure at ManifestPolicyTest.java:135
SourceInvariantsTest > downloadsAreRefused FAILED
    java.lang.AssertionError at SourceInvariantsTest.java:139
SourceInvariantsTest > theAppNeverStartsAnotherActivity FAILED
    java.lang.AssertionError at SourceInvariantsTest.java:83
SourceInvariantsTest > tlsErrorsAreNeverBypassed FAILED
    java.lang.AssertionError at SourceInvariantsTest.java:65

1640 tests completed, 4 failed
```

Cause and fix, for the record:

* The network security config uses *unprefixed* attributes (`cleartextTrafficPermitted`, `src`), so
  the test's `android:`-namespace lookup returned empty strings. The test now reads them with a plain
  `getAttribute` helper (`ManifestPolicyTest.configAttr`).
* The three `SourceInvariantsTest` failures were the scanner tripping over the *comments* of the code
  it inspects (e.g. a Javadoc that mentions `handler.proceed()` while explaining why it is never
  called). The scanner now strips comments - preserving string and character literals, so URLs inside
  string literals survive - before asserting.

## 2. Job `build-apk` — passed

```
./gradlew --no-daemon :app:assembleDebug :app:assembleRelease --stacktrace

APK: app/build/outputs/apk/release/app-release.apk
package: name='app.arenawebview.locked' versionCode='1' versionName='1.0.0'
         platformBuildVersionName='15' platformBuildVersionCode='35' compileSdkVersion='35'
minSdkVersion:'24'
targetSdkVersion:'35'
uses-permission: name='android.permission.INTERNET'
uses-permission: name='android.permission.ACCESS_NETWORK_STATE'
application-label:'Arena WebView (Locked)'
launchable-activity: name='app.arenawebview.locked.MainActivity'  label='' icon=''
supports-screens: 'small' 'normal' 'large' 'xlarge'

ok: package id
ok: has a version name
ok: INTERNET permission
ok: no CAMERA permission
ok: no location permission
ok: no storage permission
ok: no READ_CONTACTS
ok: not debuggable
ok: launcher entry present
ok: one launcher entry only
```

Checksums produced by the run (uploaded as `APK-SHA256SUMS.txt` next to both APKs):

```
353333dba096efcfc1c9978091fe0122a9efdb02c75c914a8a635fbf62151087  app/build/outputs/apk/debug/app-debug.apk
7f355f5cd3c8a855d18f8051e7149f599ef651effd7a09e5f6262d3b8ac0b76c  app/build/outputs/apk/release/app-release.apk
```

`application-debuggable` is absent from the release badging - the check asserts its absence, so a
debuggable release APK fails the build rather than shipping.

## 3. Job `instrumented-tests` (emulator)

_filled in from the same run once the emulator job completes; see TEST_REPORT.md §3._
