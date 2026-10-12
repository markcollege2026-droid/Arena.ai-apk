# Security model

This document says what the app guarantees, how it is enforced, what it deliberately does not attempt,
and how to report a problem. It is written to be checked: every "enforced" claim below names the code
that implements it and the test that exercises it.

## 1. Scope and non-goals

**In scope.** Keep a WebView pinned to `https://arena.ai/` so that the user of this app cannot end up
on another website, cannot have another app opened for them, and cannot have the WebView become a path
into the device's file system or into the app itself.

**Out of scope (and not claimed).** This app is not a privacy tool (no fingerprinting resistance, no
ad/tracker blocking beyond refusing hosts that are not Arena AI), not a sandbox against a hostile
*arena.ai* (the site is trusted to the extent that it is the site the user asked for), and not a defence
against a rooted/compromised device or a malicious WebView build.

**Sign-in and CAPTCHA (1.1.0).** Arena AI offers sign-in with a Google account, so this build allows
Google's account host for exactly that flow, and it allows the reCAPTCHA/Turnstile *widget assets* so a
challenge can be displayed and answered by the user. The app does not touch authentication beyond
deciding which hosts may take part: it never reads, stores or transmits a credential, never answers or
bypasses a CAPTCHA, never automates a consent screen, and has no JavaScript bridge through which it
could do any of those. Google itself may refuse to serve its sign-in form to an embedded browser - that
is Google's server-side policy and this app does not work around it (README §3, limitation 5).

## 2. Trust model

| Component | Trusted? | Notes |
| --- | --- | --- |
| `arena.ai` and the first-party hosts in the allow-list | yes, as the site the user asked for | TLS with system trust anchors only |
| `accounts.google.com` (sign-in pages) and the Google/Cloudflare widget hosts (assets only) | as much as any third party is ever trusted | reachable only as far as the two capability switches allow; the only third-party *page* is Google's account host, and only inside the in-app sign-in window |
| Android WebView (Chromium) | yes | kept up to date by the Play Store, not bundled by this app; Safe Browsing enabled |
| The user's network | no | HTTPS only, no user CAs trusted, no cleartext fallback |
| Everything else on the network | no | unreachable by policy; requests are refused before they are sent |
| Other apps on the device | no | the app declares one exported component and never calls `startActivity` |
| This app's own future edits | no | source-level invariants fail the build if a guard is removed |

## 3. Guarantees, and where to verify each one

| Guarantee | Implementation | Evidence |
| --- | --- | --- |
| Only `https://<exact allow-listed host>` is reachable; no wildcards, no suffix matching | `policy/UrlPolicy.java`, `policy/AllowEntry.java`, `policy/PolicyConfig.java` | `UrlPolicyTest` (269 cases x 7 assertions), `AllowlistRegressionTest` |
| Refused navigations never happen; refused requests are answered in-process (`403`, empty body) and never sent | `web/ArenaWebViewClient.shouldOverrideUrlLoading`, `shouldInterceptRequest` | `NavigationGuardTest`, `UrlPolicyTest` |
| Redirect hops are refused | same request hook (each hop is a request) + `WebResourceRequest.isRedirect()` accounting | `NavigationGuardTest.refusesRedirectsToThirdParties`, `PolicyCases` |
| Pop-ups cannot create a second surface and cannot escape | `web/ArenaWebChromeClient.onCreateWindow`, `web/PopupRouterClient` | `PopupBlockingTest` (12 tests: routed / refused / promoted, plus window bookkeeping) |
| The one extra window (a sign-in hand-off) is still inside the app, still policed, and always closable | `ui/AuthWindow.java` (a `FrameLayout`, not an activity), `MainActivity.onSignInWindowRequested` / `closeSignInWindow` / `checkSignInWindowUrl` | `AuthWindowTest`, `PopupBlockingTest`, `SourceInvariantsTest.signInNeverLeavesTheApp`, `theOnlyWebViewsAreThePrimaryOneAndTheSignInWindow` |
| The sign-in window is fenced: only Google's sign-in pages and the return hop to an Arena.ai page are displayable in it; everything else - including asset-only hosts - is refused and the window is destroyed | `PopupRouterClient.handle` (`isSignInNavigation` / `isProductNavigation` on every navigation, not just the first) | `PopupBlockingTest.aPromotedSignInWindowStillRefusesEverythingOutsideTheHandOff`, `PolicyCases` rows for `ssl.gstatic.com` as a document |
| A challenge can be displayed but never answered, automated or bypassed | CAPTCHA hosts are `Role.ASSET` entries pinned to `/recaptcha/` and `/turnstile/`; no bridge, no injected script, no solver | `AllowlistRegressionTest`, `PolicyCases` (path escapes and every other path on those hosts refused) |
| No other app is ever opened (browser, store, share sheet, file picker, download UI) | `MainActivity.startActivity*` overrides; `onShowFileChooser` refusal; `DownloadListener` refusal | `EscapeAttemptTest` (with an `ActivityMonitor` asserting zero launches), `DownloadGuardTest` |
| TLS errors are never bypassed | `ArenaWebViewClient.onReceivedSslError` → `cancel()`; no `proceed()` exists | `SourceInvariantsTest.tlsErrorsAreNeverBypassed` |
| No cleartext HTTP anywhere, even with a user-installed CA | `UrlPolicy` (scheme rule), `MIXED_CONTENT_NEVER_ALLOW`, `res/xml/network_security_config.xml` | `UrlPolicyTest`, `WebViewSettingsTest`, `ManifestPolicyTest`, CI `aapt2` step |
| No JavaScript bridge (the classic WebView RCE path) | nothing calls `addJavascriptInterface` | `SourceInvariantsTest.noJavascriptBridgeExists` |
| Service-worker requests are policed like any other request | `WebViewHardening.installServiceWorkerGuard` | `SourceInvariantsTest`, review |
| The visible URL is re-verified continuously; anything unapproved is replaced by the start page or covered by a native overlay | `MainActivity.tickWatchdog`, `ArenaWebViewClient` final-URL verification | `WatchdogRecoveryTest` |
| Back navigation cannot reach an unverified page or another app | `policy/BackNavigator.java`, `MainActivity.handleBackPressed` | `BackNavigatorTest`, `BackNavigationTest` |
| Minimal device surface: one exported activity, two permissions, no services/receivers/providers, backups off | `AndroidManifest.xml` | `ManifestPolicyTest`, `AppSurfaceTest`, CI `aapt2` step |

