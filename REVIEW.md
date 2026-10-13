# Code examination and test report - Arena WebView (Locked) 1.1.0

Date: 2026-10-13. Scope: the whole repository at commit `6e55214` (`main`), examined and tested with the
question *"is there a better way to do what it does?"*

**Short answer.** The design - one hardened WebView, a pure-Java fail-closed URL policy in front of every
hook, an in-app sign-in window instead of a system browser - is the right shape for the stated threat
model, and the policy core held up to adversarial testing: in 80,000 mutated URLs checked against the
browser's own (WHATWG) parser there was **no input the policy allowed that a browser would send to a host
off the list**. What I found and fixed are edges: one documented guarantee that the code did not actually
keep (trailing-dot hosts), one rule written before the 1.1.0 `SUBDOCUMENT` class and never revisited
(`blob:` origins), a start-up check that could be fooled by a duplicate entry, a crash-recovery path that
did not follow the platform contract (renderer loss), and a one-second verification gap after state
restore. The larger "better ways" - complete egress control for WebSockets and a slower watchdog - are
described in §5; they were deliberately left out of the first pass, because each changes behaviour that
should be decided by the maintainers, and were then implemented in a second pass at the maintainers'
request (status notes at the top of §4 and §5).

---

## 1. What was actually run (and what was not)

The sandbox has no Android SDK and no route to Gradle or Maven Central, so I built an equivalent of the
`unit-tests` CI job from source and used the real API 35 `android.jar` as a type-checker for the rest.

| Step | Tooling | Result |
| --- | --- | --- |
| Compile `policy/` (the security core) | OpenJDK 17.0.2 `javac -Xlint:all -Werror` | clean, before and after the changes |
| Run the JVM suite | JUnit 4.13.2 + Hamcrest 2.2 built from source, `JUnitCore` | **baseline `OK (2225 tests)`; after changes `OK (2397 tests)`** |
| Type-check every file under `app/src/main/java` | `javac` against `android-35.jar` (+ stub `R`/`BuildConfig`) | clean (49 classes), before and after |
| Type-check the instrumented test harness that implements `GuardHost` | same | `RecordingGuardHost` still compiles against the extended interface |
| Differential fuzz of the URL policy | 80,000 seeded mutations x 3 traffic classes, policy verdict vs Node 22 `URL` (WHATWG reference parser) | §2.1 |
| Mutation check of the new fuzz test | six hand-written policy mutants | all six killed (§3.3) |

**Not run, and therefore not claimed:** `./gradlew` (build, lint, `aapt2` badging), the signed APK
pipeline, and the 13 instrumented test classes under `app/src/androidTest` (they need an emulator). The
Android-side changes in §3.4-3.6 are type-checked and reviewed against the platform documentation, not
executed on a device. The existing JVM `SourceInvariantsTest` and `ManifestPolicyTest` still pass, so the
structural guarantees they encode (one `loadUrl`, one `new WebView(` per role, no exported components,
no JavaScript bridge, ...) are intact.

Reproduce (any machine with the Android SDK): `./gradlew :app:testDebugUnitTest` for the JVM suite, then
`connectedDebugAndroidTest` on an emulator. Offline without Gradle: compile `app/src/main/java/.../policy`
and `app/src/test/java` with JUnit 4 on the classpath and run `org.junit.runner.JUnitCore` on the ten
`*Test` classes.

---

## 2. Findings from testing

### 2.1 Differential fuzz: the policy against the browser's parser

