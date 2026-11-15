package app.arenawebview.locked.policy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Pins the allow-list.
 *
 * <p>The reachable set of hosts is a security decision, so it must not be able to change silently: any
 * edit to {@link ArenaAllowlist} that widens it (a new host, a "convenience" wildcard, an extra
 * third-party entry) fails here and forces the change to be deliberate and visible in review.</p>
 *
 * <p>The Google sign-in and CAPTCHA groups are enabled in this build, and every host in them is pinned
 * below - including the two facts that matter most: {@code accounts.google.com} is the <em>only</em>
 * third-party host that may become a page, and every other third-party host is asset-only and
 * path-scoped.</p>
 */
public class AllowlistRegressionTest {

    private static final List<String> EXPECTED_FIRST_PARTY_HOSTS = Arrays.asList(
            "arena.ai",
            "www.arena.ai",
            "auth.arena.ai",
            "cdn.arena.ai",
            "agent.arena.ai",
            "code.arena.ai",
            "indexable.arena.ai",
            "blog.arena.ai",
            "news.arena.ai",
            "help.arena.ai",
            "status.arena.ai",
            "trust.arena.ai");

    @Test
    public void startPageIsArenaAi() {
        assertEquals("https://arena.ai/", ArenaAllowlist.PRIMARY_URL);
        assertTrue(ArenaAllowlist.productionConfig().evaluateNavigation(ArenaAllowlist.PRIMARY_URL)
                .isAllowed());
    }

    @Test
    public void firstPartyHostsAreExactlyTheReviewedSet() {
        assertEquals(EXPECTED_FIRST_PARTY_HOSTS, ArenaAllowlist.FIRST_PARTY_HOSTS);

        // The enforced set is exactly "the reviewed first-party hosts" plus "the reviewed third-party
        // hosts": nothing is dropped, nothing is duplicated, and no entry can exist outside those two
        // lists - a host that is not in this assertion cannot be reached by the app.
        List<String> expectedEnforced = new ArrayList<String>(EXPECTED_FIRST_PARTY_HOSTS);
        expectedEnforced.addAll(ArenaAllowlist.thirdPartyHosts());
        assertEquals("the whole enforced allow-list", expectedEnforced, ArenaAllowlist.allowedHosts());

        Set<String> unique = new LinkedHashSet<String>(ArenaAllowlist.allowedHosts());
        assertEquals("no host may be listed twice", ArenaAllowlist.allowedHosts().size(), unique.size());
    }

    @Test
    public void theThirdPartyHostsAreExactlyTheReviewedSet() {
        assertEquals("this is the whole third-party surface of the app",
                Arrays.asList(
                        "accounts.google.com",
                        "ssl.gstatic.com",
                        "www.gstatic.com",
                        "apis.google.com",
                        "fonts.gstatic.com",
                        "lh3.googleusercontent.com",
                        "www.google.com",
                        "www.recaptcha.net",
                        "challenges.cloudflare.com",
                        // Agent Mode connectors: authorize endpoint only (see connectorEntries()).
                        "github.com",
                        "api.notion.com",
                        "linear.app",
                        "api.supabase.com",
                        "vercel.com",
                        "www.dropbox.com"),
                ArenaAllowlist.thirdPartyHosts());
    }

    @Test
    public void signInAndCaptchaGroupsAreEnabledWithTheReviewedSwitches() {
        assertTrue("sign-in with a Google account is enabled in this build",
                ArenaAllowlist.ENABLE_GOOGLE_SIGN_IN);
        assertTrue("CAPTCHA widgets are enabled in this build", ArenaAllowlist.ENABLE_CAPTCHA_WIDGETS);
    }

