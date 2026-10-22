package app.arenawebview.locked.web;

import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.util.Log;
import android.webkit.ClientCertRequest;
import android.webkit.HttpAuthHandler;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.SafeBrowsingResponse;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import app.arenawebview.locked.policy.BlockReason;
import app.arenawebview.locked.policy.PolicyMetrics;
import app.arenawebview.locked.policy.PolicyResult;

/**
 * The navigation guard.
 *
 * <p>Layers, all fail-closed:</p>
 * <ol>
 *   <li>{@link #shouldOverrideUrlLoading} - every navigation (main frame, iframe, redirect, JS
 *       {@code location}, form POST, custom scheme). Allowed URLs are handed back to the WebView by
 *       returning {@code false} so that Chromium keeps doing what it does best (POST bodies, cookies,
 *       HTTP/2, media); refused URLs are cancelled by returning {@code true}.</li>
 *   <li>{@link #shouldInterceptRequest} - every network request, including each hop of a redirect
 *       chain, XHR/fetch, images, scripts and media. A refused request is answered with an in-process
 *       empty 403 so it never reaches the network.</li>
 *   <li>{@link #onPageStarted}, {@link #onPageFinished} and {@link #doUpdateVisitedHistory} - final-URL
 *       verification. If anything is ever observed outside the policy the host is asked to reset the
 *       WebView, so a page can never remain visible on an unapproved URL.</li>
 *   <li>TLS, client-certificate and HTTP-auth handlers that always cancel.</li>
 * </ol>
 */
public class ArenaWebViewClient extends WebViewClient {

    private static final String TAG = "ArenaWebViewLocked";

    /** Refusals already written to logcat, so a busy page cannot flood the log. */
    private static final int MAX_LOGGED_REFUSALS = 200;

