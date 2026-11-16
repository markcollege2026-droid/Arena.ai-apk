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

    private final WebView popup;
    private final Runnable onClosed;
    private volatile boolean closed;
    private boolean promotedToSignInWindow;

    /**
     * The page that opened this window, captured at creation time. It decides whether a first hop to an
     * Arena.ai page is allowed to stay in this window or must be folded into the main view.
     */
    private final String openerUrl;
    private final boolean flowConnector;

    public PopupRouterClient(GuardHost host, WebView popup, Runnable onClosed) {
        this(host, popup, onClosed, null, false);
    }

    /**
     * @param openerUrl     the URL of the page that opened the window, or null when unknown
     * @param flowConnector true when the opener is Arena's Agent page, i.e. the only place a connector
     *                      hand-off can start
     */
    public PopupRouterClient(GuardHost host, WebView popup, Runnable onClosed, String openerUrl,
                             boolean flowConnector) {
        super(host);
        this.popup = popup;
        this.onClosed = onClosed;
        this.openerUrl = openerUrl;
        this.flowConnector = flowConnector;
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
        // The same rule as the main client: a main frame may only ever be an Arena.ai page or a sign-in
        // page, while a document embedded in this window (an iframe) is judged as an embedded document -
        // otherwise a widget frame - or a CAPTCHA that Google's own sign-in page embeds - would be
        // cancelled and the flow would die half-way through.
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
            // sign-in because some tracker on Google's page was refused. A refused *navigation*
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

        // --- main frame: only a sign-in/connector step or an Arena.ai page may live here -----------
        // isSignInNavigation() covers both shapes: Google's account pages (no path pin) and a
        // connector's OAuth authorize endpoint (path-pinned, so the provider's own site is still not
        // reachable). A connector window therefore behaves exactly like the Google hand-off: it stays
        // open, it is policed on every hop, and it can only ever show those two kinds of page plus
        // Arena.ai itself.
        //
        // An Arena.ai page is now allowed on the *first* hop too, not only after promotion. Arena opens a
        // connector by first loading its own staging page in the new window ("you will be sent to X to
        // sign in") and only then redirecting to the provider, so the user has to be able to see and
        // click through that page. Refusing it used to destroy the window before any sign-in could
        // start: the URL was routed into the main view, the window - and with it window.opener - was
        // gone, and the site's own postMessage callback never fired. That is why the failure showed up
        // as both an allow-list block and an error from the site itself.
        //
        // The cost is deliberate and bounded: a pop-up that lands on an Arena.ai page stays in this
        // window instead of being folded into the main view, so a target="_blank" link now opens a
        // guarded in-app window rather than replacing the page. Both are the same policy-checked
        // WebView, this window is destroyed as soon as the page closes, and nothing can navigate it
        // beyond Arena.ai and the pinned sign-in endpoints.
        boolean signInStep = host.policy().isSignInNavigation(url);
        boolean sitePage = host.policy().isProductNavigation(url);
        // An Arena.ai page may keep this window only when the window came from the connector hand-off.
        // Arena opens a connector by loading its own staging page here first ("you will be sent to X to
        // sign in"), so that page has to be displayable or the flow dies before it starts. Every other
        // pop-up keeps the original guarantee: an allowed Arena.ai target is folded into the single
        // visible WebView and this window is discarded.
        boolean stagingHop = sitePage && flowConnector && startsInConnectorFlow();
        if (signInStep || stagingHop) {
            // The sign-in / connector hand-off. The window has to stay alive because the provider's page
            // returns its result to the page that opened it through window.opener/postMessage - a flow
            // that only works if the window it opened still exists.
            //
            // Two kinds of navigation belong to that hand-off and both stay in this window:
            //   1. the provider's own pages (Google's identifier/password/consent steps, or a connector's
            //      authorize endpoint and its sign-in steps), and
            //   2. the return hop to Arena.ai's callback URL, which is where the site reads the result and
            //      usually closes the window itself with window.close().
            if (isHandOffComplete(url)) {
                // The hand-off is over and the site did not close its own window. Close it here so the
                // user is not left staring at a finished sign-in floating over the app.
                host.onSignInWindowNavigated(url);
                finish();
                return true;
            }
            if (!promotedToSignInWindow) {
                promotedToSignInWindow = true;
                host.metrics().increment(PolicyMetrics.Counter.SIGN_IN_WINDOWS);
                host.onSignInWindowRequested(popup, url);
            } else {
                host.onSignInWindowNavigated(url);
            }
            return false;
        }

        // Any other allowed page: keep the single-view guarantee - hand it to the one visible WebView and
        // discard the throwaway window Chromium created. A URL the policy allows but that is not part of
        // this hand-off can never gain a window of its own.
        host.metrics().increment(PolicyMetrics.Counter.ROUTED_POPUPS);
        host.onPopupRouted(url);
        host.loadVerifiedDestination(url, "popup");
        finish();
        return true;
    }

    /**
     * True when this pop-up has finished its hand-off and landed back on Arena.ai's own page.
     *
     * <p>A successful OAuth window is expected to return to an Arena.ai callback URL, where the site
     * reads the result and calls {@code window.close()} itself, or to the root page. Only the second
     * shape needs help: if the window sits on the app's root page with no callback parameters left, the
     * hand-off is over and the window has nothing further to show, so it closes itself rather than
     * hanging open over the app.</p>
     *
     * <p>A callback URL is deliberately <em>not</em> matched - that is the page that reads the result,
     * and closing it early would throw the result away.</p>
     */
    static boolean isHandOffComplete(String url) {
        if (url == null || url.isEmpty()) {
            return false;
        }
        String lower = url.toLowerCase(java.util.Locale.ROOT);
        int schemeEnd = lower.indexOf("://");
        if (schemeEnd < 0 || !lower.startsWith("https://")) {
            return false;
        }
        String rest = lower.substring(schemeEnd + 3);
        int slash = rest.indexOf('/');
        String host = slash < 0 ? rest : rest.substring(0, slash);
        int colon = host.indexOf(':');
        if (colon >= 0) {
            host = host.substring(0, colon);
        }
        if (!host.equals("arena.ai") && !host.equals("www.arena.ai")) {
            return false;
        }
        if (slash < 0) {
            return true;
        }
        String pathAndQuery = rest.substring(slash);
        int hash = pathAndQuery.indexOf('#');
        if (hash >= 0) {
            pathAndQuery = pathAndQuery.substring(0, hash);
        }
        int question = pathAndQuery.indexOf('?');
        String path = question < 0 ? pathAndQuery : pathAndQuery.substring(0, question);
        String query = question < 0 ? "" : pathAndQuery.substring(question + 1);
        if (!path.isEmpty() && !"/".equals(path)) {
            return false;
        }
        return query.isEmpty();
    }

    /**
     * True when this window was opened from Arena's Agent page - the only page that can start a
     * connector hand-off.
     *
     * <p>Deliberately narrow: an unknown opener (a plain {@code window.open()} whose opener we could not
     * read) is treated as "not a connector flow", so the conservative fold-into-the-main-view behaviour
     * stays the default.</p>
     */
    private boolean startsInConnectorFlow() {
        if (!flowConnector) {
            return false;
        }
        return openerUrl != null && openerUrl.contains("/agent");
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
