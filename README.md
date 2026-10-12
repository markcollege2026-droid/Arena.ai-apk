# Arena.ai APK - locked-down WebView wrapper

An Android app that shows **https://arena.ai/** in a single WebView and refuses to go anywhere else.
It is a wrapper around the real, live site - it does not reimplement, replace or restyle the Arena.ai
UI, and it does not touch Arena.ai's authentication, CAPTCHA or access rules.

**Installable APK:** [Releases page](https://github.com/markcollege2026-droid/Arena.ai-apk/releases) -
or download the `arena-webview-apk` artifact from the [Actions runs](https://github.com/markcollege2026-droid/Arena.ai-apk/actions).

**Package:** `app.arenawebview.locked` · **versionName:** 1.0.0 · **minSdk:** 24 (Android 7.0) ·
**targetSdk:** 35 · **permissions:** `INTERNET`, `ACCESS_NETWORK_STATE` only.

> Read [SECURITY.md](SECURITY.md) for the threat model and an honest list of what a WebView wrapper
> *cannot* police. Read [TEST_REPORT.md](TEST_REPORT.md) for what has actually been executed and
> measured, and [docs/EVIDENCE.md](docs/EVIDENCE.md) for the raw CI output.

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
| `unit-tests` | Verifies the Gradle wrapper jar against a pinned SHA-256, then runs the 1640-test JVM suite |
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
| 2 | Only exact, listed hosts | Exact string match against `ArenaAllowlist` - no suffix or wildcard logic, so `arena.ai.evil.com`, `arena-ai.com`, `arena.ai.` and Unicode/punycode lookalikes never match |
| 3 | Ports pinned to 443 | Any other explicit port is refused |
| 4 | No credentials in URLs | `userinfo@host` is refused (the classic `https://arena.ai@evil.com` confusion) |
| 5 | No IP-literal hosts | IPv4/hex/decimal literals and non-ASCII hosts are refused |
| 6 | Redirects re-validated | `shouldOverrideUrlLoading` and `shouldInterceptRequest` see every hop; the final URL is re-checked in the history and page-finished callbacks |
| 7 | Sub-requests are checked too | `shouldInterceptRequest` refuses off-policy requests with an in-process empty response (nothing leaves the device) |
| 8 | Service worker traffic is checked | `ServiceWorkerController` client applies the same policy |
| 9 | Pop-ups are captured, never shown | `window.open`/`target="_blank"` is intercepted; an allowed URL is routed into the single visible WebView, anything else is refused |
| 10 | No second window, ever | The hidden pop-up WebView is never attached to a view hierarchy and is destroyed immediately |
| 11 | External apps are unreachable | `startActivity`/`startActivityForResult`/`startActivities` are overridden to refuse, incoming intents are only logged, and off-policy custom schemes never reach the platform |
| 12 | Downloads never start | A `DownloadListener` refuses and reports; the platform `DownloadManager` is never used |
| 13 | File uploads / media capture / geolocation are denied | `WebChromeClient` refusals |
| 14 | TLS failures are fatal | `onReceivedSslError` always cancels, client-certificate requests and HTTP auth are cancelled |
| 15 | Android Safe Browsing is on | `setSafeBrowsingEnabled(true)`; a hit is treated as a blocked navigation |
| 16 | Back stays inside the app | Back handles the app's own fullscreen video and overlay first, then walks WebView history **only to entries that still pass policy**; leaving the app requires a double press |
| 17 | The app cannot be steered from outside | One exported launcher activity, no deep links, no services/receivers/providers |
| 18 | Runtime re-verification | A 1-second watchdog re-checks `WebView.getUrl()` and rewinds to the start page if it is ever off-policy; a lost renderer process tears the WebView down |

Failures are fail-closed: a URL that cannot be parsed, a null/empty URL, or a policy error results in
a refusal plus an in-app explanation, never in a load.

### What is allow-listed

`arena.ai` and `www.arena.ai` are the product. The remaining first-party hosts are the ones Arena.ai
operates for its own assets and links: `auth.`, `cdn.`, `agent.`, `code.`, `indexable.`, `blog.`,
`news.`, `help.`, `status.` and `trust.arena.ai`. Mail, SSO (`okta.`), e-mail security (`mta-sts.`)
and non-production preview hosts (`*.preview.arena.ai`) are deliberately **not** listed.

**Third-party capability entries (off by default).** Some deployments of the real site load a
reCAPTCHA or Cloudflare Turnstile widget from `www.google.com`, `www.gstatic.com` or
`challenges.cloudflare.com`. Those hosts are *not* allow-listed in the shipped build. The wrapper
does not solve, bypass or fake CAPTCHAs - if the site presents a challenge whose scripts cannot load,
the app shows its own message instead. Turning the widgets on is a deliberate one-line, reviewable
change (`ArenaAllowlist.ENABLE_THIRD_PARTY_CAPABILITY_ENTRIES`, see SECURITY.md §5) that adds
path-scoped asset loads only - never whole domains, never navigable pages.

### Non-goals

* No bypassing Arena.ai authentication, CAPTCHA or access restrictions.
* No reimplementation or restyling of the Arena.ai interface - the real site is loaded.
* No JavaScript bridge, no `addJavascriptInterface`, no debug WebView.

---

## 3. Known limitations (honest list)

1. **WebSocket / WebRTC payloads are not inspected.** `shouldInterceptRequest` cannot see them; the
   handshake URL is still policy-checked, but isolated-WebView traffic bypasses the request layer.
   See SECURITY.md §4.
2. **A hostile page in the allow-list would be trusted.** The guarantee is "only these hosts", not
   "only well-behaved content".
3. **A rooted/compromised device, or a modified APK, defeats all of this.** The wrapper protects
   against a hostile page, not against a hostile device owner.
4. **The demo signing key is public.** It proves the build chain; it is not a production key.
5. **CAPTCHA widgets are off by default**, so a deployment that requires them needs the deliberate
   opt-in and its own re-test.
6. **Redirects are re-validated per hop, not pre-enumerated.** If Arena.ai ever redirects to a host
   that is not on the list, the wrapper refuses and reports instead of following it.
7. **The policy is Android-free unit-tested, but the WebView wiring is tested on an emulator.** Where
   a claim depends on device behaviour it is marked as such in TEST_REPORT.md.

---

## 4. Repository layout

```
app/src/main/java/app/arenawebview/locked/
  MainActivity.java            single activity: WebView lifecycle, watchdog, back handling
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
    ArenaWebViewClient.java    navigation/request interception
    ArenaWebChromeClient.java  pop-ups, dialogs, permissions, fullscreen
    PopupRouterClient.java     hidden pop-up window: routed or destroyed
    GuardHost.java             the callbacks the activity implements
    BaseGuardHost.java         no-op base for tests
    BlockedResponses.java      the in-process 403 response
  ui/StatusOverlay.java        native "blocked / error" card
app/src/test/                  JVM tests: 229-case URL table + invariants
app/src/androidTest/           emulator tests (guards, escapes, live smoke)
.github/workflows/android.yml  CI: tests, APK build + verification, release
docs/                          evidence, release notes
keystore/                      demo signing key + guidance
SECURITY.md                    threat model, guarantees, limitations
TEST_REPORT.md                 brief test report (what ran, what it proved)
```