    @Test
    public void onlyTheReviewedThirdPartyHostsMayEverBecomeAPage() {
        PolicyConfig config = ArenaAllowlist.productionConfig();
        List<String> documentCapableThirdParties = Arrays.asList("accounts.google.com",
                "github.com", "api.notion.com", "linear.app", "api.supabase.com", "vercel.com",
                "www.dropbox.com");
        for (AllowEntry entry : ArenaAllowlist.activeEntries()) {
            if (entry.isProduct()) {
                continue;
            }
            boolean documentCapable = !entry.isResourceOnly();
            assertEquals("unexpected document-capable third party: " + entry.host(),
                    documentCapable, documentCapableThirdParties.contains(entry.host()));
            if ("accounts.google.com".equals(entry.host())) {
                assertEquals("the Google sign-in host keeps its unpinned sign-in role",
                        AllowEntry.Role.SIGN_IN, entry.role());
                continue;
            }
            assertEquals("unexpected role for " + entry.host(),
                    documentCapable ? AllowEntry.Role.CONNECTOR : AllowEntry.Role.ASSET, entry.role());
        }
        // ... and the sign-in host is the one the flow actually uses.
        assertTrue(ArenaAllowlist.isSignInHost("accounts.google.com"));
        assertFalse(ArenaAllowlist.isSignInHost("www.google.com"));
        assertFalse(ArenaAllowlist.isSignInHost("arena.ai"));
        assertTrue(config.isSignInNavigation("https://accounts.google.com/signin/v2/identifier"));
        assertFalse(config.isSignInNavigation("https://arena.ai/"));
        assertTrue(config.isProductNavigation("https://arena.ai/leaderboard"));
        assertFalse(config.isProductNavigation("https://accounts.google.com/signin"));
    }

    @Test
    public void everyThirdPartyAssetEntryIsResourceOnlyAndReviewableAtItsPathScope() {
        // Everything third-party is asset-only: none of these hosts can ever be the app's page.
        for (AllowEntry entry : ArenaAllowlist.activeEntries()) {
            // Product, Google sign-in and connector-authorize entries are the reviewed page-capable
            // classes; every other third-party entry must be asset-only.
            if (entry.isProduct() || entry.isSignIn() || entry.isConnector()) {
                continue;
            }
            assertTrue("third-party entry must be resource only (never a page): " + entry.host(),
                    entry.isResourceOnly());
            for (String prefix : entry.pathPrefixes()) {
                assertTrue("prefix must start with '/': " + prefix, prefix.startsWith("/"));
            }
        }

        // Path restrictions are the rule; the hosts below are the reviewed exceptions, and they are
        // exceptions for one reason: Google serves its identity UI from content-hashed paths that change
        // with every deployment, so any prefix list would break sign-in again (it did once - see
        // TEST_REPORT.md). Widening this set must be a deliberate edit here as well.
        List<String> deliberatelyUnrestricted = Arrays.asList("ssl.gstatic.com", "www.gstatic.com",
                // First-party asset-only entries: Google's two hosts are unrestricted for the reason
                // above; these three are demoted page hosts whose paths are not the point (they are
                // never navigable at all).
                "indexable.arena.ai", "blog.arena.ai", "news.arena.ai");
        for (AllowEntry entry : ArenaAllowlist.activeEntries()) {
            if (entry.isProduct() || entry.isSignIn() || entry.isConnector()
                    || entry.isPathRestricted()) {
                continue;
            }
            {
                assertTrue("a path-unrestricted third-party host must be a reviewed exception: " + entry.host(),
                        deliberatelyUnrestricted.contains(entry.host()));
                assertTrue("a path-unrestricted host must still be asset-only: " + entry.host(),
                        entry.isResourceOnly());
            }
        }
        for (String host : deliberatelyUnrestricted) {
            assertTrue("the reviewed exception list must match reality: " + host,
                    ArenaAllowlist.allowedHosts().contains(host));
        }
    }