    /**
     * Injected into first-party Arena.ai pages to:
     * (1) dismiss and hide left-sidebar promotional/upsell pop-up cards (e.g. "Try Agent Mode" or
     *     YouTube video promos) so they do not cover the sidebar on small screens, and
     * (2) enforce vertical scrollability on both the outer sidebar and the conversation history
     *     container so previous conversations are always reachable.
     */
    static final String SIDEBAR_GUARD_SCRIPT =
            "(function() {\n"
            + "  try {\n"
            + "    var STYLE_ID = 'arena-apk-sidebar-guard';\n"
            + "    var CSS = [\n"
            + "      '[data-sidebar=\"content\"], [data-slot=\"sidebar-content\"] {',\n"
            + "      '  overflow-y: auto !important;',\n"
            + "      '  overflow-x: hidden !important;',\n"
            + "      '  -webkit-overflow-scrolling: touch !important;',\n"
            + "      '  overscroll-behavior-y: contain !important;',\n"
            + "      '  flex: 1 1 0% !important;',\n"
            + "      '  min-height: 160px !important;',\n"
            + "      '}',\n"
            + "      '[data-sidebar=\"group\"], [data-slot=\"sidebar-group\"],',\n"
            + "      '[data-sidebar=\"group-content\"], [data-slot=\"sidebar-group-content\"] {',\n"
            + "      '  flex-shrink: 0 !important;',\n"
            + "      '}',\n"
            + "      '[data-sidebar=\"footer\"], [data-slot=\"sidebar-footer\"] {',\n"
            + "      '  flex-shrink: 1 !important;',\n"
            + "      '  max-height: 25dvh !important;',\n"
            + "      '  overflow-y: auto !important;',\n"
            + "      '}',\n"
            + "      '[data-arena-hidden-promo=\"1\"],',\n"
            + "      'aside a[href*=\"youtube.com\"], aside a[href*=\"youtu.be\"],',\n"
            + "      '[data-sidebar] a[href*=\"youtube.com\"], [data-sidebar] a[href*=\"youtu.be\"],',\n"
            + "      '[data-slot*=\"sidebar\"] a[href*=\"youtube.com\"], [data-slot*=\"sidebar\"] a[href*=\"youtu.be\"],',\n"
            + "      'aside iframe[src*=\"youtube.com\"], [data-sidebar] iframe[src*=\"youtube.com\"] {',\n"
            + "      '  display: none !important;',\n"
            + "      '}'\n"
            + "    ].join('\\n');\n"
            + "    function ensureStyle() {\n"
            + "      var root = document.head || document.documentElement;\n"
            + "      if (!root) return;\n"
            + "      var style = document.getElementById(STYLE_ID);\n"
            + "      if (!style) {\n"
            + "        style = document.createElement('style');\n"
            + "        style.id = STYLE_ID;\n"
            + "        root.appendChild(style);\n"
            + "      }\n"
            + "      if (style.textContent !== CSS) {\n"
            + "        style.textContent = CSS;\n"
            + "      }\n"
            + "    }\n"
            + "    function isProtectedSidebarStructure(el) {\n"
            + "      if (!el || el.nodeType !== 1) return true;\n"
            + "      var tag = el.tagName ? el.tagName.toLowerCase() : '';\n"
            + "      if (tag === 'aside' || tag === 'nav' || tag === 'body' || tag === 'html' || tag === 'ul' || tag === 'li') return true;\n"
            + "      var ds = (el.getAttribute('data-sidebar') || '') + ' ' + (el.getAttribute('data-slot') || '');\n"
            + "      if (ds.indexOf('sidebar') >= 0 && ds.indexOf('footer') < 0) {\n"
            + "        if (ds.indexOf('content') >= 0 || ds.indexOf('header') >= 0 || ds.indexOf('group') >= 0 || ds.indexOf('menu') >= 0 || ds.trim() === 'sidebar') {\n"
            + "          return true;\n"
            + "        }\n"
            + "      }\n"
            + "      if (el.closest('[data-sidebar=\"content\"], [data-slot=\"sidebar-content\"], [data-sidebar=\"group\"], [data-slot=\"sidebar-group\"], [data-sidebar=\"menu\"], [data-slot=\"sidebar-menu\"], [data-sidebar=\"header\"], [data-slot=\"sidebar-header\"], nav')) {\n"
            + "        return true;\n"
            + "      }\n"
            + "      if (el.querySelector('[data-sidebar=\"content\"], [data-slot=\"sidebar-content\"], [data-sidebar=\"group\"], [data-slot=\"sidebar-group\"], nav')) {\n"
            + "        return true;\n"
            + "      }\n"
            + "      var chatLinks = el.querySelectorAll('a[href^=\"/c/\"], a[href^=\"/chat/\"]');\n"
            + "      if (chatLinks && chatLinks.length > 0) {\n"
            + "        return true;\n"
            + "      }\n"
            + "      return false;\n"
            + "    }\n"
            + "    function findCloseButton(card) {\n"
            + "      var buttons = card.querySelectorAll('button, [role=\"button\"]');\n"
            + "      for (var i = 0; i < buttons.length; i++) {\n"
            + "        var btn = buttons[i];\n"
            + "        var label = ((btn.getAttribute('aria-label') || '') + ' ' + (btn.getAttribute('title') || '') + ' ' + (btn.textContent || '')).trim().toLowerCase();\n"
            + "        if (label === 'close' || label === 'dismiss' || label === '×' || label === 'x' || label.indexOf('close') >= 0 || label.indexOf('dismiss') >= 0) {\n"
            + "          return btn;\n"
            + "        }\n"
            + "        var svg = btn.querySelector('svg');\n"
            + "        if (svg && (btn.textContent || '').trim() === '') {\n"
            + "          var cls = (svg.getAttribute('class') || '').toLowerCase();\n"
            + "          var html = svg.innerHTML || '';\n"
            + "          if (cls.indexOf('lucide-x') >= 0 || html.indexOf('M18 6 6 18') >= 0 || html.indexOf('M18 6L6 18') >= 0 || html.indexOf('M6 6l12 12') >= 0) {\n"
            + "            return btn;\n"
            + "          }\n"
            + "        }\n"
            + "      }\n"
            + "      return null;\n"
            + "    }\n"
            + "    function hidePromoCard(card) {\n"
            + "      if (!card || isProtectedSidebarStructure(card)) return false;\n"
            + "      var closeBtn = findCloseButton(card);\n"
            + "      if (closeBtn && !card.getAttribute('data-arena-dismissed')) {\n"
            + "        card.setAttribute('data-arena-dismissed', '1');\n"
            + "        try { closeBtn.click(); } catch (e) {}\n"
            + "      }\n"
            + "      card.setAttribute('data-arena-hidden-promo', '1');\n"
            + "      card.style.setProperty('display', 'none', 'important');\n"
            + "      return true;\n"
            + "    }\n"
            + "    function findPromoCardAncestor(node, sidebarRoot) {\n"
            + "      var cur = node;\n"
            + "      var candidate = null;\n"
            + "      while (cur && cur !== sidebarRoot && cur !== document.body) {\n"
            + "        if (isProtectedSidebarStructure(cur)) break;\n"
            + "        candidate = cur;\n"
            + "        var parent = cur.parentElement;\n"
            + "        if (!parent || parent === sidebarRoot || isProtectedSidebarStructure(parent)) {\n"
            + "          break;\n"
            + "        }\n"
            + "        cur = parent;\n"
            + "      }\n"
            + "      return candidate;\n"
            + "    }\n"
            + "    function scanSidebar() {\n"
            + "      ensureStyle();\n"
            + "      var sidebars = document.querySelectorAll('aside, [data-sidebar=\"sidebar\"], [data-slot=\"sidebar\"], [data-sidebar=\"footer\"], [data-slot=\"sidebar-footer\"]');\n"
            + "      for (var s = 0; s < sidebars.length; s++) {\n"
            + "        var sidebar = sidebars[s];\n"
            + "        var ytNodes = sidebar.querySelectorAll('a[href*=\"youtube.com\"], a[href*=\"youtu.be\"], iframe[src*=\"youtube.com\"], iframe[src*=\"youtu.be\"], img[src*=\"ytimg.com\"], img[src*=\"youtube.com\"]');\n"
            + "        for (var y = 0; y < ytNodes.length; y++) {\n"
            + "          var ytCard = findPromoCardAncestor(ytNodes[y], sidebar);\n"
            + "          hidePromoCard(ytCard || ytNodes[y]);\n"
            + "        }\n"
            + "        var blocks = sidebar.querySelectorAll('div, section, article');\n"
            + "        for (var b = 0; b < blocks.length; b++) {\n"
            + "          var el = blocks[b];\n"
            + "          if (el.getAttribute('data-arena-hidden-promo') === '1') continue;\n"
            + "          if (isProtectedSidebarStructure(el)) continue;\n"
            + "          var text = (el.textContent || '').replace(/\\s+/g, ' ').trim().toLowerCase();\n"
            + "          if (!text || text.length > 500) continue;\n"
            + "          var isAgentPromo = text.indexOf('try agent mode') >= 0\n"
            + "              || (text.indexOf('agent mode') >= 0 && (text.indexOf('try') >= 0 || text.indexOf('new') >= 0 || text.indexOf('introduce') >= 0 || text.indexOf('introducing') >= 0));\n"
            + "          var isVideoPromo = text.indexOf('watch on youtube') >= 0 || text.indexOf('youtube') >= 0;\n"
            + "          var closeBtn = findCloseButton(el);\n"
            + "          var inFooter = !!el.closest('[data-sidebar=\"footer\"], [data-slot=\"sidebar-footer\"]');\n"
            + "          if (isAgentPromo || isVideoPromo || (inFooter && closeBtn)) {\n"
            + "            var targetCard = findPromoCardAncestor(el, sidebar) || el;\n"
            + "            hidePromoCard(targetCard);\n"
            + "          }\n"
            + "        }\n"
            + "      }\n"
            + "    }\n"
            + "    scanSidebar();\n"
            + "    if (!window.__arenaSidebarGuardInstalled) {\n"
            + "      window.__arenaSidebarGuardInstalled = true;\n"
            + "      var observer = new MutationObserver(function() {\n"
            + "        scanSidebar();\n"
            + "      });\n"
            + "      observer.observe(document.documentElement || document.body, {\n"
            + "        childList: true,\n"
            + "        subtree: true\n"
            + "      });\n"
            + "    }\n"
            + "    return 'installed';\n"
            + "  } catch (e) {\n"
            + "    return 'error:' + e;\n"
            + "  }\n"
            + "})()";

