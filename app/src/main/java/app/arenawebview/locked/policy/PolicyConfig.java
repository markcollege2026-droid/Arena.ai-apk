package app.arenawebview.locked.policy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The complete, immutable navigation policy of the wrapper.
 *
 * <p>Everything the WebView is allowed to touch is described here: one start URL, a list of
 * first-party hosts and (optionally, off by default) a small list of path-scoped third-party entries
 * that exist only because a specific site capability - e.g. Google's reCAPTCHA widget - cannot work
 * without them.</p>
 *
 * <p>The policy has exactly one public decision method per traffic class:</p>
 * <ul>
 *   <li>{@link #evaluateNavigation(String)} for anything that can become the visible document
 *       (main frame, iframes, redirects, {@code window.open} targets).</li>
 *   <li>{@link #evaluateRequest(String)} for every network request the WebView makes
 *       (XHR/fetch, scripts, images, media, trackers, ...).</li>
 * </ul>
 *
 * <p>Both return a {@link PolicyResult}; there is no code path that returns "unknown", so the default
 * for anything unverifiable is a refusal.</p>
 */
public final class PolicyConfig {

    private final String primaryUrl;
    private final List<AllowEntry> entries;

    /**
     * @param primaryUrl the app's start page; must itself pass {@link #evaluateNavigation(String)}
     */
    public PolicyConfig(String primaryUrl, List<AllowEntry> entries) {
        if (primaryUrl == null || primaryUrl.isEmpty()) {
            throw new IllegalArgumentException("primaryUrl must not be empty");
        }
        if (entries == null || entries.isEmpty()) {
            throw new IllegalArgumentException("at least one allow-list entry is required");
        }
        List<AllowEntry> copy = new ArrayList<AllowEntry>(entries);
        this.primaryUrl = primaryUrl;
        this.entries = Collections.unmodifiableList(copy);

        // Fail loudly at start-up if the configured start page would not pass the configured policy.
        if (!evaluateNavigation(primaryUrl).isAllowed()) {
            throw new IllegalStateException(
                    "primaryUrl is not allowed by this policy: " + evaluateNavigation(primaryUrl).describe());
        }
    }

    public String primaryUrl() {
        return primaryUrl;
    }

    public List<AllowEntry> entries() {
        return entries;
    }

    /** Evaluate a URL that can become (or become part of) a rendered document. */
    public PolicyResult evaluateNavigation(String rawUrl) {
        return UrlPolicy.evaluate(rawUrl, this, UrlPolicy.TrafficClass.DOCUMENT);
    }

    /** Evaluate a sub-resource request (script, image, XHR, websocket-less fetch, ...). */
    public PolicyResult evaluateRequest(String rawUrl) {
        return UrlPolicy.evaluate(rawUrl, this, UrlPolicy.TrafficClass.SUBRESOURCE);
    }

    /** Find the entry that matches a canonical host, or null. */
    AllowEntry findByHost(String canonicalHost) {
        for (AllowEntry entry : entries) {
            if (entry.host().equals(canonicalHost)) {
                return entry;
            }
        }
        return null;
    }

    /** Human readable dump of the whole policy, used by the diagnostics report and by tests. */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append("start=").append(primaryUrl).append('\n');
        for (AllowEntry entry : entries) {
            sb.append("allow ").append(entry.describe()).append('\n');
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return String.format(Locale.US, "PolicyConfig(start=%s, entries=%d)", primaryUrl, entries.size());
    }
}
