package app.arenawebview.locked;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;

/**
 * Source-level invariants.
 *
 * <p>Some of the wrapper's guarantees are "the app must never contain this call". Those cannot be
 * expressed as a unit test of a method, so they are asserted against the source: if somebody adds a
 * JavaScript bridge, a {@code proceed()} on a TLS error, a second place that loads a URL, or a path that
 * launches another activity, this test fails.</p>
 */
public class SourceInvariantsTest {

    private static String allMainSources() throws Exception {
        StringBuilder sb = new StringBuilder();
        collect(TestPaths.mainSourcesDir(), sb);
        return sb.toString();
    }

    private static void collect(File dir, StringBuilder sb) throws Exception {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (file.isDirectory()) {
                collect(file, sb);
            } else if (file.getName().endsWith(".java")) {
                sb.append("// FILE: ").append(file.getName()).append('\n');
                sb.append(stripComments(TestPaths.read(file))).append('\n');
            }
        }
    }

    /** Reads one main-source file with comments removed - the invariants are about code, not prose. */
    private static String code(String relativePath) throws Exception {
        return stripComments(TestPaths.readSource(relativePath));
    }

    /**
     * Removes {@code //} and {@code /* *}{@code /} comments while leaving string and character
     * literals untouched, so that a URL such as {@code "https://arena.ai/"} survives intact.
     */
    private static String stripComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        int n = source.length();
        while (i < n) {
            char c = source.charAt(i);
            if (c == '"' || c == '\'') {
                out.append(c);
                i++;
                while (i < n) {
                    char inLiteral = source.charAt(i);
                    out.append(inLiteral);
                    i++;
                    if (inLiteral == '\\' && i < n) {
                        out.append(source.charAt(i));
                        i++;
                    } else if (inLiteral == c) {
                        break;
                    }
                }
            } else if (c == '/' && i + 1 < n && source.charAt(i + 1) == '/') {
                while (i < n && source.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && i + 1 < n && source.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(source.charAt(i) == '*' && source.charAt(i + 1) == '/')) {
                    i++;
                }
                i += 2;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static int count(String haystack, String needle) {
        int count = 0;
        int index = haystack.indexOf(needle);
        while (index >= 0) {
            count++;
            index = haystack.indexOf(needle, index + needle.length());
        }
        return count;
    }

    @Test
    public void noJavascriptBridgeExists() throws Exception {
        String sources = allMainSources();
        assertFalse("a JavaScript bridge is the classic WebView RCE path",
                sources.contains("addJavascriptInterface"));
        assertFalse(sources.contains("@JavascriptInterface"));
        assertFalse(sources.contains("removeJavascriptInterface"));
    }

    @Test
    public void tlsErrorsAreNeverBypassed() throws Exception {
        String client = code("app/arenawebview/locked/web/ArenaWebViewClient.java");
        assertTrue("the client must handle TLS errors", client.contains("onReceivedSslError"));
        assertFalse("proceed() must never be called", client.contains("proceed()"));
        assertTrue("the handler must be cancelled", client.contains("handler.cancel()"));
    }

    @Test
    public void onlyOnePlaceLoadsUrls() throws Exception {
        String sources = allMainSources();
        assertEquals("every load must go through loadVerifiedDestination",
                1, count(sources, ".loadUrl("));
        String main = code("app/arenawebview/locked/MainActivity.java");
        assertTrue(main.contains("public void loadVerifiedDestination(String url, String source)"));
    }

    @Test
    public void theAppNeverStartsAnotherActivity() throws Exception {
        String sources = allMainSources();
        assertFalse("startActivity must never be delegated to the platform",
                sources.contains("super.startActivity"));
        assertFalse("no call sites may start an activity",
                sources.contains(".startActivity(") || sources.contains(".startActivityForResult("));

        String main = code("app/arenawebview/locked/MainActivity.java");
        assertTrue(main.contains("public void startActivity(Intent intent)"));
        assertTrue(main.contains("public void startActivityForResult(Intent intent, int requestCode)"));
        assertTrue(main.contains("blockExternalIntent"));
    }

    @Test
    public void webViewIsConfiguredStrictly() throws Exception {
        String hardening = code("app/arenawebview/locked/web/WebViewHardening.java");
        assertTrue(hardening.contains("setAllowFileAccess(false)"));
        assertTrue(hardening.contains("setAllowContentAccess(false)"));
        assertTrue(hardening.contains("setAllowFileAccessFromFileURLs(false)"));
        assertTrue(hardening.contains("setAllowUniversalAccessFromFileURLs(false)"));
        assertTrue(hardening.contains("MIXED_CONTENT_NEVER_ALLOW"));
        assertTrue(hardening.contains("setSafeBrowsingEnabled(true)"));
        assertTrue(hardening.contains("setGeolocationEnabled(false)"));
        assertTrue("the service-worker transport must be policed too",
                hardening.contains("installServiceWorkerGuard"));
        assertTrue("the guard clients must be attached to the WebView",
                hardening.contains("webView.setWebViewClient(client)")
                        && hardening.contains("webView.setWebChromeClient(chromeClient)"));

        assertFalse("file access must never be enabled", hardening.contains("setAllowFileAccess(true)"));
        assertFalse("content access must never be enabled", hardening.contains("setAllowContentAccess(true)"));
        assertFalse(hardening.contains("setAllowUniversalAccessFromFileURLs(true)"));
        assertFalse("JavaScript is required by the real site", hardening.contains("setJavaScriptEnabled(false)"));
        assertFalse("mixed content must never be allowed",
                hardening.contains("MIXED_CONTENT_ALWAYS_ALLOW")
                        || hardening.contains("MIXED_CONTENT_COMPATIBILITY_MODE"));
    }

    @Test
    public void webDebuggingOnlyExistsInDebugBuilds() throws Exception {
        String main = code("app/arenawebview/locked/MainActivity.java");
        assertTrue(main.contains("WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)"));
        assertFalse(main.contains("setWebContentsDebuggingEnabled(true)"));
    }

    @Test
    public void chromeClientRefusesEscapes() throws Exception {
        String chrome = code("app/arenawebview/locked/web/ArenaWebChromeClient.java");
        assertTrue("the file picker must be intercepted", chrome.contains("onShowFileChooser"));
        assertTrue("the file picker callback must be completed without a file",
                chrome.contains("filePathCallback.onReceiveValue(null)"));
        assertTrue("media capture must be denied", chrome.contains("request.deny()"));
        assertTrue("geolocation must be denied", chrome.contains("callback.invoke(origin, false, false)"));
        assertTrue("popups must be intercepted", chrome.contains("onCreateWindow"));
    }

    @Test
    public void downloadsAreRefused() throws Exception {
        String hardening = code("app/arenawebview/locked/web/WebViewHardening.java");
        assertTrue(hardening.contains("setDownloadListener"));
        assertFalse("the app must not use the platform download manager",
                allMainSources().contains("DownloadManager"));
        assertFalse("nothing may be shared with another app", allMainSources().contains("ACTION_SEND"));
    }

    /**
     * Exactly two WebViews can exist, and both are accounted for:
     *
     * <ol>
     *   <li>the primary one, created in {@code WebViewHardening} and owned by the activity; and</li>
     *   <li>an ordinary pop-up window, which is destroyed by {@code PopupRouterClient} on its first
     *       navigation - the one exception being a sign-in hand-off, which is shown in the in-app
     *       window instead (never handed to a system browser).</li>
     * </ol>
     *
     * <p>The invariant used to be "no second WebView is ever attached to a view hierarchy". Google
     * sign-in needs the OAuth pop-up to stay alive ({@code window.opener}), so the rule is now: the only
     * WebView that may ever be attached is the pop-up that {@code PopupRouterClient} explicitly promoted
     * to a sign-in window, and the activity destroys it when the window closes.</p>
     */
    @Test
    public void theOnlyWebViewsAreThePrimaryOneAndTheSignInWindow() throws Exception {
        String main = code("app/arenawebview/locked/MainActivity.java");
        String hardening = code("app/arenawebview/locked/web/WebViewHardening.java");
        String chrome = code("app/arenawebview/locked/web/ArenaWebChromeClient.java");
        String router = code("app/arenawebview/locked/web/PopupRouterClient.java");
        String authWindow = code("app/arenawebview/locked/ui/AuthWindow.java");

        assertFalse("the activity must not create WebViews itself", main.contains("new WebView("));
        assertTrue(main.contains("createPrimaryWebView"));
        // Exactly one WebView is created for the visible UI, in one place.
        assertEquals(1, count(hardening, "new WebView("));
        assertEquals("pop-up windows are created in exactly one place", 1, count(chrome, "new WebView("));
        assertTrue(chrome.contains("transport.setWebView(popup)"));

        // A pop-up is only attached by the sign-in window, and only after the router promoted it.
        assertFalse("a pop-up must never be added to a view hierarchy by the chrome client",
                chrome.contains("addView(popup"));
        assertFalse("a pop-up must never be added to a view hierarchy by the router",
                router.contains("addView(popup"));
        assertTrue("only the in-app sign-in window attaches a WebView",
                authWindow.contains("content.addView(webView"));

        // Promotion happens for sign-in URLs only, and the router asks the host to show it.
        assertTrue(router.contains("isSignInNavigation(url)"));
        assertTrue(router.contains("onSignInWindowRequested(popup, url)"));
        assertTrue("the sign-in window must be torn down again", main.contains("closeSignInWindow"));
        assertTrue("a closed sign-in window must destroy its WebView", main.contains("popup.destroy()"));
        assertTrue("the watchdog must check the sign-in window too", main.contains("checkSignInWindowUrl"));
    }

    /**
     * Enabling Google sign-in must not have opened a way out of the app: no system browser, no Custom
     * Tab, no ACTION_VIEW, and the in-app window is a plain child view.
     */
    @Test
    public void signInNeverLeavesTheApp() throws Exception {
        String sources = allMainSources();
        assertFalse("no Custom Tabs", sources.contains("androidx.browser"));
        assertFalse("no ACTION_VIEW", sources.contains("Intent.ACTION_VIEW"));
        assertFalse("no web-browser intent", sources.contains("setPackage(\"com.android.chrome\")"));
        assertFalse("no chooser", sources.contains("createChooser"));

        String authWindow = code("app/arenawebview/locked/ui/AuthWindow.java");
        assertFalse("the sign-in window must not be its own activity", authWindow.contains("extends Activity"));
        assertTrue("the sign-in window is a view inside the activity", authWindow.contains("extends FrameLayout"));
        assertTrue("the user can always close it", authWindow.contains("auth_window_close"));
    }

    @Test
    public void incomingIntentsAreIgnored() throws Exception {
        String main = code("app/arenawebview/locked/MainActivity.java");
        assertTrue(main.contains("recordIncomingIntent"));
        assertFalse("the activity must not read URL data out of an intent",
                main.contains("getIntent().getData()"));
        assertFalse(main.contains("getIntent().getStringExtra"));
    }
}