    private final Set<String> loggedRefusals =
            Collections.synchronizedSet(new LinkedHashSet<String>());

    private final GuardHost host;

    public ArenaWebViewClient(GuardHost host) {
        if (host == null) {
            throw new IllegalArgumentException("host");
        }
        this.host = host;
    }

    // ---------------------------------------------------------------------------------------------
    // Navigation
    // ---------------------------------------------------------------------------------------------

    @Override
    public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
        if (request == null || request.getUrl() == null) {
            host.metrics().increment(PolicyMetrics.Counter.BLOCKED_NAVIGATIONS);
            host.metrics().recordBlock(BlockReason.NULL_URL, "", null);
            host.onNavigationBlocked("", null);
            return true;
        }
        String url = request.getUrl().toString();
        boolean mainFrame = request.isForMainFrame();

        // An iframe is judged as an embedded document, not as the app's page: a widget frame from an
        // asset host is allowed to render, while the main frame may only ever be an Arena.ai or sign-in
        // page. This is the difference between "the app can show this" and "the app can fetch this".
        PolicyResult result = mainFrame
                ? host.policy().evaluateNavigation(url)
                : host.policy().evaluateSubdocument(url);
        if (result.isAllowed()) {
            if (mainFrame) {
                host.metrics().increment(PolicyMetrics.Counter.ALLOWED_NAVIGATIONS);
            }
            // Let the WebView perform the navigation itself: returning false preserves the HTTP method
            // (POST form submissions!), request headers, cookies and any server-side redirect chain
            // that stays inside the policy.
            return false;
        }

