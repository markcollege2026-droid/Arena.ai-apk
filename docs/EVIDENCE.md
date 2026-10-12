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
| [38288999321](https://github.com/markcollege2026-droid/Arena.ai-apk/actions/runs/38288999321) | `b34907f` | after that fix | unit-tests, build-apk and instrumented-tests all green |
| [38289760091](https://github.com/markcollege2026-droid/Arena.ai-apk/actions/runs/38289760091) | `5134404`, tag `v1.0.0` | the 1.0.0 release run | **all four jobs green, including `Publish release`** - this is the run whose APKs are on the [release](https://github.com/markcollege2026-droid/Arena.ai-apk/releases/tag/v1.0.0) |
| [38297414026](https://github.com/markcollege2026-droid/Arena.ai-apk/actions/runs/38297414026) | `cb94dee` | first 1.1.0 run (Google sign-in + in-app CAPTCHA) | `unit-tests` and `build-apk` green; `instrumented-tests` 45/46 - one test called a WebView method off the main thread (a bug in the test, fixed) |
| [38321983881](https://github.com/markcollege2026-droid/Arena.ai-apk/actions/runs/38321983881) (push) and [38321979886](https://github.com/markcollege2026-droid/Arena.ai-apk/actions/runs/38321979886) (PR) | `4685e71` | the fix for the failure a user hit on the built APK (a widget frame is judged as an embedded document; Google's asset paths are no longer enumerated) | **all three jobs green in both runs**: `unit-tests`, `build-apk`, `instrumented-tests` 52/52. The run's device log is quoted in §6b and §7 |

Environment for the green run: `ubuntu-24.04` (image `20261004.327.1`), Temurin JDK `17.0.20+1`,
Gradle 8.11 via the committed wrapper, AGP 8.7.3, compileSdk/targetSdk 35, minSdk 24, emulator
API 34 `google_apis` x86_64 (`emulator-5554`).

## 0. Preparing 1.1.0 without an Android SDK (local evidence)

The sandbox this change was written in has no Android SDK and no network access to Google's Maven
repository, so the APK cannot be built locally - that remains GitHub Actions' job. What *could* be run
locally is the Android-free half of the suite, and it was, against a real compiler and a real JVM
(`javac 1.8.0_152`, from an AOSP `prebuilts-jdk` checkout, plus a small JUnit-compatible runner that
understands `@Test` and `@RunWith(Parameterized.class)`):

```
$ javac -encoding UTF-8 -d /tmp/out <policy sources> <jvm test sources>     # clean, no warnings shown
$ java -cp /tmp/out TestRunner --scan app/src/test/java app/src/test/java
Tests run: 2225, Failures: 0, Ignored: 0, Test classes: 8 (216 ms)
```

Per class, from the same run:

```
2176  app.arenawebview.locked.policy.UrlPolicyTest
  11  app.arenawebview.locked.policy.AllowlistRegressionTest
   5  app.arenawebview.locked.policy.BackNavigatorTest
   6  app.arenawebview.locked.policy.PolicyMetricsTest
   4  app.arenawebview.locked.policy.PolicyPurityTest
   5  app.arenawebview.locked.ManifestPolicyTest
  13  app.arenawebview.locked.SourceInvariantsTest
   5  app.arenawebview.locked.ResourcePolicyTest
```

`UrlPolicyTest`'s 2176 comes from 272 cases x 8 assertions; the 272 are 229 base cases plus 43 cases that
describe the sign-in/CAPTCHA configuration (`PolicyCases.all()` and
`PolicyCases.withGoogleSignInAndCaptchaEnabled()`).

The first CI attempt at this change failed, and the reason is worth keeping. `aapt2` refused the values
resource:

```
Caused by: com.android.aaptcompiler.ResourceCompilationException: Resource compilation failed
  (Failed to compile values resource file .../merged.dir/values/values.xml. Cause:
   java.lang.IllegalStateException: Can not extract resource from com.android.aaptcompiler.ParsedResource@5cb9dfdd.)
Caused by: java.lang.IllegalStateException: Can not extract resource from com.android.aaptcompiler.ParsedResource@5cb9dfdd.
BUILD FAILED in 32s
```

The offender was the new sign-in copy in `res/values/strings.xml`, which contained `Google's` - an
unescaped apostrophe is valid XML but is rejected by the Android resource compiler. The JVM suite could
not see it, because resource compilation happens inside the Android build. The copy now avoids the
apostrophe, and `ResourcePolicyTest` applies that rule on the JVM (plus duplicate resource names and the
network-config wiring) so the next occurrence is caught in a second rather than in a run.

The first local run was **not** clean, and the failure is the reason this section exists:

```
FAILED AllowlistRegressionTest.firstPartyHostsAreExactlyTheReviewedSet:
  expected: <[arena.ai, www.arena.ai, ... trust.arena.ai]>            # 12 hosts
  but was:  <[arena.ai, ..., trust.arena.ai, accounts.google.com, ... challenges.cloudflare.com]>   # 21
```

`allowedHosts()` is the *whole* enforced set, so the rewritten assertion was wrong, not the allow-list.
It now asserts `firstPartyHosts + thirdPartyHosts == allowedHosts()` with no duplicates, which also fails
if a host is ever enforced without being listed in one of the two reviewed lists.

## 1. Job `unit-tests` - passed (1.0.0)

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

Release run (`v1.0.0`) - these are the bytes attached to the release:

```
ac9a86673d0c18faa14e806712b3e89fdd929b57bc4449c107ee7e7c5743ec1f  app/build/outputs/apk/debug/app-debug.apk
dcf418e8438071badf57f52411533f9b2a9fa987b07e52311ffc6af608500a3a  app/build/outputs/apk/release/app-release.apk
```

For comparison, the previous run (`b34907f`) produced
`7c434a81...d944b7` (debug) and `36ebcf7a...411ff` (release) from identical sources - the difference is
the signing block's timestamps, not the code, which is why every run prints its own checksums.

`application-debuggable` is absent from the release badging - the check asserts its absence, so a
debuggable release APK fails the build rather than shipping.

## 3. Job `instrumented-tests` - passed (API 34 emulator, 1.0.0)

```
> Task :app:connectedDebugAndroidTest
Starting 32 tests on emulator-5554 - 14
Tests on emulator-5554 - 14 passed
```

### Guard decisions captured on the device (`adb logcat -s ArenaWebViewLocked:V`)

### The live site, on the device

```
I ArenaWebViewLocked: live smoke test: url after load = https://arena.ai/
I ArenaWebViewLocked: live smoke test diagnostics:
I ArenaWebViewLocked: evaluateJavascript returned: 2
```

(`evaluateJavascript` runs `(function(){return 1+1;})()` in the page - a correct `2` means the site's
JavaScript is executing, not merely that a URL was reached.)

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

---

# The 1.1.0 runs

Everything in this section is filled in from the GitHub Actions runs that build and test the 1.1.0 APK,
in the same verbatim style as above: commit `cb94dee` (the first attempt, which is where the failure a
user hit was reproduced) and commit `4685e71` (the fix, which is what the release is built from).
Nothing here is a prediction.

## 4. Job `unit-tests` (1.1.0, commit `cb94dee`) - passed

```
> Task :app:compileDebugUnitTestJavaWithJavac
> Task :app:testDebugUnitTest

BUILD SUCCESSFUL
1930 tests completed, 0 failed
```

That run's suite was the 1930-test one; the allow-list fix described in §7 added the cases that would
have caught the field failure, so the current suite is 2225 tests (see §0). The same job failed on the
first attempt of this change with the `aapt2` resource error quoted in §0.

## 4b. Job `unit-tests` (this release, commit `4685e71`) - passed

```
2026-10-12T23:24:51.7495215Z gradle/wrapper/gradle-wrapper.jar: OK
2026-10-12T23:24:52.9436013Z ✓ Found known Gradle Wrapper JAR files:
2026-10-12T23:24:52.9437081Z   2db75c40782f5e8ba1fc278a5574bab070adccb2d21ca5a6e5ed840888448046 gradle/wrapper/gradle-wrapper.jar
2026-10-12T23:25:33.8727424Z > Task :app:compileDebugUnitTestJavaWithJavac
2026-10-12T23:25:36.2710601Z > Task :app:testDebugUnitTest
2026-10-12T23:25:36.2718155Z BUILD SUCCESSFUL in 43s
2026-10-12T23:25:36.2718630Z 21 actionable tasks: 20 executed, 1 from cache
```

The suite Gradle just ran is the 2225-test one described in §0 (the JVM half is Android-free and
deterministic, so the local count and the CI count are the same sources); the wrapper-jar checksum step
ran before it, as designed.

## 5. Job `build-apk` (this release, commit `4685e71`) - passed

```
2026-10-12T23:26:45.9423843Z package: name='app.arenawebview.locked' versionCode='2' versionName='1.1.0' platformBuildVersionName='15' platformBuildVersionCode='35' compileSdkVersion='35' compileSdkVersionCodename='15'
2026-10-12T23:26:45.9425470Z minSdkVersion:'24'
2026-10-12T23:26:45.9425878Z targetSdkVersion:'35'
2026-10-12T23:26:45.9426390Z uses-permission: name='android.permission.INTERNET'
2026-10-12T23:26:45.9426931Z uses-permission: name='android.permission.ACCESS_NETWORK_STATE'
2026-10-12T23:26:45.9428796Z application: label='Arena WebView (Locked)' icon='res/BW.xml'
2026-10-12T23:26:45.9429327Z launchable-activity: name='app.arenawebview.locked.MainActivity'  label='' icon=''
2026-10-12T23:26:45.9439703Z ok: package id
2026-10-12T23:26:45.9453564Z ok: has a version name
2026-10-12T23:26:45.9467798Z ok: INTERNET permission
2026-10-12T23:26:45.9481749Z ok: no CAMERA permission
2026-10-12T23:26:45.9495955Z ok: no location permission
2026-10-12T23:26:45.9509895Z ok: no storage permission
2026-10-12T23:26:45.9524636Z ok: no READ_CONTACTS
2026-10-12T23:26:45.9537371Z ok: not debuggable
2026-10-12T23:26:45.9551450Z ok: launcher entry present
2026-10-12T23:26:45.9564416Z ok: one launcher entry only
2026-10-12T23:26:45.9862792Z 6184548f1af25b4e90102834ebced1b92576fa1fe6777b888e7a2aade634bad3  app/build/outputs/apk/release/app-release.apk
2026-10-12T23:26:45.9862792Z 86d9bdc8405cd4fcee96b4b3d840bb0c57a174596ef1b433c833804eff3d445a  app/build/outputs/apk/debug/app-debug.apk
2026-10-12T23:26:47.1766031Z Artifact arena-webview-apk has been successfully uploaded! Final size is 98042 bytes. Artifact ID is 11778558530
```

Those are the hashes of *this commit's* build. APK bytes are not reproducible across builds (signing
timestamps), so the hashes in the release that ships are the ones `Publish release` prints for the same
run - and the release page carries its own `APK-SHA256SUMS.txt`.

## 6. Job `instrumented-tests` (1.1.0, commit `cb94dee`) - 46 tests, one test-thread bug (fixed)

> This is the *first* 1.1.0 run, kept because it is the evidence for the bug described in §2 of
> [TEST_REPORT.md](../TEST_REPORT.md). The current run is §6b.

```
> Task :app:connectedDebugAndroidTest
Starting 46 tests on emulator-5554 - 14
emulator-5554 - 14 Tests 6/46 completed. (0 skipped) (0 failed)
emulator-5554 - 14 Tests 10/46 completed. (0 skipped) (0 failed)
emulator-5554 - 14 Tests 13/46 completed. (0 skipped) (0 failed)
emulator-5554 - 14 Tests 15/46 completed. (0 skipped) (0 failed)
app.arenawebview.locked.web.PopupBlockingTest > popupToASignInHostIsKeptAndShownInApp[emulator-5554 - 14] FAILED
    java.lang.RuntimeException: java.lang.Throwable: A WebView method was called on thread
    'Instr: androidx.test.runner.AndroidJUnitRunner'. All WebView methods must be called on the same thread.
        at android.webkit.WebView.checkThread(WebView.java:2626)
Tests on emulator-5554 - 14 failed: There was 1 failure(s).
Finished 46 tests on emulator-5554 - 14
```

The failure is in the test, not the app: it read `WebView.getSettings()` from the instrumentation thread
after the guarded code had already run correctly. The assertion now runs on the main thread inside the
same `runOnMainSync` block, and it checks the same property (the hand-off window is still alive).

### What the two device probes reported (verbatim)

```
I ArenaWebViewLocked: captcha probe: url=https://arena.ai/
I ArenaWebViewLocked: captcha probe result: "https://www.gstatic.com/recaptcha/releases/nY0xPItgwtktOA7U0o-VsaLz/recaptcha__en.js
  ;; https://www.google.com/recaptcha/enterprise.js?render=6LeTGMcsAAAAALuIlkVwIxaAuZA8VledA6d3Nnb0
  ;; https://static.cloudflareinsights.com/beacon.min.js/v4bc70e2c01a94c73b74392e4234840661791215815920
  ;; https://www.google.com/recaptcha/enterprise.js?render=6LeTGMcsAAAAALuIlkVwIxaAuZA8VledA6d3Nnb0
  ;; window.grecaptcha present"
I ArenaWebViewLocked: google sign-in probe: url=https://accounts.google.com/v3/signin/identifier?flowEntry=ServiceLogin&flowName=GlifWebSignIn&dsh=S-1708191369:1791833368283910
I ArenaWebViewLocked: google sign-in probe result: "SIGNIN_FORM_PRESENT | NO_REJECTION_TEXT | title=Sign in - Google Accounts | len=221
  | text=Loading Sign in Use your Google Account Email or phone Forgot email? Not your computer? Use Guest mode to sign in privately.
  Learn more about using Guest mode Next Create account English (United State"
```

Two facts, and no more than two: the CAPTCHA this site uses is Google reCAPTCHA Enterprise on exactly the
hosts this build allows (and its script loaded - `window.grecaptcha present` - while the Cloudflare
insights beacon beside it was still refused), and Google served its real identifier page
(`SIGNIN_FORM_PRESENT`, `title=Sign in - Google Accounts`) to this WebView with no "browser or app may not
be secure" page. Neither line says a human completed a sign-in.

### The rest of the device evidence from the same run

```
I ArenaWebViewLocked: live smoke test: url after load = https://arena.ai/
I ArenaWebViewLocked: evaluateJavascript returned: 2
W ArenaWebViewLocked: watchdog found a non-approved URL, resetting: BLOCK reason=HOST_NOT_ALLOWED host=evil.example
W ArenaWebViewLocked: recovery (onPageStarted:HOST_NOT_ALLOWED) from https://evil.example/definitely-not-allowed
W ArenaWebViewLocked: refused internal load (test): BLOCK reason=HOST_NOT_ALLOWED host=evil.example
D ArenaWebViewLocked: console: Access to script at 'https://static.cloudflareinsights.com/beacon.min.js/...'
   from origin 'https://arena.ai' has been blocked by CORS policy
I ArenaWebViewLocked: load (test:google-signin): accounts.google.com
```

## 7. The field failure: what the device log was missing, and what it says now

The log in §6 was green apart from one test bug, and the build still could not sign in. The two probes it
contained said exactly the right things (the CAPTCHA is Google reCAPTCHA Enterprise on the allowed hosts;
Google served the real identifier page) - but the *reason the page could not become interactive* was
invisible, because a refused sub-resource no longer appeared anywhere. `ArenaWebViewClient` now logs one
line per distinct refused destination:

```
I ArenaWebViewLocked: refused sub-resource: PATH_NOT_ALLOWED host=www.gstatic.com path=/recaptcha/releases/x/r.js
I ArenaWebViewLocked: refused document: DOCUMENT_NOT_ALLOWED host=www.google.com path=/recaptcha/enterprise/anchor
```

and the device probe asserts the stronger, checkable statement: while the real sign-in page and the
site's CAPTCHA load, **no host on the third-party allow-list may be recorded as refused**. That is the
regression guard for this failure, and it fails in CI rather than in someone's hands.

The three gaps it protects against, each of which alone was enough to make a form dead:

| Gap (before) | Effect | Now |
| --- | --- | --- |
| `www.gstatic.com` pinned to `/accounts/`, `/gsi/`, `/recaptcha/` | Google's identity UI bundle lives under `/_/mss/boq-identity/_/js/...` - the page rendered its static markup and never became interactive (the "Loading" layer the user saw over the typed address) | `ssl.gstatic.com` and `www.gstatic.com` are asset-only hosts **without** path restrictions, a reviewed exception pinned by `AllowlistRegressionTest` |
| An iframe was evaluated as the app's own page | Asset-only hosts are refused as pages, so `www.google.com/recaptcha/enterprise/anchor` never rendered - and a form that needs the widget's token can never be submitted | New `TrafficClass.SUBDOCUMENT`: an embedded document is judged with the same host/path rules but may be an asset host; the app itself is still refused |
| `challenges.cloudflare.com` pinned to `/turnstile/` only | Turnstile renders inside `/cdn-cgi/challenge-platform/...`, so its frame was refused | Both prefixes are allowed, and neither host can ever be the page |

## 6b. Job `instrumented-tests` (this release, commit `4685e71`) - 52/52, all green

```
2026-10-12T23:28:41.8444464Z > Task :app:connectedDebugAndroidTest
2026-10-12T23:28:41.8505738Z Starting 52 tests on emulator-5554 - 14
2026-10-12T23:28:51.8466003Z emulator-5554 - 14 Tests 6/52 completed. (0 skipped) (0 failed)
2026-10-12T23:29:01.8399513Z emulator-5554 - 14 Tests 12/52 completed. (0 skipped) (0 failed)
2026-10-12T23:29:11.8401083Z emulator-5554 - 14 Tests 15/52 completed. (0 skipped) (0 failed)
2026-10-12T23:29:18.4400248Z Finished 52 tests on emulator-5554 - 14
2026-10-12T23:29:19.1943803Z BUILD SUCCESSFUL in 1m 57s
2026-10-12T23:29:19.1944257Z 61 actionable tasks: 55 executed, 6 from cache
```

The three tests this release added for the failure itself are in that run: the sign-in window's frame
rules (a CAPTCHA frame loads in place; a refused frame does not end the hand-off), the main client's
frame rules (widget frames render; the same URLs still cannot become the page), and the probe assertion
below.

### The device log, verbatim (same run, `docs/evidence/device-logcat.txt`)

```
10-12 23:29:07.831  5973  6021 I ArenaWebViewLocked: refused sub-resource: HOST_NOT_ALLOWED host=www.googletagmanager.com path=/gtag/js
10-12 23:29:07.839  5973  6021 I ArenaWebViewLocked: refused sub-resource: HOST_NOT_ALLOWED host=static.cloudflareinsights.com path=/beacon.min.js/v4bc70e2c01a94c73b74392e4234840661791215815920
10-12 23:29:08.733  5973  6021 I ArenaWebViewLocked: refused sub-resource: HOST_NOT_ALLOWED host=browser-intake-us3-datadoghq.com path=/api/v2/rum
10-12 23:29:10.326  5973  6021 I ArenaWebViewLocked: refused document: HOST_NOT_ALLOWED host=evil.example path=/definitely-not-allowed
10-12 23:29:10.689  5973  5973 W ArenaWebViewLocked: recovery (onPageStarted:HOST_NOT_ALLOWED) from https://evil.example/definitely-not-allowed
10-12 23:29:15.742  5973  6022 I ArenaWebViewLocked: refused sub-resource: HOST_NOT_ALLOWED host=www.googletagmanager.com path=/gtag/js
10-12 23:29:15.743  5973  6022 I ArenaWebViewLocked: refused sub-resource: HOST_NOT_ALLOWED host=static.cloudflareinsights.com path=/beacon.min.js/v4bc70e2c01a94c73b74392e4234840661791215815920
10-12 23:29:16.769  5973  5992 I ArenaWebViewLocked: refused sub-resource: HOST_NOT_ALLOWED host=evil.example path=/steal
10-12 23:29:16.808  5973  5992 I ArenaWebViewLocked: refused sub-resource: HOST_NOT_ALLOWED host=evil.example path=/tracker.js
10-12 23:29:17.010  5973  5992 I ArenaWebViewLocked: refused document: HOST_NOT_ALLOWED host=evil.example path=/step2
10-12 23:29:17.089  5973  5992 I ArenaWebViewLocked: refused sub-resource: SCHEME_NOT_HTTPS host= path=/x.js
10-12 23:29:03.395  5973  5992 I ArenaWebViewLocked: live smoke test: url after load = https://arena.ai/
10-12 23:29:09.415  5973  5992 I ArenaWebViewLocked: evaluateJavascript returned: 2
10-12 23:29:01.670  5973  5992 I ArenaWebViewLocked: google sign-in probe: url=https://accounts.google.com/v3/signin/identifier?flowEntry=ServiceLogin&flowName=GlifWebSignIn&dsh=S-351856751:1791847739799923
10-12 23:29:01.673  5973  5992 I ArenaWebViewLocked: google sign-in probe result: "SIGNIN_FORM_PRESENT | NO_REJECTION_TEXT | title=Sign in - Google Accounts | len=221 | text=Loading Sign in Use your Google Account Email or phone Forgot email? Not your computer? Use Guest mode to sign in privately. Learn more about using Guest mode Next Create account English (United State"
10-12 23:29:06.279  5973  5992 I ArenaWebViewLocked: captcha probe result: "https://www.gstatic.com/recaptcha/releases/nY0xPItgwtlvjEjk2TqdwOQz/recaptcha__en.js ;; https://www.google.com/recaptcha/enterprise.js?render=6LeTGMcsAAAAALuIlkVwIxaAuZA8VledA6d3Nnb0 ;; https://static.cloudflareinsights.com/beacon.min.js/v4bc70e2c01a94c73b74392e4234840661791215815920 ;; https://www.google.com/recaptcha/enterprise.js?render=6LeTGMcsAAAAALuIlkVwIxaAuZA8VledA6d3Nnb0 ;; window.grecaptcha present"
```

Read literally:

* **the refusals are now visible and correctly classified**, with `document` and `sub-resource` kept
  apart: everything refused on the two real pages is the site's own analytics (Googletagmanager,
  Cloudflare Insights, Datadog RUM) plus `evil.example` from the escape tests - and `SCHEME_NOT_HTTPS`
  for the plain-HTTP probe, with an empty host, exactly as designed;
* **nothing on the sign-in page's own critical path is refused any more.** The previous run's refusal
  that mattered - Google's identity bundle under `www.gstatic.com/_/mss/...` - no longer appears; the
  only refusals during that page load are `accounts.youtube.com/accounts/CheckConnection` and
  `play.google.com/log`, i.e. Google's optional telemetry, and the probe now *asserts* this
  (`no host on the third-party allow-list may be recorded as refused`), so it cannot silently come back;
* `window.grecaptcha present` on the real Arena.ai page, with the reCAPTCHA Enterprise widget script
  being served from the allowed hosts - the widget whose *frame* previously could not render.
