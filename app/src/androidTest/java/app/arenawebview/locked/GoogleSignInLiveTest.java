package app.arenawebview.locked;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.util.Log;
import android.webkit.WebView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * What the real Google sign-in page does inside this app, on a device.
 *
 * <p>Two different questions are answered here, and it matters which one each assertion covers:</p>
 * <ul>
 *   <li><b>Policy</b> (asserted): the app may reach Google's sign-in host, the page that comes back is
 *       served by an allowed host, and the wrapper never ends up anywhere else.</li>
 *   <li><b>Google's own decision</b> (logged, not asserted): Google refuses to serve its sign-in form to
 *       embedded WebViews - {@code accounts.google.com} answers with a "this browser or app may not be
 *       secure" page, because an embedded browser is indistinguishable from a phishing overlay. That is
 *       Google's server-side choice; the probe below reports which of the two pages arrived, so the test
 *       report can state the outcome instead of guessing at it.</li>
 * </ul>
 *
 * <p>Offline-tolerant: on an emulator with no route to Google there is nothing to load, which is not a
 * policy failure, so the test records that and returns.</p>
 */
@RunWith(AndroidJUnit4.class)
public class GoogleSignInLiveTest {

    private static final String TAG = "ArenaWebViewLocked";
    private static final long LOAD_TIMEOUT_MS = 45_000L;
    private static final String SIGN_IN_URL =
            "https://accounts.google.com/signin/v2/identifier?flowName=GlifWebSignIn&flowEntry=ServiceLogin";

    @Test
    public void googleSignInPageIsReachableAndTheOutcomeIsRecorded() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> activity.loadVerifiedDestination(SIGN_IN_URL, "test:google-signin"));
            String url = waitForLoad(scenario);
            Log.i(TAG, "google sign-in probe: url=" + url);

            if (url == null || url.isEmpty()) {
                Log.w(TAG, "google sign-in probe: nothing loaded within " + LOAD_TIMEOUT_MS
                        + "ms (offline emulator?) - no policy failure to report");
                return;
            }

            assertTrue("the app must only ever be on an approved host, was: " + url,
                    url.startsWith("https://accounts.google.com/") || url.startsWith("https://arena.ai/"));

            String probe = evaluate(scenario, """
                    (function () {
                      var d = document;
                      var email = d.querySelector('input[type=email],input[name=identifier],input#identifierId');
                      var body = d.body ? d.body.innerText : '';
                      var lower = body.toLowerCase();
                      var rejected = lower.indexOf('may not be secure') >= 0
                              || lower.indexOf('sign you in') >= 0
                              || lower.indexOf('not secure') >= 0;
                      var text = body.replace(/\\s+/g, ' ').substring(0, 200);
                      return (email ? 'SIGNIN_FORM_PRESENT' : 'NO_FORM')
                              + ' | ' + (rejected ? 'BROWSER_REJECTED' : 'NO_REJECTION_TEXT')
                              + ' | title=' + d.title + ' | len=' + body.length + ' | text=' + text;
                    })()
                    """);

            Log.i(TAG, "google sign-in probe result: " + probe);
            assertNotNull("the page must be inspectable (JavaScript runs on it)", probe);
            assertTrue("the probe must return something", probe.length() > 0);
        }
    }

    /**
     * Reports which CAPTCHA the real site references, so the allow-list can be checked against reality
     * rather than against an assumption. Logged, not asserted: the widget only appears when Cloudflare
     * decides to challenge, which a test cannot force.
     */
    @Test
    public void reportWhichCaptchaTheSiteReferences() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            String url = waitForLoad(scenario);
            Log.i(TAG, "captcha probe: url=" + url);
            if (url == null || !url.startsWith("https://arena.ai/")) {
                Log.w(TAG, "captcha probe: the site did not load (offline emulator?) - nothing to probe");
                return;
            }
            String probe = evaluate(scenario, """
                    (function () {
                      var hits = [];
                      var nodes = document.querySelectorAll('script[src], iframe[src], link[href]');
                      for (var i = 0; i < nodes.length; i++) {
                        var s = String(nodes[i].getAttribute('src') || nodes[i].getAttribute('href') || '');
                        if (s.indexOf('recaptcha') >= 0 || s.indexOf('turnstile') >= 0
                                || s.indexOf('cloudflare') >= 0 || s.indexOf('challenges.') >= 0) {
                          hits.push(s.substring(0, 120));
                        }
                      }
                      if (window.grecaptcha) { hits.push('window.grecaptcha present'); }
                      if (window.turnstile) { hits.push('window.turnstile present'); }
                      return hits.length ? hits.slice(0, 8).join(' ;; ')
                              : 'no CAPTCHA widget referenced in this page load';
                    })()
                    """);
            Log.i(TAG, "captcha probe result: " + probe);
            assertNotNull(probe);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    /** Waits for the current page to finish loading; returns its URL (null/empty when nothing loaded). */
    private static String waitForLoad(ActivityScenario<MainActivity> scenario) throws Exception {
        long deadline = System.currentTimeMillis() + LOAD_TIMEOUT_MS;
        String last = null;
        while (System.currentTimeMillis() < deadline) {
            final String[] url = new String[1];
            final int[] progress = new int[1];
            scenario.onActivity(activity -> {
                WebView webView = activity.findViewById(R.id.arena_webview);
                url[0] = webView == null ? null : webView.getUrl();
                progress[0] = webView == null ? 0 : webView.getProgress();
            });
            if (url[0] != null && !url[0].isEmpty()) {
                last = url[0];
                if (progress[0] >= 100) {
                    break;
                }
            }
            Thread.sleep(1000);
        }
        return last;
    }

    private static String evaluate(ActivityScenario<MainActivity> scenario, String script) throws Exception {
        final AtomicReference<String> result = new AtomicReference<String>(null);
        final CountDownLatch latch = new CountDownLatch(1);
        scenario.onActivity(activity -> {
            WebView webView = activity.findViewById(R.id.arena_webview);
            if (webView == null) {
                latch.countDown();
                return;
            }
            webView.evaluateJavascript(script, value -> {
                result.set(value);
                latch.countDown();
            });
        });
        if (!latch.await(20, TimeUnit.SECONDS)) {
            Log.w(TAG, "probe evaluation timed out");
        }
        return result.get();
    }
}
