package app.arenawebview.locked.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import app.arenawebview.locked.policy.ArenaAllowlist;
import app.arenawebview.locked.policy.PolicyMetrics;

/**
 * Downloads are refused outright: the app never hands a URL to the platform download manager, to a
 * browser, or to a file picker, and never writes a downloaded file anywhere.
 */
@RunWith(AndroidJUnit4.class)
public class DownloadGuardTest {

    @Test
    public void everyDownloadIsRefused() {
        RecordingGuardHost host = new RecordingGuardHost(ArenaAllowlist.productionConfig());
        WebViewHardening.BlockingDownloadListener listener = new WebViewHardening.BlockingDownloadListener(host);

        listener.onDownloadStart("https://cdn.arena.ai/report.pdf", "UA", "attachment", "application/pdf", 1024);
        listener.onDownloadStart("blob:https://arena.ai/8f1c", "UA", "attachment", "image/png", 2048);
        listener.onDownloadStart("https://evil.example/payload.apk", "UA", "attachment",
                "application/vnd.android.package-archive", 4096);

        assertEquals(3, host.metrics().count(PolicyMetrics.Counter.BLOCKED_DOWNLOADS));
        assertEquals("https://evil.example/payload.apk", host.lastDownloadBlocked);
        assertEquals(3, host.metrics().recordedBlockCount());
        for (PolicyMetrics.BlockEvent event : host.metrics().recentBlocks()) {
            assertTrue("the refusal must be recorded with a reason",
                    event.reason() != null && !event.reason().name().isEmpty());
        }
    }
}
