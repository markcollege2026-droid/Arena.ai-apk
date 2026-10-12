package app.arenawebview.locked.policy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/**
 * Pins the allow-list.
 *
 * <p>The reachable set of hosts is a security decision, so it must not be able to change silently: any
 * edit to {@link ArenaAllowlist} that widens it (a new host, a "convenience" wildcard, an enabled
 * third-party entry) fails here and forces the change to be deliberate and visible in review.</p>
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
        assertEquals(EXPECTED_FIRST_PARTY_HOSTS, ArenaAllowlist.allowedHosts());
    }

    @Test
    public void thirdPartyEntriesAreDisabledByDefault() {
        assertFalse("third-party capability entries must stay opt-in",
                ArenaAllowlist.ENABLE_THIRD_PARTY_CAPABILITY_ENTRIES);

        PolicyConfig config = ArenaAllowlist.productionConfig();
        for (AllowEntry entry : ArenaAllowlist.capabilityEntries()) {
            assertEquals("capability host must not be reachable by default: " + entry.host(),
                    BlockReason.HOST_NOT_ALLOWED,
                    config.evaluateRequest("https://" + entry.host() + entry.pathPrefixes().get(0)).reason());
        }
    }

    @Test
    public void capabilityEntriesArePathScopedAndResourceOnly() {
        for (AllowEntry entry : ArenaAllowlist.capabilityEntries()) {
            assertTrue("capability entry must be path scoped: " + entry.host(), entry.isPathRestricted());
            assertTrue("capability entry must be resource only: " + entry.host(), entry.isResourceOnly());
            for (String prefix : entry.pathPrefixes()) {
                assertTrue("prefix must start with '/': " + prefix, prefix.startsWith("/"));
            }
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
