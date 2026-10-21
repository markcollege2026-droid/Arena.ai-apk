package app.arenawebview.locked.web;

import android.content.Intent;
import android.webkit.WebView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import app.arenawebview.locked.policy.PolicyConfig;
import app.arenawebview.locked.policy.PolicyMetrics;
import app.arenawebview.locked.policy.PolicyResult;

/**
 * Test double for {@link GuardHost}: records everything the guard clients report, so instrumented tests
 * can assert on the decisions without an activity.
 */
public final class RecordingGuardHost extends BaseGuardHost {

    private final PolicyConfig policy;
    private final PolicyMetrics metrics = new PolicyMetrics();
    private final List<String> events = Collections.synchronizedList(new ArrayList<String>());

    public String lastLoadedUrl;
    public String lastSource;
    public String lastBlockedNavigation;
    public String lastBlockedPageLoad;
    public String lastPopupRouted;
    public String lastPopupBlocked;
    public String lastSignInWindowUrl;
    public String lastSignInWindowClosedReason;
    public String lastSignInWindowNavigated;
    public String lastDownloadPermitted;
    public String lastError;
    public String lastRecoveryReason;
    public int fileChooserRequests;
    public int fileChooserResults;
    public int sslBlocks;
    public int fullscreenRequests;
    public Intent lastFileChooserIntent;
    public int lastFileChooserRequestCode;

    public RecordingGuardHost(PolicyConfig policy) {
        this.policy = policy;
    }

    @Override
    public PolicyConfig policy() {
        return policy;
    }

    @Override
    public PolicyMetrics metrics() {
        return metrics;
    }

    @Override
    public void loadVerifiedDestination(String url, String source) {
        lastLoadedUrl = url;
        lastSource = source;
        event("load " + url + " (" + source + ")");
    }

    @Override
    public void onNavigationBlocked(String url, PolicyResult result) {
        lastBlockedNavigation = url;
        event("navigation blocked " + url);
    }

    @Override
    public void onPageLoadBlocked(String url, PolicyResult result) {
        lastBlockedPageLoad = url;
        event("page load blocked " + url);
    }

    @Override
    public void onLoadError(String url, String description) {
        lastError = description;
        event("error " + url + " " + description);
    }

    @Override
    public void onSslErrorBlocked(String url) {
        sslBlocks++;
        event("ssl blocked " + url);
    }

    @Override
    public void onRecoveryRequired(String reason, String offendingUrl) {
        lastRecoveryReason = reason;
        event("recovery " + reason + " " + offendingUrl);
    }

    @Override
    public void onPopupRouted(String url) {
        lastPopupRouted = url;
        event("popup routed " + url);
    }

    @Override
    public void onPopupBlocked(String url, PolicyResult result) {
        lastPopupBlocked = url;
        event("popup blocked " + url);
    }

    @Override
    public void onDownloadPermitted(String url, String userAgent, String contentDisposition,
                                    String mimeType, long contentLength) {
        lastDownloadPermitted = url;
        event("download permitted " + url);
    }

    @Override
    public void onFileChooserRequested(Intent intent, int requestCode) {
        fileChooserRequests++;
        lastFileChooserIntent = intent;
        lastFileChooserRequestCode = requestCode;
        event("file chooser requested");
    }

    @Override
    public boolean onFileChooserResult(int requestCode, int resultCode, Intent data) {
        fileChooserResults++;
        event("file chooser result requestCode=" + requestCode + " resultCode=" + resultCode);
        return true;
    }

    @Override
    public void onFullscreenRequested(android.view.View view, android.webkit.WebChromeClient.CustomViewCallback callback) {
        fullscreenRequests++;
        if (callback != null) {
            callback.onCustomViewHidden();
        }
    }

    @Override
    public void onMessage(String message) {
        event("message " + message);
    }

    /** True when the guard clients reported a hidden pop-up WebView (which must never be attached). */
    @Override
    public void onSignInWindowRequested(WebView popup, String url) {
        lastSignInWindowUrl = url;
        events.add("signInWindow:" + url);
    }

    @Override
    public void onSignInWindowClosed(String reason) {
        lastSignInWindowClosedReason = reason;
        events.add("signInWindowClosed:" + reason);
    }

    @Override
    public void onSignInWindowNavigated(String url) {
        lastSignInWindowNavigated = url;
        events.add("signInWindowNavigated:" + url);
    }

    @Override
    public void onPopupClosed(WebView popup) {
        event("popup closed");
    }

    public List<String> events() {
        return new ArrayList<String>(events);
    }

    private void event(String value) {
        events.add(value);
    }
}
