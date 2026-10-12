package app.arenawebview.locked;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.WindowInsets;
import android.webkit.CookieManager;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.WebBackForwardList;
import android.webkit.WebChromeClient;
import android.webkit.WebHistoryItem;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.util.Locale;

import app.arenawebview.locked.policy.ArenaAllowlist;
import app.arenawebview.locked.policy.BackNavigator;
import app.arenawebview.locked.policy.PolicyConfig;
import app.arenawebview.locked.policy.PolicyMetrics;
import app.arenawebview.locked.policy.PolicyResult;
import app.arenawebview.locked.ui.StatusOverlay;
import app.arenawebview.locked.web.ArenaWebChromeClient;
import app.arenawebview.locked.web.ArenaWebViewClient;
import app.arenawebview.locked.web.GuardHost;
import app.arenawebview.locked.web.WebViewHardening;

/**
 * The whole app: one WebView showing arena.ai, and the guards that keep it there.
 *
 * <p>Invariants this class maintains (also asserted by the tests):</p>
 * <ul>
 *   <li>{@link #loadVerifiedDestination(String, String)} is the <b>only</b> place that calls
 *       {@code WebView.loadUrl} for the visible WebView, and it re-checks the policy first.</li>
 *   <li>{@code startActivity}/{@code startActivityForResult} are overridden to refuse: this app never
 *       hands control to another application, not even for a download, a file picker or a share
 *       sheet.</li>
 *   <li>Incoming intents are ignored completely - the activity has no deep-link handling, so another
 *       app cannot steer it.</li>
 *   <li>A watchdog re-checks the visible URL every second; anything that is not approved is replaced by
 *       the start page.</li>
 *   <li>Back never leaves the app: it goes back through verified history, falls back to the start page,
 *       and otherwise asks for a second press before closing.</li>
 * </ul>
 */
public class MainActivity extends Activity implements GuardHost {

    private static final String TAG = "ArenaWebViewLocked";

    private static final long WATCHDOG_INTERVAL_MS = 1000L;
    private static final long EXIT_BACK_WINDOW_MS = 2500L;
    private static final long MESSAGE_THROTTLE_MS = 900L;

    private final PolicyConfig policy = ArenaAllowlist.productionConfig();
    private final PolicyMetrics metrics = PolicyMetrics.get();

    private FrameLayout root;
    private WebView webView;
    private ArenaWebChromeClient chromeClient;
    private StatusOverlay overlay;

    private FrameLayout fullscreenContainer;
    private View fullscreenView;
    private WebChromeClient.CustomViewCallback fullscreenCallback;

    private Handler handler;
    private Runnable watchdogTask;
    private boolean resumed;

    private long lastExitBackPressMs;
    private long lastMessageMs;
    private String lastMessage;

