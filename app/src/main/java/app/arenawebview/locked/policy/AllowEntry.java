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
 * <p>Entries carry a {@link Role}: Arena.ai's own pages, third-party asset hosts that may only ever
 * serve sub-resources, and the sign-in hosts that a login hand-off needs to display. Only
 * {@code PRODUCT} and {@code SIGN_IN} entries can become the visible document, and the runner-up
 * window (used for pop-up based sign-in) additionally refuses anything that is not one of those two
 * roles.</p>
 *
 * <p>Immutable and Android-free so that it can be unit tested on a plain JVM.</p>
 */
public final class AllowEntry {

    /**
     * What an entry is for. The role never widens the set of hosts - it only records which capability
     * the entry exists for, so the app can tell an ordinary page navigation apart from the step of a
     * sign-in hand-off (see {@link #isSignIn()}).
     */
    public enum Role {
        /** Arena.ai itself: pages of the product. */
        PRODUCT,
        /** A third-party asset host: sub-resources only, never a page. */
        ASSET,
        /** A third-party page that is part of a sign-in hand-off (Google's OAuth pages). */
        SIGN_IN,
        /**
         * A third-party <em>authorisation</em> page that an Agent Mode connector needs: it may become a
         * page, but only at the entry's exact path prefixes - the provider's OAuth {@code authorize}
         * endpoint and nothing else.
         *
         * <p>This is deliberately narrower than {@link #SIGN_IN} (which is path-free because Google's
         * sign-in flow lives at arbitrary paths on its one account host). A connector only ever needs
         * the one endpoint it redirects to, so the rest of that provider's site (dashboards, settings,
         * repositories, mail) stays unreachable exactly as before.</p>
         */
        CONNECTOR
    }

    /** Exact canonical host, lower case, ASCII. */
    private final String host;

    /** Allowed path prefixes; empty list means "any path on this host". */
    private final List<String> pathPrefixes;

    /** Human readable justification, shown in the diagnostics report. */
    private final String purpose;

    /** What this entry is for; see {@link Role}. */
    private final Role role;

    /**
     * When true, the path prefixes above are <em>whole</em> paths, not prefixes: the request path must
     * equal one of them.
     *
     * <p>This matters for OAuth endpoints that sit beside siblings on the same host: GitHub serves
     * {@code /login/oauth/authorize} and {@code /login/oauth/access_token}, Linear serves
     * {@code /oauth/authorize} beside other endpoints. A plain prefix would let a token endpoint become
     * a page. Exact matching is opt-in per entry so the existing prefix entries are unaffected.</p>
     */
    private final boolean exactPaths;

    /**
     * @param host         exact canonical host (lower case ASCII, e.g. {@code arena.ai})
     * @param pathPrefixes optional path prefixes such as {@code /recaptcha/}; empty means any path
     * @param purpose      why this host is reachable, for auditability
     */
    public AllowEntry(String host, List<String> pathPrefixes, String purpose) {
        this(host, pathPrefixes, purpose, Role.PRODUCT);
    }

    /**
     * @param role what the entry is for; {@link Role#ASSET} means "sub-resources only, never a page"
     */
    public AllowEntry(String host, List<String> pathPrefixes, String purpose, Role role) {
        this(host, pathPrefixes, purpose, role, false);
    }

    /**
     * @param exactPaths true when the prefixes must match the whole request path, not just its start
     */
    public AllowEntry(String host, List<String> pathPrefixes, String purpose, Role role,
                      boolean exactPaths) {
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
        this.role = role == null ? Role.PRODUCT : role;
        this.exactPaths = exactPaths;
    }

    /** Convenience factory for an "any path on this host" entry. */
    public static AllowEntry anyPath(String host, String purpose) {
        return new AllowEntry(host, Collections.<String>emptyList(), purpose);
    }

    /** Convenience factory for a path-scoped, resource-only entry (third-party asset/widget hosts). */
    public static AllowEntry resourceOnly(String host, List<String> pathPrefixes, String purpose) {
        return new AllowEntry(host, pathPrefixes, purpose, Role.ASSET);
    }

    /**
     * Convenience factory for a third-party <em>page</em> that exists only as one step of a sign-in
     * hand-off. Such a host may become the visible document, which is exactly what the OAuth flow
     * needs, and it is the only class of entry that may do so outside the {@link Role#PRODUCT} hosts.
     */
    public static AllowEntry signIn(String host, List<String> pathPrefixes, String purpose) {
        return new AllowEntry(host, pathPrefixes, purpose, Role.SIGN_IN);
    }

    /**
     * Convenience factory for a third-party connector's OAuth authorisation endpoint.
     *
     * <p>The entry <b>must</b> be path-restricted: a connector page role without a path pin would make
     * the provider's whole site navigable, which is precisely what this wrapper refuses to do. An empty
     * prefix list is therefore a programming error and throws at start-up rather than silently widening
     * the policy.</p>
     */
    public static AllowEntry connectorSignIn(String host, List<String> authorizePathPrefixes,
                                             String purpose) {
        if (authorizePathPrefixes == null || authorizePathPrefixes.isEmpty()) {
            throw new IllegalArgumentException(
                    "A connector authorisation entry must be pinned to its authorize path: " + host);
        }
        return new AllowEntry(host, authorizePathPrefixes, purpose, Role.CONNECTOR, true);
    }

    /** True when this host may serve sub-resources but must never become the visible document. */
    public boolean isResourceOnly() {
        return role == Role.ASSET;
    }

    /** True when this entry is a step of a sign-in hand-off rather than an Arena.ai page. */
    public boolean isSignIn() {
        return role == Role.SIGN_IN;
    }

    /**
     * True when this entry is a connector's OAuth authorisation endpoint: page-capable, but only at the
     * exact path prefixes it declares.
     */
    public boolean isConnector() {
        return role == Role.CONNECTOR;
    }

    /**
     * True when this entry may become the app's visible page. Only {@link Role#PRODUCT}, {@link
     * Role#SIGN_IN} and {@link Role#CONNECTOR} entries can; an asset entry never can.
     */
    public boolean isPageCapable() {
        return role != Role.ASSET;
    }

    /** True when this entry is part of the product itself. */
    public boolean isProduct() {
        return role == Role.PRODUCT;
    }

    public Role role() {
        return role;
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

    /** True when the restriction is an exact-path match rather than a prefix match. */
    public boolean isExactPaths() {
        return exactPaths;
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
        if (exactPaths) {
            for (String prefix : pathPrefixes) {
                if (rawPath.equals(prefix)) {
                    return true;
                }
                // "/login/oauth/authorize/" is the same endpoint with a trailing slash, and a request
                // line may carry one; nothing else is folded.
                if (rawPath.equals(prefix + "/") || (prefix.endsWith("/")
                        && rawPath.equals(prefix.substring(0, prefix.length() - 1)))) {
                    return true;
                }
            }
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
        if (role == Role.ASSET) {
            sb.append(" [resource-only]");
        } else if (role == Role.SIGN_IN) {
            sb.append(" [sign-in page]");
        } else if (role == Role.CONNECTOR) {
            sb.append(" [connector authorize endpoint]");
        }
        if (isPathRestricted()) {
            sb.append(" paths=").append(String.valueOf(pathPrefixes));
        }
        if (!purpose.isEmpty()) {
            sb.append("  // ").append(purpose);
        }
        return sb.toString();
    }
}
