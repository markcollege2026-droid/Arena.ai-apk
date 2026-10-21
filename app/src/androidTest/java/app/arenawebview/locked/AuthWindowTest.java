package app.arenawebview.locked;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.Button;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import app.arenawebview.locked.policy.PolicyMetrics;

/**
 * The in-app sign-in window.
 *
 * <p>Google sign-in is the one flow that needs a second WebView on screen: the OAuth pop-up returns its
 * result to the page that opened it through {@code window.opener}, so the window has to exist. These
 * tests pin the properties that keep that exception harmless - it is a view inside the activity (not a
 * second activity, not another app), it can always be closed, closing it destroys the WebView, and the
 * watchdog covers it.</p>
 */
@RunWith(AndroidJUnit4.class)
public class AuthWindowTest {

    private static final String SIGN_IN_URL = "https://accounts.google.com/signin/v2/identifier";

    @Test
    public void signInWindowHostsTheWebViewAndCanBeClosed() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            final WebView[] holder = new WebView[1];
            scenario.onActivity(activity -> {
                WebView popup = new WebView(activity);
                holder[0] = popup;
                activity.onSignInWindowRequested(popup, SIGN_IN_URL);

                View window = activity.findViewById(R.id.auth_window);
                assertNotNull("the sign-in window must exist in the view hierarchy", window);
                assertEquals("the sign-in window must be visible", View.VISIBLE, window.getVisibility());

                ViewGroup content = activity.findViewById(R.id.auth_window_content);
                assertEquals("the sign-in WebView must be hosted inside the window", 1,
                        content.getChildCount());
                assertEquals("the hosted view must be the pop-up WebView", popup, content.getChildAt(0));

                // It is a child of this activity's own hierarchy - no second activity was started.
                assertNotNull("the window must be attached to the activity", window.getRootView());
            });

            // The user can always close it.
            scenario.onActivity(activity -> {
                Button close = activity.findViewById(R.id.auth_window_close);
                assertNotNull(close);
                assertTrue("the close button must react", close.performClick());

                View window = activity.findViewById(R.id.auth_window);
                assertEquals("the window must be hidden after closing", View.GONE, window.getVisibility());
                ViewGroup content = activity.findViewById(R.id.auth_window_content);
                assertEquals("the WebView must be detached when the window closes", 0,
                        content.getChildCount());
            });
        }
    }

    @Test
    public void backClosesTheSignInWindowBeforeAnythingElse() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                WebView popup = new WebView(activity);
                activity.onSignInWindowRequested(popup, SIGN_IN_URL);
                assertEquals(View.VISIBLE, activity.findViewById(R.id.auth_window).getVisibility());

                activity.handleBackPressed();

                assertEquals("back must close the sign-in window", View.GONE,
                        activity.findViewById(R.id.auth_window).getVisibility());
                assertFalse("back must not finish the activity while the site is open",
                        activity.isFinishing());
            });
        }
    }

    /** Repeated navigation inside the hand-off must not re-parent the WebView or stack windows. */
    @Test
    public void repeatedRequestsReuseTheSameWindow() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                WebView popup = new WebView(activity);
                activity.onSignInWindowRequested(popup, SIGN_IN_URL);
                activity.onSignInWindowRequested(popup, "https://accounts.google.com/signin/v2/pwd");

                ViewGroup content = activity.findViewById(R.id.auth_window_content);
                assertEquals("the same window must be reused, not stacked", 1, content.getChildCount());
                assertEquals(popup, content.getChildAt(0));
                assertEquals(View.VISIBLE, activity.findViewById(R.id.auth_window).getVisibility());
            });
        }
    }

    @Test
    public void noSignInWindowIsOpenAfterStartup() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                View window = activity.findViewById(R.id.auth_window);
                assertNotNull(window);
                assertEquals("the sign-in window must start hidden", View.GONE, window.getVisibility());

                ViewGroup content = activity.findViewById(R.id.auth_window_content);
                assertEquals("nothing may be hosted before a sign-in starts", 0, content.getChildCount());
            });
        }
    }

    /**
     * A sign-in window is never invisible in the diagnostics: the counter is part of the report the user
     * can copy, next to every other guard.
     */
    @Test
    public void signInWindowsAreReportedInTheDiagnostics() {
        PolicyMetrics metrics = PolicyMetrics.get();
        metrics.increment(PolicyMetrics.Counter.SIGN_IN_WINDOWS);
        String report = metrics.snapshot();
        assertTrue("the report must account for sign-in windows: " + report,
                report.contains(PolicyMetrics.Counter.SIGN_IN_WINDOWS.name()));
    }

    @Test
    public void signInWindowReturnsToMainPageWhenRedirectLandsOnRoot() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                WebView popup = new WebView(activity);
                activity.onSignInWindowRequested(popup, SIGN_IN_URL);
                assertEquals(View.VISIBLE, activity.findViewById(R.id.auth_window).getVisibility());

                // An OAuth callback URL keeps the sign-in window open so the callback JS can finish.
                activity.onSignInWindowNavigated("https://arena.ai/auth/callback?code=abc123");
                assertEquals(View.VISIBLE, activity.findViewById(R.id.auth_window).getVisibility());

                // Once the flow lands back on the root main page, the sign-in window closes automatically.
                activity.onSignInWindowNavigated("https://arena.ai/");
                assertEquals(View.GONE, activity.findViewById(R.id.auth_window).getVisibility());
            });
        }
    }
}
