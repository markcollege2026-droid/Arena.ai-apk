package app.arenawebview.locked.web;

import android.webkit.WebResourceRequest;
import android.webkit.WebView;

import app.arenawebview.locked.policy.PolicyMetrics;
import app.arenawebview.locked.policy.PolicyResult;

/**
 * Client attached to the <b>hidden</b> WebView that Android creates for {@code window.open()} and
 * {@code target="_blank"}.
 *
 * <p>The wrapper never shows a second window. {@code onCreateWindow} hands Chromium a WebView that is
 * never attached to the view hierarchy; this client catches the URL that window was about to load,
 * applies the same policy, and either re-routes it into the single visible WebView or refuses it.
 * Either way the hidden window is destroyed, so nothing that the page tries to open can ever become
 * visible, and every request it makes still goes through {@link ArenaWebViewClient}'s request
 * interception (this class is a subclass for exactly that reason).</p>
 */
public final class PopupRouterClient extends ArenaWebViewClient {

    private final WebView popup;
    private final Runnable onClosed;
    private volatile boolean closed;

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
        return handle(request.getUrl().toString());
    }

    @Override
    @SuppressWarnings("deprecation")
    public boolean shouldOverrideUrlLoading(WebView view, String url) {
        return handle(url);
    }

    private boolean handle(String url) {
        GuardHost host = host();
        if (url == null || url.isEmpty()) {
            finish();
            return true;
        }
        if (isInertPopupLocation(url)) {
            // window.open() with no URL, or a script writing into a blank popup: keep the hidden window
            // alive so a subsequent real navigation can still be routed, but never display anything.
            return false;
        }
        PolicyResult result = host.policy().evaluateNavigation(url);
        if (result.isAllowed()) {
            host.metrics().increment(PolicyMetrics.Counter.ROUTED_POPUPS);
            host.onPopupRouted(url);
            host.loadVerifiedDestination(url, "popup");
        } else {
            host.metrics().increment(PolicyMetrics.Counter.BLOCKED_POPUPS);
            host.metrics().recordBlock(result.reason(), result.canonicalHost(), url);
            host.onPopupBlocked(url, result);
        }
        finish();
        // Always cancel the navigation in the hidden window: nothing is ever rendered there.
        return true;
    }

    /** about:blank, data: and blob: are the inert bootstrap locations of a brand new window. */
    private static boolean isInertPopupLocation(String url) {
        String lower = url.toLowerCase(java.util.Locale.ROOT);
        return lower.equals("about:blank")
                || lower.startsWith("data:")
                || lower.startsWith("blob:");
    }

    private void finish() {
        if (closed) {
            return;
        }
        closed = true;
        popup.post(new Runnable() {
            @Override
            public void run() {
                try {
                    popup.setWebViewClient(new android.webkit.WebViewClient());
                    popup.destroy();
                } catch (Throwable ignored) {
                    // A dead WebView is not an error here; it must simply never come back.
                }
                if (onClosed != null) {
                    onClosed.run();
                }
            }
        });
    }
}
