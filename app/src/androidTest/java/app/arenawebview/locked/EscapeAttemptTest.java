package app.arenawebview.locked;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Instrumentation;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.webkit.GeolocationPermissions;
import android.webkit.ValueCallback;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

import app.arenawebview.locked.policy.ArenaAllowlist;
import app.arenawebview.locked.policy.PolicyMetrics;
import app.arenawebview.locked.web.ArenaWebChromeClient;
import app.arenawebview.locked.web.RecordingGuardHost;

/**
 * Attempts to leave the app by every route the Android WebView offers, and checks that each one is
 * refused. These are the "app escape" tests: none of them may open another application.
 */
@RunWith(AndroidJUnit4.class)
public class EscapeAttemptTest {

    /** Any attempt to start another activity must be refused (and nothing may be launched). */
    @Test
    public void startActivityIsRefused() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        // Any activity launched to VIEW something would show up here; the assertion is that none is.
        Instrumentation.ActivityMonitor monitor =
                instrumentation.addMonitor(new IntentFilter(Intent.ACTION_VIEW), null, false);
        try {
            try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
                scenario.onActivity(activity -> {
                    long before = PolicyMetrics.get().count(PolicyMetrics.Counter.BLOCKED_EXTERNAL_INTENTS);

                    activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://evil.example/")));
                    activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("tel:+15551234567")));
                    activity.startActivity(new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:a@b.example")));
                    activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=x")));
                    activity.startActivityForResult(new Intent(Intent.ACTION_SEND), 1);
                    activity.startActivity(Intent.createChooser(new Intent(Intent.ACTION_VIEW), "open"));

                    assertEquals("every attempt must be counted as refused",
                            before + 6, PolicyMetrics.get().count(PolicyMetrics.Counter.BLOCKED_EXTERNAL_INTENTS));
                });
            }
            assertEquals("no activity may actually be launched", 0, monitor.getHits());
        } finally {
            instrumentation.removeMonitor(monitor);
        }
    }

    /** The file picker delegates to the host when uploads are handled and completes with null when not. */
    @Test
    public void fileChooserDelegatesToHostAndValidatesContentUris() {
        RecordingGuardHost host = new RecordingGuardHost(ArenaAllowlist.productionConfig());
        ArenaWebChromeClient chrome = new ArenaWebChromeClient(
                InstrumentationRegistry.getInstrumentation().getTargetContext(), host);

        final List<Uri[]> received = new ArrayList<Uri[]>();
        final boolean[] completed = {false};
        ValueCallback<Uri[]> callback = new ValueCallback<Uri[]>() {
            @Override
            public void onReceiveValue(Uri[] value) {
                completed[0] = true;
                received.add(value);
            }
        };

        // 1. When the host handles the file chooser (Activity is present), it takes ownership of the callback.
        host.allowFileChooser = true;
        assertTrue(chrome.onShowFileChooser(null, callback, null));
        assertEquals(1, host.metrics().count(PolicyMetrics.Counter.ALLOWED_FILE_CHOOSERS));

        // Only content:// URIs are accepted from the picker result; file:// URIs are rejected.
        Intent contentResult = new Intent();
        contentResult.setData(Uri.parse("content://com.android.providers.media.documents/document/image%3A1"));
        Uri[] validUris = MainActivity.extractSelectedContentUris(android.app.Activity.RESULT_OK, contentResult);
        assertNotNull(validUris);
        assertEquals(1, validUris.length);

        Intent fileSchemeResult = new Intent();
        fileSchemeResult.setData(Uri.parse("file:///data/data/app.arenawebview.locked/databases/webview.db"));
        assertNull("file:// URIs must never be accepted for upload",
                MainActivity.extractSelectedContentUris(android.app.Activity.RESULT_OK, fileSchemeResult));

        // 2. When no activity can handle the picker, the callback is completed with null immediately.
        host.allowFileChooser = false;
        boolean handled = chrome.onShowFileChooser(null, callback, null);

        assertTrue("the app must claim the file-chooser request", handled);
        assertTrue("the page must not be left waiting", completed[0]);
        assertNull("when declined, null is delivered to the callback", received.get(0));
        assertEquals(1, host.metrics().count(PolicyMetrics.Counter.BLOCKED_FILE_CHOOSERS));
    }

    /** Geolocation is refused in-process, without ever prompting the user or the OS. */
    @Test
    public void geolocationIsRefused() {
        RecordingGuardHost host = new RecordingGuardHost(ArenaAllowlist.productionConfig());
        ArenaWebChromeClient chrome = new ArenaWebChromeClient(
                InstrumentationRegistry.getInstrumentation().getTargetContext(), host);

        final List<String> invokedWith = new ArrayList<String>();
        GeolocationPermissions.Callback callback = new GeolocationPermissions.Callback() {
            @Override
            public void invoke(String origin, boolean allow, boolean retain) {
                invokedWith.add(origin + "|" + allow + "|" + retain);
            }
        };

        chrome.onGeolocationPermissionsShowPrompt("https://arena.ai", callback);

        assertEquals(1, invokedWith.size());
        assertEquals("https://arena.ai|false|false", invokedWith.get(0));
        assertEquals(1, host.metrics().count(PolicyMetrics.Counter.BLOCKED_GEOLOCATION));
    }

    /** A deep link from another app must not be able to steer the app to a foreign URL. */
    @Test
    public void incomingIntentsCannotSteerTheApp() {
        Intent hostile = new Intent(Intent.ACTION_VIEW, Uri.parse("https://evil.example/steal"));
        hostile.setClassName(InstrumentationRegistry.getInstrumentation().getTargetContext(),
                MainActivity.class.getName());

        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(hostile)) {
            scenario.onActivity(activity -> {
                android.webkit.WebView webView = activity.findViewById(R.id.arena_webview);
                assertNotNull(webView);
                String url = webView.getUrl();
                if (url != null && !url.isEmpty()) {
                    assertTrue("must never load the URL from the incoming intent: " + url,
                            url.startsWith("https://arena.ai/"));
                }
            });
        }
        assertTrue("the ignored intent must be recorded",
                PolicyMetrics.get().count(PolicyMetrics.Counter.IGNORED_INCOMING_INTENTS) >= 1);
    }
}
