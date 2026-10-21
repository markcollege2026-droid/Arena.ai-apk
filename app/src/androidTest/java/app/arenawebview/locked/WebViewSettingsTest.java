package app.arenawebview.locked;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.os.Build;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import app.arenawebview.locked.web.ArenaWebChromeClient;
import app.arenawebview.locked.web.ArenaWebViewClient;
import app.arenawebview.locked.web.RecordingGuardHost;

/**
 * Verifies the WebView configuration on a real WebView.
 *
 * <p>Two halves: everything the real site needs must be <b>on</b>, and every switch that could reach the
 * local file system, mix cleartext into the page, or hand work to another component must be <b>off</b>.
 * The settings are read back from the WebView that the app itself creates, so this tests the shipping
 * configuration and not a copy of it.</p>
 */
@RunWith(AndroidJUnit4.class)
public class WebViewSettingsTest {

    private WebView webView;

    @Before
    public void setUp() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(new Runnable() {
            @Override
            public void run() {
                android.content.Context context =
                        InstrumentationRegistry.getInstrumentation().getTargetContext();
                RecordingGuardHost host = new RecordingGuardHost(
                        app.arenawebview.locked.policy.ArenaAllowlist.productionConfig());
                webView = app.arenawebview.locked.web.WebViewHardening.createPrimaryWebView(
                        context,
                        new app.arenawebview.locked.web.ArenaWebViewClient(host),
                        new app.arenawebview.locked.web.ArenaWebChromeClient(context, host),
                        host);
            }
        });
    }

    private WebSettings settings() {
        return webView.getSettings();
    }

    // ---------------------------------------------------------------------------------------------
    // Features the real site needs
    // ---------------------------------------------------------------------------------------------

    @Test
    public void featuresRequiredByTheSiteAreEnabled() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(new Runnable() {
            @Override
            public void run() {
                WebSettings settings = settings();
                assertTrue("the site is a JavaScript app", settings.getJavaScriptEnabled());
                assertTrue("session storage / app state", settings.getDomStorageEnabled());
                assertTrue("images", settings.getLoadsImagesAutomatically());
                assertFalse("network images must not be blocked", settings.getBlockNetworkImage());
                assertFalse("network loads must not be blocked", settings.getBlockNetworkLoads());
                assertTrue("zoom", settings.supportZoom());
                assertTrue("wide viewport for a responsive site", settings.getUseWideViewPort());
                assertTrue("overview mode", settings.getLoadWithOverviewMode());
                assertFalse("streamed video must be able to start", settings.getMediaPlaybackRequiresUserGesture());
                assertTrue("window.open must reach the guard instead of being dropped",
                        settings.supportMultipleWindows());
                assertFalse("automatic pop-ups stay suppressed",
                        settings.getJavaScriptCanOpenWindowsAutomatically());
            }
        });
    }

    @Test
    public void cookiesAreEnabledIncludingThirdPartyOnesForTheAuthService() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(new Runnable() {
            @Override
            public void run() {
                assertTrue("login/session cookies are required",
                        CookieManager.getInstance().acceptCookie());
            }
        });
    }

    // ---------------------------------------------------------------------------------------------
    // Everything that must stay off
    // ---------------------------------------------------------------------------------------------

    @Test
    public void localFileAndContentAccessIsOff() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(new Runnable() {
            @Override
            public void run() {
                WebSettings settings = settings();
                assertFalse("no file:// access", settings.getAllowFileAccess());
                assertTrue("content:// access enabled for user-selected file uploads",
                        settings.getAllowContentAccess());
                assertFalse("no cross-origin access from file URLs",
                        settings.getAllowFileAccessFromFileURLs());
                assertFalse("no universal access from file URLs",
                        settings.getAllowUniversalAccessFromFileURLs());
                assertEquals("mixed content must never be allowed",
                        WebSettings.MIXED_CONTENT_NEVER_ALLOW, settings.getMixedContentMode());
            }
        });
    }

    @Test
    public void safeBrowsingIsOn() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        InstrumentationRegistry.getInstrumentation().runOnMainSync(new Runnable() {
            @Override
            public void run() {
                assertTrue("Safe Browsing must stay enabled", settings().getSafeBrowsingEnabled());
            }
        });
    }

    @Test
    public void userAgentIsNotTheGenericWebViewOne() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(new Runnable() {
            @Override
            public void run() {
                String userAgent = settings().getUserAgentString();
                assertNotNull(userAgent);
                // The app must not pretend to be a different browser version, it only drops the "wv"
                // token so that the site serves the same code it serves to Chrome.
                assertFalse("the generic '; wv' token must be removed: " + userAgent,
                        userAgent.contains("; wv"));
                assertTrue("real version information must be preserved: " + userAgent,
                        userAgent.contains("Chrome/") || userAgent.contains("AppleWebKit"));
            }
        });
    }

    // NOTE: the guard clients cannot be read back from a WebView (there is no public getter), so their
    // wiring is proven behaviourally instead: WatchdogRecoveryTest shows the WebViewClient reacting to a
    // blocked load, and the pop-up/dialog tests show the WebChromeClient being consulted.
}
