package app.arenawebview.locked.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.webkit.WebResourceResponse;
import android.webkit.WebView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import app.arenawebview.locked.policy.ArenaAllowlist;
import app.arenawebview.locked.policy.PolicyConfig;
import app.arenawebview.locked.policy.PolicyMetrics;

/**
 * Drives the real {@link ArenaWebViewClient} on a device with synthesized requests.
 *
 * <p>No network is used: these assertions are about the guard's decisions, which is what decides whether
 * a request ever leaves the device.</p>
 */
@RunWith(AndroidJUnit4.class)
public class NavigationGuardTest {

    private WebView webView;
    private RecordingGuardHost host;
    private ArenaWebViewClient client;
    private PolicyConfig policy;

    @Before
    public void setUp() {
        policy = ArenaAllowlist.productionConfig();
        host = new RecordingGuardHost(policy);
        client = new ArenaWebViewClient(host);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(new Runnable() {
            @Override
            public void run() {
                webView = new WebView(InstrumentationRegistry.getInstrumentation().getTargetContext());
                WebViewHardening.applyHardenedSettings(webView.getSettings());
            }
        });
    }

    @Test
    public void allowsTheSiteItself() {
        assertFalse(client.shouldOverrideUrlLoading(webView, FakeWebResourceRequest.navigation("https://arena.ai/chat")));
        assertEquals(1, host.metrics().count(PolicyMetrics.Counter.ALLOWED_NAVIGATIONS));
        assertNull(client.shouldInterceptRequest(webView, FakeWebResourceRequest.navigation("https://arena.ai/chat")));
        assertNull(client.shouldInterceptRequest(webView, FakeWebResourceRequest.subresource("https://cdn.arena.ai/x.js")));
    }

    @Test
    public void allowsPostNavigationsWithoutTouchingThem() {
        // A "return true" here would silently turn a form POST into a GET; the guard must not do that.
        assertFalse(client.shouldOverrideUrlLoading(webView,
                new FakeWebResourceRequest("https://arena.ai/api/chat", true, false, true, "POST")));
    }

    @Test
    public void refusesThirdPartyNavigation() {
        assertTrue(client.shouldOverrideUrlLoading(webView, FakeWebResourceRequest.navigation("https://evil.example/")));
        assertEquals(1, host.metrics().count(PolicyMetrics.Counter.BLOCKED_NAVIGATIONS));
        assertEquals("https://evil.example/", host.lastBlockedNavigation);
        assertEquals("evil.example", host.metrics().recentBlocks().get(0).host());
    }

    @Test
    public void refusesThirdPartySubresourcesWithoutSendingThem() {
        WebResourceResponse response = client.shouldInterceptRequest(webView,
                FakeWebResourceRequest.subresource("https://evil.example/tracker.js"));
        assertNotNull("a refused request must be answered in-process, not with null", response);
        assertEquals(403, response.getStatusCode());
        assertEquals(1, host.metrics().count(PolicyMetrics.Counter.BLOCKED_REQUESTS));
    }

    @Test
    public void refusesThirdPartyIframes() {
        assertTrue(client.shouldOverrideUrlLoading(webView, FakeWebResourceRequest.subframe("https://evil.example/frame")));
        assertEquals(1, host.metrics().count(PolicyMetrics.Counter.BLOCKED_REQUESTS));
    }

    @Test
    public void refusesRedirectsToThirdParties() {
        assertTrue(client.shouldOverrideUrlLoading(webView, FakeWebResourceRequest.redirect("https://evil.example/")));
        assertEquals(1, host.metrics().count(PolicyMetrics.Counter.BLOCKED_REDIRECTS));
        // ... and the redirect target is refused at the network layer as well.
        WebResourceResponse response = client.shouldInterceptRequest(webView,
                new FakeWebResourceRequest("https://evil.example/step2", true, true, false, "GET"));
        assertNotNull(response);
        assertEquals(403, response.getStatusCode());
        assertEquals(2, host.metrics().count(PolicyMetrics.Counter.BLOCKED_REDIRECTS));
    }

    @Test
    public void refusesCustomSchemes() {
        String[] schemes = {
                "intent://scan/#Intent;scheme=zxing;end",
                "market://details?id=com.example",
                "mailto:someone@arena.ai",
                "tel:+15551234567",
                "whatsapp://send?text=hi",
                "javascript:alert(1)",
                "file:///etc/hosts",
                "content://com.android.providers.media.documents/document/1",
        };
        for (String url : schemes) {
            assertTrue("must be refused: " + url,
                    client.shouldOverrideUrlLoading(webView, FakeWebResourceRequest.navigation(url)));
        }
        assertEquals(schemes.length, host.metrics().count(PolicyMetrics.Counter.BLOCKED_NAVIGATIONS));
    }

    @Test
    public void refusesCleartextHttp() {
        assertTrue(client.shouldOverrideUrlLoading(webView, FakeWebResourceRequest.navigation("http://arena.ai/")));
        assertNotNull(client.shouldInterceptRequest(webView, FakeWebResourceRequest.subresource("http://arena.ai/x.js")));
    }

    @Test
    public void refusesLookAlikeHosts() {
        String[] hosts = {
                "https://arena-ai.com/",
                "https://arena.ai.evil.example/",
                "https://\u0430rena.ai/",
                "https://xn--rena-4qa.ai/",
                "https://mail.arena.ai/",
                "https://sub.arena.ai/",
                "https://arena.ai@evil.example/",
                "https://arena.ai:8443/",
        };
        for (String url : hosts) {
            assertTrue("must be refused: " + url,
                    client.shouldOverrideUrlLoading(webView, FakeWebResourceRequest.navigation(url)));
        }
    }

    @Test
    public void failsClosedOnUnusableRequests() {
        assertTrue(client.shouldOverrideUrlLoading(webView, FakeWebResourceRequest.navigation(null)));
        WebResourceResponse response = client.shouldInterceptRequest(webView, FakeWebResourceRequest.subresource(null));
        assertNotNull(response);
        assertEquals(403, response.getStatusCode());
        assertTrue(host.metrics().count(PolicyMetrics.Counter.BLOCKED_NAVIGATIONS) >= 1);
        assertTrue(host.metrics().count(PolicyMetrics.Counter.BLOCKED_REQUESTS) >= 1);
    }

    @Test
    public void dataUrlsAreNeverDocuments() {
        assertTrue(client.shouldOverrideUrlLoading(webView,
                FakeWebResourceRequest.navigation("data:text/html,<script>alert(1)</script>")));
        // ... but an inline resource is fine.
        assertNull(client.shouldInterceptRequest(webView,
                FakeWebResourceRequest.subresource("data:image/png;base64,iVBORw0KGgo=")));
    }
}
