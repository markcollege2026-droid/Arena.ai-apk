# Test report

What has actually been executed for **1.1.0** (Google sign-in + in-app CAPTCHA support), what it
proved, and what it did not. Raw excerpts live in [docs/EVIDENCE.md](docs/EVIDENCE.md); the workflow is
[.github/workflows/android.yml](.github/workflows/android.yml). The previous release's report is kept at
the end of this file.

| Suite | Where | Result |
| --- | --- | --- |
| Security core (URL policy, allow-list, back navigation, manifest, source invariants) | JVM, JDK 17 - **run locally while preparing this release** (see §1) | **2225 tests, 0 failures** |
| Same suite, authoritative build | JVM, GitHub runner, JDK 17 (`unit-tests` job) | **passed** - run [38321983881](https://github.com/markcollege2026-droid/Arena.ai-apk/actions/runs/38321983881), `BUILD SUCCESSFUL in 43s` |
| APK build + artifact inspection | `aapt2 dump badging` on the shipped release APK | **passed** - `versionName='1.1.0'`, minSdk 24 / targetSdk 35, `INTERNET` + `ACCESS_NETWORK_STATE` only, release APK sha256 `6184548f…bad3` |
| Guards on a real WebView | API 34 emulator, `connectedDebugAndroidTest` (52 tests) | **passed** - 52/52, `Finished 52 tests on emulator-5554 - 14`, `BUILD SUCCESSFUL in 1m 57s` |

Nothing in this file is a prediction: every row is filled in from the run that produces the
APK, and a claim that is not backed by a run is written as unverified.

## 1. JVM suite - 2225 tests, 0 failures

`./gradlew :app:testDebugUnitTest` (the CI job) - and, while preparing this release, the same sources
compiled and executed locally with `javac 1.8.0_152` plus a small JUnit-compatible runner, because this
sandbox has no Android SDK. That local run is a *stronger* check than a brace-count, but it is not the
authoritative one: the CI run is, it uses the project's real JDK 17/Gradle toolchain, and it is the one
that caught the resource bug described below.

| Class | Tests | What it pins |
| --- | --- | --- |
| `UrlPolicyTest` | **2176** | **272 URL cases × 8 assertions each** (1.0.0 had 229 cases). Every case states the expected decision *and* the exact refusal reason. 229 cases are the base table; **43 more describe the sign-in/CAPTCHA configuration**, including the hosts that must stay refused *next to* Google's, and the cross-check that a widget host embeddable in a frame is still never a reachable page. |
| `AllowlistRegressionTest` | 11 | the exact enforced host list: first-party set, third-party set, "no host listed twice", "the enforced set is exactly the two reviewed lists", and that both capability switches are on in this build |
| `BackNavigatorTest` | 5 | only history entries that still pass policy may be revisited; no history means "leave the app" |
| `PolicyMetricsTest` | 6 | counters, bounded 50-entry block log, newest-first ordering, URL truncation, query/fragment stripping, reset |
| `PolicyPurityTest` | 4 | no Android imports in `policy/`, no `java.net.URI`/`URL` parsing (parser differential = classic bypass), no logging or file access in the security core |
| `ManifestPolicyTest` | 5 | one exported activity, two permissions, not debuggable, no backup, cleartext off, system-only trust anchors, and the network security config's pinned host set is a **subset of the runtime allow-list** |
| `ResourcePolicyTest` | 5 | every XML file under `res/` parses; no string resource carries an unescaped `'` or `"` (the rule `aapt2` enforces); ids are unique and the ones the code uses are declared; every **page-capable** host is pinned in `network_security_config.xml` while asset-only hosts deliberately are not |
| `SourceInvariantsTest` | 13 | comment-stripped source scan: no JavaScript bridge; TLS errors never proceeded; exactly one URL-loading call site; no `startActivity*` delegation; hardened settings present/unsafe ones absent; **the sign-in window is a view, not an activity**; **no `ACTION_VIEW`, no `androidx.browser`, no `createChooser`**; the hidden pop-up window is never attached to a view hierarchy by the pop-up plumbing |

Three things the new coverage is built to catch:

1. **A Google host that is not needed.** The capability table has 14 `doc()`/`sub()` rows for hosts that
   must stay refused - Gmail, Drive, Docs, Play, `googleapis.com`, other `googleusercontent.com` hosts,
   `accounts.google.com.evil.example` - so "sign-in support" cannot quietly become "Google is allowed".
2. **A CAPTCHA host that is not a widget.** `www.google.com` is allowed *only* on `/recaptcha/`; the
   table asserts that Google Search, `/accounts/` and any other path on that host are refused, and that
   percent-encoded or `..` path escapes (`/recaptcha%2f..%2fx`) are refused as well.
3. **An asset host becoming a page.** `ssl.gstatic.com/accounts/o/x.gif` is allowed as a *sub-resource*
   and refused as a *document* (`DOCUMENT_NOT_ALLOWED`), and the same holds for the CAPTCHA and Turnstile
   frames: they may render inside the page, never as the page. This is what stops the sign-in mechanism
   from becoming a general-purpose browser.
4. **An allow-listed host being refused anyway.** The device probe now asserts that no host on the
   third-party allow-list is recorded as refused while the real sign-in page and the site's CAPTCHA load -
   the invariant that would have caught the field failure described in §2.

Two real failures were found and fixed before this build. The first came out of the CI run for this
change, which is the honest reason the local run is not the authority:

* **CI: `aapt2` refused `res/values/strings.xml`.** The new sign-in copy contained `Google's` with an
  unescaped apostrophe, which the Android resource compiler rejects
  (`ResourceCompilationException: Can not extract resource from ... ParsedResource`) - a resource rule the
  JVM suite could not see because resource compilation happens inside the Android build. The copy now
  avoids the apostrophe, and `ResourcePolicyTest` (5 tests) applies that rule, plus the duplicate-name
  rule and the network-config wiring, on the JVM in a second. It cannot run *before* `aapt2` in CI - a
  broken resource fails that build first - but it means the mistake is caught locally next time.
* **Local: the rewritten `AllowlistRegressionTest` assertion.** the rewritten
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
| `PopupBlockingTest` | **14** | all three pop-up outcomes: an allowed URL is routed into the single visible WebView; a third party is refused, counted, and the window destroyed; **a sign-in URL keeps its window and it is shown in-app**; **the return hop to Arena.ai's callback stays in that window** (cancelling it would break the flow); **the window is still fenced** - an asset host or a third party is refused even after promotion; **a site pop-up is never promoted**; `window.close()` from the page is reported to the app; **hidden windows are released immediately**, and twelve pop-ups in a row do not trip the app's own three-window cap. **Plus the frame rules inside the sign-in window: a CAPTCHA/identity frame the page embeds loads in place and is never dragged into the main view, and a refused frame costs that frame only - it does not end the hand-off** |
| `AuthWindowTest` | **5** | the sign-in window is part of the activity's own view hierarchy (no second activity), it hosts exactly the pop-up WebView, repeated hand-off navigations reuse the same window instead of stacking, the close button hides it and detaches the WebView, back closes it first and does not finish the activity |
| `GoogleSignInLiveTest` | **2** | on a device: the app reaches `accounts.google.com`, the page that arrives is served by an allowed host, and the result is **logged** (which page Google actually served) rather than asserted; a second probe logs which CAPTCHA the real site references |
| `SourceInvariantsTest` | 13 | the code-level fences listed in §1 - the sign-in support cannot introduce a Custom Tab, an `ACTION_VIEW`, a chooser or an extra activity without failing the build |

Two implementation bugs were found while writing those tests, plus one test-thread mistake found by the
first CI run; all three were fixed. The user-visible sign-in failure described in §2b was found
afterwards, on real hardware, and is fixed in the same change - with the device probe now asserting the
invariant that would have caught it:

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

## 2b. The failure a user hit, and what actually caused it

The first build handed to a user could open the sign-in screens but **could not submit an email** in
either the "Continue with Google" form or Arena.ai's own email form; the email prompt sat superimposed
over the typed address. Three separate policy gaps produced exactly that, and all three were silent -
the app looked fine, the URL bar was fine, and the synthetic suite (1930 tests, green) could not see any
of them, because it never loaded a real third-party page:

1. **Google's sign-in JavaScript was refused.** The page renders its controls from static HTML and then
   loads its entire UI bundle from a content-hashed path under `www.gstatic.com/_/mss/boq-identity/...`.
   The allow-list pinned `www.gstatic.com` to `/accounts/`, `/gsi/`, `/recaptcha/`, so every one of
   those scripts was refused: the page never became interactive, which is why its form could not submit
   and why a pre-JS "Loading" layer stayed on top of the typed address.
2. **CAPTCHA widget frames were refused.** An iframe was evaluated as if it were the app's own page, and
   asset-only hosts are refused as pages - so `www.google.com/recaptcha/enterprise/anchor` (the widget
   the site really uses, visible in the device log) never rendered. A form that needs a CAPTCHA token
   from that widget cannot be submitted at all.
3. **Cloudflare's challenge frame was refused.** Turnstile renders inside
   `challenges.cloudflare.com/cdn-cgi/challenge-platform/...`, and only `/turnstile/` was pinned.

The fixes, in the order they matter:

* **A new traffic class, `SUBDOCUMENT`.** An iframe is judged as an *embedded* document instead of as the
  app's page: same exact-host and path rules, but an asset-only host may be embedded. The app itself can
  still never be navigated to a widget host - that is asserted both in the case table and on a device.
* **Enumerating Google's asset paths was the wrong idea.** `ssl.gstatic.com` and `www.gstatic.com` are now
  asset-only hosts *without* path restrictions, with the reason written down in `ArenaAllowlist` and the
  exception pinned by `AllowlistRegressionTest` (adding another such host is a deliberate, reviewable
  edit). `apis.google.com` gained `/_/`; Turnstile gained `/cdn-cgi/challenge-platform/`.
* **Refusals are no longer invisible.** `ArenaWebViewClient` logs one line per distinct refused
  destination - reason, host, path (never the query string) - so the next report of "the button does
  nothing" can be matched against the allow-list directly.
* **The device probe now asserts the invariant**: while the real sign-in page and the site's CAPTCHAs
  load, no host on the third-party allow-list may be recorded as refused. That is the check that turns
  this whole class of failure into a red build.

## 3. The live site, Google and the CAPTCHA, on a device (measured)

The `instrumented-tests` job runs on an API 34 emulator with a working network path, and
`GoogleSignInLiveTest` records what really happened rather than asserting a hoped-for outcome. On the
first 1.1.0 run (45 of 46 tests passed; the one failure was a test-thread mistake described below), the
device log said:

```
captcha probe result: "https://www.gstatic.com/recaptcha/releases/nY0xPItgwtktOA7U0o-VsaLz/recaptcha__en.js
  ;; https://www.google.com/recaptcha/enterprise.js?render=6LeTGMcsAAAAALuIlkVwIxaAuZA8VledA6d3Nnb0
  ;; https://static.cloudflareinsights.com/beacon.min.js/... ;; window.grecaptcha present"

google sign-in probe: url=https://accounts.google.com/v3/signin/identifier?flowEntry=ServiceLogin&flowName=GlifWebSignIn...
google sign-in probe result: "SIGNIN_FORM_PRESENT | NO_REJECTION_TEXT | title=Sign in - Google Accounts | len=221
  | text=Loading Sign in Use your Google Account Email or phone Forgot email? Not your computer? ..."
```

Read literally, on that emulator:

* the site really does use **Google reCAPTCHA (Enterprise)** - the exact hosts this build allows - and
  `window.grecaptcha` was present in the page, i.e. the widget script **loaded through the policy** rather
  than being refused. The Cloudflare insights beacon next to it was still refused, which is the policy
  behaving as designed;
* the `text=Loading ...` at the front of that string is the failure described in §2b in its raw form: the
  page's own scripts had been refused, so the controls it had already rendered could never become
  interactive - which is exactly what "the form does not submit" looked like from the outside;
* Google served its **real sign-in page** to this WebView (`title=Sign in - Google Accounts`, the
  "Email or phone" identifier field present, no "this browser or app may not be secure" page).

Neither of those is a claim that a human completed a sign-in: that needs a real account and a challenge
answer, which no automated test may do. It is evidence that the mechanism, the allow-list and the hosts
are wired to reality - and, per the README, Google may still refuse at a later step.

The one failure of that run was in the test, not the app: `popupToASignInHostIsKeptAndShownInApp`
checked a WebView's state from the instrumentation thread, which Android forbids
(`A WebView method was called on thread 'Instr: androidx.test.runner.AndroidJUnitRunner'`). The check now
happens on the main thread inside the same `runOnMainSync` block.

## 4. What is *not* covered by an automated test

Stated plainly, because a wrapper's weaknesses matter more than its claims:

* **The interactive consent flow is not automated.** No test drives a real Google account through the
  identifier/password/consent pages: that needs a human, a real credential and a CAPTCHA answer, and
  automating it is exactly what this project refuses to do. What is tested is the mechanism (the window
  is kept, fenced, closable, destroyed) and the reachability of the host.
* **Google's own decision about embedded browsers** is outside this app's control and is only *reported*
  by the probe, never worked around (no Custom Tab, no `ACTION_VIEW`; the single deliberate user-agent
  change - dropping the `; wv` WebView marker - is disclosed in SECURITY.md §4).
* **A CAPTCHA cannot be forced on demand**, so the widget test logs what the site references in that
  environment instead of asserting a challenge appeared.
* **WebSocket payloads** are not inspectable: since 1.2.0 every WebSocket is forced through the in-app
  egress proxy, which refuses any host that is not on the list (`EgressGuardTest` proves this with a
  page-script WebSocket to a foreign host), but the proxy forwards TLS bytes unread, so the stream to an
  allow-listed host is not inspected.
* **Media-capture denial** is covered by a source invariant only: driving it needs a page that asks for
  the microphone/camera, which the test suite does not do.
* **A rooted device, or a repackaged APK**, defeats the wrapper; nothing here detects that.
* **Arena.ai's server-side behaviour** (auth, CAPTCHA, rate limits) is deliberately untouched.

## 5. Reproduce

```bash
./gradlew :app:testDebugUnitTest          # 2225 tests, seconds
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
