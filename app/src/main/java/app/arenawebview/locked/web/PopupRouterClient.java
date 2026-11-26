package app.arenawebview.locked.web;

import android.os.Handler;
import android.os.Looper;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;

import app.arenawebview.locked.policy.PolicyMetrics;
import app.arenawebview.locked.policy.PolicyResult;

/**
 * Client attached to the WebView that Android creates for {@code window.open()} and
 * {@code target="_blank"}.
 *
 * <p>Every pop-up is decided here, and there are exactly three outcomes:</p>
 * <ol>
 *   <li><b>Ordinary allowed target</b> (a page on arena.ai, say {@code target="_blank"} on a
 *       leaderboard link): the URL is handed to the single visible WebView and the extra window is
 *       destroyed, exactly as before. Nothing new can ever appear on screen.</li>
 *   <li><b>Sign-in hand-off</b> (Google's account host): the window is <em>kept</em> and handed to the
 *       host, which shows it as an in-app sign-in window. This is the one case where a second WebView
 *       stays alive, because the OAuth pop-up flow is not a navigation - Google's page returns the
 *       result to the opening page through {@code window.opener}/postMessage, which only works if the
 *       window it opened still exists. It stays inside this app, under the same policy and the same
 *       request interception (this class extends {@link ArenaWebViewClient}).</li>
 *   <li><b>Anything else</b>: refused, counted and reported; the window is destroyed.</li>
 * </ol>
 *
 * <p>The sign-in window is fenced in: it may display Google's sign-in pages and Arena.ai pages - the two
 * halves of the hand-off - and nothing else. Every other allowed URL (a first-party page that is not part
 * of a sign-in, an asset host, anything the policy allows but that is not a step of this flow) is treated
 * as an ordinary pop-up target: it is handed to the single visible WebView and this window is destroyed,
 * so a sign-in window can never be steered into becoming a general-purpose browser.</p>
 *
 * <p>Both roles are checked on <em>every</em> navigation, not just the first one, so a window that was
 * promoted for a Google page cannot later walk somewhere the policy would refuse.</p>
 */
public final class PopupRouterClient extends ArenaWebViewClient {

    /** A first-party staging page is kept hidden for at most this long before normal routing wins. */
    private static final long STAGING_GRACE_MS = 8000L;

    private final WebView popup;
    private final Runnable onClosed;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile boolean closed;
    private boolean promotedToSignInWindow;
    private boolean stagingCandidate;
    private String stagedUrl;

    /**
     * If a blank pop-up was pointed at an Arena staging page and never became an OAuth hand-off, treat
     * that first-party page like every other ordinary pop-up target: show it in the single WebView and
     * destroy the extra window. The timeout bounds the grace period, so no hidden WebView accumulates.
     */
    private final Runnable stagingTimeout = new Runnable() {
        @Override
        public void run() {
            if (closed || promotedToSignInWindow || !stagingCandidate || stagedUrl == null) {
                return;
            }
            String url = stagedUrl;
            stagingCandidate = false;
            stagedUrl = null;
            GuardHost host = host();
            host.metrics().increment(PolicyMetrics.Counter.ROUTED_POPUPS);
            host.onPopupRouted(url);
            host.loadVerifiedDestination(url, "popup-staging");
            finish();
        }
    };

    public PopupRouterClient(GuardHost host, WebView popup, Runnable onClosed) {
        super(host);
        this.popup = popup;
        this.onClosed = onClosed;
    }

