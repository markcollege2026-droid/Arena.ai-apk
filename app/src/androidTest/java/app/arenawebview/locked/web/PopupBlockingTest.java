package app.arenawebview.locked.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.os.Message;
import android.webkit.WebView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Constructor;

import app.arenawebview.locked.policy.ArenaAllowlist;
import app.arenawebview.locked.policy.PolicyConfig;
import app.arenawebview.locked.policy.PolicyMetrics;

/**
 * Pop-ups ({@code window.open()}, {@code target="_blank"}, {@code isDialog} windows).
 *
 * <p>The wrapper's promise is that no second window is ever shown and no pop-up can escape: Chromium is
 * handed a hidden WebView, the URL that window wanted is re-validated, an allowed URL is loaded in the
 * single visible WebView and anything else is refused.</p>
 */
@RunWith(AndroidJUnit4.class)
public class PopupBlockingTest {

    private static final PolicyConfig POLICY = ArenaAllowlist.productionConfig();

    /** The routing decision itself: a hidden window that wanted a third-party URL must be refused. */
    @Test
    public void popupToThirdPartyIsRefusedAndNeverAttached() {
        final RecordingGuardHost host = new RecordingGuardHost(POLICY);
        final WebView[] holder = new WebView[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(new Runnable() {
            @Override
            public void run() {
                WebView popup = new WebView(InstrumentationRegistry.getInstrumentation().getTargetContext());
                holder[0] = popup;
                PopupRouterClient client = new PopupRouterClient(host, popup, null);

                assertTrue("the hidden window must never load the target itself",
                        client.shouldOverrideUrlLoading(popup, FakeWebResourceRequest.navigation("https://evil.example/")));

                // ... and requests made from inside it are policed as well.
                assertNotNull(client.shouldInterceptRequest(popup, FakeWebResourceRequest.subresource("https://evil.example/tracker.js")));
                assertNull(client.shouldInterceptRequest(popup, FakeWebResourceRequest.subresource("https://arena.ai/x.js")));
            }
        });

        assertEquals(1, host.metrics().count(PolicyMetrics.Counter.BLOCKED_POPUPS));
        assertEquals("https://evil.example/", host.lastPopupBlocked);
        assertNull("nothing may be routed into the main WebView", host.lastLoadedUrl);
        assertNull("a pop-up must never be attached to a view hierarchy", holder[0].getParent());
    }

    /** A pop-up that targets the site itself is loaded in the one visible WebView, not in a new window. */
    @Test
    public void popupToTheSiteIsReroutedIntoTheSingleWebView() {
        final RecordingGuardHost host = new RecordingGuardHost(POLICY);
        final WebView[] holder = new WebView[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(new Runnable() {
            @Override
            public void run() {
                WebView popup = new WebView(InstrumentationRegistry.getInstrumentation().getTargetContext());
                holder[0] = popup;
                PopupRouterClient client = new PopupRouterClient(host, popup, null);

                assertTrue(client.shouldOverrideUrlLoading(popup,
                        FakeWebResourceRequest.navigation("https://arena.ai/leaderboard")));
            }
        });

        assertEquals(1, host.metrics().count(PolicyMetrics.Counter.ROUTED_POPUPS));
        assertEquals("https://arena.ai/leaderboard", host.lastPopupRouted);
        assertEquals("https://arena.ai/leaderboard", host.lastLoadedUrl);
        assertEquals("popup", host.lastSource);
        assertNull(holder[0].getParent());
    }

    /** A blank pop-up is left alone (scripts can still write into it) but nothing is ever displayed. */
    @Test
    public void blankPopupIsNotNavigated() {
        final RecordingGuardHost host = new RecordingGuardHost(POLICY);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(new Runnable() {
            @Override
            public void run() {
                WebView popup = new WebView(InstrumentationRegistry.getInstrumentation().getTargetContext());
                PopupRouterClient client = new PopupRouterClient(host, popup, null);
                assertFalse(client.shouldOverrideUrlLoading(popup, FakeWebResourceRequest.navigation("about:blank")));
            }
        });
        assertEquals(0, host.metrics().count(PolicyMetrics.Counter.ROUTED_POPUPS));
        assertNull(host.lastLoadedUrl);
    }

    /**
     * Structural check of {@code onCreateWindow}: it claims the request (so no window is created by the
     * platform), hands Chromium a WebView that is never attached anywhere, and destroys it when its
     * client is done.
     */
    @Test
    public void onCreateWindowHandsOverAHiddenWebView() {
        final RecordingGuardHost host = new RecordingGuardHost(POLICY);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(new Runnable() {
            @Override
            public void run() {
                android.content.Context context =
                        InstrumentationRegistry.getInstrumentation().getTargetContext();
                WebView primary = new WebView(context);
                ArenaWebChromeClient chrome = new ArenaWebChromeClient(context, host);

                // Chromium always hands over a Message that has a target handler; a bare
                // Message.obtain() would make sendToTarget() throw, so mirror the real shape.
                Message message = new android.os.Handler(android.os.Looper.getMainLooper())
                        .obtainMessage();
                WebView.WebViewTransport transport;
                try {
                    // WebViewTransport is a public type but not a public constructor; reflection keeps
                    // this test compiling on every SDK while still exercising the real code path.
                    Constructor<WebView.WebViewTransport> constructor =
                            WebView.WebViewTransport.class.getDeclaredConstructor(WebView.class);
                    constructor.setAccessible(true);
                    transport = constructor.newInstance(primary);
                } catch (Throwable t) {
                    Assume.assumeNoException("WebViewTransport is not constructible on this SDK", t);
                    return;
                }
                message.obj = transport;

                boolean handled = chrome.onCreateWindow(primary, false, true, message);

                assertTrue("the app must handle window creation itself", handled);
                WebView popup = transport.getWebView();
                assertNotNull("a hidden WebView must have been handed to Chromium", popup);
                assertNull("the hidden window must not be attached to any view hierarchy", popup.getParent());
                // The hidden window gets the same hardened configuration as the visible one, and is
                // policed by PopupRouterClient (asserted directly in the tests above).
                assertTrue("the site must keep working in the hidden window",
                        popup.getSettings().getJavaScriptEnabled());
                assertFalse(popup.getSettings().getAllowFileAccess());
                assertFalse(popup.getSettings().getAllowUniversalAccessFromFileURLs());
            }
        });
    }

    /** Dialog-style windows are refused before any window object exists. */
    @Test
    public void dialogPopupsAreRefused() {
        final RecordingGuardHost host = new RecordingGuardHost(POLICY);
        final boolean[] handled = new boolean[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(new Runnable() {
            @Override
            public void run() {
                ArenaWebChromeClient chrome = new ArenaWebChromeClient(
                        InstrumentationRegistry.getInstrumentation().getTargetContext(), host);
                handled[0] = chrome.onCreateWindow(null, true, true, Message.obtain());
            }
        });
        assertFalse(handled[0]);
        assertEquals(1, host.metrics().count(PolicyMetrics.Counter.BLOCKED_POPUPS));
    }
}
