package app.arenawebview.locked.ui;

import android.content.Context;
import android.os.Build;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;

import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import app.arenawebview.locked.R;

/**
 * Pull-to-refresh for the primary WebView, started from the top edge only.
 *
 * <p>A stock {@code SwipeRefreshLayout} takes over any downward drag as soon as its child cannot scroll
 * up. A WebView reports that for the <em>document</em> scroll position, so on a page whose content lives
 * in its own scroll container (a chat transcript, say) every drag towards the top of that container would
 * become a refresh instead. Two rules keep the gesture out of the page's way: it only counts when it
 * <em>starts</em> within the top {@link #EDGE_DP} of the WebView and the document itself is already at the
 * top, and it needs a long pull ({@link #TRIGGER_DP}) before it fires. Everything else goes to the page
 * untouched.</p>
 *
 * <p>What a refresh does is decided by the activity: the current URL is verified again before it is
 * reloaded, and anything that no longer passes is replaced by the start page. This view only reports the
 * gesture and shows the spinner.</p>
 */
public final class PullToRefreshFrame extends SwipeRefreshLayout {

    /** The gesture must begin within this distance from the top of the visible page. */
    private static final int EDGE_DP = 72;
    /** How far the finger has to travel before a refresh fires. */
    private static final int TRIGGER_DP = 128;
    /**
     * Tolerance (px) for treating the WebView as scrolled to the document top.
     * {@link View#getScrollY()} can be off by a pixel or two on some WebView builds.
     */
    private static final int SCROLL_TOP_TOLERANCE_PX = 2;

    public interface Listener {
        void onRefreshRequested();
    }

    private final int edgePx;
    /** Top system-window inset (status bar / display cutout), so the edge zone is measured from the
     *  visible page top rather than from the absolute window top. */
    private int topInsetPx;
    private boolean gestureDeclined;

    public PullToRefreshFrame(Context context, final Listener listener) {
        super(context);
        setFitsSystemWindows(true);
        edgePx = dp(EDGE_DP);
        setDistanceToTriggerSync(dp(TRIGGER_DP));
        setColorSchemeColors(context.getColor(R.color.accent));
        setProgressBackgroundColorSchemeColor(context.getColor(R.color.card));
        setOnRefreshListener(new OnRefreshListener() {
            @Override
            public void onRefresh() {
                listener.onRefreshRequested();
            }
        });
        setOnChildScrollUpCallback(new OnChildScrollUpCallback() {
            @Override
            public boolean canChildScrollUp(SwipeRefreshLayout parent, View child) {
                // The scroll gate is the whole gate: SwipeRefreshLayout only treats a downward drag as a
                // refresh when the child cannot scroll up. The old implementation relied solely on
                // WebView.canScrollVertically(-1), which Chromium's render-view can misreport on some
                // WebView builds (after flings, programmatic scrolls, CSS overflow containers, iframes
                // or transformed layers) - returning "can scroll up" even though the page is at the top.
                // When that happens SwipeRefreshLayout never intercepts the drag and pull-to-refresh
                // silently does nothing on exactly those devices. View.getScrollY() measures the actual
                // scroll position and is more reliable, so it is the primary check.
                if (child == null) {
                    return false;
                }
                boolean atTop = child.getScrollY() <= SCROLL_TOP_TOLERANCE_PX;
                if (atTop) {
                    // At the document top: allow the refresh gesture. Ignore canScrollVertically here,
                    // since it is the unreliable signal this fix works around.
                    return false;
                }
                // Scrolled down from the top: defer to the platform query to decide whether the page
                // can scroll up further, in which case the drag belongs to the page.
                return child.canScrollVertically(-1);
            }
        });
        setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View view, WindowInsets insets) {
                topInsetPx = insets.getSystemWindowInsetTop();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                        && insets.getDisplayCutout() != null) {
                    topInsetPx =
                            Math.max(topInsetPx, insets.getDisplayCutout().getSafeInsetTop());
                }
                return insets;
            }
        });
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                // Measure from the visible page top (below the status bar / display cutout) so the
                // edge zone has the same effective size on every device shape, instead of starting
                // from the absolute window top where notches and tall status bars shrink it.
                gestureDeclined = event.getY() > topInsetPx + edgePx;
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (gestureDeclined) {
                    gestureDeclined = false;
                    return false;
                }
                break;
            default:
                break;
        }
        if (gestureDeclined) {
            return false;
        }
        return super.onInterceptTouchEvent(event);
    }

    /** End the spinner (the page finished loading, or the reload was replaced by something else). */
    public void finishRefreshing() {
        if (isRefreshing()) {
            setRefreshing(false);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
