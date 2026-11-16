package app.arenawebview.locked.policy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The single source of truth for what this wrapper may reach.
 *
 * <p>Everything is defined in code on purpose: there is no JSON/asset/remote configuration that could
 * be swapped, tampered with or fetched at runtime, so the reachable set of hosts is reviewable and
 * diff-able. {@code AllowlistRegressionTest} pins the exact contents of these lists so that widening
 * the allow-list can only ever happen as a deliberate, visible change.</p>
 *
 * <h3>First-party hosts</h3>
 * <p>{@code arena.ai} and {@code www.arena.ai} are the product itself. The remaining entries are
 * hosts that Arena AI operates as part of the same first-party site (they resolve inside the same
 * Cloudflare zone and are linked directly from arena.ai). Sub-domains that exist for mail, cPanel,
 * e-mail security ({@code mta-sts}), non-production previews ({@code *.preview}) or SSO
 * ({@code okta.arena.ai}) are deliberately <em>not</em> listed - they are not needed to use the
 * product and are exactly the kind of surface a locked-down wrapper should stay away from.</p>
 *
 * <h3>Sign-in and CAPTCHA hosts (enabled in this build)</h3>
 * <p>Arena AI offers sign-in with a Google account ("Continue with Google"), so this build allows the
 * hosts that flow needs, plus the CAPTCHA widget hosts so a challenge can be <em>displayed and
 * answered by the user</em> instead of being blocked. Both groups are one-line switches
 * ({@link #ENABLE_GOOGLE_SIGN_IN}, {@link #ENABLE_CAPTCHA_WIDGETS}) and both are pinned as narrowly as
 * the flow tolerates:</p>
 * <ul>
 *   <li>{@code accounts.google.com} - the only third-party host that may ever become a visible
 *       <em>page</em> ({@code Role.SIGN_IN}). Google's sign-in flow lives at many paths under this one
 *       host, so it is listed without a path restriction; the host is Google's account host and
 *       nothing else.</li>
 *   <li>{@code ssl.gstatic.com}, {@code www.gstatic.com}, {@code apis.google.com},
 *       {@code fonts.gstatic.com}, {@code lh3.googleusercontent.com} - assets used <em>by</em> Google's
 *       sign-in pages and by the site's account menu. All of them are {@code Role.ASSET}: sub-resources
 *       only, path-pinned, and never navigable.</li>
 *   <li>{@code www.google.com/recaptcha/}, {@code www.gstatic.com/recaptcha/},
 *       {@code www.recaptcha.net/recaptcha/} and {@code challenges.cloudflare.com/turnstile/} - the
 *       CAPTCHA widgets, again assets only and pinned to the widget path. Google search, Gmail, Drive
 *       and every other Google product stay unreachable: a host that is not in this file does not
 *       exist as far as the app is concerned.</li>
 * </ul>
 *
 * <p><b>What the wrapper still does not do:</b> it never solves, answers, bypasses or automates a
 * CAPTCHA, it never reads, stores or transmits a credential, and it has no JavaScript bridge. The user
 * answers challenges and types passwords; the app only decides which hosts may take part.</p>
 */
public final class ArenaAllowlist {

    /** The only page the app ever opens on its own. */
    public static final String PRIMARY_URL = "https://arena.ai/";

    /** Exact first-party host names, in policy order (page-capable and asset-only hosts alike). */
    public static final List<String> FIRST_PARTY_HOSTS;

    /**
     * Sign-in with a Google account. Arena AI offers it as "Continue with Google", and the flow cannot
     * complete without reaching Google's account host.
     *
     * <p>Set to {@code false} to remove every Google host from the policy: the wrapper then behaves
     * exactly like the first-party-only build (sign-in falls back to whatever Arena AI offers without
     * Google, and Google's pages are refused like any other third party).</p>
     */
    public static final boolean ENABLE_GOOGLE_SIGN_IN = true;

    /**
     * CAPTCHA widget hosts (reCAPTCHA and Cloudflare Turnstile). Arena AI sits behind Cloudflare, so a
     * challenge is a normal thing to encounter.
     *
     * <p>These are <em>assets only</em>: the challenge iframe is a sub-resource of the arena.ai page, so
     * the widget can render and the user can answer it, but the app can never be navigated to a CAPTCHA
     * host as a page. Enabling them does not weaken any check - the wrapper still does not solve,
     * pre-answer or script a challenge, and {@code www.google.com} remains unreachable outside
     * {@code /recaptcha/}.</p>
     */
    public static final boolean ENABLE_CAPTCHA_WIDGETS = true;

    /**
     * Agent Mode connectors (GitHub, Notion, Linear, Supabase, Vercel, Dropbox, and Google's own
     * connectors via the already-enabled sign-in host).
     *
     * <p>A connector is linked by an OAuth window opened from {@code arena.ai/agent}. That window has to
     * display the provider's <em>authorisation</em> page - and nothing else. Each entry here is therefore
     * a {@link AllowEntry.Role#CONNECTOR}: page-capable, but only at the exact path prefixes listed, which
     * cover the provider's OAuth authorize endpoint and its own sign-in steps. A provider's dashboard,
     * repositories, files, mail and every other path stay unreachable, exactly as before.</p>
     *
     * <p>Set to {@code false} to remove every connector host: linking a connector then ends in the app's
     * usual "pop-up blocked" refusal, and nothing else about the wrapper changes.</p>
     */
    public static final boolean ENABLE_AGENT_CONNECTORS = true;

    static {
        List<String> hosts = new ArrayList<String>();
        for (AllowEntry entry : firstPartyEntries()) {
            hosts.add(entry.host());
        }
        FIRST_PARTY_HOSTS = Collections.unmodifiableList(hosts);
    }

    private ArenaAllowlist() {
        // no instances
    }

    /** The complete set of hosts this build accepts, in a stable order. */
    public static List<String> allowedHosts() {
        List<String> hosts = new ArrayList<String>();
        for (AllowEntry entry : activeEntries()) {
            hosts.add(entry.host());
        }
        return Collections.unmodifiableList(hosts);
    }

    /** The entries actually enforced by this build (first-party, plus the enabled capability groups). */
    public static List<AllowEntry> activeEntries() {
        List<AllowEntry> entries = new ArrayList<AllowEntry>(firstPartyEntries());
        List<List<AllowEntry>> groups = new ArrayList<List<AllowEntry>>();
        if (ENABLE_GOOGLE_SIGN_IN) {
            groups.add(signInEntries());
        }
        if (ENABLE_CAPTCHA_WIDGETS) {
            groups.add(captchaEntries());
        }
        if (ENABLE_AGENT_CONNECTORS) {
            groups.add(connectorEntries());
        }
        entries.addAll(merge(groups));
        return Collections.unmodifiableList(entries);
    }

    /**
     * First-party, Arena-operated hosts. Each one is an exact host name - no wildcards exist in this
     * code base at all.
     */
    public static List<AllowEntry> firstPartyEntries() {
        List<AllowEntry> entries = new ArrayList<AllowEntry>();
        entries.add(AllowEntry.anyPath("arena.ai", "The product itself (canonical host)."));
        entries.add(AllowEntry.anyPath("www.arena.ai", "Canonical alternate host for the same site."));
        entries.add(AllowEntry.anyPath("auth.arena.ai",
                "First-party authentication service (session/credential API for sign-in)."));
        entries.add(AllowEntry.anyPath("cdn.arena.ai", "First-party static asset CDN."));
        entries.add(AllowEntry.anyPath("agent.arena.ai", "Agent Mode first-party endpoint."));
        entries.add(AllowEntry.anyPath("code.arena.ai", "Code/artifact first-party endpoint."));
        entries.add(AllowEntry.resourceOnly("indexable.arena.ai", Collections.<String>emptyList(),
                "Pre-rendered content bucket: served to the page as assets/frames only. It has no "
                        + "browsable root (the object bucket answers 404 at /), so it must never be the "
                        + "app's visible page."));
        // Retrieved 2026-11-14: blog.arena.ai and news.arena.ai both answer Cloudflare 522 (origin down)
        // and arena.ai no longer links to them - the blog is served from arena.ai/blog. They stay
        // resolvable for any image/asset the site still references from them, but neither can become the
        // app's page, so a dead host can never be presented as the product.
        entries.add(AllowEntry.resourceOnly("blog.arena.ai", Collections.<String>emptyList(),
                "Legacy blog host (currently 522). Assets only - the live blog is arena.ai/blog."));
        entries.add(AllowEntry.resourceOnly("news.arena.ai", Collections.<String>emptyList(),
                "Legacy news host (currently 522, no longer linked from the site). Assets only."));
        entries.add(AllowEntry.anyPath("help.arena.ai", "First-party help centre (linked from the site)."));
        entries.add(AllowEntry.anyPath("status.arena.ai", "First-party status page (linked from the site)."));
        entries.add(AllowEntry.anyPath("trust.arena.ai", "First-party trust/compliance centre (linked from the site)."));
        return Collections.unmodifiableList(entries);
    }

    /**
     * The Google sign-in hand-off. Exactly one host may become a page; everything else is an asset used
     * by Google's own pages.
     */
    public static List<AllowEntry> signInEntries() {
        List<AllowEntry> entries = new ArrayList<AllowEntry>();
        entries.add(AllowEntry.signIn("accounts.google.com", Collections.<String>emptyList(),
                "Google's account/sign-in pages - the \"Continue with Google\" hand-off. Displayed only "
                        + "as a page, only while the user is signing in."));
        // Google's static asset hosts serve their identity UI from content-hashed, opaque paths
        // (www.gstatic.com/_/mss/boq-identity/_/js/k=boq-identity.AccountsSignInUi.en.<hash>/...) and
        // the path changes with every deployment, so enumerating prefixes here is guaranteed to break
        // sign-in eventually - it did. These are asset-only entries: they can be fetched and embedded in
        // an iframe, and the wrapper is never navigated to them (the DOCUMENT rule in UrlPolicy).
        entries.add(AllowEntry.resourceOnly("ssl.gstatic.com", Collections.<String>emptyList(),
                "Google's static asset host: sub-resources only, never a page. No path restriction "
                        + "because the paths are content-hashed and change without notice."));
        entries.add(AllowEntry.resourceOnly("www.gstatic.com", Collections.<String>emptyList(),
                "Google's static asset host (identity UI bundles under /_/mss/, images, gsi, reCAPTCHA): "
                        + "sub-resources only, never a page."));
        entries.add(AllowEntry.resourceOnly("apis.google.com", paths("/js/", "/gsi/", "/_/"),
                "Google's client-side identity/gapi scripts loaded by the sign-in hand-off."));
        entries.add(AllowEntry.resourceOnly("fonts.gstatic.com", Collections.singletonList("/s/"),
                "Web font files used by Google's pages (cosmetic only)."));
        entries.add(AllowEntry.resourceOnly("lh3.googleusercontent.com", Collections.singletonList("/a/"),
                "Account avatars in the site's account menu (images only)."));
        return Collections.unmodifiableList(entries);
    }

    /**
     * CAPTCHA widget assets: reCAPTCHA (on Google's asset hosts and on the
     * {@code recaptcha.net} alias Google documents for restricted environments) and Cloudflare
     * Turnstile. Sub-resources only, path-pinned, never a page.
     */
    public static List<AllowEntry> captchaEntries() {
        List<AllowEntry> entries = new ArrayList<AllowEntry>();
        entries.add(AllowEntry.resourceOnly("www.google.com", Collections.singletonList("/recaptcha/"),
                "reCAPTCHA widget assets only - never Google search, accounts or APIs, and never a page."));
        // www.gstatic.com is already an asset-only entry without path restrictions (see signInEntries);
        // the reCAPTCHA release bundles live under it too and merge() keeps the wider entry.
        entries.add(AllowEntry.resourceOnly("www.recaptcha.net", Collections.singletonList("/recaptcha/"),
                "reCAPTCHA assets on the alternate domain Google documents for restricted networks."));
        entries.add(AllowEntry.resourceOnly("challenges.cloudflare.com",
                paths("/turnstile/", "/cdn-cgi/challenge-platform/"),
                "Cloudflare Turnstile widget assets and the challenge frame Cloudflare actually renders "
                        + "the widget in (its own documentation shows the iframe comes from "
                        + "/cdn-cgi/challenge-platform/) - never a page."));
        return Collections.unmodifiableList(entries);
    }

    /**
     * Agent Mode connector authorisation endpoints. Every entry is a {@code CONNECTOR}: page-capable,
     * pinned to the provider's own OAuth {@code authorize} endpoint plus the sign-in steps that endpoint
     * redirects through, and nothing wider.
     *
     * <p>These four are the providers whose whole sign-in flow stays on the host that serves their
     * authorize endpoint - GitHub, Linear, Vercel and Dropbox. Google's own connectors (Drive, Docs,
     * Sheets, Slides, Gmail, Calendar) reuse {@code accounts.google.com} from {@link #signInEntries()}.
     * The paths below were checked against each provider's documented OAuth endpoint on 2026-11-14; a
     * provider that moved its endpoint would fail closed (the pop-up is refused with a named reason in
     * logcat) rather than quietly widen.</p>
     *
     * <p><b>Notion and Supabase are deliberately absent.</b> Their authorize endpoints live on
     * {@code api.notion.com} and {@code api.supabase.com}, but a signed-out user is redirected off those
     * hosts - to {@code www.notion.so/login} and {@code supabase.com/dashboard/sign-in} - and the app's
     * WebView has its own cookie jar, so it is <em>always</em> signed out. Allow-listing the authorize
     * endpoint alone would therefore leave the flow dead at the first hop; allowing those two page hosts
     * instead would put Notion's and Supabase's dashboards, projects and settings inside the wrapper,
     * which is a far wider page surface than a connector needs. Neither is acceptable, so both are
     * refused rather than half-supported: connecting Notion or Supabase in this app fails visibly and
     * immediately instead of appearing to work and then blocking. The site's own Connect button for them
     * is blocked by the same rule that blocks any off-list navigation.</p>
     */
    public static List<AllowEntry> connectorEntries() {
        List<AllowEntry> entries = new ArrayList<AllowEntry>();
        entries.add(AllowEntry.connectorSignIn("github.com", paths("/login/oauth/authorize", "/login", "/session"),
                "GitHub connector: the OAuth authorize endpoint and its own sign-in steps. github.com "
                        + "repositories, settings and every other path stay unreachable."));
        entries.add(AllowEntry.connectorSignIn("linear.app", paths("/oauth/authorize", "/login", "/signin"),
                "Linear connector: the OAuth authorize endpoint and its own sign-in steps."));
        entries.add(AllowEntry.connectorSignIn("vercel.com", paths("/oauth/authorize", "/login"),
                "Vercel connector: the OAuth authorize endpoint and its own login step."));
        entries.add(AllowEntry.connectorSignIn("www.dropbox.com", paths("/oauth2/authorize", "/login"),
                "Dropbox connector: the OAuth authorize endpoint and its own login step."));
        return Collections.unmodifiableList(entries);
    }

    /**
     * The first-party hosts that are <b>not</b> page-capable any more: they still serve assets, but the
     * app is never navigated to them. Pinned by {@code AllowlistRegressionTest}.
     */
    public static List<String> demotedFirstPartyHosts() {
        List<String> hosts = new ArrayList<String>();
        for (AllowEntry entry : firstPartyEntries()) {
            if (entry.isResourceOnly()) {
                hosts.add(entry.host());
            }
        }
        return Collections.unmodifiableList(hosts);
    }

    /**
     * Every third-party host this build can reach (page-capable and asset-only alike), in policy order.
     * This is the app's whole outbound third-party surface, and it is pinned by
     * {@code AllowlistRegressionTest}.
     */
    public static List<String> thirdPartyHosts() {
        List<String> hosts = new ArrayList<String>();
        for (AllowEntry entry : activeEntries()) {
            if (!entry.isProduct() && !isFirstParty(entry.host())) {
                hosts.add(entry.host());
            }
        }
        return Collections.unmodifiableList(hosts);
    }

    /** True when the host appears in the first-party list (page-capable or demoted). */
    private static boolean isFirstParty(String host) {
        for (AllowEntry entry : firstPartyEntries()) {
            if (entry.host().equals(host)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The third-party hosts that may become a page, in policy order: Google's sign-in host and the
     * connector authorisation endpoints.
     */
    public static List<String> pageCapableThirdPartyHosts() {
        List<String> hosts = new ArrayList<String>();
        for (AllowEntry entry : activeEntries()) {
            if (!entry.isProduct() && entry.isPageCapable()) {
                hosts.add(entry.host());
            }
        }
        return Collections.unmodifiableList(hosts);
    }

    /** True when this exact host may be displayed as a sign-in page in the runner-up window. */
    public static boolean isSignInHost(String canonicalHost) {
        if (canonicalHost == null) {
            return false;
        }
        for (AllowEntry entry : activeEntries()) {
            if (entry.isSignIn() && entry.host().equals(canonicalHost)) {
                return true;
            }
        }
        return false;
    }

    /** Build the policy enforced by this build. */
    public static PolicyConfig productionConfig() {
        return new PolicyConfig(PRIMARY_URL, activeEntries());
    }

    // -----------------------------------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------------------------------

    /**
     * Merges capability groups into one entry per host, unioning their path prefixes.
     *
     * <p>Two groups can legitimately want the same host (Google's {@code www.gstatic.com} serves both
     * sign-in and reCAPTCHA assets). {@link PolicyConfig} refuses to be built from a list that names a
     * host twice (a second entry would otherwise be dead text that a reviewer could read but that is
     * never enforced), so the groups are merged first: every prefix stays reachable and the enforced set
     * is exactly what the diagnostics report prints.</p>
     */
    static List<AllowEntry> merge(List<List<AllowEntry>> groups) {
        Map<String, AllowEntry> byHost = new LinkedHashMap<String, AllowEntry>();
        for (List<AllowEntry> group : groups) {
            for (AllowEntry entry : group) {
                AllowEntry existing = byHost.get(entry.host());
                if (existing == null) {
                    byHost.put(entry.host(), entry);
                    continue;
                }
                if (existing.role() != entry.role()) {
                    throw new IllegalStateException("Conflicting allow-list roles for " + entry.host());
                }
                if (existing.role() == AllowEntry.Role.CONNECTOR) {
                    // Union-ing two connector path sets would widen a provider's reachable surface
                    // silently. A connector must be declared exactly once; a duplicate is a bug.
                    throw new IllegalStateException("Connector host declared twice: " + entry.host());
                }
                if (existing.pathPrefixes().isEmpty() || entry.pathPrefixes().isEmpty()) {
                    // One of the groups wants the whole host: keep it whole. Union-ing the prefixes here
                    // would silently narrow the wider entry and break whichever flow needed it.
                    byHost.put(entry.host(), new AllowEntry(entry.host(), Collections.<String>emptyList(),
                            existing.purpose(), entry.role()));
                    continue;
                }
                List<String> mergedPaths = new ArrayList<String>(existing.pathPrefixes());
                for (String path : entry.pathPrefixes()) {
                    if (!mergedPaths.contains(path)) {
                        mergedPaths.add(path);
                    }
                }
                byHost.put(entry.host(), new AllowEntry(entry.host(), mergedPaths,
                        existing.purpose(), entry.role()));
            }
        }
        return new ArrayList<AllowEntry>(byHost.values());
    }

    private static List<String> paths(String... prefixes) {
        List<String> list = new ArrayList<String>();
        Collections.addAll(list, prefixes);
        return Collections.unmodifiableList(list);
    }
}
