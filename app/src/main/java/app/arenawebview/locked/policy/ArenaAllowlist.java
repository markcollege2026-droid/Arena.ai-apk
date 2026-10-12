package app.arenawebview.locked.policy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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
 * <h3>Third-party capability entries</h3>
 * <p>Off by default. See {@link #ENABLE_THIRD_PARTY_CAPABILITY_ENTRIES}.</p>
 */
public final class ArenaAllowlist {

    /** The only page the app ever opens on its own. */
    public static final String PRIMARY_URL = "https://arena.ai/";

    /** Exact end-point of every redirect that the wrapper follows back into the app. */
    public static final List<String> FIRST_PARTY_HOSTS;

    /**
     * <b>Off by default - read this before turning it on.</b>
     *
     * <p>Two capabilities of the real arena.ai page are served from third-party hosts that cannot be
     * reached from {@code arena.ai} itself:</p>
     * <ul>
     *   <li>the reCAPTCHA widget, served from {@code www.google.com/recaptcha/*} and
     *       {@code www.gstatic.com/recaptcha/*};</li>
     *   <li>the Cloudflare Turnstile challenge widget, served from
     *       {@code challenges.cloudflare.com/turnstile/*}.</li>
     * </ul>
     *
     * <p>Those are the only third-party hosts that would ever be reachable, they are pinned to the
     * exact asset path of the widget (never the whole domain - {@code www.google.com/search} stays
     * unreachable), and they are HTTP GET-only asset loads. Even so, enabling them means the app can
     * talk to two hosts that are not Arena AI, and a CAPTCHA challenge is a browser check that the
     * wrapper must deliberately not interfere with, so the honest default is
     * <b>off</b>: if Arena AI shows a challenge, the wrapper shows its in-app message instead of
     * silently contacting Google. Flip this constant to {@code true} and rebuild if a deployment
     * turns out to require the widget (see README, "Third-party capability entries").</p>
     */
    public static final boolean ENABLE_THIRD_PARTY_CAPABILITY_ENTRIES = false;

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

    /** The entries actually enforced by this build (first-party, plus the opt-in widgets if enabled). */
    public static List<AllowEntry> activeEntries() {
        List<AllowEntry> entries = new ArrayList<AllowEntry>(firstPartyEntries());
        if (ENABLE_THIRD_PARTY_CAPABILITY_ENTRIES) {
            entries.addAll(capabilityEntries());
        }
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
        entries.add(AllowEntry.anyPath("indexable.arena.ai", "Pre-rendered content used by the site."));
        entries.add(AllowEntry.anyPath("blog.arena.ai", "First-party blog (linked from the site)."));
        entries.add(AllowEntry.anyPath("news.arena.ai", "First-party news pages (linked from the site)."));
        entries.add(AllowEntry.anyPath("help.arena.ai", "First-party help centre (linked from the site)."));
        entries.add(AllowEntry.anyPath("status.arena.ai", "First-party status page (linked from the site)."));
        entries.add(AllowEntry.anyPath("trust.arena.ai", "First-party trust/compliance centre (linked from the site)."));
        return Collections.unmodifiableList(entries);
    }

    /**
     * Third-party widget hosts, each pinned to the exact asset path of the widget and nothing else.
     * Only active when {@link #ENABLE_THIRD_PARTY_CAPABILITY_ENTRIES} is true.
     */
    public static List<AllowEntry> capabilityEntries() {
        List<AllowEntry> entries = new ArrayList<AllowEntry>();
        entries.add(AllowEntry.resourceOnly("www.google.com", Collections.singletonList("/recaptcha/"),
                "reCAPTCHA widget assets only - never Google search, accounts or APIs, and never a page."));
        entries.add(AllowEntry.resourceOnly("www.gstatic.com", Collections.singletonList("/recaptcha/"),
                "reCAPTCHA static assets only - never a page."));
        entries.add(AllowEntry.resourceOnly("challenges.cloudflare.com", Collections.singletonList("/turnstile/"),
                "Cloudflare Turnstile widget assets only - never a page."));
        return Collections.unmodifiableList(entries);
    }

    /** Build the policy enforced by this build. */
    public static PolicyConfig productionConfig() {
        return new PolicyConfig(PRIMARY_URL, activeEntries());
    }
}