    /**
     * The two groups want the same Google asset host, and a host may only appear once in the policy
     * ({@code findByHost} returns the first match), so the merged entry must carry every path.
     */
    @Test
    public void mergedEntriesKeepEveryPathPrefix() {
        PolicyConfig config = ArenaAllowlist.productionConfig();
        List<String> hosts = ArenaAllowlist.allowedHosts();
        assertEquals("a host must never be listed twice: " + hosts,
                hosts.size(), new java.util.HashSet<String>(hosts).size());

        // Two groups want the same host (sign-in assets and the reCAPTCHA bundles both live on
        // www.gstatic.com): whichever is wider must survive the merge, or one flow silently loses its
        // assets - which is exactly the class of bug this whole change is about.
        for (String path : Arrays.asList("/accounts/t.js", "/gsi/c.js", "/recaptcha/r.js",
                "/_/mss/boq-identity/_/js/k=boq-identity.AccountsSignInUi.en.x/y")) {
            assertTrue("merged path must stay reachable: " + path,
                    config.evaluateRequest("https://www.gstatic.com" + path).isAllowed());
        }
        assertTrue("the wider (unrestricted) entry must win the merge",
                config.findByHost("www.gstatic.com").pathPrefixes().isEmpty());

        // The merge itself: two restricted groups union their prefixes, and an unrestricted entry stays
        // unrestricted instead of being narrowed by the other group.
        List<AllowEntry> union = ArenaAllowlist.merge(Arrays.asList(
                Arrays.asList(AllowEntry.resourceOnly("merge.example", Arrays.asList("/a/"), "one")),
                Arrays.asList(AllowEntry.resourceOnly("merge.example", Arrays.asList("/b/"), "two"))));
        assertEquals(Arrays.asList("/a/", "/b/"), union.get(0).pathPrefixes());

        List<AllowEntry> widened = ArenaAllowlist.merge(Arrays.asList(
                Arrays.asList(AllowEntry.resourceOnly("merge.example", Arrays.asList("/a/"), "one")),
                Arrays.asList(AllowEntry.resourceOnly("merge.example",
                        java.util.Collections.<String>emptyList(), "two"))));
        assertTrue("an unrestricted group must not be narrowed by a restricted one",
                widened.get(0).pathPrefixes().isEmpty());
        assertEquals("and the host must not be duplicated", 1, widened.size());
    }

