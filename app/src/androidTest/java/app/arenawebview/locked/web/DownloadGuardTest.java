package app.arenawebview.locked.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import app.arenawebview.locked.policy.ArenaAllowlist;
import app.arenawebview.locked.policy.PolicyMetrics;

/**
 * Downloads are permitted and saved to app-private storage: the app never hands a URL to the platform
 * download manager or an external browser, but it does write the downloaded file itself.
 */
@RunWith(AndroidJUnit4.class)
public class DownloadGuardTest {

    @Test
    public void everyDownloadIsPermitted() {
        RecordingGuardHost host = new RecordingGuardHost(ArenaAllowlist.productionConfig());
        WebViewHardening.PermittingDownloadListener listener = new WebViewHardening.PermittingDownloadListener(host);

        listener.onDownloadStart("https://cdn.arena.ai/report.pdf", "UA", "attachment", "application/pdf", 1024);
        listener.onDownloadStart("blob:https://arena.ai/8f1c", "UA", "attachment", "image/png", 2048);
        listener.onDownloadStart("https://evil.example/payload.apk", "UA", "attachment",
                "application/vnd.android.package-archive", 4096);

        assertEquals(3, host.metrics().count(PolicyMetrics.Counter.ALLOWED_DOWNLOADS));
        assertEquals("https://evil.example/payload.apk", host.lastDownloadPermitted);
        assertEquals(0, host.metrics().count(PolicyMetrics.Counter.BLOCKED_DOWNLOADS));
    }
}
