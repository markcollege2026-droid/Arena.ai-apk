# Demo signing key

`arena-webview-demo.p12` is the release signing key used by this repository's CI so that consecutive
builds of the APK can upgrade each other instead of failing with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`.

| property | value |
| --- | --- |
| file | `keystore/arena-webview-demo.p12` |
| store type | PKCS12 |
| store password | `arenawebview` |
| key alias | `arenawebview` |
| key password | `arenawebview` |
| distinguished name | `CN=Arena WebView Locked Wrapper (DEMO KEY), OU=Demo, O=Arena WebView Wrapper Project` |
| algorithm | RSA 4096, valid for 30 years |

## This key is public on purpose, and that is a trade-off

Everything above is in the repository, which means **anyone can build an APK with the same signing
identity** as the artifacts published here. That is acceptable for a demo/hobby wrapper that is
distributed as a GitHub artifact, and it is the reason the key is clearly marked as a demo key: it
proves integrity of *this* build pipeline, not authorship of a published app.

If you are going to distribute this app anywhere real (or sideload updates to people who trust you),
use your own key:

```bash
keytool -genkeypair -v -keystore my-release.p12 -storetype PKCS12 \
  -alias mykey -keyalg RSA -keysize 4096 -validity 10950 \
  -dname "CN=My Wrapper Key, O=Me, C=US"
```

Then either

* export the key as base64, store it as the repository secret `SIGNING_KEYSTORE_BASE64`, and add a step
  that writes it to a file and exports `SIGNING_KEYSTORE_PATH` before the build, or
* run the build locally with the environment variables:

```bash
export SIGNING_KEYSTORE_PATH=/absolute/path/to/my-release.p12
export SIGNING_STORE_PASSWORD='...'
export SIGNING_KEY_ALIAS='mykey'
export SIGNING_KEY_PASSWORD='...'
./gradlew :app:assembleRelease
```

`app/build.gradle` reads those variables and falls back to the demo key only when they are absent.
