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
 * <p>The Google sign-in, CAPTCHA and Agent Mode connector groups are enabled in this build. Every host
 * is pinned below, together with what it is allowed to be: a product page, a sign-in window page, a
 * connector window page, or an asset that can never be a document.</p>
 */
public class AllowlistRegressionTest {

    private static final List<String> EXPECTED_FIRST_PARTY_HOSTS = Arrays.asList(
            "arena.ai",
            "www.arena.ai",
            "auth.arena.ai",
            "cdn.arena.ai",
            "indexable.arena.ai",
            "agent.arena.ai",
            "code.arena.ai",
            "help.arena.ai",
            "status.arena.ai",
            "trust.arena.ai");

    private static final List<String> CONNECTOR_HOSTS = Arrays.asList(
            "github.com",
            "api.notion.com",
            "app.notion.com",
            "www.notion.so",
            "linear.app",
            "api.supabase.com",
            "supabase.com",
            "vercel.com",
            "www.dropbox.com");

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
                        "github.com",
                        "github.githubassets.com",
                        "api.notion.com",
                        "app.notion.com",
                        "www.notion.so",
                        "linear.app",
                        "api.supabase.com",
                        "supabase.com",
                        "vercel.com",
                        "www.dropbox.com"),
                ArenaAllowlist.thirdPartyHosts());
    }

    @Test
    public void allCapabilityGroupsAreEnabledWithTheReviewedSwitches() {
        assertTrue("sign-in with a Google account is enabled in this build",
                ArenaAllowlist.ENABLE_GOOGLE_SIGN_IN);
        assertTrue("CAPTCHA widgets are enabled in this build", ArenaAllowlist.ENABLE_CAPTCHA_WIDGETS);
        assertTrue("Agent Mode connector hand-offs are enabled in this build",
                ArenaAllowlist.ENABLE_AGENT_CONNECTORS);
    }

    @Test
    public void onlyTheReviewedThirdPartiesMayBecomePages() {
        PolicyConfig config = ArenaAllowlist.productionConfig();
        for (AllowEntry entry : ArenaAllowlist.activeEntries()) {
            if (EXPECTED_FIRST_PARTY_HOSTS.contains(entry.host())) {
                continue;
            }
            if (entry.isSignIn()) {
                assertEquals("unexpected sign-in host: " + entry.host(),
                        "accounts.google.com", entry.host());
            } else if (entry.isConnector()) {
                assertTrue("unexpected connector host: " + entry.host(),
                        CONNECTOR_HOSTS.contains(entry.host()));
                assertTrue("a connector page belongs to the OAuth window only: " + entry.host(),
                        config.evaluatePrimaryDocument("https://" + entry.host()
                                + entry.pathPrefixes().get(0) + "x").isAllowed() == false);
            } else {
                assertTrue("third-party host must be asset-only: " + entry.host(),
                        entry.isResourceOnly());
                assertEquals(AllowEntry.Role.ASSET, entry.role());
            }
        }
        // ... and the sign-in host is the one the flow actually uses.
        assertTrue(ArenaAllowlist.isSignInHost("accounts.google.com"));
        assertFalse(ArenaAllowlist.isSignInHost("www.google.com"));
        assertFalse(ArenaAllowlist.isSignInHost("arena.ai"));
        assertTrue(config.isSignInNavigation("https://accounts.google.com/signin/v2/identifier"));
        assertFalse(config.isSignInNavigation("https://arena.ai/"));
        assertTrue(config.isProductNavigation("https://arena.ai/leaderboard"));
        assertFalse(config.isProductNavigation("https://accounts.google.com/signin"));

        for (String host : CONNECTOR_HOSTS) {
            assertTrue(ArenaAllowlist.isConnectorHost(host));
            assertFalse("a connector host is never a Google sign-in host", ArenaAllowlist.isSignInHost(host));
            assertFalse("a connector host must never be the app's own page",
                    config.isProductNavigation("https://" + host + "/"));
        }
        assertFalse(ArenaAllowlist.isConnectorHost("accounts.google.com"));
        assertFalse(ArenaAllowlist.isConnectorHost("github.com.evil.example"));
    }

    @Test
    public void everyThirdPartyAssetEntryIsResourceOnlyAndReviewableAtItsPathScope() {
        for (AllowEntry entry : ArenaAllowlist.activeEntries()) {
            if (EXPECTED_FIRST_PARTY_HOSTS.contains(entry.host())
                    || entry.isSignIn() || entry.isConnector()) {
                continue;
            }
            assertTrue("third-party entry must be resource only (never a page): " + entry.host(),
                    entry.isResourceOnly());
            for (String prefix : entry.pathPrefixes()) {
                assertTrue("prefix must start with '/': " + prefix, prefix.startsWith("/"));
            }
        }

        // Content-hashed asset paths cannot be enumerated; these are the reviewed exceptions.
        List<String> deliberatelyUnrestricted = Arrays.asList(
                "ssl.gstatic.com", "www.gstatic.com", "github.githubassets.com");
        for (AllowEntry entry : ArenaAllowlist.activeEntries()) {
            if (EXPECTED_FIRST_PARTY_HOSTS.contains(entry.host())
                    || entry.isSignIn() || entry.isConnector() || entry.isPathRestricted()) {
                continue;
            }
            assertTrue("a path-unrestricted third-party host must be a reviewed exception: " + entry.host(),
                    deliberatelyUnrestricted.contains(entry.host()));
            assertTrue("a path-unrestricted host must still be asset-only: " + entry.host(),
                    entry.isResourceOnly());
        }
        for (String host : deliberatelyUnrestricted) {
            assertTrue("the reviewed exception list must match reality: " + host,
                    ArenaAllowlist.thirdPartyHosts().contains(host));
        }
    }

    /**
     * Two groups can want the same host (Google's {@code www.gstatic.com} serves sign-in and reCAPTCHA
     * assets), and a host may only appear once in the policy. The merged entry must carry every path.
     */
    @Test
    public void mergedEntriesKeepEveryPathPrefix() {
        PolicyConfig config = ArenaAllowlist.productionConfig();
        List<String> hosts = ArenaAllowlist.allowedHosts();
        assertEquals("a host must never be listed twice: " + hosts,
                hosts.size(), new java.util.HashSet<String>(hosts).size());

        for (String path : Arrays.asList("/accounts/t.js", "/gsi/c.js", "/recaptcha/r.js",
                "/_/mss/boq-identity/_/js/k=boq-identity.AccountsSignInUi.en.x/y")) {
            assertTrue("merged path must stay reachable: " + path,
                    config.evaluateRequest("https://www.gstatic.com" + path).isAllowed());
        }
        assertTrue("the wider (unrestricted) entry must win the merge",
                config.findByHost("www.gstatic.com").pathPrefixes().isEmpty());

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

    @Test
    public void eachSwitchOnlyControlsItsOwnGroup() {
        for (AllowEntry entry : ArenaAllowlist.signInEntries()) {
            assertTrue("sign-in group must be pinned to one document host",
                    entry.isSignIn() || entry.isResourceOnly());
        }
        for (AllowEntry entry : ArenaAllowlist.captchaEntries()) {
            assertTrue("CAPTCHA group must be asset-only", entry.isResourceOnly());
        }
        for (AllowEntry entry : ArenaAllowlist.connectorEntries()) {
            assertTrue("connector group must only contain connector pages or their assets",
                    entry.isConnector() || entry.isResourceOnly());
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
                // Removed after the live audit: both hosts returned 522 and blog moved to /blog.
                "blog.arena.ai",
                "news.arena.ai",
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
                // Connector look-alikes and services that Arena does not list as connectors:
                "github.com.evil.example",
                "api.notion.com.evil.example",
                "www.dropbox.com.evil.example",
                "slack.com",
                "mail.github.com",
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
        for (String host : CONNECTOR_HOSTS) {
            assertTrue("description must explain the connector role for " + host,
                    description.contains("allow " + host + " [connector sign-in window only]"));
        }
    }
}
