# Release notes - Arena WebView (Locked) 1.0.0

First public build of the locked-down WebView wrapper for **https://arena.ai/**.

## What it is

A single-activity Android app that renders the real, live arena.ai site in one hardened WebView and
refuses to load anything that is not on a short, exact allow-list of first-party hosts. It is a wrapper,
not a clone: no page of the Arena.ai interface is reimplemented or restyled, no JavaScript bridge exists,
and nothing in the app touches Arena.ai authentication, CAPTCHA or access rules.

## Highlights

* **Exact-host allow-list** (no wildcards): `arena.ai`, `www.`, `auth.`, `cdn.`, `agent.`, `code.`,
  `indexable.`, `blog.`, `news.`, `help.`, `status.`, `trust.arena.ai`.
* **HTTPS only**, port 443 only, no `userinfo@host`, no IP literals, no lookalike domains.
* Navigations, redirects, iframes, sub-resources, `window.open` pop-ups and service-worker traffic all
  pass through the same fail-closed policy; a URL that cannot be parsed is refused.
* Off-policy requests are answered with an in-process empty response, so nothing leaves the device.
* External apps are unreachable: `startActivity*` is refused, downloads are refused, file uploads, media
  capture and geolocation prompts are denied.
* Back navigation stays inside the app; leaving needs a deliberate double press.
* A blocked navigation shows a native explanation card with the reason, plus a diagnostics report that can
  be copied from the app.

## Verified in CI for this release

* 1640 JVM tests (229-case URL table, allow-list regression, back navigation, manifest and network
  security config, source invariants) - green.
* Release + debug APKs built from this source, with an `aapt2` badging check on the shipped release APK:
  package `app.arenawebview.locked`, versionName 1.0.0, minSdk 24, targetSdk 35, Internet +
  network-state permissions only, **not** debuggable, one launcher activity.
* Instrumented tests on an API 34 emulator (WebView settings, navigation/redirect/iframe/request guards,
  pop-up routing, escape attempts, watchdog recovery, back handling); the live-site smoke test skips
  itself when the emulator has no route to arena.ai.

See [TEST_REPORT.md](../TEST_REPORT.md) for what each test proves and [EVIDENCE.md](EVIDENCE.md) for the
raw CI output, including the SHA-256 of both APKs.

## Signing

The APK attached here is signed with the **demo key committed in the repository**
(`keystore/arena-webview-demo.p12`, password `arenawebview`). That key is public by design - it makes the
build verifiable by anyone, and it is explicitly not a production signing key. Build from source or set
the `SIGNING_*` environment variables / repository secrets to use your own key.

## Known limitations (unchanged from the README)

* WebSocket and WebRTC payloads cannot be inspected by `shouldInterceptRequest`; their handshake URL is
  policy-checked, the stream is not.
* Third-party CAPTCHA widget hosts are **not** allow-listed by default (the wrapper never solves or fakes
  a CAPTCHA; it shows its own message if a challenge cannot run).
* A rooted device or a repackaged APK defeats all of this.
* Only the hosts on the allow-list are reachable; if Arena.ai ever serves a required asset from a new
  host, the wrapper refuses it and reports it instead of silently following.
