# Arena.ai APK - locked-down WebView wrapper

An Android app that shows **https://arena.ai/** in a single WebView and refuses to go anywhere else.
It is a wrapper around the real, live site - it does not reimplement, replace or restyle the Arena.ai
UI, and it does not touch Arena.ai's authentication, CAPTCHA or access rules.

**Installable APK:** [Releases page](https://github.com/markcollege2026-droid/Arena.ai-apk/releases) -
or download the `arena-webview-apk` artifact from the [Actions runs](https://github.com/markcollege2026-droid/Arena.ai-apk/actions).

**Package:** `app.arenawebview.locked` · **versionName:** 1.2.0 (unreleased) · **minSdk:** 24 (Android 7.0) ·
**targetSdk:** 35 · **permissions:** `INTERNET`, `ACCESS_NETWORK_STATE` only.

> Read [SECURITY.md](SECURITY.md) for the threat model and an honest list of what a WebView wrapper
> *cannot* police. Read [TEST_REPORT.md](TEST_REPORT.md) for what has actually been executed and
> measured, and [docs/EVIDENCE.md](docs/EVIDENCE.md) for the raw CI output.

**1.1.0 fixes the sign-in failure reported on the first build**: entering an email in either the
"Continue with Google" form or Arena.ai's own email form did nothing, because Google's sign-in scripts
were not on the allow-list (its identity UI is served from content-hashed paths that cannot be pinned in
advance) and because a CAPTCHA widget's *iframe* was being judged as if it wanted to replace the app's
page. An iframe is now judged as an embedded document - a widget may render in the page, while the app
itself is still never navigated to a third-party host - and every refusal is written to logcat with its
reason, host and path. [TEST_REPORT.md §2b](TEST_REPORT.md) has the full account, including why a green
test suite could not see it.

---

## 1. Build it yourself

Prerequisites: JDK 17 and the Android SDK (platform 35, build-tools 35.x). Nothing else - the Gradle
wrapper (8.11) is committed, so no local Gradle installation is needed.

```bash
git clone https://github.com/markcollege2026-droid/Arena.ai-apk.git
cd Arena.ai-apk

./gradlew :app:assembleDebug          # debug APK
./gradlew :app:assembleRelease        # release APK, signed with the committed demo key
./gradlew :app:testDebugUnitTest      # JVM unit tests (security core)
./gradlew :app:connectedDebugAndroidTest   # on-device tests, needs a device or emulator
```

Outputs:

* `app/build/outputs/apk/debug/app-debug.apk`
* `app/build/outputs/apk/release/app-release.apk`

Install over USB with `adb install -r app/build/outputs/apk/release/app-release.apk`.

### Signing

The release build is signed with the demo keystore committed at `keystore/arena-webview-demo.p12`
(store/key password `arenawebview`, alias `arenawebview`). **That key is public - it is fine for
sideloading and for CI verification, and it is not a production signing key.** See
[keystore/README.md](keystore/README.md) for the keytool recipe, and set `SIGNING_KEYSTORE_PATH`,
`SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`, `SIGNING_STORE_TYPE` (or the
`SIGNING_KEYSTORE_BASE64` secret in CI) to sign with your own key.

### Continuous integration

`.github/workflows/android.yml` runs four jobs on every push and pull request:

| Job | What it does |
| --- | --- |
| `unit-tests` | Verifies the Gradle wrapper jar against a pinned SHA-256, then runs the JVM suite (2420 tests, including a seeded 120,000-evaluation URL fuzz and the egress proxy on real loopback sockets) |
| `build-apk` | Builds debug + release APKs, runs `aapt2 dump badging` assertions, writes `APK-SHA256SUMS.txt` |
| `instrumented-tests` | Boots an API 34 emulator and runs `connectedDebugAndroidTest` (the live-site smoke test is skipped if the runner has no network path to arena.ai) |
| `release` | On a `v*` tag: publishes a GitHub Release with both APKs, checksums, badging output and release notes |

---

## 2. What the wrapper enforces

Every URL that the WebView is about to load - top-level navigation, redirect, iframe, sub-resource,
`fetch`/XHR, pop-up or service worker request - passes through one pure-Java policy engine
(`policy/UrlPolicy.java`) that is compiled into the APK. There is no JSON, asset or remote
configuration that could be swapped out at runtime.

| # | Guarantee | Mechanism |
| --- | --- | --- |
| 1 | Only `https://` loads | Scheme check; `http://`, `file:`, `content:`, `intent:`, `market:`, `javascript:`, `socket:`, `ws(s):` and every other scheme are refused |
| 2 | Only exact, listed hosts | Exact string match against `ArenaAllowlist` - no suffix or wildcard logic, so `arena.ai.evil.com`, `arena-ai.com`, `arena.ai.` (a trailing dot is a different browser origin) and Unicode/punycode lookalikes never match. `PolicyConfig` refuses to start if a host is listed twice, so no entry can silently shadow another |
| 3 | Ports pinned to 443 | Any other explicit port is refused |
| 4 | No credentials in URLs | `userinfo@host` is refused (the classic `https://arena.ai@evil.com` confusion) |
| 5 | No IP-literal hosts | IPv4/hex/decimal literals and non-ASCII hosts are refused |
| 6 | Redirects re-validated | `shouldOverrideUrlLoading` and `shouldInterceptRequest` see every hop; the final URL is re-checked in the history and page-finished callbacks |
| 7 | Sub-requests are checked too | `shouldInterceptRequest` refuses off-policy requests with an in-process empty response (nothing leaves the device) |
| 7a | **Every connection goes through an in-app egress guard** | At start-up the app starts a tiny `CONNECT`-only proxy on `127.0.0.1` (`net/EgressProxy`) and pins the WebView to it with `androidx.webkit.ProxyController` (`web/EgressGuard`) - one rule for every scheme, no direct fallback. The proxy opens a tunnel only to an allow-listed host on port 443 and answers `403` to anything else, so a WebSocket, a fetch from a worker, a `<link rel=preconnect>` or any other connection the `WebViewClient` hooks never see is still stopped at the host level. Nothing loads before the guard is confirmed in place, and if it cannot be installed the app shows an error instead of loading without it. The proxy never terminates TLS: it forwards bytes, so the system-CA-only trust policy and certificate checks are untouched |
| 8 | An embedded document is not a navigation | An iframe/frame/embed is judged as an *embedded* document: a CAPTCHA or identity widget frame may render from an asset-only host, while the same URL is refused as the app's own page. A sub-document cannot replace the page, navigate the app or leave it |
| 9 | Every refusal is named in the log | One logcat line per distinct refused destination - reason, host and path, never the query string (`refused sub-resource: PATH_NOT_ALLOWED host=www.gstatic.com path=/...`). A page that half-works is diagnosable instead of mysterious |
| 10 | Service worker traffic is checked | `ServiceWorkerController` client applies the same policy |
| 11 | Pop-ups are captured, never shown | `window.open`/`target="_blank"` is intercepted; an allowed URL is routed into the single visible WebView, anything else is refused |
| 12 | The only second window is the sign-in window | Google sign-in is an OAuth *pop-up* flow: the page that opened it receives the result through `window.opener`, so that one window has to stay alive. It is a plain child view of the same activity (never a second activity, never a system browser, never a Custom Tab), it can always be closed by the user, and the guard destroys it when the flow ends (`window.close()`), when the user closes it, when back is pressed, when the activity is destroyed, or when the watchdog sees it off-policy |
| 13 | The sign-in window is fenced to the hand-off | Inside it, only Google's sign-in pages (`accounts.google.com`) and the return hop to an Arena.ai page are displayable. Every other URL - a third party, a look-alike, or an asset-only host such as `ssl.gstatic.com` - is refused and the window is torn down, even though the main WebView would have allowed that host as an asset |
| 14 | CAPTCHAs are shown, never solved | The reCAPTCHA/Turnstile widget hosts are path-scoped *asset* entries, so a challenge renders inside the arena.ai page and the user answers it. The wrapper never reads, pre-answers, automates or bypasses a challenge, and there is no JavaScript bridge through which it could |
| 15 | Sign-in never leaves the app | No `ACTION_VIEW`, no `androidx.browser`/Custom Tab, no chooser, no `startActivity` - `signInNeverLeavesTheApp` fails the build if any of those appears in the sources |
| 16 | External apps are unreachable | `startActivity`/`startActivityForResult`/`startActivities` are overridden to refuse, incoming intents are only logged, and off-policy custom schemes never reach the platform |
| 17 | Downloads never start | A `DownloadListener` refuses and reports; the platform `DownloadManager` is never used |
| 18 | File uploads / media capture / geolocation are denied | `WebChromeClient` refusals |
| 19 | TLS failures are fatal | `onReceivedSslError` always cancels, client-certificate requests and HTTP auth are cancelled |
| 20 | Android Safe Browsing is on | `setSafeBrowsingEnabled(true)`; a hit is treated as a blocked navigation |
| 21 | Back stays inside the app | Back closes the sign-in window first, then handles the app's own fullscreen video and overlay, then walks WebView history **only to entries that still pass policy**; leaving the app requires a double press |
| 22 | The app cannot be steered from outside | One exported launcher activity, no deep links, no services/receivers/providers |
| 23 | Resource files cannot break the build quietly | A JVM test parses every XML file under `res/` and applies the two rules `aapt2` enforces - no unescaped apostrophe/quote in a string resource, no duplicate resource name - and asserts that every page-capable host is pinned in `network_security_config.xml` |
| 24 | Runtime re-verification | A watchdog re-checks `WebView.getUrl()` immediately on resume and every 3 seconds while the app is in the foreground, and rewinds to the start page if it is ever off-policy (the request layer and the egress guard refuse the load first; the watchdog is the last line); a restored session is verified synchronously before it is shown; a lost renderer process destroys the dead WebView and replaces it with a fresh one on the start page (a dead sign-in window is simply closed) |
| 25 | Reloading is a navigation too | Pull down from the top of the page, press F5 / Ctrl+R on a keyboard, or use the overlay's button: the current URL is verified again before `WebView.reload()` is called (one call site, like `loadUrl`), and a page that no longer passes is replaced by the start page instead of being reloaded |

Failures are fail-closed: a URL that cannot be parsed, a null/empty URL, or a policy error results in
a refusal plus an in-app explanation, never in a load.

### What is allow-listed

`arena.ai` and `www.arena.ai` are the product. The remaining first-party hosts are the ones Arena.ai
operates for its own assets and links: `auth.`, `cdn.`, `agent.`, `code.`, `indexable.`, `blog.`,
`news.`, `help.`, `status.` and `trust.arena.ai`. Mail, SSO (`okta.`), e-mail security (`mta-sts.`)
and non-production preview hosts (`*.preview.arena.ai`) are deliberately **not** listed.

**Sign-in and CAPTCHA hosts (enabled in this build).** Arena.ai lets you create an account and sign in
**with a Google account** ("Continue with Google"), and it sits behind Cloudflare, so a CAPTCHA can
appear. Both capabilities need hosts outside arena.ai, and both are allow-listed as narrowly as the
flows tolerate:

* `accounts.google.com` is the **only** third-party host that may ever become a visible page
  (`Role.SIGN_IN`). It normally appears in the in-app sign-in window (the OAuth pop-up); if Arena.ai
  navigates the main view there instead (a redirect-based flow), that is allowed too, and the same
  path-free entry applies in both places. Google's OAuth pages live at many paths under that one host,
  so it is listed without a path restriction. No other Google host can ever become a page.
* Everything else Google needs is an **asset-only** entry (`Role.ASSET`: it can be fetched and embedded
  in an iframe, and the app is never navigated to it). These are path-pinned, with **two reviewed
  exceptions**: `ssl.gstatic.com` and `www.gstatic.com` are unrestricted because Google serves its whole
  identity UI from content-hashed paths
  (`www.gstatic.com/_/mss/boq-identity/_/js/k=boq-identity.AccountsSignInUi.en.<hash>/...`) that change
  with every deployment - pinning them by prefix is guaranteed to break sign-in again. The other entries
  stay scoped: `apis.google.com/js/`, `/gsi/`, `/_/`; `fonts.gstatic.com/s/`;
  `lh3.googleusercontent.com/a/` (account avatars).
* The CAPTCHA widget hosts are asset-only too: `www.google.com/recaptcha/`,
  `www.recaptcha.net/recaptcha/`, `www.gstatic.com/recaptcha/` and `challenges.cloudflare.com` on
  `/turnstile/` and `/cdn-cgi/challenge-platform/` (the frame Cloudflare's own documentation shows the
  widget rendering in).

Gmail, Drive, Docs, Play, `googleapis.com`, other `googleusercontent.com` hosts, Google Search outside
`/recaptcha/`, and every other third party remain unreachable - they are not in `ArenaAllowlist`, and a
host that is not in that file does not exist as far as this app is concerned.

The wrapper **never solves, pre-answers, automates or bypasses a CAPTCHA**, and it never reads, stores
or transmits a credential: it has no JavaScript bridge, and it renders whatever challenge the site
sends for the user to answer. The two switches are `ArenaAllowlist.ENABLE_GOOGLE_SIGN_IN` and
`ArenaAllowlist.ENABLE_CAPTCHA_WIDGETS`; setting either to `false` removes those hosts entirely and the
`unit-tests` job pins the exact resulting host list (see SECURITY.md §5).

### Reloading a page

A page can be reloaded without leaving the app: pull down from the top edge of the page (the
pull-to-refresh gesture, with a short dead zone so ordinary scrolling is not affected), press **F5**,
**Ctrl+R** or a keyboard's refresh key, or tap **Back to arena.ai** on the blocked/error overlay. Every
one of these goes through the same verification as any other navigation (guarantee 25).

### Non-goals

* No bypassing Arena.ai authentication, CAPTCHA or access restrictions.
* No reimplementation or restyling of the Arena.ai interface - the real site is loaded.
* No JavaScript bridge, no `addJavascriptInterface`, no debug WebView.

---

## 3. Known limitations (honest list)

1. **The guard decides by host and port, not by content.** WebSockets, worker fetches and every other
   connection are forced through the in-app egress proxy, which refuses any host that is not on the list
   - but it forwards TLS bytes without reading them, so *what* an allow-listed host sends or receives is
   not inspected (and could not be, without breaking TLS). WebRTC is disabled at the permission level.
   See SECURITY.md §4.
2. **A hostile page in the allow-list would be trusted.** The guarantee is "only these hosts", not
   "only well-behaved content".
3. **A rooted/compromised device, or a modified APK, defeats all of this.** The wrapper protects
   against a hostile page, not against a hostile device owner.
4. **The demo signing key is public.** It proves the build chain; it is not a production key.
5. **Google's sign-in form did load in this WebView - but Google, not this app, decides whether it
   keeps doing so.** On the API 34 emulator used by CI, `accounts.google.com` served its real sign-in
   page (`title=Sign in - Google Accounts`, the "Email or phone" identifier field present, no
   "this browser or app may not be secure" page) - see TEST_REPORT.md §3 and
   [docs/EVIDENCE.md](docs/EVIDENCE.md) §6 for the raw log line. Google nonetheless documents sign-in
   inside a WebView as unsupported and may refuse at a later step (for example after the identifier is
   submitted), which is a server-side decision this wrapper does not, and will not, work around: no
   `ACTION_VIEW`, no Custom Tab, and exactly **one** deliberate user-agent change - the `; wv` token
   that marks a WebView is removed (`WebViewHardening`); the Chrome/Android version string is left as
   it is, nothing is impersonated beyond that, and it is disclosed here and in SECURITY.md §4. So: the
   hand-off mechanism, the window and the allow-list are verified; a *completed* sign-in is not claimed.
   Arena.ai's own sign-in inside the same WebView is unaffected either way.
6. **A CAPTCHA has to be answered by you.** The widget loads and renders (reCAPTCHA or Cloudflare
   Turnstile, in an iframe, from the widget hosts above), but the wrapper will not interact with it, so a
   challenge that never clears - or a site that demands an unavailable third-party host - ends in the
   app's own "cannot load" message.
7. **A CAPTCHA that insists on a pop-up window would be refused.** Widget hosts are asset-only, so they
   may render inside the page but can never *become* a window or the app's page. reCAPTCHA and Turnstile
   both render in iframes, which is the layout this app supports; a challenge implementation that opened
   its challenge in a separate window would be blocked. That is the deliberate trade for never letting a
   third party take over the screen.
8. **Redirects are re-validated per hop, not pre-enumerated.** If Arena.ai ever redirects to a host
   that is not on the list, the wrapper refuses and reports instead of following it.
9. **The policy is Android-free unit-tested, but the WebView wiring is tested on an emulator.** Where
   a claim depends on device behaviour it is marked as such in TEST_REPORT.md.
10. **The egress guard is a loopback listener.** It binds `127.0.0.1` on a random port for the life of
    the process, so another app on the same device could connect to it - and obtain exactly what this
    app can obtain: a TCP tunnel to an allow-listed host on port 443, nothing else (no cookies, no
    session, no other destination). It also means HTTP/3 (QUIC) is not used by the WebView - everything
    is HTTP/1.1 or HTTP/2 inside an ordinary TLS tunnel - and that a device-wide HTTP proxy, if one is
    configured, is chained through rather than bypassed.

---

## 4. Repository layout

```
app/src/main/java/app/arenawebview/locked/
  MainActivity.java            single activity: WebView lifecycle, egress guard start-up, watchdog, reload, back handling
  net/EgressProxy.java         pure Java CONNECT-only loopback proxy: the policy applied to every connection
  policy/                      pure Java, no Android imports - the security core
    UrlPolicy.java             the validator (schema, host, port, path, fail-closed)
    ArenaAllowlist.java        the one and only allow-list
    AllowEntry.java            exact host + optional raw-path prefixes
    PolicyConfig.java          allow-list + entry points
    BlockReason.java           machine-readable reasons
    PolicyResult.java          immutable decision
    PolicyMetrics.java         counters + last blocked URLs (diagnostics)
    BackNavigator.java         where "back" may go
  web/                         Android side
    WebViewHardening.java      hardened settings, cookies, ServiceWorker guard
    EgressGuard.java           starts the proxy and pins the WebView to it (ProxyController), fail-closed
    ArenaWebViewClient.java    navigation/request interception
    ArenaWebChromeClient.java  pop-ups, dialogs, permissions, fullscreen
    PopupRouterClient.java     hidden pop-up window: routed, refused, or promoted to the sign-in window
    GuardHost.java             the callbacks the activity implements
    BaseGuardHost.java         no-op base for tests
    BlockedResponses.java      the in-process 403 response
  ui/StatusOverlay.java        native "blocked / error" card
  ui/PullToRefreshFrame.java   pull-to-refresh container around the primary WebView
  ui/AuthWindow.java           in-app sign-in window (a view, not an activity)
app/src/test/                  JVM tests: URL policy table (292 cases in this configuration) + seeded fuzz + invariants + the egress proxy on real loopback sockets
app/src/androidTest/           emulator tests (guards, escapes, live smoke)
.github/workflows/android.yml  CI: tests, APK build + verification, release
docs/                          evidence, release notes
keystore/                      demo signing key + guidance
SECURITY.md                    threat model, guarantees, limitations
TEST_REPORT.md                 brief test report (what ran, what it proved)
```
