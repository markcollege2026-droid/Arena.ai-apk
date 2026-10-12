# Test report

What has actually been executed for **1.1.0** (Google sign-in + in-app CAPTCHA support), what it
proved, and what it did not. Raw excerpts live in [docs/EVIDENCE.md](docs/EVIDENCE.md); the workflow is
[.github/workflows/android.yml](.github/workflows/android.yml). The previous release's report is kept at
the end of this file.

| Suite | Where | Result |
| --- | --- | --- |
| Security core (URL policy, allow-list, back navigation, manifest, source invariants) | JVM, JDK 17 - **run locally while preparing this release** (see §1) | **1925 tests, 0 failures** |
| Same suite, authoritative build | JVM, GitHub runner, JDK 17 (`unit-tests` job) | PENDING - CI run for 1.1.0 |
| APK build + artifact inspection | `aapt2 dump badging` on the shipped release APK | PENDING - CI run for 1.1.0 |
| Guards on a real WebView | API 34 emulator, `connectedDebugAndroidTest` (46 tests) | PENDING - CI run for 1.1.0 |

Nothing in this file is a prediction: rows marked PENDING are filled in from the run that produces the
APK, and a claim that is not backed by a run is written as unverified.

## 1. JVM suite - 1925 tests, 0 failures

`./gradlew :app:testDebugUnitTest` (the CI job) - and, while preparing this release, the same sources
compiled and executed locally with `javac 1.8.0_152` and a small JUnit-compatible runner, because this
sandbox has no Android SDK. That local run is a *stronger* check than a brace-count, but it is not the
authoritative one: the CI run is, and it uses the project's real JDK 17/Gradle toolchain.

| Class | Tests | What it pins |
| --- | --- | --- |
| `UrlPolicyTest` | **1883** | **269 URL cases × 7 assertions each** (1.0.0 had 229 cases). Every case states the expected decision *and* the exact refusal reason. 229 cases are the base table; **40 more describe the sign-in/CAPTCHA configuration**, including the hosts that must stay refused *next to* Google's. |
| `AllowlistRegressionTest` | 11 | the exact enforced host list: first-party set, third-party set, "no host listed twice", "the enforced set is exactly the two reviewed lists", and that both capability switches are on in this build |
| `BackNavigatorTest` | 5 | only history entries that still pass policy may be revisited; no history means "leave the app" |
| `PolicyMetricsTest` | 6 | counters, bounded 50-entry block log, newest-first ordering, URL truncation, query/fragment stripping, reset |
| `PolicyPurityTest` | 4 | no Android imports in `policy/`, no `java.net.URI`/`URL` parsing (parser differential = classic bypass), no logging or file access in the security core |
| `ManifestPolicyTest` | 5 | one exported activity, two permissions, not debuggable, no backup, cleartext off, system-only trust anchors, and the network security config's pinned host set is a **subset of the runtime allow-list** |
| `SourceInvariantsTest` | 11 | comment-stripped source scan: no JavaScript bridge; TLS errors never proceeded; exactly one URL-loading call site; no `startActivity*` delegation; hardened settings present/unsafe ones absent; **the sign-in window is a view, not an activity**; **no `ACTION_VIEW`, no `androidx.browser`, no `createChooser`**; the hidden pop-up window is never attached to a view hierarchy by the pop-up plumbing |

Three things the new coverage is built to catch:

1. **A Google host that is not needed.** The capability table has 14 `doc()`/`sub()` rows for hosts that
   must stay refused - Gmail, Drive, Docs, Play, `googleapis.com`, other `googleusercontent.com` hosts,
   `accounts.google.com.evil.example` - so "sign-in support" cannot quietly become "Google is allowed".
2. **A CAPTCHA host that is not a widget.** `www.google.com` is allowed *only* on `/recaptcha/`; the
   table asserts that Google Search, `/accounts/` and any other path on that host are refused, and that
   percent-encoded or `..` path escapes (`/recaptcha%2f..%2fx`) are refused as well.
3. **An asset host becoming a page.** `ssl.gstatic.com/accounts/o/x.gif` is allowed as a *sub-resource*
   and refused as a *document* (`DOCUMENT_NOT_ALLOWED`), which is what stops the sign-in mechanism from
   being usable as a general-purpose window.

One real failure was found by the local run and fixed before this build: the rewritten
`AllowlistRegressionTest` asserted that `ArenaAllowlist.allowedHosts()` equals the first-party list,
which is wrong once capability groups are enabled - `allowedHosts()` is the *whole* enforced set. It now
asserts `firstPartyHosts + thirdPartyHosts == allowedHosts()`, with no duplicates, which is a stronger
statement than the one that failed. (Recorded rather than quietly repaired.)

## 2. What changed for sign-in, and the tests that cover it

Google sign-in is an **OAuth pop-up** flow: the site opens a window, Google authenticates inside it, and
the result goes back to the opening page through `window.opener`. A wrapper that destroys every pop-up
breaks that flow, so 1.1.0 has one carefully bounded exception.