    /**
     * A connector entry is page-capable, so its path pin is the only thing standing between "the
     * provider's OAuth authorize endpoint" and "the provider's whole site". These assertions are the
     * guard for that: every connector entry is path-restricted, and the provider's ordinary pages are
     * refused.
     */
    @Test
    public void connectorEntriesArePageCapableOnlyAtTheirAuthorizeEndpoint() {
        PolicyConfig config = ArenaAllowlist.productionConfig();
        assertTrue("connectors are enabled in this build", ArenaAllowlist.ENABLE_AGENT_CONNECTORS);

        // The documented authorize endpoint of every connector is reachable as a page...
        String[][] authorizeUrls = {
                {"github.com", "https://github.com/login/oauth/authorize?client_id=x&state=y"},
                {"api.notion.com", "https://api.notion.com/v1/oauth/authorize?owner=user&client_id=x"},
                {"linear.app", "https://linear.app/oauth/authorize?client_id=x&response_type=code"},
                {"api.supabase.com", "https://api.supabase.com/v1/oauth/authorize?client_id=x"},
                {"vercel.com", "https://vercel.com/oauth/authorize?client_id=x"},
                {"www.dropbox.com", "https://www.dropbox.com/oauth2/authorize?client_id=x"},
        };
        for (String[] row : authorizeUrls) {
            AllowEntry entry = null;
            for (AllowEntry candidate : ArenaAllowlist.activeEntries()) {
                if (candidate.host().equals(row[0])) {
                    entry = candidate;
                }
            }
            assertNotNull("connector host must be allow-listed: " + row[0], entry);
            assertTrue("a connector entry must be path-pinned: " + row[0], entry.isPathRestricted());
            assertTrue("connector role: " + row[0], entry.isConnector());
            assertTrue("authorize endpoint must be a page: " + row[1],
                    config.evaluateNavigation(row[1]).isAllowed());
            assertTrue("authorize endpoint counts as a sign-in step: " + row[1],
                    config.isSignInNavigation(row[1]));
        }

        // ...and the provider's ordinary pages are not: dashboards, repositories, APIs, token endpoints.
        String[] refusedPages = {
                "https://github.com/settings/tokens",
                "https://github.com/torvalds/linux",
                "https://github.com/login/oauth/access_token",
                "https://www.notion.so/my-integrations",
                "https://api.notion.com/v1/users",
                "https://linear.app/settings",
                "https://supabase.com/dashboard",
                "https://api.supabase.com/v1/projects",
                "https://vercel.com/dashboard",
                "https://www.dropbox.com/home",
                "https://www.dropbox.com/oauth2/token",
        };
        for (String url : refusedPages) {
            assertFalse("must not be a page: " + url, config.evaluateNavigation(url).isAllowed());
            assertFalse("must not be a sign-in step: " + url, config.isSignInNavigation(url));
        }

        // The authorize path is pinned exactly, not as a prefix: GitHub serves its token endpoint as a
        // sibling of the authorize endpoint, and a prefix pin would have made that a displayable page.
        // (Caught by running the policy, not by reading it.)
        for (String sibling : new String[]{
                "https://github.com/login/oauth/access_token",
                "https://github.com/login/oauth/authorize/../access_token",
                "https://api.notion.com/v1/oauth/token",
                "https://api.supabase.com/v1/oauth/token"}) {
            assertFalse("a sibling of the authorize endpoint must not be a page: " + sibling,
                    config.evaluateNavigation(sibling).isAllowed());
        }

        // A connector page is never an Arena.ai product page, so it can never be treated as one by the
        // sidebar guard or by the "is the user still on the site?" checks.
        assertFalse(config.isProductNavigation("https://github.com/login/oauth/authorize?client_id=x"));
        assertFalse(config.isProductNavigation("https://api.notion.com/v1/oauth/authorize?client_id=x"));
    }

    /**
     * The three first-party hosts that are no longer browsable (checked 2026-11-14) stay reachable as
     * <em>assets</em> only.
     *
     * <p>{@code blog.} and {@code news.arena.ai} answer Cloudflare 522 and the site now serves the blog
     * from {@code arena.ai/blog}; {@code indexable.arena.ai} is an object bucket with no browsable root.
     * Presenting any of them as the app's page would show a dead or meaningless screen, so they are
     * demoted rather than removed: whatever the site still references still loads, and the page surface
     * only ever contains live product hosts.</p>
     */
    @Test
    public void demotedFirstPartyHostsAreAssetsOnly() {
        PolicyConfig config = ArenaAllowlist.productionConfig();
        String[] demoted = {"indexable.arena.ai", "blog.arena.ai", "news.arena.ai"};
        for (String host : demoted) {
            assertTrue("still allow-listed for assets: " + host,
                    ArenaAllowlist.FIRST_PARTY_HOSTS.contains(host));
            assertFalse("must never become the app's page: " + host,
                    config.evaluateNavigation("https://" + host + "/").isAllowed());
            // Asset-only hosts deliberately stay embeddable - that is the same rule that lets a CAPTCHA
            // widget render in an iframe - so the guarantee is "never the app's own page", not "never a
            // frame".
            assertTrue("asset semantics: still embeddable: " + host,
                    config.evaluateSubdocument("https://" + host + "/").isAllowed());
            assertTrue("assets must still load: " + host,
                    config.evaluateRequest("https://" + host + "/x.png").isAllowed());
        }
        assertEquals("the demoted first-party hosts, pinned",
                Arrays.asList("indexable.arena.ai", "blog.arena.ai", "news.arena.ai"),
                ArenaAllowlist.demotedFirstPartyHosts());
        assertEquals("page-capable third parties, pinned",
                Arrays.asList("accounts.google.com", "github.com", "api.notion.com", "linear.app",
                        "api.supabase.com", "vercel.com", "www.dropbox.com"),
                ArenaAllowlist.pageCapableThirdPartyHosts());

        // The live blog, help, status and trust centres remain pages.
        for (String url : new String[]{"https://arena.ai/blog/connectors-in-agent-mode",
                "https://help.arena.ai/articles/6934758149-connect-your-apps-to-arena",
                "https://status.arena.ai/", "https://trust.arena.ai/"}) {
            assertTrue("must still be a page: " + url, config.evaluateNavigation(url).isAllowed());
        }
    }

