package app.arenawebview.locked.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import app.arenawebview.locked.policy.ArenaAllowlist;
import app.arenawebview.locked.policy.PolicyMetrics;

/**
 * Valid downloads ({@code https://}, {@code blob:}, {@code data:}) are permitted and handed to the
 * host to be saved in-app, while unsafe schemes ({@code http://}, {@code file://}, {@code javascript:})
 * and malformed URLs are refused.
 */
@RunWith(AndroidJUnit4.class)
public class DownloadGuardTest {

    @Test
    public void allowsValidDownloadsAndRefusesUnsafeOnes() {
        RecordingGuardHost host = new RecordingGuardHost(ArenaAllowlist.productionConfig());
        WebViewHardening.BlockingDownloadListener listener = new WebViewHardening.BlockingDownloadListener(host);

        listener.onDownloadStart("https://cdn.arena.ai/report.pdf", "UA", "attachment", "application/pdf", 1024);
        listener.onDownloadStart("https://storage.googleapis.com/bucket/export.zip", "UA", "attachment",
                "application/zip", 4096);
        listener.onDownloadStart("blob:https://arena.ai/8f1c", "UA", "attachment", "image/png", 2048);
        listener.onDownloadStart("data:text/plain;base64,SGVsbG8=", "UA", "attachment", "text/plain", 5);

        assertEquals(4, host.metrics().count(PolicyMetrics.Counter.ALLOWED_DOWNLOADS));
        assertEquals(4, host.downloadsRequested);
        assertEquals("data:text/plain;base64,SGVsbG8=", host.lastDownloadRequested);

        listener.onDownloadStart("http://cdn.arena.ai/report.pdf", "UA", "attachment", "application/pdf", 1024);
        listener.onDownloadStart("file:///etc/hosts", "UA", "attachment", "text/plain", 512);
        listener.onDownloadStart("javascript:alert(1)", "UA", "attachment", "text/html", 16);
        listener.onDownloadStart("blob:https://evil.example/8f1c", "UA", "attachment", "image/png", 2048);

        assertEquals(4, host.metrics().count(PolicyMetrics.Counter.BLOCKED_DOWNLOADS));
        assertEquals("blob:https://evil.example/8f1c", host.lastDownloadBlocked);
        assertEquals(4, host.metrics().recordedBlockCount());
        for (PolicyMetrics.BlockEvent event : host.metrics().recentBlocks()) {
            assertTrue("the refusal must be recorded with a reason",
                    event.reason() != null && !event.reason().name().isEmpty());
        }
    }
}