The policy's safety argument is "whatever string Chromium is handed, if the policy says ALLOW then
Chromium's host is on the list". The right oracle for that is the WHATWG URL parser, which Chromium
implements. I generated 80,000 URLs by mutating the 18 seed URLs from the test table with the tokens that
URL parsers are known to disagree on (`@`, `\`, tabs/newlines, `%2e`, double ports, trailing dots,
fullwidth letters, nested schemes, ...) and compared every verdict with Node's `URL`.

| | Before | After |
| --- | --- | --- |
| ALLOW verdicts issued | 19,877 | 19,509 |
| Unsound (ALLOW but browser host differs / userinfo / non-https / odd port) | **446**, all one shape: a trailing-dot host (`https://arena.ai./...`) | **0** |
| Host disagreements of any other kind | 0 | 0 |
| Policy threw an exception | 0 | 0 |

Everything else the two disagree on is the policy being *stricter* than the browser, which is the
intended direction (e.g. `https:/host`, interior control characters, fullwidth letters that IDNA would
map, `https://@host/` with empty userinfo, `%2e` in a host). Throughput is ~1.5 microseconds per
evaluation, so none of the checks is a cost worth trading away.

**The trailing-dot case** was not an egress bypass - `arena.ai.` resolves to the same server - but it was
a real contradiction: README rule 2 and the `AllowEntry` Javadoc both say `arena.ai.` never matches, and
`UrlPolicy.canonicalizeHost` stripped the dot and matched. In a browser `https://arena.ai./` is a
*different origin* (separate cookies and storage, CORS failures against the real API), so the page the
policy was allowing would have been a logged-out, half-broken copy of the site. Fixed by refusing it
(§3.1).

### 2.2 Edge-case probes (hand-written)

* Every divergence between the policy and `java.net.URI` goes the strict way - good, and it is why the
  purity test bans `java.net.URI` in the policy package.
* `blob:https://www.google.com/<uuid>` as a sub-resource was refused with `DOCUMENT_NOT_ALLOWED`, and
  `blob:HTTPS://ARENA.AI/<uuid>` was refused because the prefix check was case-sensitive. The first is a
  functional bug for the CAPTCHA frames (§3.2); the second is merely inconsistent with the rest of the
  policy, which is case-insensitive in scheme and host.
* `UrlPolicy.evaluate(url, config, null)` threw `NullPointerException` on the `about:` branch. No caller
  passes null today, but every caller is a WebView callback, and an exception there is the one failure
  mode that is *not* fail-closed - it takes the app down (§3.1).
* `new PolicyConfig("about:blank", ...)` was accepted: the constructor only checked that the primary URL
  is "allowed", and inert `about:blank` is (§3.3).
* Two `AllowEntry` objects for the same host were accepted by `PolicyConfig`; the first silently won
  every lookup. `ArenaAllowlist.merge` plus `AllowlistRegressionTest` currently prevent this in the
  shipped list, but the class that enforces the policy should not depend on a test to be correct (§3.3).

---

## 3. Changes made (all behaviour-tightening; nothing new becomes reachable)

### 3.1 `policy/UrlPolicy.java`
* **Trailing-dot hosts are refused** (`INVALID_HOST`). `canonicalizeHost` now does exactly one
  transformation - lower-casing - which is what the Javadoc already promised. Table rows updated: the two
  "single trailing dot" rows now expect a refusal and four more trailing-dot shapes were added;
  `hostCanonicalisationIsStrict` lists `arena.ai.` among the invalid inputs.
* **Null traffic class fails closed** (mirrors the existing `config == null` guard).
* **`blob:` is judged by its origin as an embedded document.** The text after the origin is the blob's
  UUID, not a path, so the entry's path pins are not applied to it; the origin is evaluated with
  `TrafficClass.SUBDOCUMENT`, because any host that may *exist as a document in this app* (product and
  sign-in pages, asset hosts as the CAPTCHA/identity frames) may mint a blob. The scheme prefix check is
  now case-insensitive like the rest of the policy. A blob is still never a `DOCUMENT` or a `SUBDOCUMENT`,
  opaque origins (`blob:null/`) are still refused, and a forged "origin" with userinfo, a non-default port,
  an `http` scheme or a look-alike host is refused with the precise reason (new rows cover each).

### 3.2 Why the blob rule matters
1.1.0 introduced `SUBDOCUMENT` so that reCAPTCHA/Turnstile frames can render; those frames routinely
create `blob:` URLs (workers, images, audio challenges) and fetch them. The old rule asked the blob's
origin "may this be the app's page?" - to which the answer for an asset host is always no - and then
compared the UUID against the host's path pins, which could never match. So blobs from exactly the
frames 1.1.0 set out to support were refused. This is the same class of bug as the three documented in
TEST_REPORT §2b (a rule written for the main frame applied to something that is not the main frame).

### 3.3 `policy/PolicyConfig.java` (+ new `PolicyConfigTest`)
* The constructor builds a `HashMap<host, entry>` and **throws `IllegalArgumentException` if a host is
  listed twice** or an entry is null. A duplicate would otherwise be dead text that a reviewer can read
  but that is never enforced - the failure mode `ArenaAllowlist.merge` was written to avoid; now the
  enforcing class guarantees it.
* The primary URL must be a **product page**, not merely an allowed string.
* `findByHost` is a map lookup (constant time; the previous linear scan was fine at 21 entries, the point
  is that there is now exactly one entry per host by construction).
* `ArenaAllowlist.merge` Javadoc updated to describe the new contract.

### 3.4 Renderer loss: destroy and replace, do not reload
`ArenaWebViewClient.onRenderProcessGone` returned `true` (keeping the app alive) and then asked the host
to "recover" by calling `loadUrl` on the same WebView. The platform contract for a renderer that is gone
is that the WebView object is dead: it must be removed from the hierarchy and destroyed, and loads into
it show nothing - so the user got a blank, unrecoverable screen until they restarted the app, and README
rule 24's "tears the WebView down" was not what happened.

Now:
* `GuardHost` gains `onRenderProcessGone(WebView view, boolean crashed)` (no-op in `BaseGuardHost`, so
  `RecordingGuardHost` in the instrumented tests is unaffected).
* `ArenaWebViewClient` reports the event and hands the dead view to the host; it never loads into it.
* `PopupRouterClient` finishes a dead hidden pop-up through its existing teardown (which already closes
  the sign-in window via `onPopupClosed` if that is what it was).
* `MainActivity.onRenderProcessGone`: a dead sign-in window is closed; the dead primary view is detached
  and destroyed together with everything hanging off it (sign-in window, hidden pop-ups, fullscreen
  video) and a fresh one is attached through the single hardened factory (`attachPrimaryWebView`, now also
  used by `onCreate`), which then loads the start page via the one `loadVerifiedDestination`. The user
  sees a short message. `SourceInvariantsTest` has a new test pinning this shape (no reload into a dead
  view; `dead.destroy()`; exactly one `createPrimaryWebView(` call site).

This path only exists on API 26+ (the callback does not fire below that), and it is type-checked but not
executed here - it needs a device and a way to terminate the renderer. I would add an instrumented test
that records the `R.id.arena_webview` object, kills the app's `:sandboxed_process` renderer from the test
(or loads `chrome://crash`, which multi-process WebViews honour), and asserts that `R.id.arena_webview` is
a *different* object afterwards, showing the start page, with `RENDERER_GONE` and `RECOVERY_RELOADS` each
incremented once.

### 3.5 Restored sessions are verified before they are shown
`onCreate` called `restoreState` and left verification to "the watchdog on its first tick", i.e. up to one
second during which a Bundle-supplied history entry could render. `verifyRestoredSession` now checks the
current item of the returned `WebBackForwardList` synchronously and falls back to a fresh start-page load
if it is off-policy. (`android:configChanges` already avoids recreation on rotation, so this path is only
hit after the process was killed in the background; it is still the right order of operations.)

### 3.6 The network layer asks the document question for main-frame requests
`shouldInterceptRequest` evaluated every request - including main-frame and redirect hops - as a
`SUBRESOURCE`. Because `shouldOverrideUrlLoading` already refuses such navigations (API 24+ reports
redirects there too) nothing was reachable, but the layer that sees *every* hop should ask the right
question: a main-frame request now goes through `evaluateNavigation`, so an asset host, a `blob:` or a
`data:` URL as the page is refused there as well, with `DOCUMENT_NOT_ALLOWED`/`UNSUPPORTED_SCHEME` as the
recorded reason instead of an allow. `NavigationGuardTest`'s existing expectations are unchanged by this.

### 3.7 New `UrlPolicyFuzzTest` (JVM, deterministic)
A seeded property test that re-creates the differential fuzz on every CI run without Node: 40,000 mutated
URLs x 3 traffic classes, built from every row of the policy table. It checks invariants rather than
expectations: the policy never throws; an allowed network URL is `https`, its re-derived browser host is
exactly the reported allow-listed host, there is no userinfo/backslash/bracket/control character and only
the default port, path pins hold with no `..` or `%`; an allowed `DOCUMENT` is never an asset host; the
only non-https allows are the inert `about:`/`data:` forms in the classes that permit them; and verdicts
are deterministic and case-insensitive in scheme and host. The seed is fixed and the failing input is
printed with control characters made visible. I ran it with seven different seeds (all green) and against
six deliberately broken policies - trailing dot stripped again, suffix host matching, userinfo accepted,
asset host allowed as a page, path pins ignored, an exception on odd input - and it failed on each one
within the first few hundred iterations. Runtime: ~1.2 s.

### 3.8 Documentation
README rows 2 and 24 and the test counts, SECURITY.md's evidence row, and an *Unreleased* section in
`docs/RELEASE_NOTES.md`. TEST_REPORT.md and docs/EVIDENCE.md are left as the historical record of the
1.1.0 run (their numbers describe that run).

---

## 4. Things the documentation promises that the code does not do (decisions for the maintainers)

> **Status (second pass):** all three are resolved. 1 - the `; wv` strip is kept and disclosed as the
> single deliberate deviation (README limitation 5, SECURITY.md §4.5, TEST_REPORT.md). 2 - the code's
> behaviour is kept (`accounts.google.com` may be a top-level page in the main view too; the live test
> depends on it) and the documents now say so (README "What is allow-listed", SECURITY.md §4.6).
> 3 - superseded by §5.1 being implemented: WebSockets are now host-checked by the egress guard, and
> the wording everywhere is "host-checked, not content-checked".

At the time of the first pass these were left alone because each is a product decision rather than a bug.

1. **"No user-agent spoofing" vs. the `; wv` strip.** SECURITY.md §4.5 and README limitation 5 say the
   UA is not spoofed; `WebViewHardening` removes the `; wv` token. Google's sign-in explicitly refuses
   embedded WebViews, and it detects them by more than that token, so the strip buys little and makes
   the document wrong. Either keep it and say so ("the WebView marker is removed; nothing else"), or
   drop it and let Google's policy apply.
2. **"`accounts.google.com` only inside the sign-in window."** README and the release notes say Google's
   host can only ever appear in the in-app sign-in window. In the code, `accounts.google.com` is a
   `SIGN_IN` entry, and a `SIGN_IN` entry passes `evaluateNavigation`, so the *main* WebView may navigate
   top-level to it (a redirect-based OAuth flow would need exactly that; the `auth.arena.ai/v1/oauth_callback`
   entry suggests it may happen). If the pop-up-only claim is the intent, `ArenaWebViewClient` should
   refuse `SIGN_IN` hosts as top-level navigations in the primary view and allow them only in a
   `PopupRouterClient`; if not, the two documents should say "in the sign-in window or as a top-level
   step of the hand-off".
3. **WebSockets.** SECURITY.md §4.1 says the WebSocket handshake URL "is still policy-checked".
   `shouldInterceptRequest` never sees WebSocket connections, so it is not; only the manifest-level
   `network_security_config` (TLS only) and the fact that the page's own code must open the socket apply.
   The honest wording is "not checked by the app"; the real fix is in §5.1.

---

## 5. Better ways (recommended; 5.1, 5.2 and the documentation items implemented in the second pass)

> **Status (second pass):**
> * **5.1 implemented** - `net/EgressProxy.java` (pure Java, loopback, `CONNECT`-only, `PolicyConfig.evaluateTunnel`
>   on every tunnel, `403` otherwise, `502` on upstream failure, never a fallback) and `web/EgressGuard.java`
>   (`WebViewFeature.PROXY_OVERRIDE` check, `ProxyController.setProxyOverride` with a single rule for every
>   scheme and no `addDirect`, state machine `NOT_STARTED → STARTING → READY | FAILED`). `MainActivity`
>   loads nothing until `onReady`; a load requested while starting is deferred, a load after a failure shows
>   the error overlay. Tests: `EgressProxyTest` (12, real sockets), `TunnelPolicyTest` (9),
>   `SourceInvariantsTest.nothingLoadsBeforeTheEgressGuardIsReadyAndThereIsNoDirectFallback`, and on the
>   device `EgressGuardTest` (3). Dependency added: `androidx.webkit:webkit:1.12.1`. Verified on the API 34
>   emulator in CI run 38484542679 (55/55; the WebView's own traffic tunnels through the guard, a page-script
>   WebSocket to a foreign host is refused at the proxy, and Chromium's autofill query to
>   `content-autofill.googleapis.com` - invisible to every hook - is refused too). One gotcha worth recording:
>   Android's `InetAddress.getLoopbackAddress()` is `::1`; the listener must bind the IPv4 literal it tells
>   Chromium about, or every load fails closed with `ERR_PROXY_CONNECTION_FAILED` (TEST_REPORT.md).
> * **5.2 implemented** - 3 s interval with an immediate tick on resume (`startWatchdog` posts the first tick
>   at once). The callbacks remain the primary mechanism; README rule 24 now says so.
> * **5.3 not taken** - `restoreState` stays, verified synchronously (§3.5); the user keeps their place.
> * **5.4** - `pruneDeadPopups` turned out to be called (it only removes nulls, harmless); left as is with
>   the SDK check. The other small items are unchanged.
> * **Also added on request:** an in-app reload (`ui/PullToRefreshFrame` around the primary WebView, F5 /
>   Ctrl+R / refresh key, the overlay button) through `MainActivity.reloadCurrentPage`, the single
>   `WebView.reload()` call site, which re-verifies the current URL first. Dependency added:
>   `androidx.swiperefreshlayout:swiperefreshlayout:1.1.0`.

### 5.1 Complete egress control: a local CONNECT proxy via `androidx.webkit.ProxyController`
The interception hooks cover navigations, sub-resources and (via the service-worker client) SW fetches,
but not WebSockets, and not anything Chromium's network stack does on its own. The one mechanism that
sees *every* byte is a proxy: `ProxyController.setProxyOverride` (feature `PROXY_OVERRIDE`; check
`WebViewFeature.isFeatureSupported` at start-up and refuse to load if it is absent, which only a
years-old WebView would be) can point the WebView at `127.0.0.1:<port>`, and a ~150-line
in-process `CONNECT` proxy can allow-list the `host:443` it is asked to tunnel - using the same
`PolicyConfig`, host and port only, TLS untouched end-to-end. That makes the host allow-list hold for
WebSockets and for anything added to Chromium later, and it turns the current defence-in-depth into a
single enforced boundary. Cost: one AndroidX dependency, a listening socket on loopback, and a careful
fail-closed default if the proxy ever cannot start (refuse to load rather than load unproxied).

### 5.2 An event-driven watchdog instead of a 1 s poll
`doUpdateVisitedHistory`, `onPageStarted` and `onPageFinished` already verify every committed URL,
including `pushState`. The 1 s timer is a safety net against unknown unknowns, which is fine, but 1 s is
busy for a net: 3-5 s with an immediate check on `onResume` and after `restoreState` gives the same
guarantee against the failure modes it exists for (a meta refresh or a WebView bug still commits through
the callbacks) at a fifth of the wake-ups. If the timer stays at 1 s, that is a defensible choice too -
just say in README rule 24 that the callbacks are the primary mechanism and the timer is the backstop.

### 5.3 Consider dropping `restoreState` altogether
The app already restarts on the start page after any policy doubt, and with `configChanges` set it is
only recreated after a background kill. Restoring history from a Bundle is the one input path to the
WebView that is not a navigation; §3.5 verifies it now, but "always start at `arena.ai`" is simpler and
removes the path entirely. The cost is losing the user's place after a background kill.

### 5.4 Smaller items
* `shouldOverrideUrlLoading(WebView, String)` (the legacy overload, API < 24) is never invoked at
  `minSdk 24`; keeping it fail-closed is fine but it can be documented as dead.
* `ArenaWebChromeClient.pruneDeadPopups` is unused; `installServiceWorkerGuard`'s SDK check is always true
  at `minSdk 24`.
* `onReceivedLoginRequest` is counted under `IGNORED_INCOMING_INTENTS`; a dedicated counter would make the
  diagnostics report more precise.
* `PolicyMetrics.recordBlock` inserts at index 0 of an `ArrayList` (bounded at 50, so harmless); an
  `ArrayDeque` says what is meant.
* Instrumented tests to add: renderer-loss replacement (§3.4), restored-session refusal (§3.5), a
  `blob:` fetch from inside a CAPTCHA frame (§3.2).

### 5.5 The architectural question
Could this be a Trusted Web Activity, Custom Tabs, or Credential Manager for the Google sign-in? Each is
Google's sanctioned route and each is *less* locked down than this app: a TWA/Custom Tab is the user's
browser (full link-following, extensions, profile), and Credential Manager returns tokens to native code,
which the threat model here explicitly does not want to hold. The single hardened WebView with an
allow-list and an in-app sign-in window remains the right design for "the user can only ever be on
arena.ai and the app never touches a credential". The improvements above make that design hold in the
places where it currently relies on more than one layer agreeing.

---

## 6. Files changed

| File | Change |
| --- | --- |
| `policy/UrlPolicy.java` | trailing dot refused; null traffic class fails closed; origin-based blob rule; `checkPath` internal flag; second pass: `evaluateTunnel(host, port, config)` |
| `net/EgressProxy.java`, `web/EgressGuard.java`, `ui/PullToRefreshFrame.java` | second pass: new (egress guard, reload gesture) |
| `MainActivity.java` | second pass: guard start-up and gating, deferred first load, `reloadCurrentPage`, 3 s watchdog with immediate tick, keyboard reload |
| `policy/PolicyMetrics.java` | second pass: `TUNNELS_OPENED`, `BLOCKED_TUNNELS`, `USER_RELOADS` |
| `policy/PolicyConfig.java` | map lookup; duplicate/null entries rejected; primary URL must be a product page |
| `policy/ArenaAllowlist.java` | Javadoc of `merge` |
| `web/GuardHost.java`, `web/BaseGuardHost.java` | `onRenderProcessGone(view, crashed)` |
| `web/ArenaWebViewClient.java` | renderer loss handed to host, never reloaded; main-frame requests evaluated as documents |
| `web/PopupRouterClient.java` | dead pop-up finished |
| `MainActivity.java` | `attachPrimaryWebView`, `verifyRestoredSession`, `onRenderProcessGone`, `replacePrimaryWebView` |
| `res/values/strings.xml` | `renderer_restarted` |
| tests | `UrlPolicyFuzzTest` (new), `PolicyConfigTest` (new), `PolicyCases` (+20 rows, 2 flipped), `UrlPolicyTest`, `SourceInvariantsTest` (+1) |
| docs | README rows 2/24 and counts, SECURITY.md evidence row, `docs/RELEASE_NOTES.md` Unreleased section, this file |