    @Test
    public void turningTheSwitchesOffRemovesEveryGoogleHost() {
        // The groups themselves describe what would be reachable if enabled; the *active* set is what
        // matters, and it is derived from the switches. Assert the groups are well-formed so that
        // flipping a switch back cannot accidentally enable something unreviewed.
        for (AllowEntry entry : ArenaAllowlist.signInEntries()) {
            assertTrue("sign-in group must be pinned to one document host",
                    entry.isSignIn() || entry.isResourceOnly());
        }
        for (AllowEntry entry : ArenaAllowlist.captchaEntries()) {
            assertTrue("CAPTCHA group must be asset-only", entry.isResourceOnly());
        }
    }

    @Test
    public void noEntryUsesWildcardsOrBroaderMatches() {
        for (AllowEntry entry : ArenaAllowlist.activeEntries()) {
            String host = entry.host();
            assertFalse("wildcards are not supported: " + host, host.contains("*"));
            assertFalse("wildcards are not supported: " + host, host.contains("?"));
            assertFalse("no leading dots: " + host, host.startsWith("."));
            assertEquals("entries must already be canonical: " + host,
                    host, UrlPolicy.canonicalizeHost(host));
            assertNotNull(entry.purpose());
            assertFalse("every entry must document why it is reachable", entry.purpose().isEmpty());
        }
    }

    @Test
    public void excludedHostsAreNotReachable() {
        String[] excluded = {
                "lmarena.ai",
                "www.lmarena.ai",
                "mail.arena.ai",
                "webmail.arena.ai",
                "cpanel.arena.ai",
                "autodiscover.arena.ai",
                "mta-sts.arena.ai",
                "openpgpkey.arena.ai",
                "okta.arena.ai",
                "www.okta.arena.ai",
                "api.preview.arena.ai",
                "api-k8s.preview.arena.ai",
                "portal.api.preview.arena.ai",
                "portal-k8s.api.preview.arena.ai",
                "cn.arena.ai",
                "arena.com",
                "arena-ai.com",
                "arenaai.com",
                "getarena.ai",
                // Google hosts that are NOT part of the sign-in flow and must stay unreachable:
                "mail.google.com",
                "drive.google.com",
                "docs.google.com",
                "play.google.com",
                "translate.google.com",
                "myaccount.google.com",
                "oauth2.googleapis.com",
                "www.googleapis.com",
                "googleusercontent.com",
                "lh4.googleusercontent.com",
                "ogs.google.com",
                "accounts.google.com.evil.example",
        };
        PolicyConfig config = ArenaAllowlist.productionConfig();
        for (String host : excluded) {
            PolicyResult result = config.evaluateNavigation("https://" + host + "/x");
            assertFalse("must not be reachable: " + host, result.isAllowed());
        }
    }

    @Test
    public void describeIsStableAndReadable() {
        String description = ArenaAllowlist.productionConfig().describe();
        assertTrue(description.contains("start=https://arena.ai/"));
        for (String host : EXPECTED_FIRST_PARTY_HOSTS) {
            assertTrue("description must mention " + host, description.contains("allow " + host));
        }
    }
}
