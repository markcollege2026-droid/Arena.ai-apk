package app.arenawebview.locked.policy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * One entry of the allow-list: an <em>exact</em> host plus an optional set of allowed path prefixes.
 *
 * <p>There is deliberately <b>no wildcard support</b>. An entry matches a host if and only if the
 * canonical host is byte-for-byte equal to {@link #host}. That single design decision is what makes
 * look-alike domains ({@code arena-ai.com}, {@code arena.ai.evil.example}, {@code xn--arena-...ai},
 * {@code аrena.ai} with a Cyrillic 'а', {@code arena.ai.} with a trailing dot) impossible to reach:
 * they simply are not equal to {@code arena.ai}.</p>
 *
 * <p>Immutable and Android-free so that it can be unit tested on a plain JVM.</p>
 */
public final class AllowEntry {

    /** Exact canonical host, lower case, ASCII. */
    private final String host;

    /** Allowed path prefixes; empty list means "any path on this host". */
    private final List<String> pathPrefixes;

    /** Human readable justification, shown in the diagnostics report. */
    private final String purpose;

    /**
     * True when this entry may only serve sub-resources. Used for the opt-in third-party widget hosts so
     * that, even with those enabled, a page can never be navigated to them.
     */
    private final boolean resourceOnly;

    /**
     * @param host         exact canonical host (lower case ASCII, e.g. {@code arena.ai})
     * @param pathPrefixes optional path prefixes such as {@code /recaptcha/}; empty means any path
     * @param purpose      why this host is reachable, for auditability
     */
    public AllowEntry(String host, List<String> pathPrefixes, String purpose) {
        this(host, pathPrefixes, purpose, false);
    }

    /**
     * @param resourceOnly when true the entry can only serve sub-resources; a navigation to it is refused
     */
    public AllowEntry(String host, List<String> pathPrefixes, String purpose, boolean resourceOnly) {
        String canonical = UrlPolicy.canonicalizeHost(host);
        if (canonical == null) {
            // A malformed allow-list entry is a programming error and must fail loudly at start-up
            // rather than silently widening or breaking the policy.
            throw new IllegalArgumentException("Not a valid allow-list host: " + host);
        }
        if (!canonical.equals(host)) {
            throw new IllegalArgumentException(
                    "Allow-list hosts must already be canonical (lower case, no trailing dot): " + host);
        }
        List<String> cleaned = new ArrayList<String>();
        if (pathPrefixes != null) {
            for (String p : pathPrefixes) {
                if (p == null || !p.startsWith("/")) {
                    throw new IllegalArgumentException("Path prefix must start with '/': " + p);
                }
                cleaned.add(p);
            }
        }
        this.host = canonical;
        this.pathPrefixes = Collections.unmodifiableList(cleaned);
        this.purpose = purpose == null ? "" : purpose;
        this.resourceOnly = resourceOnly;
    }

    /** Convenience factory for an "any path on this host" entry. */
    public static AllowEntry anyPath(String host, String purpose) {
        return new AllowEntry(host, Collections.<String>emptyList(), purpose);
    }

    /** Convenience factory for a path-scoped, resource-only entry (third-party widget hosts). */
    public static AllowEntry resourceOnly(String host, List<String> pathPrefixes, String purpose) {
        return new AllowEntry(host, pathPrefixes, purpose, true);
    }

    /** True when this host may serve sub-resources but must never become the visible document. */
    public boolean isResourceOnly() {
        return resourceOnly;
    }

    public String host() {
        return host;
    }

    public List<String> pathPrefixes() {
        return pathPrefixes;
    }

    public String purpose() {
        return purpose;
    }

    /** True when this entry restricts which paths may be used. */
    public boolean isPathRestricted() {
        return !pathPrefixes.isEmpty();
    }

    /**
     * Match the raw (still percent-encoded) path of a request against this entry's prefixes.
     *
     * <p>Matching is done on the <em>raw</em> path on purpose. Percent-decoding before the comparison
     * would let {@code /recaptcha%2f..%2fanything} look like it starts with {@code /recaptcha/}. Any
     * path on a path-restricted entry that contains a percent sign or a {@code ..} segment is refused
     * outright (fail closed) - no legitimate reCAPTCHA/Turnstile asset URL needs either.</p>
     */
    public boolean matchesPath(String rawPath) {
        if (pathPrefixes.isEmpty()) {
            return true;
        }
        if (rawPath == null || rawPath.indexOf('%') >= 0 || rawPath.contains("..")) {
            return false;
        }
        for (String prefix : pathPrefixes) {
            if (rawPath.startsWith(prefix)) {
                return true;
            }
            // "/recaptcha" should also match a prefix written as "/recaptcha/".
            if (prefix.endsWith("/") && rawPath.equals(prefix.substring(0, prefix.length() - 1))) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        if (pathPrefixes.isEmpty()) {
            return "AllowEntry{" + host + " any path}";
        }
        return "AllowEntry{" + host + " paths=" + pathPrefixes + "}";
    }

    /** Equality is host+paths only; used by regression tests. */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof AllowEntry)) {
            return false;
        }
        AllowEntry that = (AllowEntry) other;
        return host.equals(that.host) && pathPrefixes.equals(that.pathPrefixes);
    }

    @Override
    public int hashCode() {
        return host.hashCode() * 31 + pathPrefixes.hashCode();
    }

    /** Renders the entry in a stable, lower-case form for reporting. */
    public String describe() {
        StringBuilder sb = new StringBuilder(host.toLowerCase(Locale.ROOT));
        if (resourceOnly) {
            sb.append(" [resource-only]");
        }
        if (isPathRestricted()) {
            sb.append(" paths=").append(pathPrefixes);
        }
        if (!purpose.isEmpty()) {
            sb.append("  // ").append(purpose);
        }
        return sb.toString();
    }
}
