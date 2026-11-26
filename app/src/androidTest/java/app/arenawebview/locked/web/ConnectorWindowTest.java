package app.arenawebview.locked.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.webkit.WebView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import app.arenawebview.locked.policy.ArenaAllowlist;
import app.arenawebview.locked.policy.BlockReason;
import app.arenawebview.locked.policy.PolicyConfig;
import app.arenawebview.locked.policy.PolicyMetrics;

/**
 * On-device behaviour of the Agent Mode connector exception.
 *
 * <p>The policy is pure Java, but the decision that matters here is which WebView owns a page: these
 * tests drive the real {@link PopupRouterClient} and {@link ArenaWebViewClient} and assert that an OAuth
 * window survives the provider -> Arena staging -> callback sequence while the single visible WebView
 * never accepts a provider page.</p>
 */
@RunWith(AndroidJUnit4.class)
public class ConnectorWindowTest {

    private static final PolicyConfig POLICY = ArenaAllowlist.productionConfig();

    @Test
    public void connectorHandOffStaysInOneWindowAcrossTheStagingHop() {
        final RecordingGuardHost host = new RecordingGuardHost(POLICY);
        final WebView[] holder = new WebView[1];
        final boolean[] alive = new boolean[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(new Runnable() {
            @Override
            public void run() {
                WebView popup = new WebView(InstrumentationRegistry.getInstrumentation().getTargetContext());
                holder[0] = popup;
                final PopupRouterClient client = new PopupRouterClient(host, popup, null);

                assertFalse("about:blank must bootstrap the pop-up",
                        client.shouldOverrideUrlLoading(popup, FakeWebResourceRequest.navigation("about:blank")));

                // Arena writes its own staging URL into the blank window before the provider hop.
                assertFalse("the staging page must not be routed into the main view yet",
                        client.shouldOverrideUrlLoading(popup, FakeWebResourceRequest.navigation(
                                "https://arena.ai/api/connectors/github/start")));
                assertFalse("the staging page is not a visible auth window yet", client.isSignInWindow());
                assertNull("nothing may be loaded in the main WebView", host.lastLoadedUrl);

                assertFalse("the provider's login page must load in this window",
                        client.shouldOverrideUrlLoading(popup, FakeWebResourceRequest.navigation(
                                "https://github.com/login/oauth/authorize?client_id=arena")));
                assertTrue("the window is now the in-app sign-in window", client.isSignInWindow());

                assertFalse("the callback staging hop must stay in the same window",
                        client.shouldOverrideUrlLoading(popup, FakeWebResourceRequest.navigation(
                                "https://arena.ai/api/connectors/github/callback?code=abc&state=xyz")));
                assertFalse("the final provider/consent hop must stay in the same window",
                        client.shouldOverrideUrlLoading(popup, FakeWebResourceRequest.navigation(
                                "https://github.com/login?client_id=arena")));

                alive[0] = popup.getSettings() != null;
            }
        });

        assertEquals(1, host.metrics().count(PolicyMetrics.Counter.SIGN_IN_WINDOWS));
        assertEquals("the first visible URL must be the provider's authorize page",
                "https://github.com/login/oauth/authorize?client_id=arena", host.lastSignInWindowUrl);
        assertEquals("the return hop must be reported as a navigation of the same window",
                "https://arena.ai/api/connectors/github/callback?code=abc&state=xyz",
                host.lastSignInWindowNavigated);
        assertNull("a connector hand-off must never take over the main WebView", host.lastLoadedUrl);
        assertEquals(0, host.metrics().count(PolicyMetrics.Counter.ROUTED_POPUPS));
        assertTrue("the OAuth WebView must still be alive for window.opener", alive[0]);
        assertNull(holder[0].getParent());
    }

    @Test
    public void everyConnectorProviderCanOpenTheWindow() {
        final String[] urls = {
                "https://github.com/login/oauth/authorize?client_id=arena",
                "https://api.notion.com/v1/oauth/authorize?client_id=arena",
                "https://app.notion.com/install-integration?client_id=arena",
                "https://linear.app/oauth/authorize?client_id=arena",
                "https://api.supabase.com/v1/oauth/authorize?client_id=arena",
                "https://vercel.com/oauth/authorize?client_id=arena",
                "https://www.dropbox.com/oauth2/authorize?client_id=arena",
                "https://accounts.google.com/o/oauth2/v2/auth?client_id=arena",
        };
        for (final String url : urls) {
            final RecordingGuardHost host = new RecordingGuardHost(POLICY);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(new Runnable() {
                @Override
                public void run() {
                    WebView popup = new WebView(InstrumentationRegistry.getInstrumentation().getTargetContext());
                    PopupRouterClient client = new PopupRouterClient(host, popup, null);
                    assertFalse("must stay in the window: " + url,
                            client.shouldOverrideUrlLoading(popup, FakeWebResourceRequest.navigation(url)));
                    assertTrue("must be recognised as a hand-off: " + url, client.isSignInWindow());
                }
            });
            assertEquals(url, 1, host.metrics().count(PolicyMetrics.Counter.SIGN_IN_WINDOWS));
            assertNull("must not be routed into the main view: " + url, host.lastLoadedUrl);
        }
    }

    @Test
    public void mainWebViewNeverAcceptsConnectorLoginPages() {
        final RecordingGuardHost host = new RecordingGuardHost(POLICY);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(new Runnable() {
            @Override
            public void run() {
                WebView view = new WebView(InstrumentationRegistry.getInstrumentation().getTargetContext());
                ArenaWebViewClient client = new ArenaWebViewClient(host);
                assertTrue("the main view must refuse a provider page",
                        client.shouldOverrideUrlLoading(view, FakeWebResourceRequest.navigation(
                                "https://github.com/login/oauth/authorize?client_id=arena")));
                assertTrue("the main view must refuse a Notion consent page",
                        client.shouldOverrideUrlLoading(view, FakeWebResourceRequest.navigation(
                                "https://app.notion.com/install-integration?client_id=arena")));
                assertTrue("the main view must refuse a Dropbox consent page",
                        client.shouldOverrideUrlLoading(view, FakeWebResourceRequest.navigation(
                                "https://www.dropbox.com/oauth2/authorize?client_id=arena")));
                assertNull("the main view must not load any of them", host.lastLoadedUrl);
                assertNotNull("a refused document must be answered",
                        client.shouldInterceptRequest(view, FakeWebResourceRequest.navigation(
                                "https://github.com/login/oauth/authorize?client_id=arena")));
                assertNull("backend API traffic is still unrestricted",
                        client.shouldInterceptRequest(view, FakeWebResourceRequest.subresource(
                                "https://api.github.com/user")));
            }
        });
        assertEquals("https://github.com/login/oauth/authorize?client_id=arena", host.lastBlockedNavigation);
        assertNotNull(POLICY.evaluatePrimaryDocument("https://github.com/login/oauth/authorize"));
        assertEquals(BlockReason.CONNECTOR_WINDOW_ONLY,
                POLICY.evaluatePrimaryDocument("https://github.com/login/oauth/authorize").reason());
    }

    @Test
    public void connectorPathsOutsideTheReviewedSetStillCloseTheWindow() {
        final RecordingGuardHost host = new RecordingGuardHost(POLICY);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(new Runnable() {
            @Override
            public void run() {
                WebView popup = new WebView(InstrumentationRegistry.getInstrumentation().getTargetContext());
                PopupRouterClient client = new PopupRouterClient(host, popup, null);
                assertFalse(client.shouldOverrideUrlLoading(popup, FakeWebResourceRequest.navigation(
                        "https://github.com/login/oauth/authorize?client_id=arena")));
                assertTrue("an unrelated page on the provider host must be refused",
                        client.shouldOverrideUrlLoading(popup, FakeWebResourceRequest.navigation(
                                "https://github.com/settings/tokens")));
            }
        });
        assertEquals("https://github.com/settings/tokens", host.lastPopupBlocked);
        assertNull(host.lastLoadedUrl);
    }
}
