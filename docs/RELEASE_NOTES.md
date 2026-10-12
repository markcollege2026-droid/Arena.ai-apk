# Release notes - Arena WebView (Locked) 1.1.0

Second build of the locked-down WebView wrapper for **https://arena.ai/**. It adds **sign-in with a
Google account** and lets the site's **CAPTCHA widget load so the user can answer it** - both as narrowly
as the flows tolerate, and without the wrapper ever touching a credential or a challenge.

> The 1.0.0 notes are kept at the bottom of this file. The APK for 1.1.0 is published by the Actions run
> only after its three jobs pass; the checksum section below is filled in from that run.

## What is new in 1.1.0

* **"Continue with Google" works as far as the site and Google allow it.** `accounts.google.com` is the
  only third-party host that can ever become a visible page, and only inside a new in-app sign-in window.
* **The sign-in window** is a plain view inside the existing activity - not a second activity, not a
  system browser, not a Custom Tab. It exists because Google's hand-off is an OAuth *pop-up*: the page
  that opened the window receives the result through `window.opener`, so the window must stay alive. It
  can always be closed by the user; the guard closes it on `window.close()`, on back, on
  `onDestroy`, and whenever the watchdog sees it off-policy; and it is fenced to Google's sign-in pages
  plus the return hop to Arena.ai.
* **Google's supporting assets are path-pinned and asset-only** (`ssl.gstatic.com/accounts/`,`/gsi/`;
  `www.gstatic.com/accounts/`,`/gsi/`,`/recaptcha/`; `apis.google.com/js/`,`/gsi/`;
  `fonts.gstatic.com/s/`; `lh3.googleusercontent.com/a/`). None of them can be displayed as a page.
* **CAPTCHAs render, and only you answer them.** reCAPTCHA and Cloudflare Turnstile widget assets
  (`www.google.com/recaptcha/`, `www.recaptcha.net/recaptcha/`, `www.gstatic.com/recaptcha/`,
  `challenges.cloudflare.com/turnstile/`) are reachable as sub-resources only. There is still no
  JavaScript bridge, no injected script and no solver: the wrapper does not read, pre-answer, automate
  or bypass a challenge, and Gmail, Drive, Docs, Play, `googleapis.com`, Google Search outside
  `/recaptcha/` and every other third party stay unreachable.
* **Two bugs found and fixed while building this** (both would have hurt sign-in specifically): the
  sign-in window had no `WebChromeClient`, so a page's `window.close()` could never end the hand-off and
  the window would have lingered; and every hidden pop-up leaked, because the teardown was posted
  through `WebView.post()` on a view that is never attached, so after three pop-ups the app would have
  refused all further `window.open()` calls. Details in TEST_REPORT.md §2.
* **The bug that mattered most, found by a user report and fixed here.** The first build could open the
  sign-in screens but could not submit an email in either form (the prompt sat superimposed over the
  typed address). Three silent policy gaps caused it: Google's identity UI is served from content-hashed
  paths under `www.gstatic.com/_/mss/...` that the allow-list had pinned away (so the page never became
  interactive), CAPTCHA widget **iframes** were judged as if they were the app's own page (so the widget
  never rendered and no form could get its token), and Turnstile's real challenge frame lives under
  `/cdn-cgi/challenge-platform/`, not `/turnstile/`. Fixed with a new `SUBDOCUMENT` traffic class (an
  iframe may embed an asset host; the app is still never navigated to one), asset-only hosts without
  brittle path pins where Google's paths are content-hashed (a reviewed exception, pinned by test),
  Turnstile's real paths, refusal logging (one logcat line per distinct refused destination), and a
  device-side invariant that fails the build if any allow-listed host is ever refused while the real
  sign-in page and the site's CAPTCHAs load.
* **A third bug, found by CI:** the new sign-in copy contained an unescaped apostrophe in a string
  resource, which `aapt2` rejects - a resource rule the JVM suite could not see. Fixed, and
  `ResourcePolicyTest` now applies that rule (plus duplicate resource names and the network-config
  wiring) on the JVM.
* Version bumped to `versionCode 2` / `versionName 1.1.0`; `accounts.google.com` added to the pinned
  `network_security_config.xml` host list (which the unit suite asserts is a subset of the runtime
  allow-list).

## What it is

A single-activity Android app that renders the real, live arena.ai site in one hardened WebView and
refuses to load anything that is not on a short, exact allow-list of first-party hosts. It is a wrapper,
not a clone: no page of the Arena.ai interface is reimplemented or restyled, no JavaScript bridge exists,
and nothing in the app touches Arena.ai authentication, CAPTCHA or access rules.

## Highlights

* **Exact-host allow-list** (no wildcards): `arena.ai`, `www.`, `auth.`, `cdn.`, `agent.`, `code.`,
  `indexable.`, `blog.`, `news.`, `help.`, `status.`, `trust.arena.ai` - plus the sign-in and
  CAPTCHA entries described above and nothing else.
* **HTTPS only**, port 443 only, no `userinfo@host`, no IP literals, no lookalike domains.
* Navigations, redirects, iframes, sub-resources, `window.open` pop-ups and service-worker traffic all
  pass through the same fail-closed policy; a URL that cannot be parsed is refused.
