# Arena WebView (Locked)

A single-purpose Android app: one WebView, pointed at <https://arena.ai/>, with every route out of it
closed.

## What is in the box

* `app-release.apk` - installable release build (signed with the demo key committed to this repository,
  see `keystore/README.md`).
* `app-debug.apk` - the same code with debugging enabled, for inspection.
* `APK-SHA256SUMS.txt` - checksums of the artifacts above.
* `apk-badging.txt` - the `aapt2 dump badging` output of the release APK (package id, permissions,
  minSdk/targetSdk, launcher activity).

## Install

```bash
adb install -r app-release.apk
```

Requires Android 7.0 (API 24) or newer. The app requests only `INTERNET` and `ACCESS_NETWORK_STATE`.

## Security summary

* Only `https://arena.ai/`, `https://www.arena.ai/`, and the explicitly listed first-party Arena hosts
  are reachable. No wildcards, no suffix matching, no third-party domains by default.
* Navigation, redirects, iframes, pop-ups, custom schemes, deep links, downloads and file pickers are
  refused in-process - the request never leaves the device.
* Cleartext HTTP is refused three times over: at the URL policy, by the WebView's mixed-content setting,
  and by the network security config.
* TLS errors are never bypassed; there is no code path that calls `SslErrorHandler.proceed()`.
* The app declares one exported component (the launcher activity) and never calls `startActivity`.

See `README.md` and `SECURITY.md` in the repository for the full detail, the allow-list, the threat
model, and the list of things this app deliberately does **not** claim to do.