## 4. What is *not* interceptor-blocked (honest list)

1. **WebSockets, WebRTC and QUIC-like transports that do not go through `WebViewClient`.** Android's
   WebView API exposes `shouldInterceptRequest` for URL-loader requests and `ServiceWorkerClient` for
   service workers, but not for WebSocket handshakes or WebRTC data channels. Page JavaScript on
   arena.ai (or any script arena.ai chooses to run) could therefore open a connection to another host.
   Consequences: it cannot display anything, cannot navigate the WebView, cannot reach the file system,
   and cannot open another app - but it is an outbound connection this app does not see, and it is not
   claimed to be blocked. Closing this would require an in-app allow-listing CONNECT proxy plus
   `ProxyController`; that is deliberately not part of this version.
2. **Anything the page does entirely in memory** (e.g. computing, rendering, storing data in
   localStorage/IndexedDB in the app's private storage). That is normal browser behaviour and is not
   restricted; the app's private storage is not readable by other apps, and backups are disabled.
3. **Bot-protection challenges are displayed, not defeated.** This build allows the widget hosts
   (`www.google.com/recaptcha/`, `www.recaptcha.net/recaptcha/`, `www.gstatic.com/recaptcha/`,
   `challenges.cloudflare.com/turnstile/`) so a challenge renders and **you** answer it. The app never
   pre-answers, scripts, automates or solves a challenge: it has no JavaScript bridge, injects no script
   into the page and has no access to what you type. Turning both switches off
   (`ENABLE_CAPTCHA_WIDGETS`, `ENABLE_GOOGLE_SIGN_IN`) returns the build to refusing those hosts
   entirely, and the unit suite then asserts the smaller host list.
4. **Google's sign-in pages may refuse to load in an embedded browser.** Google documents sign-in inside
   a WebView as unsupported and commonly answers `accounts.google.com` with a "this browser or app may
   not be secure" page. This is a server-side decision by Google that this app neither bypasses nor
   obscures (no user-agent spoofing, no Custom Tab, no `ACTION_VIEW`); the device log records which page
   was actually served (TEST_REPORT.md §3). Arena.ai's own sign-in inside the WebView is unaffected, and
   the allow-list still refuses every other Google host - Gmail, Drive, `googleapis.com`, Google Search
   and the rest.

## 5. Residual risks and abuse

* **The demo signing key is public** (`keystore/README.md`). Anyone can produce an APK with the same
  signing identity as the release artifacts. Use your own key for anything you distribute.
* **Sideloaded apps are only as trustworthy as their source.** Verify the SHA-256 published with each
  release, or build the APK yourself from this repository - the build is reproducible from source with
  no runtime dependencies and no downloaded binaries other than the Gradle wrapper (whose checksum is
  committed and validated in CI).
* **A rooted or otherwise compromised device** can bypass anything an app does; that is outside the
  model.
* **A modified build** is outside the model too. That is exactly why the security-relevant invariants are
  asserted in tests (so an accidental weakening fails the build) and why the guard code is small,
  dependency-free and readable in one sitting.

## 6. Reporting

Open an issue in this repository for anything that gets past the guards. Useful evidence, all of which
the app produces locally:

* the in-app "Copy diagnostics" report (counters plus the most recent refused destinations - URLs are
  stored without query strings or fragments),
* `adb logcat -s ArenaWebViewLocked:*`,
* the steps to reproduce, plus the Android/WebView version (`adb shell dumpsys package com.google.android.webview | grep versionName`).

Please do not include personal data or session cookies in a report.
