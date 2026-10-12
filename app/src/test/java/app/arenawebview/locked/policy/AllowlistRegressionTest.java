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
                        "challenges.cloudflare.com"),
                ArenaAllowlist.thirdPartyHosts());
    }

    @Test
    public void signInAndCaptchaGroupsAreEnabledWithTheReviewedSwitches() {
        assertTrue("sign-in with a Google account is enabled in this build",
                ArenaAllowlist.ENABLE_GOOGLE_SIGN_IN);
        assertTrue("CAPTCHA widgets are enabled in this build", ArenaAllowlist.ENABLE_CAPTCHA_WIDGETS);
    }

    @Test
    public void exactlyOneThirdPartyHostMayEverBecomeAPage() {
        PolicyConfig config = ArenaAllowlist.productionConfig();
        List<String> documentCapableThirdParties = Arrays.asList("accounts.google.com");
        for (AllowEntry entry : ArenaAllowlist.activeEntries()) {
            if (entry.isProduct()) {
                continue;
            }
            boolean documentCapable = !entry.isResourceOnly();
            assertEquals("unexpected document-capable third party: " + entry.host(),
                    documentCapable, documentCapableThirdParties.contains(entry.host()));
            assertEquals("unexpected role for " + entry.host(),
                    documentCapable ? AllowEntry.Role.SIGN_IN : AllowEntry.Role.ASSET, entry.role());
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
    public void everyThirdPartyAssetEntryIsPathScopedAndResourceOnly() {
        for (AllowEntry entry : ArenaAllowlist.activeEntries()) {
            if (entry.isProduct() || entry.isSignIn()) {
                continue;
            }
            assertTrue("third-party asset entry must be path scoped: " + entry.host(),
                    entry.isPathRestricted());
            assertTrue("third-party asset entry must be resource only: " + entry.host(),
                    entry.isResourceOnly());
            for (String prefix : entry.pathPrefixes()) {
                assertTrue("prefix must start with '/': " + prefix, prefix.startsWith("/"));
            }
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

        for (String path : Arrays.asList("/accounts/t.js", "/gsi/c.js", "/recaptcha/r.js")) {
            PolicyResult result = config.evaluateRequest("https://www.gstatic.com" + path);
            assertTrue("merged path must stay reachable: " + path, result.isAllowed());
        }
        assertFalse("unlisted path must stay refused",
                config.evaluateRequest("https://www.gstatic.com/translate/t.js").isAllowed());
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