| Class | Tests | What it proves |
| --- | --- | --- |
| `PopupBlockingTest` | **12** | all three pop-up outcomes: an allowed URL is routed into the single visible WebView; a third party is refused, counted, and the window destroyed; **a sign-in URL keeps its window and it is shown in-app**; **the return hop to Arena.ai's callback stays in that window** (cancelling it would break the flow); **the window is still fenced** - an asset host or a third party is refused even after promotion; **a site pop-up is never promoted**; `window.close()` from the page is reported to the app; **hidden windows are released immediately**, and twelve pop-ups in a row do not trip the app's own three-window cap |
| `AuthWindowTest` | **5** | the sign-in window is part of the activity's own view hierarchy (no second activity), it hosts exactly the pop-up WebView, repeated hand-off navigations reuse the same window instead of stacking, the close button hides it and detaches the WebView, back closes it first and does not finish the activity |
| `GoogleSignInLiveTest` | **2** | on a device: the app reaches `accounts.google.com`, the page that arrives is served by an allowed host, and the result is **logged** (which page Google actually served) rather than asserted; a second probe logs which CAPTCHA the real site references |
| `SourceInvariantsTest` | 11 | the code-level fences listed in §1 - the sign-in support cannot introduce a Custom Tab, an `ACTION_VIEW`, a chooser or an extra activity without failing the build |

Two implementation bugs were found while writing those tests and fixed:

* **The sign-in window could never close itself.** Chromium delivers `window.close()` only to a
  `WebChromeClient`; the pop-up WebView had none, so Google's returning page could not end the hand-off
  and the in-app window would have hung around. The activity now attaches a guarded chrome client to the
  sign-in window (which also puts JS dialogs, permission requests and file choosers behind the same
  refusals as the main view).
* **Every hidden pop-up leaked.** The teardown was scheduled with `WebView.post(...)`, and a *detached*
  view only queues posted runnables until something attaches it - and nothing attaches a hidden pop-up.
  The window was therefore never destroyed and never released from the chrome client's live-window list,
  so after three pop-ups the app would have refused every further `window.open()` forever.
  `PopupRouterClient` now releases the window synchronously (so the cap is never consumed by a dead
  window) and destroys the WebView on the main looper afterwards (destroying a WebView inside its own
  navigation callback is not safe). `finishedWindowsDoNotPileUpAgainstTheOpenWindowLimit` fails if this
  regresses.

## 3. The live site and Google, on a device

The `instrumented-tests` job runs on an API 34 emulator with a working network path, and
`GoogleSignInLiveTest` records what really happened rather than asserting a hoped-for outcome. The
device log for the 1.1.0 run is reproduced in [docs/EVIDENCE.md](docs/EVIDENCE.md); the two lines that
matter are the ones starting `google sign-in probe:` and `captcha probe result:`. If Google answers
embedded WebViews with its "this browser or app may not be secure" page, the log says so, and the README
states that as a limitation instead of this report claiming sign-in works end to end.

## 4. What is *not* covered by an automated test

Stated plainly, because a wrapper's weaknesses matter more than its claims:

* **The interactive consent flow is not automated.** No test drives a real Google account through the
  identifier/password/consent pages: that needs a human, a real credential and a CAPTCHA answer, and
  automating it is exactly what this project refuses to do. What is tested is the mechanism (the window
  is kept, fenced, closable, destroyed) and the reachability of the host.
* **Google's own decision about embedded browsers** is outside this app's control and is only *reported*
  by the probe, never worked around (no user-agent spoofing, no Custom Tab, no `ACTION_VIEW`).
* **A CAPTCHA cannot be forced on demand**, so the widget test logs what the site references in that
  environment instead of asserting a challenge appeared.
* **WebSocket/WebRTC payloads** are not inspectable through `shouldInterceptRequest`; the handshake URL
  is policy-checked, the stream is not.
* **Media-capture denial** is covered by a source invariant only: driving it needs a page that asks for
  the microphone/camera, which the test suite does not do.
* **A rooted device, or a repackaged APK**, defeats the wrapper; nothing here detects that.
* **Arena.ai's server-side behaviour** (auth, CAPTCHA, rate limits) is deliberately untouched.

## 5. Reproduce

```bash
./gradlew :app:testDebugUnitTest          # 1925 tests, seconds
./gradlew :app:assembleDebug :app:assembleRelease
./gradlew :app:connectedDebugAndroidTest  # 46 tests, needs an emulator/device
```

or push to the repository and read the `Android CI` run: the same three jobs, a badging verification of
the artifact, and - on a `v*` tag - a GitHub Release with the APKs.

---

## Appendix: the 1.0.0 release (for reference)

Release [v1.0.0](https://github.com/markcollege2026-droid/Arena.ai-apk/releases/tag/v1.0.0), run
[38289760091](https://github.com/markcollege2026-droid/Arena.ai-apk/actions/runs/38289760091): 1640 JVM
tests / 32 instrumented tests / 0 failures, `versionName 1.0.0`, `app-release.apk` 42,420 bytes,
SHA-256 `dcf418e8438071badf57f52411533f9b2a9fa987b07e52311ffc6af608500a3a`. In that build every Google and
CAPTCHA host was refused, and the guarantees were the 18 rows of the README's table as it stood then.
