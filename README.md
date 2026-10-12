# Arena WebView (Locked)

A single-purpose Android app: **one WebView, pointed at <https://arena.ai/>, with every route out of it
closed.**

It is a *wrapper for the real website* - the app does not reimplement, restyle, proxy or scrape anything.
It loads arena.ai in a WebView and spends its entire security budget on making sure that WebView cannot
leave arena.ai, cannot reach any other domain, and cannot hand control to another application.

> **Not affiliated with Arena AI.** "arena.ai" and its content belong to Arena AI. This is an independent
> client that renders the public site in a WebView. It does not bypass authentication, paywalls, CAPTCHAs
> or any access restriction, it does not modify the site, and it does not collect anything.

---

## Table of contents

1. [Download and install](#download-and-install)
2. [Build it yourself](#build-it-yourself)
3. [What the app enforces](#what-the-app-enforces)
4. [The allow-list](#the-allow-list)
5. [How it works (architecture)](#how-it-works-architecture)
6. [Configuration](#configuration)
7. [Verification: what is tested and how](#verification-what-is-tested-and-how)
8. [Known limitations](#known-limitations)
9. [Repository layout](#repository-layout)

---

## Download and install

Grab `app-release.apk` from the [latest release](../../releases/latest), then:

```bash
adb install -r app-release.apk
```

Or copy the APK to the device and tap it (you will need to allow "install unknown apps" for that source -
that prompt comes from Android, not from this app).

* Minimum: Android 7.0 (API 24). The WebView (Chromium) is updated through the Play Store, so even old
  devices get a current engine.
* Permissions requested: `INTERNET` and `ACCESS_NETWORK_STATE`. Nothing else - no storage, camera,
  location, contacts or phone state.
* The release APK is signed with the demo key committed in [`keystore/`](keystore/README.md), so builds
  from this repository can upgrade each other.

## Build it yourself

Requirements:

* JDK 17
* Android SDK with platform 35 and build-tools 35.x (`ANDROID_HOME`/`ANDROID_SDK_ROOT` set, or a
  `local.properties` with `sdk.dir=...`)
* Nothing else: the project has no runtime dependencies and uses the Gradle wrapper that is committed here

```bash
./gradlew :app:assembleRelease          # app/build/outputs/apk/release/app-release.apk
./gradlew :app:assembleDebug            # debug build with WebView debugging enabled
./gradlew :app:testDebugUnitTest        # the whole security core, on the JVM, in seconds
./gradlew :app:connectedDebugAndroidTest  # the guards on a connected device/emulator
```

The APK is also built automatically by GitHub Actions on every push
([`.github/workflows/android.yml`](.github/workflows/android.yml)): unit tests, then the APK (plus a
verification pass over the built artifact), then the instrumented tests on an emulator. Pushing a `v*`
tag publishes a release with the APK and its checksum attached.

To sign with your own key instead of the demo key, see [`keystore/README.md`](keystore/README.md).

---

## What the app enforces

| # | Threat | Enforcement | Where |
| --- | --- | --- | --- |
| 1 | Navigation to any other domain | every navigation is matched against an **exact-host** allow-list; refused navigations are cancelled | `UrlPolicy` + `ArenaWebViewClient.shouldOverrideUrlLoading` |
| 2 | Redirects (server-side, meta-refresh, JS) to another domain | every network request - including each hop of a redirect chain - is checked; a refused one is answered with an in-process empty `403`, so it never reaches the network | `ArenaWebViewClient.shouldInterceptRequest` |
| 3 | Requests from scripts, images, XHR/fetch, service workers | same request-level check, plus a dedicated `ServiceWorkerClient` for the service-worker transport | `WebViewHardening.installServiceWorkerGuard` |
| 4 | iframes to other domains | sub-frame navigations and requests go through the same checks | `ArenaWebViewClient` (both callbacks) |
| 5 | Pop-ups / `window.open()` / `target="_blank"` | `onCreateWindow` is intercepted: a WebView is handed to Chromium that is **never attached to any view hierarchy**; its URL is re-validated, an approved URL is loaded in the single visible WebView, anything else is dropped. Dialog-style windows are refused outright | `ArenaWebChromeClient.onCreateWindow`, `PopupRouterClient` |
| 6 | Custom schemes and deep links (`intent:`, `market:`, `tel:`, `mailto:`, `whatsapp:`, ...) | refused by the scheme rule, and again by the fact that `startActivity` is overridden to refuse | `UrlPolicy`, `MainActivity.startActivity` |
| 7 | `javascript:` URLs (including newline-split smuggling) | refused by the scheme rule (tab/newline handling mirrors the WHATWG algorithm Chromium uses, so both parsers see the same string) | `UrlPolicy` |
| 8 | Look-alike domains (`arena-ai.com`, `arena.ai.evil.example`, `аrena.ai`, `xn--…`, `arena.ai.`) | non-ASCII, percent-escapes, backslashes and userinfo are rejected before parsing; the host is then compared **byte-for-byte** with the allow-list - there is no wildcard or suffix matching anywhere in the code | `UrlPolicy.canonicalizeHost` |
| 9 | Local file/asset access (`file://`, `content://`) | both refused by the scheme rule and disabled in the WebView (`allowFileAccess`, `allowContentAccess`, `allowFileAccessFromFileURLs`, `allowUniversalAccessFromFileURLs` all off) | `UrlPolicy`, `WebViewHardening` |
| 10 | Downloads | every download is refused in the `DownloadListener`; the app never touches `DownloadManager` | `WebViewHardening.BlockingDownloadListener` |
| 11 | File pickers / camera / microphone / location | the file chooser is intercepted and completed with "no file" (never launches DocumentsUI), media-capture and geolocation requests are denied, and the app declares no camera/location permissions at all | `ArenaWebChromeClient` |
| 12 | TLS errors | `onReceivedSslError` always cancels - there is no code path that calls `proceed()` (asserted by a test) | `ArenaWebViewClient` |
| 13 | Cleartext HTTP | refused three times over: by the URL policy, by `MIXED_CONTENT_NEVER_ALLOW`, and by a network security config that trusts only system CAs (so a user-installed proxy CA cannot intercept either) | `UrlPolicy`, `WebViewHardening`, `res/xml/network_security_config.xml` |
| 14 | Another app being opened (browser, share sheet, store, download UI) | `startActivity`, `startActivityForResult` and `startActivities` are overridden and refuse everything; nothing in the project ever calls them | `MainActivity` |
| 15 | Deep links / being steered from outside | the app has no deep-link handling: incoming intents are recorded and ignored, and the exported surface is a single launcher activity | `MainActivity.recordIncomingIntent`, `AndroidManifest.xml` |
| 16 | JavaScript bridge attacks | there is no JavaScript bridge in the project at all (no `addJavascriptInterface`); a test fails the build if one is ever added | `SourceInvariantsTest` |
| 17 | Anything that still slips through | final-URL verification on `onPageStarted`/`onPageFinished`/`doUpdateVisitedHistory` **and** a 1-second watchdog that re-checks the visible URL; a URL that is not approved is replaced by the start page or covered by a native overlay | `ArenaWebViewClient`, `MainActivity.tickWatchdog` |
| 18 | Back navigation becoming an escape route | back only moves through history entries that still pass the policy, otherwise it reloads the start page; it never starts another activity, and it takes two presses to close the app | `BackNavigator`, `MainActivity.handleBackPressed` |

Every refusal is a *fail-closed* refusal: when a URL cannot be positively proven to be
`https://<allow-listed host>`, it is refused and the reason is recorded (visible in the in-app
diagnostics report, in logcat under the tag `ArenaWebViewLocked`, and in the tests).

---

## The allow-list

The reachable set of hosts is defined in code
([`ArenaAllowlist.java`](app/src/main/java/app/arenawebview/locked/policy/ArenaAllowlist.java)) and pinned
by a regression test, so it cannot change silently.

### Start page

```
https://arena.ai/
```

### First-party hosts (exact match, no wildcards)

| Host | Why |
| --- | --- |
| `arena.ai` | the product itself (canonical host) |
| `www.arena.ai` | canonical alternate host for the same site |
| `auth.arena.ai` | first-party authentication service (sign-in/session API) |
| `cdn.arena.ai` | first-party static asset CDN |
| `agent.arena.ai` | Agent Mode first-party endpoint |
| `code.arena.ai` | code/artifact first-party endpoint |
| `indexable.arena.ai` | pre-rendered content used by the site |
| `blog.arena.ai`, `news.arena.ai` | first-party content pages linked from the site |
| `help.arena.ai` | first-party help centre |
| `status.arena.ai`, `trust.arena.ai` | first-party status and trust pages |

These are all DNS names inside Arena AI's own zone (`arena.ai`), i.e. the *same first party* the user
intends to visit - not third parties. They are listed explicitly, one by one, because the app has no
concept of a wildcard.

### Deliberately **not** reachable

* `mail.`, `webmail.`, `cpanel.`, `autodiscover.`, `mta-sts.`, `openpgpkey.`, `exchange.` - mail and
  hosting infrastructure that the product does not need.
* `okta.arena.ai` and `www.okta.arena.ai` - SSO identity provider. Signing in through a third-party
  identity provider is out of scope for a locked-down client (and would be a redirect chain across two
  domains).
* `api.preview.`, `api-k8s.preview.`, `portal.api.preview.`, `portal-k8s.api.preview.` - non-production
  hosts.
* `lmarena.ai` - legacy domain; it only redirects to `arena.ai`.
* Everything else on the internet, including every look-alike domain (`arena-ai.com`, `arenaai.com`,
  `arenas.ai`, `arena.ai.evil.example`, `xn--rena-4qa.ai`, Cyrillic-homograph spellings, ...).

### Third-party capability entries - **off by default**

Two site capabilities physically load assets from hosts that are not Arena AI:

* the **reCAPTCHA** widget: `www.google.com/recaptcha/*`, `www.gstatic.com/recaptcha/*`
* the **Cloudflare Turnstile** challenge widget: `challenges.cloudflare.com/turnstile/*`

They are prepared in the code but **disabled** (`ENABLE_THIRD_PARTY_CAPABILITY_ENTRIES = false`), because
enabling them means the app can talk to two additional organisations, and because a CAPTCHA/bot check is
precisely the kind of browser verification this app must not interfere with or try to work around. If a
deployment of arena.ai starts showing a widget that cannot be avoided, flip that one constant, rebuild,
and the build log plus `dependencies`-free diff will show exactly what changed. Even then the entries are
path-scoped (`/recaptcha/`, `/turnstile/` - `www.google.com/search` stays unreachable) and *resource-only*:
they can serve a script or a frame, but they can never become the visible page.

---

## How it works (architecture)

```
        ┌──────────────────────────── MainActivity (the only activity) ────────────────────────────┐
        │  single WebView  +  native StatusOverlay (covers the WebView when a page is refused)      │
        │                                                                                           │
        │  loadVerifiedDestination(url)   ← the only call site of WebView.loadUrl()                 │
        │        │                                                                                  │
        │        └─► UrlPolicy.evaluateNavigation(url)   ──► allow / refuse(reason)                 │
        │                                                                                           │
        │  startActivity*() overridden → refuse + count          back → BackNavigator.decide()      │
        │  watchdog (1 s) → re-verify the visible URL            incoming intents → ignored          │
        └───────────────────────────────────────────────┬───────────────────────────────────────────┘
                                                        │
                 ┌──────────────────────────────────────┴───────────────────────────────────────┐
                 │ ArenaWebViewClient                                     ArenaWebChromeClient   │
                 │ · shouldOverrideUrlLoading  (navigations, redirects)   · onCreateWindow      │
                 │ · shouldInterceptRequest    (every request/hop)        · onShowFileChooser   │
                 │ · onPageStarted/Finished, doUpdateVisitedHistory       · onPermissionRequest │
                 │ · onReceivedSslError → cancel                          · geolocation → deny   │
                 │ · onSafeBrowsingHit → back to safety                   · fullscreen video    │
                 │ · onRenderProcessGone → recover                        · JS dialogs (in-app) │
                 └──────────────────────────────────────┬───────────────────────────────────────┘
                                                        │
                             ┌──────────────────────────┴──────────────────────────┐
                             │ UrlPolicy (pure Java, no Android, no java.net.URI)  │
                             │  canonicalise → exact host match → path scope       │
                             │  every branch that cannot prove safety → BLOCK      │
                             └─────────────────────────────────────────────────────┘
```

Design decisions worth calling out:

* **The policy layer is pure Java with no Android imports.** It can (and does) run as a plain JVM unit
  test with hundreds of hostile inputs on every build. Deliberately, it does not use `java.net.URI`,
  because those rules differ from Chromium's for exactly the inputs that matter (backslashes, tabs and
  newlines, collapsed slashes, percent-escapes in the host).
* **No wildcards anywhere.** An entry matches only if the canonical host is byte-for-byte equal to it.
  That single rule removes an entire class of bypasses.
* **Fail closed, always.** There is no "unknown → allow" path. Unparseable, ambiguous or unfixable input
  produces a refusal with a reason.
* **The WebView still does the browser work.** Allowed requests return `null` from
  `shouldInterceptRequest`, so cookies, HTTP/2, range requests, streaming media and the HTTP cache all
  behave exactly as in a browser. Nothing is proxied, buffered, rewritten or MITM'd.
* **The app never "helps" the site.** No injected scripts, no modified headers other than dropping the
  `wv` token from the user agent (so the site serves the same code it serves to Chrome rather than its
  WebView fallback), no DOM manipulation.

---

## Configuration

| Constant | File | Default | Effect |
| --- | --- | --- | --- |
| `PRIMARY_URL` | `policy/ArenaAllowlist.java` | `https://arena.ai/` | the only page the app opens on its own |
| `firstPartyEntries()` | `policy/ArenaAllowlist.java` | 12 hosts | exact-match allow-list |
| `ENABLE_THIRD_PARTY_CAPABILITY_ENTRIES` | `policy/ArenaAllowlist.java` | `false` | enables the path-scoped reCAPTCHA/Turnstile entries |
| signing | environment / `app/build.gradle` | demo key | `SIGNING_KEYSTORE_PATH`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`, `SIGNING_STORE_TYPE` |
| `WATCHDOG_INTERVAL_MS` | `MainActivity.java` | `1000` | how often the visible URL is re-verified |
| `EXIT_BACK_WINDOW_MS` | `MainActivity.java` | `2500` | window for the second back press that closes the app |

Everything else that could be a "setting" is intentionally in code: there is no remote config, no
preference screen and no asset file that could be swapped at runtime, and the whole reachable set is one
reviewable diff.

---

## Verification: what is tested and how

Two suites, both run in CI on every push:

* **JVM unit tests** (`./gradlew :app:testDebugUnitTest`) - the security core:
  * ~190 URL cases, each asserting allow/refuse *and* the exact refusal reason (scheme smuggling, userinfo
    tricks, IP literals, ports, look-alike domains, punycode, homoglyphs, percent-escapes in the host,
    backslashes, control characters, inert-scheme rules, path-scoped widget entries, ...)
  * the allow-list itself (exact contents, no wildcards, excluded hosts stay excluded)
  * back-navigation decisions, diagnostics counters, source-level invariants
    (no JS bridge, no `proceed()`, exactly one `loadUrl` call site, no `startActivity` call sites, ...)
  * the manifest and the network security config (permissions, exported surface, cleartext off,
    system-only trust anchors, no debug overrides)
* **Instrumented tests on an emulator** (`./gradlew :app:connectedDebugAndroidTest`) - the real WebView:
  * WebView settings read back from the app's own WebView
  * the guard clients driven with synthesized requests: allowed/refused navigations, POST preservation,
    iframes, redirects, custom schemes, cleartext, look-alikes, null URLs
  * pop-ups (refused, re-routed, never attached to a view hierarchy, dialog windows refused)
  * downloads and file chooser refusals, geolocation refusal
  * escape attempts: `startActivity` refused with an `ActivityMonitor` asserting nothing was launched
  * a deliberate bypass (`WebView.loadUrl("https://evil.example/")` behind the guard's back) - the
    third-party page must never become visible
  * back navigation: never launches anything, never exposes another site, eventually closes the app
  * the installed app's surface: one exported activity, no services/receivers/providers, two permissions
  * a live smoke test against the real arena.ai (tolerant of an offline emulator, but never tolerant of
    ending up on another host)

The results of the most recent CI run - including the artifact verification (`aapt2 dump badging`) and a
screenshot from the emulator - are summarised in [`TEST_REPORT.md`](TEST_REPORT.md).

What is *not* claimed: see the honest list in [Known limitations](#known-limitations) below and in
[`SECURITY.md`](SECURITY.md).

---

## Known limitations

These are deliberate, documented trade-offs - not oversights:

1. **WebSockets, WebRTC and other transports that bypass `shouldInterceptRequest`.** Android's WebView
   API has no hook for them, and injecting a CSP into a page you do not control is not possible from a
   wrapper. Consequence: page JavaScript could open a WebSocket to a third-party host. This is *not* an
   escape route (nothing becomes visible, no navigation happens, no other app is opened, nothing is
   rendered), but it is not intercepted, and the app says so instead of pretending otherwise. Closing it
   properly would mean running an allow-listing HTTP CONNECT proxy inside the app and pointing the WebView
   at it via `ProxyController` - a deliberate non-goal for this version.
2. **Downloads are refused entirely**, including files Arena AI might otherwise offer. Saving files would
   need either the platform download UI (another app) or a hand-written HTTP client outside the WebView
   policy; both were rejected. Generated content is still viewable in the page.
3. **File uploads are refused** (the picker is another app). No camera/gallery input.
4. **Third-party sign-in (Google/Apple/Okta) is refused**, because it is a cross-domain redirect chain -
   exactly what a locked-down wrapper must not allow. Email/password sign-in against
   `auth.arena.ai` works, and third-party cookies are enabled so the session keeps working.
5. **CAPTCHA widgets are blocked by default** (see the third-party capability entries above). If Arena AI
   presents a challenge, the app shows its in-app message instead of silently contacting Google.
6. **Anti-fingerprinting / privacy is not the goal.** Cookies, storage and the normal WebView profile are
   kept so the site works as intended; the app is a lockdown, not a privacy browser.
7. **No deep links.** Links like `https://arena.ai/...` opened from other apps will not open this app
   (deliberate: no exported deep-link surface).
8. **Media capture (mic/camera) is denied.** No permissions are declared for them at all.
9. **The demo signing key is public** (see `keystore/README.md`): use your own key for any real
   distribution.

---

## Repository layout

```
.github/workflows/android.yml                    CI: unit tests → APK + artifact verification → emulator tests → release
app/build.gradle                                 app config; signing via env vars with demo-key fallback
app/src/main/AndroidManifest.xml                 one exported activity, two permissions, cleartext off
app/src/main/res/xml/network_security_config.xml HTTPS-only, system trust anchors only, no debug overrides
app/src/main/java/app/arenawebview/locked/
    MainActivity.java                            the only activity; single funnel for loads; escape prevention; watchdog
    policy/                                      pure Java security core (no Android imports, no java.net.URI)
        UrlPolicy.java                           the URL parser/validator - every decision, fail closed
        ArenaAllowlist.java                      the reachable set, in code, with a reason per entry
        AllowEntry.java / PolicyConfig.java      exact-host matching with optional path scoping and resource-only entries
        BackNavigator.java                       in-app-only back navigation decisions
        PolicyMetrics.java                       bounded, local diagnostics counters + recent refusals
    web/
        ArenaWebViewClient.java                   navigations, requests, redirect hops, TLS, final-URL verification
        ArenaWebChromeClient.java                 pop-ups, dialogs, permissions, file chooser, fullscreen video
        PopupRouterClient.java                    routes verified pop-up targets into the single WebView
        WebViewHardening.java                     WebView settings, cookies, service-worker guard, download refusal
        GuardHost.java / BaseGuardHost.java       the seam between the guards and the activity (also the test seam)
    ui/StatusOverlay.java                         native "blocked / could not load" surface (never a web page)
app/src/test/…                                    JVM tests: URL case table, allow-list, manifest, source invariants
app/src/androidTest/…                             on-device tests: settings, guards, pop-ups, escapes, back, live smoke
keystore/                                         demo signing key + how to use your own
docs/RELEASE_NOTES.md                             release notes attached to GitHub releases
TEST_REPORT.md                                    test report with the actual CI evidence
SECURITY.md                                       threat model, guarantees, non-goals, disclosure
```
