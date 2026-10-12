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

    @Test
    public void theVisibleWebViewIsOnlyEverTheOnePrimaryWebView() throws Exception {
        String main = code("app/arenawebview/locked/MainActivity.java");
        String hardening = code("app/arenawebview/locked/web/WebViewHardening.java");
        String chrome = code("app/arenawebview/locked/web/ArenaWebChromeClient.java");

        assertFalse("the activity must not create WebViews itself", main.contains("new WebView("));
        assertTrue(main.contains("createPrimaryWebView"));
        // Exactly one WebView is created for the visible UI, in one place.
        assertEquals(1, count(hardening, "new WebView("));
        // The only other WebView is the hidden pop-up window, and it is never attached to a view group.
        assertFalse("a pop-up must never be added to a view hierarchy", chrome.contains("addView(popup"));
        assertTrue(chrome.contains("transport.setWebView(popup)"));
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