    @Override
    public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
        if (request == null || request.getUrl() == null) {
            finish();
            return true;
        }
        return handle(request.getUrl().toString(), request.isForMainFrame());
    }

    @Override
    @SuppressWarnings("deprecation")
    public boolean shouldOverrideUrlLoading(WebView view, String url) {
        // The legacy callback carries no frame information; treating it as a main frame is the
        // conservative reading (Chromium uses the WebResourceRequest overload above on every API level
        // this app supports).
        return handle(url, true);
    }

    private boolean handle(String url, boolean mainFrame) {
        GuardHost host = host();
        if (url == null || url.isEmpty()) {
            finish();
            return true;
        }
        if (isInertPopupLocation(url)) {
            // window.open() with no URL, or a script writing into a blank popup: keep the window alive
            // so a subsequent real navigation can still be routed, but never display anything.
            return false;
        }
        // The same policy question as the main client, with one difference: this window may display a
        // connector provider's OAuth page, while the main WebView may never do so. Embedded documents
        // (widget/CAPTCHA frames) are judged with the in-page rules.
        PolicyResult result = mainFrame
                ? host.policy().evaluateNavigation(url)
                : host.policy().evaluateSubdocument(url);
        if (!result.isAllowed()) {
            if (mainFrame) {
                host.metrics().increment(PolicyMetrics.Counter.BLOCKED_POPUPS);
            } else {
                host.metrics().increment(PolicyMetrics.Counter.BLOCKED_REQUESTS);
            }
            host.metrics().recordBlock(result.reason(), result.canonicalHost(), url);
            if (mainFrame) {
                host.onPopupBlocked(url, result);
            }
            // A refused sub-document only loses its frame: tearing down the whole window would end a
            // sign-in because some tracker on the provider's page was refused. A refused *navigation*
            // (main frame) still ends the window - it has nowhere legitimate left to go.
            if (mainFrame || !promotedToSignInWindow) {
                finish();
            }
            return true;
        }

        if (!mainFrame) {
            // An embedded document inside this window: let it load in place. It cannot become the app's
            // page and it cannot take the window anywhere.
            return false;
        }

        // --- main frame: a sign-in step, a connector OAuth step or an Arena.ai page ---------------
        boolean signInStep = host.policy().isSignInNavigation(url);
        boolean connectorStep = host.policy().isConnectorNavigation(url);
        boolean sitePage = host.policy().isProductNavigation(url);
        boolean handOffStep = signInStep || connectorStep;

        if (promotedToSignInWindow) {
            if (sitePage || handOffStep) {
                // Both halves of the hand-off stay here: the provider's login/consent pages and the
                // first-party callback/staging page that reads the result and calls window.close().
                cancelStaging();
                host.onSignInWindowNavigated(url);
                return false;
            }
            // An unrelated allowed page must not turn the auth window into a browser.
            routeToPrimary(url, "popup");
            return true;
        }

        if (handOffStep) {
            cancelStaging();
            promotedToSignInWindow = true;
            host.metrics().increment(PolicyMetrics.Counter.SIGN_IN_WINDOWS);
            host.onSignInWindowRequested(popup, url);
            return false;
        }

        if (sitePage && host.policy().isHandoffStagingNavigation(url)) {
            // Arena sometimes opens a blank pop-up and writes a first-party staging URL into it before
            // redirecting to the provider. Hold the window hidden for a bounded grace period; only a
            // sign-in/connector URL promotes it, otherwise the ordinary single-view routing wins.
            stagingCandidate = true;
            stagedUrl = url;
            handler.removeCallbacks(stagingTimeout);
            handler.postDelayed(stagingTimeout, STAGING_GRACE_MS);
            return false;
        }

        routeToPrimary(url, "popup");
        return true;
    }

    private void cancelStaging() {
        stagingCandidate = false;
        stagedUrl = null;
        handler.removeCallbacks(stagingTimeout);
    }

    private void routeToPrimary(String url, String source) {
        cancelStaging();
        GuardHost host = host();
        host.metrics().increment(PolicyMetrics.Counter.ROUTED_POPUPS);
        host.onPopupRouted(url);
        host.loadVerifiedDestination(url, source);
        finish();
    }

    /** about:blank, data: and blob: are the inert bootstrap locations of a brand new window. */
    private static boolean isInertPopupLocation(String url) {
        String lower = url.toLowerCase(java.util.Locale.ROOT);
        return lower.equals("about:blank")
                || lower.startsWith("data:")
                || lower.startsWith("blob:");
    }

    /** True once this window was promoted to a visible in-app sign-in window. */
    public boolean isSignInWindow() {
        return promotedToSignInWindow;
    }

    /**
     * A pop-up whose renderer died is simply over. It is deregistered and destroyed like any other
     * finished window; if it was the sign-in window the host closes that window through the usual
     * {@code onPopupClosed} route. Nothing is ever reloaded into it, and the primary view is untouched.
     */
    @Override
    public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
        host().metrics().increment(PolicyMetrics.Counter.RENDERER_GONE);
        finish();
        return true;
    }

    /**
     * Tear this hidden window down and tell whoever created it.
     *
     * <p>Two separate things have to happen, in this order:</p>
     * <ol>
     *   <li><b>Deregister now.</b> The creator is told immediately, so the window stops occupying one of
     *       the chrome client's live-window slots and the host stops tracking it. It is a plain callback,
     *       safe to run inside a navigation callback, and it has to run synchronously: a window that is
     *       only released "later" is a window that can be counted against the app's pop-up limit
     *       indefinitely.</li>
     *   <li><b>Destroy after the callback returns.</b> Destroying a {@code WebView} from inside its own
     *       navigation callback is not safe, so the destruction is posted to the main looper - <em>not</em>
     *       through {@link WebView#post(Runnable)}, which a detached view merely queues until something
     *       attaches it. Nothing ever attaches a hidden pop-up, so that route would have left every hidden
     *       window alive.</li>
     * </ol>
     */
    private void finish() {
        if (closed) {
            return;
        }
        closed = true;
        cancelStaging();
        if (onClosed != null) {
            onClosed.run();
        }
        Runnable destroy = new Runnable() {
            @Override
            public void run() {
                try {
                    popup.setWebViewClient(new android.webkit.WebViewClient());
                    popup.destroy();
                } catch (Throwable ignored) {
                    // A dead WebView is not an error here; it must simply never come back.
                }
            }
        };
        new Handler(Looper.getMainLooper()).post(destroy);
    }
}