* Off-policy requests are answered with an in-process empty response, so nothing leaves the device.
* External apps are unreachable: `startActivity*` is refused, downloads are refused, file uploads, media
  capture and geolocation prompts are denied.
* Back navigation stays inside the app; leaving needs a deliberate double press.
* A blocked navigation shows a native explanation card with the reason, plus a diagnostics report that can
  be copied from the app.

## Verified for this release

* **2225 JVM tests, 0 failures** against a real `javac` and JVM while preparing the change (272-case URL
  table x 8 assertions, allow-list regression over the whole enforced set, back navigation, metrics,
  policy purity, manifest + network security config, source invariants). The GitHub Actions run re-runs
  the same suite with the project's JDK 17/Gradle toolchain and is the authoritative result.
* **52 instrumented tests** on an API 34 emulator: WebView settings, navigation/redirect/iframe/request
  guards, all three pop-up outcomes, the sign-in window itself (hosting, reuse, close, back), escape
  attempts, watchdog recovery, back handling, downloads - plus two device probes that *log* what Google
  and the site actually served. On the first 1.1.0 run 45 passed; the one failure was a test reading a
  WebView's state from the wrong thread (a bug in the test, fixed). The probes reported that arena.ai
  uses **Google reCAPTCHA Enterprise** on exactly the hosts this build allows (`window.grecaptcha`
  present) and that Google **served its real sign-in identifier page to this WebView** - evidence that
  the mechanism and the allow-list are wired to reality, not a claim that a human completed a sign-in.
* **APK checksums and `aapt2` badging** for 1.1.0 are printed by that run and filled in below.

On the 1.0.0 emulator run the wrapper reached `https://arena.ai/`, the site's own JavaScript executed
(`evaluateJavascript` returned `2`), and the page's third-party beacons
(`static.cloudflareinsights.com`, `browser-intake-us3-datadoghq.com`) were refused by the policy.

See [TEST_REPORT.md](../TEST_REPORT.md) for what each test proves and [EVIDENCE.md](EVIDENCE.md) for the
raw CI output.

## Checksums

For 1.1.0: PENDING - the run that builds and publishes the 1.1.0 APK prints both hashes
(`APK-SHA256SUMS.txt` next to the APKs). Nothing is claimed here until that run exists.

For reference, the 1.0.0 release was built by
[run 38289760091](https://github.com/markcollege2026-droid/Arena.ai-apk/actions/runs/38289760091) from
commit `5134404` (tag `v1.0.0`):

```
dcf418e8438071badf57f52411533f9b2a9fa987b07e52311ffc6af608500a3a  app-release.apk   (42,420 bytes)
ac9a86673d0c18faa14e806712b3e89fdd929b57bc4449c107ee7e7c5743ec1f  app-debug.apk     (53,560 bytes)
```

Verify with `sha256sum app-release.apk` (or `certutil -hashfile app-release.apk SHA256` on Windows)
before installing; `APK-SHA256SUMS.txt` and the full `aapt2` badging output are attached next to the
APKs. APKs are not bit-for-bit reproducible across builds (signing timestamps), so rebuilding from
source yields a different checksum than the one above.

## Signing

The APK attached here is signed with the **demo key committed in the repository**
(`keystore/arena-webview-demo.p12`, password `arenawebview`). That key is public by design - it makes the
build verifiable by anyone, and it is explicitly not a production signing key. Build from source or set
the `SIGNING_*` environment variables / repository secrets to use your own key.

## Known limitations (unchanged from the README)

* **Google decides whether to serve its sign-in form to an embedded browser.** `accounts.google.com` is
  reachable and displayed in the in-app window, but Google documents WebView sign-in as unsupported and
  commonly answers with a "this browser or app may not be secure" page. This app reports that outcome
  rather than working around it (no user-agent spoofing, no Custom Tab, no `ACTION_VIEW`); Arena.ai's own
  email/password sign-in inside the same WebView is unaffected.
* **A CAPTCHA has to be answered by you**, and a challenge that never clears ends in the app's own
  "cannot load" message.
* WebSocket and WebRTC payloads cannot be inspected by `shouldInterceptRequest`; their handshake URL is
  policy-checked, the stream is not.
* A rooted device or a repackaged APK defeats all of this.
* Only the hosts on the allow-list are reachable; if Arena.ai ever serves a required asset from a new
  host, the wrapper refuses it and reports it instead of silently following.

---

# Release notes - Arena WebView (Locked) 1.0.0

First public build of the locked-down WebView wrapper for **https://arena.ai/** (no Google sign-in, no
CAPTCHA hosts: every third-party host was refused). Its verified results were 1640 JVM tests, 32
instrumented tests and the release + debug APKs built by
[run 38289760091](https://github.com/markcollege2026-droid/Arena.ai-apk/actions/runs/38289760091) from
commit `5134404` (tag `v1.0.0`), with `app-release.apk`
`dcf418e8438071badf57f52411533f9b2a9fa987b07e52311ffc6af608500a3a` (42,420 bytes).
