# Raw CI evidence

Verbatim excerpts from the GitHub Actions runs, kept in the repository so every number in
[TEST_REPORT.md](../TEST_REPORT.md) can be checked without trusting the summary. Nothing here is
paraphrased.

**Workflow:** [`.github/workflows/android.yml`](../.github/workflows/android.yml) ("Android CI")

| Run | Commit | What it was | Result |
| --- | --- | --- | --- |
| [38287200419](https://github.com/markcollege2026-droid/Arena.ai-apk/actions/runs/38287200419) | `b3853fe` | first build | 4 unit-test failures (test bugs) |
| [38287613538](https://github.com/markcollege2026-droid/Arena.ai-apk/actions/runs/38287613538) | `8697d6c` | after fixing those | unit + APK green; instrumented suite did not compile (3 wrong `WebSettings` getters) |
| [38288257746](https://github.com/markcollege2026-droid/Arena.ai-apk/actions/runs/38288257746) | `4252a89` | after fixing those | unit + APK green; emulator: 32 tests, 1 failure (test built a `Message` with no target handler) |
| [38288999321](https://github.com/markcollege2026-droid/Arena.ai-apk/actions/runs/38288999321) | `b34907f` | after that fix | **unit-tests, build-apk and instrumented-tests all green** |

Environment for the green run: `ubuntu-24.04` (image `20261004.327.1`), Temurin JDK `17.0.20+1`,
Gradle 8.11 via the committed wrapper, AGP 8.7.3, compileSdk/targetSdk 35, minSdk 24, emulator
API 34 `google_apis` x86_64 (`emulator-5554`).

## 1. Job `unit-tests` - passed

```
> Found known Gradle Wrapper JAR files:
  2db75c40782f5e8ba1fc278a5574bab070adccb2d21ca5a6e5ed840888448046 gradle/wrapper/gradle-wrapper.jar

> Task :app:compileDebugJavaWithJavac
> Task :app:compileDebugUnitTestJavaWithJavac
> Task :app:testDebugUnitTest

BUILD SUCCESSFUL

1640 tests completed, 0 failed
```

The wrapper-jar digest above is the value pinned in `gradle/wrapper/gradle-wrapper.jar.sha256` (checked
by a workflow step) and cross-checked by `gradle/actions/wrapper-validation`.

Failures from the earlier runs, kept for the record:

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
```
WebViewSettingsTest.java:74: error: cannot find symbol   settings.getSupportZoom()
WebViewSettingsTest.java:79: error: cannot find symbol   settings.getSupportMultipleWindows()
WebViewSettingsTest.java:115: error: cannot find symbol  settings.getGeolocationEnabled()
3 errors
```
```
PopupBlockingTest > onCreateWindowHandsOverAHiddenWebView FAILED
    java.lang.NullPointerException: Attempt to invoke virtual method
    'boolean android.os.Handler.sendMessage(android.os.Message)' on a null object reference
    at android.os.Message.sendToTarget(Message.java:468)
```

## 2. Job `build-apk` - passed

```
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

Checksums of the artifacts of this run (also uploaded as `APK-SHA256SUMS.txt`):

```
7c434a8147ac7d4882b529633bd1283dfee9a8c65a489568e76739e251d944b7  app/build/outputs/apk/debug/app-debug.apk
36ebcf7ab390c57269dac0f034e05d58d1478074b4f2e513c606964215a411ff  app/build/outputs/apk/release/app-release.apk
```

`application-debuggable` is absent from the release badging - the check asserts its absence, so a
debuggable release APK fails the build rather than shipping.

## 3. Job `instrumented-tests` - passed (API 34 emulator)

```
> Task :app:connectedDebugAndroidTest
Starting 32 tests on emulator-5554 - 14
Tests on emulator-5554 - 14 passed
```

### Guard decisions captured on the device (`adb logcat -s ArenaWebViewLocked:V`)

Policy and start-up:

```
I ArenaWebViewLocked: start with policy:
I ArenaWebViewLocked: start=https://arena.ai/
I ArenaWebViewLocked: allow arena.ai  // The product itself (canonical host).
I ArenaWebViewLocked: allow www.arena.ai  // Canonical alternate host for the same site.
I ArenaWebViewLocked: allow auth.arena.ai  // First-party authentication service (session/credential API for sign-in).
I ArenaWebViewLocked: allow cdn.arena.ai  // First-party static asset CDN.
I ArenaWebViewLocked: allow agent.arena.ai  // Agent Mode first-party endpoint.
I ArenaWebViewLocked: allow code.arena.ai  // Code/artifact first-party endpoint.
I ArenaWebViewLocked: allow indexable.arena.ai  // Pre-rendered content used by the site.
I ArenaWebViewLocked: allow blog.arena.ai  // First-party blog (linked from the site).
I ArenaWebViewLocked: allow news.arena.ai  // First-party news pages (linked from the site).
I ArenaWebViewLocked: allow help.arena.ai  // First-party help centre (linked from the site).
I ArenaWebViewLocked: allow status.arena.ai  // First-party status page (linked from the site).
I ArenaWebViewLocked: allow trust.arena.ai  // First-party trust/compliance centre (linked from the site).
I ArenaWebViewLocked: load (startup): arena.ai
```

The real site running inside the wrapper (console messages injected from arena.ai's own scripts):

```
D ArenaWebViewLocked: console: Visit ID: 01a13152-a1e2-72a9-a2ca-ddf5ffe05de2
  @https://arena.ai/_next/static/chunks/18zyla74w09g6.js?dpl=dpl_Cm8x5vMyPYcFGRmmj6AQ8KvVBfTA:1
D ArenaWebViewLocked: console: Deprecated API for given entry type.
  @https://arena.ai/rpc/static/web-vitals.js?v=1.360.0:1
```

Third-party requests that the policy refused - the page tried, the wrapper did not let them out:

```
D ArenaWebViewLocked: console: Access to script at
  'https://static.cloudflareinsights.com/beacon.min.js/v4bc70e2c01a94c73b74392e4234840661791215815920'
  from origin 'https://arena.ai' has been blocked by CORS policy
D ArenaWebViewLocked: console: Access to fetch at
  'https://browser-intake-us3-datadoghq.com/api/v2/rum?ddsource=browser&...'
  from origin 'https://arena.ai' has been blocked by CORS policy
```

(The "CORS policy" wording is Chromium's: the request was answered by the wrapper's in-process refusal,
so the script sees a response without CORS headers. The important part is that the beacon request never
reached Cloudflare Insights or Datadog.)

The watchdog and the recovery path, driving `WatchdogRecoveryTest`:

```
W ArenaWebViewLocked: watchdog found a non-approved URL, resetting: BLOCK reason=HOST_NOT_ALLOWED host=evil.example
W ArenaWebViewLocked: recovery (onPageStarted:HOST_NOT_ALLOWED) from https://evil.example/definitely-not-allowed
W ArenaWebViewLocked: recovery (onPageFinished:HOST_NOT_ALLOWED) from https://evil.example/definitely-not-allowed
W ArenaWebViewLocked: recovery (history:HOST_NOT_ALLOWED) from https://evil.example/definitely-not-allowed
W ArenaWebViewLocked: refused internal load (test): BLOCK reason=HOST_NOT_ALLOWED host=evil.example
I ArenaWebViewLocked: ignored incoming intent: action=null data=null (this app has no deep links)
```

### Artifacts published by the job

`app/build/reports/androidTests/connected/**` (HTML + XML per-test results),
`app/build/outputs/androidTest-results/**`, `docs/evidence/device-logcat.txt`,
`docs/evidence/app-screenshot.png`, `docs/evidence/device-activities.txt`.

`app-screenshot.png` is captured after the tests by reinstalling the debug APK, launching it and letting
the site load for ~25 s in the emulator; it shows what this environment rendered, which is evidence of
rendering and not a pixel-level assertion.
