# Test report

Everything below is the output of the CI run that built the APK attached to the
[release](https://github.com/markcollege2026-droid/Arena.ai-apk/releases). Raw excerpts live in
[docs/EVIDENCE.md](docs/EVIDENCE.md); the workflow is [.github/workflows/android.yml](.github/workflows/android.yml).

| Suite | Where | Result |
| --- | --- | --- |
| Security core (URL policy, allow-list, back navigation, manifest, source invariants) | JVM, GitHub runner, JDK 17 | **1640 tests, 0 failures** |
| APK build + artifact inspection | `aapt2 dump badging` on the shipped release APK | **all 10 checks passed** |
| Guards on a real WebView | API 34 emulator, `connectedDebugAndroidTest` | **32 tests, 0 failures** |

Run: https://github.com/markcollege2026-droid/Arena.ai-apk/actions/runs/38288999321 ·
commit `b34907f` · runner `ubuntu-24.04`, Temurin JDK 17.0.20, Gradle 8.11 (wrapper, hash-pinned),
AGP 8.7.3, compileSdk/targetSdk 35, minSdk 24.

## 1. JVM suite - 1640 tests, 0 failures

`./gradlew :app:testDebugUnitTest`

* **229 URL cases × 7 assertions (1603 parameterised tests)**. Every case states the exact expected
  decision *and* the exact refusal reason, so `UrlPolicy` cannot silently change behaviour. Coverage
  includes: the allowed hosts and paths; every rejected scheme (`http`, `file`, `content`, `intent`,
  `market`, `javascript`, `ws:`, `wss:`, `ftp`, `chrome`, `vbscript`, ...); ports other than 443;
  `userinfo@host`; IPv4/hex/decimal IP literals; non-ASCII and punycode hosts; lookalikes
  (`arena.ai.evil.com`, `arena-ai.com`, `arena.ai.`, `аrena.ai` with a Cyrillic "а");
  control characters, DEL, embedded tab/newline/CR, backslashes, percent-encoded hosts, empty and
  `null` URLs; `about:blank`/`about:srcdoc`/`data:`/`blob:` handling (including a `blob:` whose
  embedded origin is a third party - the blob is refused with the origin's own reason).
* **Allow-list regression** (7 tests): the shipping host list is pinned element by element, so widening
  it requires a visible edit; the third-party CAPTCHA entries are off by default, are resource-only and
  are pinned to widget paths.
* **Back navigation** (5 tests): only history entries that still pass policy may be revisited, unknown
  history goes back to the start page, no history means "leave the app".
* **Metrics** (6 tests): counters, bounded 50-entry block log, newest-first ordering, URL truncation,
  query/fragment stripping in the report, reset.
* **Policy purity** (4 tests): no Android imports in `policy/`, no `java.net.URI`/`URL` parsing (a
  parser differential is a classic wrapper bypass), no logging or file access in the security core.
* **Manifest + network security config** (6 tests): one exported activity, two permissions, not
  debuggable, no backup, cleartext off, system-only trust anchors, no `debug-overrides`, and the
  network config's host list is asserted equal to the runtime allow-list.
* **Source invariants** (10 tests, comment-stripped scan): no JavaScript bridge; no `proceed()` on TLS
  errors; exactly one call site loads a URL; no `startActivity*` delegation; hardened `WebSettings`
  values present and the unsafe ones absent; debugging enabled only via `BuildConfig.DEBUG`; refusal
  paths present in the chrome client; no `DownloadManager`/`ACTION_SEND`; only one visible WebView is
  ever created and the hidden pop-up window is never attached to a view hierarchy; incoming intent data
  is never read.

Four failures were found by the first run and fixed before this build (they were test bugs, not policy
bugs, and they are recorded rather than quietly repaired):

1. `ManifestPolicyTest` read the network security config with an `android:`-namespace lookup, but those
   attributes are unprefixed.
2-4. Three `SourceInvariantsTest` checks tripped over the *comments* of the code under test (a Javadoc
   that mentions `handler.proceed()` while explaining why it is never called). The scan now removes
   comments while preserving string/char literals.

## 2. The shipped APK

```
package: name='app.arenawebview.locked' versionCode='1' versionName='1.0.0' compileSdkVersion='35'
minSdkVersion:'24'   targetSdkVersion:'35'
uses-permission: name='android.permission.INTERNET'
uses-permission: name='android.permission.ACCESS_NETWORK_STATE'
application-label:'Arena WebView (Locked)'
launchable-activity: name='app.arenawebview.locked.MainActivity'

ok: package id / version name / INTERNET / no CAMERA / no location / no storage / no READ_CONTACTS
ok: not debuggable
ok: launcher entry present / one launcher entry only
```

```
7c434a8147ac7d4882b529633bd1283dfee9a8c65a489568e76739e251d944b7  app-debug.apk
36ebcf7ab390c57269dac0f034e05d58d1478074f2e513c606964215a411ff  app-release.apk
```

The "not debuggable" check is an assertion, so a debuggable release APK fails the build instead of
shipping.

## 3. Guards on a real WebView - 32 tests, 0 failures

`./gradlew :app:connectedDebugAndroidTest` on an API 34 (`google_apis`, x86_64) emulator, emulator-5554.

| Class | Tests | What it proves |
| --- | --- | --- |
| `AppSurfaceTest` | 1 | as installed: exactly one activity, exported, `singleTop`, no alias, zero services/receivers/providers, exactly the two network permissions |
| `WebViewSettingsTest` | 5 | on the WebView the app itself builds: JS, DOM storage, images, zoom, wide viewport, media autoplay and multi-window on; file/content access, mixed content, file-URL access off; Safe Browsing on; user-agent keeps real version info but drops `; wv` |
| `NavigationGuardTest` | 11 | direct calls into the shipping `ArenaWebViewClient`: allowed navigation allowed; third-party navigation, sub-resource, iframe and redirect refused; custom schemes and cleartext refused; lookalike host refused; `null` URL fails closed; `data:` document refused; blocked sub-resources answered with an in-process 403 (no network) and counted |
| `PopupBlockingTest` | 5 | pop-up decisions through `PopupRouterClient`: an allowed URL is routed into the single WebView, anything else is refused and counted; a dialog pop-up is refused; `onCreateWindow` hands Chromium a hidden WebView that is never attached to a view hierarchy and carries the same hardened settings |
| `DownloadGuardTest` | 1 | three download attempts (including a `blob:` and an `.apk`) are refused, counted and reported |
| `EscapeAttemptTest` | 4 | `startActivity`/`startActivityForResult` refuse (activity monitor sees no launch); file chooser completes with `null`; geolocation is denied in-process; a hostile incoming intent cannot steer the app |
| `WatchdogRecoveryTest` | 2 | a deliberately unguarded `WebView.loadUrl("https://evil.example/...")` is caught: the URL is refused, counted, never displayed, and the app recovers to the start page |
| `BackNavigationTest` | 2 | back never leaves the app on a single press, never starts another activity, and needs a deliberate second press to exit |
| `LiveArenaSmokeTest` | 1 | the real site, described below |

One failure was found and fixed before this build: `PopupBlockingTest.onCreateWindowHandsOverAHiddenWebView`
built a bare `Message.obtain()` with no target handler, so Chromium's own `sendToTarget()` contract
could not be satisfied. The test now builds the message from a `Handler` (as Chromium does), and
`ArenaWebChromeClient` additionally treats an unanswerable transport message as a blocked pop-up
instead of letting the exception escape.

### The live site, on the device (logcat evidence)

The emulator has a network path to arena.ai, so the smoke test exercised the real site. The device log
shows, in order:

* the app printing its policy and loading `arena.ai` (`load (startup): arena.ai`);
* **the site's own JavaScript executing** - console messages originating from
  `https://arena.ai/_next/static/chunks/18zyla74w09g6.js` and `https://arena.ai/rpc/static/web-vitals.js`;
* **third-party requests being refused** - Chromium reports `blocked by CORS policy` for
  `static.cloudflareinsights.com/beacon.min.js` and for `browser-intake-us3-datadoghq.com/api/v2/rum`,
  i.e. the wrapper's policy stopped analytics beacons that the real page tried to send;
* the watchdog catching the deliberately injected `https://evil.example/definitely-not-allowed`
  (`watchdog found a non-approved URL, resetting: BLOCK reason=HOST_NOT_ALLOWED host=evil.example`),
  and the same reason being reported by the page hooks and by the activity's own re-check.

The smoke test asserts the app is on `https://arena.ai/` and never anywhere else. It is written to be
offline-tolerant (an emulator without a route to the site records "nothing loaded" rather than a false
failure), so this section is evidence from *this* run rather than a guarantee of every run.

## 4. What is not covered by an automated test

Stated plainly, because a wrapper's weaknesses matter more than its claims:

* **WebSocket/WebRTC payloads** are not inspectable through `shouldInterceptRequest`; the handshake URL
  is policy-checked, the stream is not. No automated test asserts anything about their contents.
* **Media-capture denial** (`onPermissionRequest`) is covered by a source invariant only: driving it on
  a device requires the page to request the microphone/camera, which the smoke test does not do.
* **Chromium's own parser** is not re-tested. The policy is validated against the case table and against
  what the live site actually does (above), not against a WebView conformance suite.
* **A rooted device or a repackaged APK** defeats the wrapper; nothing here can detect that.
* **Arena.ai's server-side behaviour** (auth, CAPTCHA, rate limits) is deliberately untouched and not
  characterised by these tests; the wrapper never bypasses it.
* The **screenshot** captured by CI (`docs/evidence/app-screenshot.png`, attached to the run) shows what
  the emulator rendered in that environment; it is evidence of rendering, not a pixel-level assertion.

## 5. Reproduce

```bash
./gradlew :app:testDebugUnitTest          # 1640 tests, seconds
./gradlew :app:assembleDebug :app:assembleRelease
./gradlew :app:connectedDebugAndroidTest  # needs an emulator/device
```

or push to the repository and read the `Android CI` run - the same three jobs, plus a badging
verification of the artifact and, on a `v*` tag, a GitHub Release with the APKs.