    // ---------------------------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.i(TAG, "start with policy:\n" + policy.describe());

        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG);
        WebViewHardening.installServiceWorkerGuard(policy, metrics);
        recordIncomingIntent(getIntent());

        handler = new Handler(Looper.getMainLooper());

        chromeClient = new ArenaWebChromeClient(this, this);
        webView = WebViewHardening.createPrimaryWebView(
                this, new ArenaWebViewClient(this), chromeClient, this);
        webView.setId(R.id.arena_webview);

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.setFitsSystemWindows(true);
        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        overlay = new StatusOverlay(this, new StatusOverlay.Listener() {
            @Override
            public void onReloadRequested() {
                loadVerifiedDestination(policy.primaryUrl(), "overlay-reload");
            }

            @Override
            public void onCopyDiagnosticsRequested() {
                copyDiagnosticsToClipboard();
            }

            @Override
            public void onDismissRequested() {
                overlay.hide();
            }
        });
        root.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        applyWindowInsetsPadding();
        setContentView(root);

        boolean restored = false;
        if (savedInstanceState != null) {
            try {
                restored = webView.restoreState(savedInstanceState) != null;
            } catch (Throwable t) {
                Log.w(TAG, "could not restore WebView state", t);
            }
        }
        if (!restored) {
            loadVerifiedDestination(policy.primaryUrl(), "startup");
        } else {
            // A restored session is verified by the watchdog on its first tick; until then nothing from
            // the restored page can be considered displayed.
            Log.i(TAG, "restored WebView state, awaiting watchdog verification");
        }

        registerBackCallback();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Deliberately not calling setIntent(): incoming intents can never influence navigation.
        recordIncomingIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        startWatchdog();
    }

    @Override
    protected void onPause() {
        resumed = false;
        stopWatchdog();
        try {
            CookieManager.getInstance().flush();
        } catch (Throwable ignored) {
            // Cookie flushing is best effort.
        }
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (webView != null) {
            try {
                webView.saveState(outState);
            } catch (Throwable t) {
                Log.w(TAG, "could not save WebView state", t);
            }
        }
    }

    @Override
    protected void onDestroy() {
        stopWatchdog();
        if (chromeClient != null) {
            chromeClient.destroyPopups();
        }
        if (fullscreenView != null) {
            exitFullscreen(false);
        }
        if (webView != null) {
            try {
                webView.stopLoading();
                webView.setWebChromeClient(null);
                webView.setWebViewClient(new WebViewClient());
                root.removeView(webView);
                webView.destroy();
            } catch (Throwable t) {
                Log.w(TAG, "WebView teardown failed", t);
            }
            webView = null;
        }
        super.onDestroy();
    }

    // ---------------------------------------------------------------------------------------------
    // Back navigation - always internal, never another app
    // ---------------------------------------------------------------------------------------------

    private void registerBackCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                        android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                        new android.window.OnBackInvokedCallback() {
                            @Override
                            public void onBackInvoked() {
                                handleBackPressed();
                            }
                        });
            } catch (Throwable t) {
                Log.w(TAG, "predictive back registration failed", t);
            }
        }
    }

    /** Legacy hook, still used below API 33 and by the instrumented tests. */
    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        handleBackPressed();
    }

    /** Single entry point for every back gesture. */
    public void handleBackPressed() {
        if (fullscreenView != null) {
            exitFullscreen(true);
            return;
        }
        if (overlay != null && overlay.isOverlayVisible()) {
            overlay.hide();
            String current = webView == null ? null : webView.getUrl();
            if (current == null || !policy.evaluateNavigation(current).isAllowed()) {
                loadVerifiedDestination(policy.primaryUrl(), "back-from-overlay");
            }
            return;
        }
        if (webView == null) {
            finish();
            return;
        }
        BackNavigator.Action action = BackNavigator.decide(webView.canGoBack(), previousHistoryUrl(), policy);
        switch (action) {
            case GO_BACK:
                if (webView.canGoBack()) {
                    webView.goBack();
                } else {
                    loadVerifiedDestination(policy.primaryUrl(), "back-fallback");
                }
                break;
            case SHOW_START:
                loadVerifiedDestination(policy.primaryUrl(), "back-unverified-history");
                break;
            case EXIT:
            default:
                confirmExit();
                break;
        }
    }

    private String previousHistoryUrl() {
        try {
            WebBackForwardList list = webView.copyBackForwardList();
            int index = list.getCurrentIndex();
            if (index <= 0) {
                return null;
            }
            WebHistoryItem item = list.getItemAtIndex(index - 1);
            return item == null ? null : item.getUrl();
        } catch (Throwable t) {
            return null;
        }
    }

    private void confirmExit() {
        long now = SystemClock.uptimeMillis();
        if (now - lastExitBackPressMs <= EXIT_BACK_WINDOW_MS) {
            finish();
            return;
        }
        lastExitBackPressMs = now;
        onMessage("Press back again to close");
    }

    // ---------------------------------------------------------------------------------------------
    // Escape prevention: this app never starts another activity
    // ---------------------------------------------------------------------------------------------

    @Override
    public void startActivity(Intent intent) {
        blockExternalIntent(intent);
    }

    @Override
    public void startActivity(Intent intent, Bundle options) {
        blockExternalIntent(intent);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void startActivityForResult(Intent intent, int requestCode) {
        blockExternalIntent(intent);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void startActivityForResult(Intent intent, int requestCode, Bundle options) {
        blockExternalIntent(intent);
    }

    @Override
    public void startActivities(Intent[] intents) {
        blockExternalIntents(intents);
    }

    @Override
    public void startActivities(Intent[] intents, Bundle options) {
        blockExternalIntents(intents);
    }

    private void blockExternalIntents(Intent[] intents) {
        if (intents == null) {
            return;
        }
        for (Intent intent : intents) {
            blockExternalIntent(intent);
        }
    }

    /**
     * Refuses to open anything. Called for every {@code startActivity} the WebView (or a page) tries -
     * including the system browser, share sheets, the Play Store and download managers.
     */
    private void blockExternalIntent(Intent intent) {
        metrics.increment(PolicyMetrics.Counter.BLOCKED_EXTERNAL_INTENTS);
        String target = "";
        if (intent != null) {
            Uri data = intent.getData();
            target = (intent.getAction() == null ? "" : intent.getAction())
                    + (data == null ? "" : " " + data.getScheme() + "://" + data.getHost());
            if (intent.getComponent() != null) {
                target = target + " component=" + intent.getComponent().flattenToShortString();
            }
        }
        metrics.recordBlock(app.arenawebview.locked.policy.BlockReason.EXTERNAL_INTENT_BLOCKED, "", target);
        Log.i(TAG, "blocked attempt to start another activity: " + target);
        onMessage("This app does not open other apps");
    }

    private void recordIncomingIntent(Intent intent) {
        if (intent == null) {
            return;
        }
        String action = intent.getAction();
        boolean launcherIntent = Intent.ACTION_MAIN.equals(action)
                && intent.hasCategory(Intent.CATEGORY_LAUNCHER)
                && intent.getData() == null;
        if (launcherIntent) {
            return;
        }
        metrics.increment(PolicyMetrics.Counter.IGNORED_INCOMING_INTENTS);
        Log.i(TAG, "ignored incoming intent: action=" + action + " data=" + intent.getData()
                + " (this app has no deep links)");
    }

    // ---------------------------------------------------------------------------------------------
    // GuardHost - the only place that loads anything
    // ---------------------------------------------------------------------------------------------

    @Override
    public PolicyConfig policy() {
        return policy;
    }

    @Override
    public PolicyMetrics metrics() {
        return metrics;
    }

    @Override
    public boolean isPrimaryWebView(WebView view) {
        return view != null && view == webView;
    }

    @Override
    public void loadVerifiedDestination(String url, String source) {
        WebView view = webView;
        if (view == null) {
            return;
        }
        PolicyResult result = policy.evaluateNavigation(url);
        if (!result.isAllowed()) {
            metrics.increment(PolicyMetrics.Counter.BLOCKED_NAVIGATIONS);
            metrics.recordBlock(result.reason(), result.canonicalHost(), url);
            Log.w(TAG, "refused internal load (" + source + "): " + result.describe());
            return;
        }
        metrics.increment(PolicyMetrics.Counter.ALLOWED_NAVIGATIONS);
        overlay.hide();
        Log.i(TAG, "load (" + source + "): " + result.canonicalHost());
        view.loadUrl(url);
    }

    @Override
    public void onNavigationBlocked(String url, PolicyResult result) {
        onMessage("Blocked " + describeDestination(url, result));
    }

    @Override
    public void onPageLoadBlocked(String url, PolicyResult result) {
        // The page itself failed to load because it was not allowed. Cover the (now broken) WebView with
        // the native overlay and leave it to the user to return to the start page.
        overlay.showBlocked(destinationHost(url, result),
                result == null || result.reason() == null ? "not allowed" : result.reason().humanMessage(),
                url == null ? "" : url);
        onMessage("Blocked " + describeDestination(url, result));
    }

    @Override
    public void onLoadError(String url, String description) {
        Log.i(TAG, "main frame load error: " + description);
        overlay.showError("arena.ai could not be reached (" + description + ").\n\n"
                + "Check the network connection and try again, or copy the diagnostics report.");
    }

    @Override
    public void onSslErrorBlocked(String url) {
        overlay.showBlocked(destinationHost(url, null),
                "the certificate could not be verified, and this app never bypasses a TLS error",
                url == null ? "" : url);
    }

    @Override
    public void onRecoveryRequired(String reason, String offendingUrl) {
        metrics.increment(PolicyMetrics.Counter.RECOVERY_RELOADS);
        Log.w(TAG, "recovery (" + reason + ") from " + offendingUrl);
        if (overlay.isOverlayVisible()) {
            // The native overlay is already covering the WebView: nothing unverified is visible.
            return;
        }
        loadVerifiedDestination(policy.primaryUrl(), "recovery:" + reason);
    }

    @Override
    public void onPopupRouted(String url) {
        Log.i(TAG, "popup target verified and loaded in the single WebView");
    }

    @Override
    public void onPopupBlocked(String url, PolicyResult result) {
        onMessage("Pop-up blocked: " + describeDestination(url, result));
    }

    @Override
    public void onPopupClosed(WebView popup) {
        // nothing to do
    }

    @Override
    public void onDownloadBlocked(String url, String mimeType) {
        onMessage("Downloads are disabled in this app");
    }

    @Override
    public void onFileChooserBlocked() {
        // Message already sent by the chrome client.
    }

    @Override
    public void onMediaPermissionDenied(String origin, String[] resources) {
        onMessage("Camera/microphone access is disabled in this app");
    }

    @Override
    public void onGeolocationDenied(String origin) {
        onMessage("Location access is disabled in this app");
    }

    @Override
    public void onJsAlert(String url, String message, JsResult result) {
        if (!canShowDialogs()) {
            result.cancel();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(dialogTitle(url))
                .setMessage(message)
                .setCancelable(false)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> result.confirm())
                .show();
    }

    @Override
    public void onJsConfirm(String url, String message, JsResult result) {
        if (!canShowDialogs()) {
            result.cancel();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(dialogTitle(url))
                .setMessage(message)
                .setCancelable(false)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> result.confirm())
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> result.cancel())
                .show();
    }

    @Override
    public void onJsPrompt(String url, String message, String defaultValue, JsPromptResult result) {
        if (!canShowDialogs()) {
            result.cancel();
            return;
        }
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        if (defaultValue != null) {
            input.setText(defaultValue);
        }
        new AlertDialog.Builder(this)
                .setTitle(dialogTitle(url))
                .setMessage(message)
                .setView(input)
                .setCancelable(false)
                .setPositiveButton(android.R.string.ok,
                        (dialog, which) -> result.confirm(input.getText().toString()))
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> result.cancel())
                .show();
    }

    private boolean canShowDialogs() {
        return !isFinishing() && (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR1 || !isDestroyed());
    }

    private String dialogTitle(String url) {
        String host = destinationHost(url, null);
        return host.isEmpty() ? getString(R.string.app_name) : host + " says";
    }

    // ---------------------------------------------------------------------------------------------
    // Full-screen video (in-app only)
    // ---------------------------------------------------------------------------------------------

    @Override
    public void onFullscreenRequested(View view, WebChromeClient.CustomViewCallback callback) {
        if (!canShowDialogs() || webView == null) {
            if (callback != null) {
                callback.onCustomViewHidden();
            }
            return;
        }
        if (fullscreenView != null) {
            // Already in full screen: refuse the nested request rather than stack windows.
            if (callback != null) {
                callback.onCustomViewHidden();
            }
            return;
        }
        fullscreenView = view;
        fullscreenCallback = callback;
        fullscreenContainer = new FrameLayout(this);
        fullscreenContainer.setId(R.id.fullscreen_container);
        fullscreenContainer.setBackgroundColor(Color.BLACK);
        fullscreenContainer.addView(view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(fullscreenContainer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        hideSystemBars();
    }

    @Override
    public void onFullscreenExitRequested() {
        exitFullscreen(true);
    }

    private void exitFullscreen(boolean notifyCallback) {
        if (fullscreenView == null) {
            return;
        }
        View view = fullscreenView;
        WebChromeClient.CustomViewCallback callback = fullscreenCallback;
        fullscreenView = null;
        fullscreenCallback = null;
        try {
            if (fullscreenContainer != null) {
                fullscreenContainer.removeView(view);
                root.removeView(fullscreenContainer);
            }
        } catch (Throwable t) {
            Log.w(TAG, "fullscreen teardown failed", t);
        }
        fullscreenContainer = null;
        showSystemBars();
        if (notifyCallback && callback != null) {
            try {
                callback.onCustomViewHidden();
            } catch (Throwable t) {
                Log.w(TAG, "custom view callback failed", t);
            }
        }
    }

    @SuppressWarnings("deprecation")
    private void hideSystemBars() {
        try {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        } catch (Throwable ignored) {
            // cosmetic only
        }
    }

    @SuppressWarnings("deprecation")
    private void showSystemBars() {
        try {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        } catch (Throwable ignored) {
            // cosmetic only
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Messages, watchdog, diagnostics
    // ---------------------------------------------------------------------------------------------

    @Override
    public void onMessage(String message) {
        if (message == null || message.isEmpty() || isFinishing()) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (message.equals(lastMessage) && now - lastMessageMs < MESSAGE_THROTTLE_MS) {
            return;
        }
        lastMessage = message;
        lastMessageMs = now;
        try {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
            Log.i(TAG, message);
        }
    }

    private void startWatchdog() {
        if (handler == null) {
            return;
        }
        if (watchdogTask == null) {
            watchdogTask = new Runnable() {
                @Override
                public void run() {
                    if (!resumed) {
                        return;
                    }
                    tickWatchdog();
                    handler.postDelayed(this, WATCHDOG_INTERVAL_MS);
                }
            };
        }
        handler.removeCallbacks(watchdogTask);
        handler.postDelayed(watchdogTask, WATCHDOG_INTERVAL_MS);
    }

    private void stopWatchdog() {
        if (handler != null && watchdogTask != null) {
            handler.removeCallbacks(watchdogTask);
        }
    }

    /**
     * Last line of defence: whatever the WebView believes it is showing must be an approved page. This
     * catches anything that could ever slip past the interception hooks (a meta refresh, a
     * service-worker navigation, a bug in the WebView, or a future edit to this app).
     */
    private void tickWatchdog() {
        WebView view = webView;
        if (view == null) {
            return;
        }
        metrics.increment(PolicyMetrics.Counter.WATCHDOG_CHECKS);
        String current = view.getUrl();
        if (current == null || current.isEmpty()) {
            return;
        }
        PolicyResult result = policy.evaluateNavigation(current);
        if (result.isAllowed()) {
            return;
        }
        metrics.recordBlock(result.reason(), result.canonicalHost(), current);
        Log.w(TAG, "watchdog found a non-approved URL, resetting: " + result.describe());
        if (overlay.isOverlayVisible()) {
            return;
        }
        loadVerifiedDestination(policy.primaryUrl(), "watchdog");
    }

    private void copyDiagnosticsToClipboard() {
        try {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("arena-webview-locked", diagnosticsReport()));
                onMessage("Diagnostics copied");
            }
        } catch (Throwable t) {
            Log.w(TAG, "clipboard failed", t);
        }
    }

    /** The report shown in logcat and copied by the "Copy diagnostics" button. */
    public String diagnosticsReport() {
        return "policy:\n" + policy.describe()
                + "\nallowed hosts: " + ArenaAllowlist.allowedHosts() + "\n\n"
                + metrics.snapshot();
    }

    private String describeDestination(String url, PolicyResult result) {
        String host = destinationHost(url, result);
        String reason = result == null || result.reason() == null ? "" : result.reason().humanMessage();
        if (host.isEmpty()) {
            return reason.isEmpty() ? "that destination (not allowed)" : reason;
        }
        return reason.isEmpty() ? host : host + " (" + reason + ")";
    }

    private String destinationHost(String url, PolicyResult result) {
        if (result != null && !result.canonicalHost().isEmpty()) {
            return result.canonicalHost();
        }
        if (url == null || url.isEmpty()) {
            return "";
        }
        try {
            Uri uri = Uri.parse(url);
            String host = uri.getHost();
            if (host != null && !host.isEmpty()) {
                return host.toLowerCase(Locale.ROOT);
            }
            String scheme = uri.getScheme();
            return scheme == null ? "" : scheme + ":";
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * Keep the WebView inside the system bars. targetSdk 35 makes Android 15+ draw apps edge to edge by
     * default, which would put the site's own header under the status bar.
     */
    private void applyWindowInsetsPadding() {
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View view, WindowInsets insets) {
                int left = insets.getSystemWindowInsetLeft();
                int top = insets.getSystemWindowInsetTop();
                int right = insets.getSystemWindowInsetRight();
                int bottom = insets.getSystemWindowInsetBottom();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && insets.getDisplayCutout() != null) {
                    top = Math.max(top, insets.getDisplayCutout().getSafeInsetTop());
                    bottom = Math.max(bottom, insets.getDisplayCutout().getSafeInsetBottom());
                    left = Math.max(left, insets.getDisplayCutout().getSafeInsetLeft());
                    right = Math.max(right, insets.getDisplayCutout().getSafeInsetRight());
                }
                view.setPadding(left, top, right, bottom);
                return insets;
            }
        });
        root.setFitsSystemWindows(true);
        Window window = getWindow();
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    }
}