        recordBlock(result, url, mainFrame, isRedirect(request));
        if (mainFrame) {
            host.onNavigationBlocked(url, result);
        }
        return true;
    }

    /**
     * Legacy callback. Chrome-based WebViews on API 24+ use the {@code WebResourceRequest} overload
     * above; this override exists so that the same rule applies if it is ever called.
     */
    @Override
    @SuppressWarnings("deprecation")
    public boolean shouldOverrideUrlLoading(WebView view, String url) {
        PolicyResult result = host.policy().evaluateNavigation(url);
        if (result.isAllowed()) {
            return false;
        }
        recordBlock(result, url, true, false);
        host.onNavigationBlocked(url, result);
        return true;
    }

    // ---------------------------------------------------------------------------------------------
    // Requests (every sub-resource and every redirect hop)
    // ---------------------------------------------------------------------------------------------

    @Override
    public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
        if (request == null || request.getUrl() == null) {
            host.metrics().increment(PolicyMetrics.Counter.BLOCKED_REQUESTS);
            host.metrics().recordBlock(BlockReason.NULL_URL, "", null);
            return BlockedResponses.create(null);
        }
        Uri uri = request.getUrl();
        String url = uri.toString();
        // The network layer sees every hop of every load, so it must ask the right question for each: a
        // main-frame request *is* the page (an asset host, a blob or a data URL must be refused here even
        // if an earlier hook were ever skipped); an embedded frame (Sec-Fetch-Dest: iframe/frame/embed)
        // must pass the embedded-document rules; sub-resources inside an approved page use evaluateRequest.
        PolicyResult result;
        if (request.isForMainFrame()) {
            result = host.policy().evaluateNavigation(url);
        } else if (isSubdocumentRequest(request)) {
            result = host.policy().evaluateSubdocument(url);
        } else {
            result = host.policy().evaluateRequest(url);
        }
        if (result.isAllowed()) {
            // null == "carry on normally". This is what keeps cookies, range requests, streaming media
            // and WebView's own cache working: nothing is proxied, rewritten or buffered.
            return null;
        }
        final boolean mainFrame = request.isForMainFrame();
        final boolean redirect = isRedirect(request);
        final String blockedUrl = url;
        final PolicyResult blockedResult = result;
        recordBlock(result, url, mainFrame, redirect);
        logRefusal(result, url, mainFrame);
        if (mainFrame) {
            // shouldInterceptRequest runs on a background thread; hop to the UI thread before the host
            // touches any view.
            postToUi(view, new Runnable() {
                @Override
                public void run() {
                    host.onPageLoadBlocked(blockedUrl, blockedResult);
                }
            });
        }
        return BlockedResponses.create(result);
    }

    /**
     * Writes one line per distinct refused destination to logcat.
     *
     * <p>Without this, a page whose script was refused simply behaves strangely - which is exactly how a
     * sign-in form can end up dead with nothing to see. The line names the reason, the host and the path
     * (never the query string), so a user report can be matched against the allow-list.</p>
     */
    private void logRefusal(PolicyResult result, String url, boolean mainFrame) {
        String reason = result == null || result.reason() == null ? "?" : result.reason().name();
        String host = result == null ? "" : result.canonicalHost();
        String path = pathOnly(url);
        synchronized (loggedRefusals) {
            if (loggedRefusals.size() >= MAX_LOGGED_REFUSALS || !loggedRefusals.add(reason + "|" + host + path)) {
                return;
            }
        }
        Log.i(TAG, "refused " + (mainFrame ? "document" : "sub-resource") + ": " + reason
                + " host=" + host + " path=" + path);
    }

    /** The raw path of a URL, without query or fragment, for the refusal log. */
    private static String pathOnly(String url) {
        if (url == null || url.isEmpty()) {
            return "";
        }
        int schemeEnd = url.indexOf("://");
        int start = schemeEnd < 0 ? 0 : schemeEnd + 3;
        int slash = url.indexOf('/', start);
        if (slash < 0) {
            return "/";
        }
        int end = url.length();
        int query = url.indexOf('?', slash);
        int fragment = url.indexOf('#', slash);
        if (query >= 0 && query < end) {
            end = query;
        }
        if (fragment >= 0 && fragment < end) {
            end = fragment;
        }
        return url.substring(slash, end);
    }

    // ---------------------------------------------------------------------------------------------
    // Final-URL verification
    // ---------------------------------------------------------------------------------------------

    @Override
    public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
        super.onPageStarted(view, url, favicon);
        verifyFinalUrl(view, url, "onPageStarted");
    }

    @Override
    public void onPageFinished(WebView view, String url) {
        super.onPageFinished(view, url);
        verifyFinalUrl(view, url, "onPageFinished");
        if (host.isPrimaryWebView(view)) {
            if (host.policy().isProductNavigation(url)) {
                injectSidebarGuard(view);
            }
            host.onPageFinished(url);
        }
    }

    @Override
    public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
        super.doUpdateVisitedHistory(view, url, isReload);
        if (!host.isPrimaryWebView(view)) {
            // Hidden popup windows never drive the visible UI; their own client handles routing.
            return;
        }
        PolicyResult result = host.policy().evaluateNavigation(url);
        if (!result.isAllowed()) {
            host.metrics().increment(PolicyMetrics.Counter.RECOVERY_RELOADS);
            host.metrics().recordBlock(result.reason(), result.canonicalHost(), url);
            // Do not let a refused URL stay in the history stack either: back navigation must only ever
            // be able to reach verified pages.
            view.clearHistory();
            host.onRecoveryRequired("history:" + (result.reason() == null ? "?" : result.reason().name()), url);
            return;
        }
        if (host.policy().isProductNavigation(url)) {
            injectSidebarGuard(view);
        }
    }

    void injectSidebarGuard(WebView view) {
        if (view == null) {
            return;
        }
        try {
            view.evaluateJavascript(SIDEBAR_GUARD_SCRIPT, null);
        } catch (Throwable ignored) {
            // Detached or uninitialised WebView in unit/synthetic tests.
        }
    }

    private void verifyFinalUrl(WebView view, String url, String where) {
        if (url == null || url.isEmpty()) {
            return;
        }
        if (!host.isPrimaryWebView(view)) {
            return;
        }
        PolicyResult result = host.policy().evaluateNavigation(url);
        if (!result.isAllowed()) {
            host.metrics().increment(PolicyMetrics.Counter.RECOVERY_RELOADS);
            host.metrics().recordBlock(result.reason(), result.canonicalHost(), url);
            host.onRecoveryRequired(where + ":" + (result.reason() == null ? "?" : result.reason().name()), url);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Errors and other transports
    // ---------------------------------------------------------------------------------------------

    @Override
    public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
        if (request != null && request.isForMainFrame()) {
            String description = error == null
                    ? "unknown"
                    : (String.valueOf(error.getErrorCode()) + " " + String.valueOf(error.getDescription()));
            host.onLoadError(request.getUrl() == null ? "" : request.getUrl().toString(), description);
        }
    }

    /**
     * Certificate errors are never bypassed. Note that there is deliberately no branch that can call
     * {@code handler.proceed()}.
     */
    @Override
    public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
        host.metrics().increment(PolicyMetrics.Counter.BLOCKED_SSL_ERRORS);
        host.metrics().recordBlock(BlockReason.CAPABILITY_DISABLED,
                error == null ? "" : String.valueOf(error.getUrl()), null);
        handler.cancel();
        host.onSslErrorBlocked(error == null ? "" : String.valueOf(error.getUrl()));
    }

    @Override
    public void onReceivedClientCertRequest(WebView view, ClientCertRequest request) {
        host.metrics().increment(PolicyMetrics.Counter.BLOCKED_CLIENT_CERT);
        request.cancel();
    }

    @Override
    public void onReceivedHttpAuthRequest(WebView view, HttpAuthHandler handler, String hostName, String realm) {
        host.metrics().increment(PolicyMetrics.Counter.BLOCKED_HTTP_AUTH);
        handler.cancel();
    }

    /** Android's saved-credential login flow is never used: this wrapper has no credential store. */
    @Override
    @SuppressWarnings("deprecation")
    public void onReceivedLoginRequest(WebView view, String realm, String account, String args) {
        host.metrics().increment(PolicyMetrics.Counter.IGNORED_INCOMING_INTENTS);
    }

    @Override
    public void onSafeBrowsingHit(WebView view, WebResourceRequest request, int threatType,
                                  SafeBrowsingResponse callback) {
        host.metrics().increment(PolicyMetrics.Counter.BLOCKED_SAFE_BROWSING);
        String url = request == null || request.getUrl() == null ? "" : request.getUrl().toString();
        host.metrics().recordBlock(BlockReason.CAPABILITY_DISABLED, "", url);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            // Go back to the last safe page instead of showing the unsafe one.
            callback.backToSafety(true);
        }
        host.onPageLoadBlocked(url, null);
    }

    /**
     * A lost renderer process would otherwise take the whole app down. Reporting the event as handled
     * keeps the app alive, but the WebView that lost its renderer is dead from this moment on: the
     * platform requires it to be removed and destroyed, and a {@code loadUrl} on it would only ever
     * show a blank view. So this does not "recover" the view - it hands it to the host to be replaced
     * (primary view) or closed (pop-up / sign-in window).
     */
    @Override
    public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
        host.metrics().increment(PolicyMetrics.Counter.RENDERER_GONE);
        boolean crashed = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && detail != null && detail.didCrash();
        host.onRenderProcessGone(view, crashed);
        return true;
    }

    // ---------------------------------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------------------------------

    /** Accessor for subclasses (the hidden-popup router). */
    protected GuardHost host() {
        return host;
    }

    protected void recordBlock(PolicyResult result, String url, boolean mainFrame, boolean redirect) {
        PolicyMetrics metrics = host.metrics();
        metrics.increment(mainFrame
                ? PolicyMetrics.Counter.BLOCKED_NAVIGATIONS
                : PolicyMetrics.Counter.BLOCKED_REQUESTS);
        if (redirect) {
            metrics.increment(PolicyMetrics.Counter.BLOCKED_REDIRECTS);
        }
        metrics.recordBlock(result == null ? BlockReason.NULL_URL : result.reason(),
                result == null ? "" : result.canonicalHost(), url);
    }

    private static boolean isRedirect(WebResourceRequest request) {
        // WebResourceRequest.isRedirect() exists since API 24, which is this app's minSdk.
        try {
            return request.isRedirect();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isSubdocumentRequest(WebResourceRequest request) {
        try {
            java.util.Map<String, String> headers = request.getRequestHeaders();
            if (headers == null || headers.isEmpty()) {
                return false;
            }
            for (java.util.Map.Entry<String, String> entry : headers.entrySet()) {
                if ("Sec-Fetch-Dest".equalsIgnoreCase(entry.getKey())) {
                    String dest = entry.getValue();
                    if (dest == null) {
                        return false;
                    }
                    return "iframe".equalsIgnoreCase(dest)
                            || "frame".equalsIgnoreCase(dest)
                            || "embed".equalsIgnoreCase(dest)
                            || "object".equalsIgnoreCase(dest)
                            || "fencedframe".equalsIgnoreCase(dest);
                }
            }
        } catch (Throwable ignored) {
            // Fall through to standard sub-resource evaluation.
        }
        return false;
    }

    private static void postToUi(WebView view, Runnable action) {
        if (view != null) {
            view.post(action);
        } else {
            action.run();
        }
    }
}
