package app.arenawebview.locked.web;

import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.ServiceWorkerClient;
import android.webkit.ServiceWorkerController;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;

import java.util.Locale;

import app.arenawebview.locked.policy.BlockReason;
import app.arenawebview.locked.policy.PolicyConfig;
import app.arenawebview.locked.policy.PolicyMetrics;
import app.arenawebview.locked.policy.PolicyResult;

/**
 * WebView configuration: the settings that keep arena.ai working, and the switches that keep the
 * wrapper a wrapper.
 *
 * <p>Everything that is <em>disabled</em> here is disabled because it either reaches the local file
 * system, bridges JavaScript into the app, or hands control to another application.</p>
 */
public final class WebViewHardening {

    private static final String TAG = "ArenaWebViewLocked";
    private static final Object SERVICE_WORKER_LOCK = new Object();

    private static boolean serviceWorkerGuardInstalled;

    private WebViewHardening() {
        // no instances
    }

    /**
     * Enable everything the real site needs and nothing else.
     *
     * <p>Kept <b>on</b>: JavaScript, DOM storage, database storage, images, network loads, zoom,
     * wide-viewport/overview layout, cookies (including third-party cookies for the authentication
     * service), autoplay of muted-capable media so streamed video previews work, and multiple-window
     * support (which is what lets us <em>intercept</em> pop-ups instead of losing them).</p>
     */
    public static void applyHardenedSettings(WebSettings settings) {
        if (settings == null) {
            return;
        }

        // --- required for the site to work at all -------------------------------------------------
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadsImagesAutomatically(true);
        settings.setBlockNetworkImage(false);
        settings.setBlockNetworkLoads(false);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setDefaultTextEncodingName("utf-8");
        // Streamed/generated media should be able to start without an extra tap.
        settings.setMediaPlaybackRequiresUserGesture(false);
        // window.open()/target="_blank" must reach onCreateWindow so the wrapper can decide. Automatic
        // (non user-gesture) pop-ups are still suppressed by Chromium itself.
        settings.setSupportMultipleWindows(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);

        // --- local-file and mixed-content lockdown ------------------------------------------------
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setGeolocationEnabled(false);
        settings.setSaveFormData(false);
        
        // Never remember credentials in the WebView's own password store.
        try {
            settings.setSavePassword(false);
        } catch (Throwable ignored) {
            // deprecated / removed on some API levels
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settings.setSafeBrowsingEnabled(true);
        }

        // Present as the platform Chrome build rather than as a generic "wv" WebView. Some sites
        // (including bot-protection and login pages) refuse the "wv" token outright; removing only that
        // token keeps the real, honest UA version information intact.
        String userAgent = settings.getUserAgentString();
        if (userAgent != null && userAgent.contains("; wv")) {
            settings.setUserAgentString(userAgent.replace("; wv", ""));
        }
    }

    /** Cookies must work (login/session) - including third-party cookies for the auth service. */
    public static void applyCookiePolicy(Context context, WebView webView) {
        try {
            CookieManager cookieManager = CookieManager.getInstance();
            cookieManager.setAcceptCookie(true);
            if (webView != null) {
                cookieManager.setAcceptThirdPartyCookies(webView, true);
            }
        } catch (Throwable t) {
            Log.w(TAG, "cookie policy failed", t);
        }
    }

    /**
     * Create the single visible WebView with the guard clients attached.
     *
     * <p>The clients are passed in (rather than constructing them here) so that the activity holds the
     * only reference to them and no hidden WebView getter is needed.</p>
     */
    public static WebView createPrimaryWebView(Context context, android.webkit.WebViewClient client,
                                               android.webkit.WebChromeClient chromeClient, GuardHost host) {
        WebView webView = new WebView(context);
        applyHardenedSettings(webView.getSettings());
        applyCookiePolicy(context, webView);
        webView.setWebViewClient(client);
        webView.setWebChromeClient(chromeClient);
        webView.setBackgroundColor(android.graphics.Color.BLACK);
        webView.setDownloadListener(new PermittingDownloadListener(host));
        // There is deliberately no Java-to-JavaScript bridge anywhere in this project: a bridge is the
        // classic WebView remote-code-execution path, and this wrapper needs none (SourceInvariantsTest
        // fails the build if one is ever added).
        return webView;
    }

    /**
     * Service workers make network requests outside the page's own document context. This installs the
     * same policy on that transport so a service worker cannot become a way to reach a blocked host.
     * Idempotent; safe to call from {@code onCreate}.
     */
    public static void installServiceWorkerGuard(final PolicyConfig policy, final PolicyMetrics metrics) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return;
        }
        synchronized (SERVICE_WORKER_LOCK) {
            if (serviceWorkerGuardInstalled) {
                return;
            }
            serviceWorkerGuardInstalled = true;
        }
        try {
            ServiceWorkerController.getInstance().setServiceWorkerClient(new ServiceWorkerClient() {
                @Override
                public WebResourceResponse shouldInterceptRequest(WebResourceRequest request) {
                    if (request == null || request.getUrl() == null) {
                        metrics.increment(PolicyMetrics.Counter.BLOCKED_REQUESTS);
                        metrics.recordBlock(app.arenawebview.locked.policy.BlockReason.NULL_URL, "", null);
                        return BlockedResponses.create(null);
                    }
                    String url = request.getUrl().toString();
                    PolicyResult result = policy.evaluateRequest(url);
                    if (result.isAllowed()) {
                        return null;
                    }
                    metrics.increment(PolicyMetrics.Counter.BLOCKED_REQUESTS);
                    metrics.recordBlock(result.reason(), result.canonicalHost(), url);
                    return BlockedResponses.create(result);
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "service worker guard could not be installed", t);
        }
    }

    /**
     * Permits downloads: the user may download files from the WebView, saved to app-private storage.
     * Nothing is ever handed to a download manager or external browser.
     */
    static final class PermittingDownloadListener implements DownloadListener {

        private final GuardHost host;

        PermittingDownloadListener(GuardHost host) {
            this.host = host;
        }

        @Override
        public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                    String mimeType, long contentLength) {
            String hostName = "";
            try {
                Uri uri = Uri.parse(url);
                hostName = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            } catch (Throwable ignored) {
                // reporting only
            }
            host.metrics().increment(PolicyMetrics.Counter.ALLOWED_DOWNLOADS);
            host.onDownloadPermitted(url, userAgent, contentDisposition, mimeType, contentLength);
        }
    }
}
