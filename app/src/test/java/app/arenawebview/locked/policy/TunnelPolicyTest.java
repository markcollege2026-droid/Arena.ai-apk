package app.arenawebview.locked.policy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/**
 * The tunnel decision is the one the egress proxy makes for every connection the WebView opens, including
 * WebSockets. It has to be at least as strict as the navigation decision on the host, and stricter on the
 * port: there is exactly one, 443, and no path dimension because a tunnel has no path.
 */
public class TunnelPolicyTest {

    private static final PolicyConfig POLICY = ArenaAllowlist.productionConfig();

    @Test
    public void everyAllowListedHostMayBeTunnelledOnPort443() {
        for (AllowEntry entry : ArenaAllowlist.activeEntries()) {
            PolicyResult result = POLICY.evaluateTunnel(entry.host(), 443);
            assertTrue(entry.host() + " -> " + result.describe(), result.isAllowed());
            assertEquals(entry.host(), result.canonicalHost());
            assertEquals(entry.host(), result.matchedEntry().host());
            assertEquals("connect://" + entry.host() + ":443", result.rawUrl());
        }
    }

    @Test
    public void onlyPort443IsEverAllowed() {
        int[] ports = {1, 80, 8080, 8443, 442, 444, 4443, 65535, 0, -1, 70000};
        for (int port : ports) {
            PolicyResult result = POLICY.evaluateTunnel("arena.ai", port);
            assertFalse("arena.ai:" + port, result.isAllowed());
            assertEquals("arena.ai:" + port, BlockReason.PORT_NOT_ALLOWED, result.reason());
            assertEquals("arena.ai", result.canonicalHost());
        }
    }

    @Test
    public void hostIsLowerCasedAndNothingElse() {
        assertTrue(POLICY.evaluateTunnel("ARENA.AI", 443).isAllowed());
        assertEquals("arena.ai", POLICY.evaluateTunnel("ARENA.AI", 443).canonicalHost());
        assertEquals(BlockReason.INVALID_HOST, POLICY.evaluateTunnel("arena.ai.", 443).reason());
        assertEquals(BlockReason.INVALID_HOST, POLICY.evaluateTunnel(" arena.ai", 443).reason());
        assertEquals(BlockReason.INVALID_HOST, POLICY.evaluateTunnel("arena.ai ", 443).reason());
        assertEquals(BlockReason.INVALID_HOST, POLICY.evaluateTunnel("arena.ai/", 443).reason());
        assertEquals(BlockReason.INVALID_HOST, POLICY.evaluateTunnel("arena.ai:443", 443).reason());
        assertEquals(BlockReason.INVALID_HOST, POLICY.evaluateTunnel("arena%2eai", 443).reason());
        assertEquals(BlockReason.INVALID_HOST, POLICY.evaluateTunnel("arena.ai\r\nX: y", 443).reason());
    }

    @Test
    public void externalBackendHostsAreAllowedAsTunnelsWhileRefusedAsPages() {
        List<String> hosts = Arrays.asList(
                "evil.example", "arena.ai.evil.example", "evilarena.ai", "arena.ai-evil.example",
                "www.arena.ai.evil.example", "xn--rena-4qa.ai", "google.com", "google.co.il",
                "mail.google.com", "accounts.google.com.evil.example", "www.googleapis.com",
                "oauth2.googleapis.com", "gstatic.com", "recaptcha.net", "fonts.googleapis.com",
                "cdn.arena.ai.example", "arena.ai.localhost", "arena.ai.local");
        for (String host : hosts) {
            PolicyResult tunnel = POLICY.evaluateTunnel(host, 443);
            assertTrue(host + " tunnel must be allowed on 443", tunnel.isAllowed());
            assertFalse(host + " must still be refused as a top-level page",
                    POLICY.evaluateNavigation("https://" + host + "/").isAllowed());
            assertEquals(host, BlockReason.HOST_NOT_ALLOWED,
                    POLICY.evaluateNavigation("https://" + host + "/").reason());
        }
    }

    @Test
    public void addressesAndLocalNamesAreNeverTunnelled() {
        assertEquals(BlockReason.IP_LITERAL_HOST, POLICY.evaluateTunnel("[::1]", 443).reason());
        assertEquals(BlockReason.IP_LITERAL_HOST, POLICY.evaluateTunnel("[2606:4700::1]", 443).reason());
        assertEquals(BlockReason.IP_LITERAL_HOST, POLICY.evaluateTunnel("arena.ai]", 443).reason());
        assertEquals(BlockReason.INVALID_HOST, POLICY.evaluateTunnel("127.0.0.1", 443).reason());
        assertEquals(BlockReason.INVALID_HOST, POLICY.evaluateTunnel("10.0.0.1", 443).reason());
        assertEquals(BlockReason.INVALID_HOST, POLICY.evaluateTunnel("0x7f000001", 443).reason());
        assertEquals(BlockReason.INVALID_HOST, POLICY.evaluateTunnel("2130706433", 443).reason());
        assertEquals(BlockReason.INVALID_HOST, POLICY.evaluateTunnel("localhost", 443).reason());
        assertEquals(BlockReason.INVALID_HOST, POLICY.evaluateTunnel("metadata", 443).reason());
        assertEquals(BlockReason.INVALID_HOST, POLICY.evaluateTunnel("arena_ai.example", 443).reason());
    }

    @Test
    public void userInfoAndNonAsciiAreRefusedBeforeAnyLookup() {
        assertEquals(BlockReason.USERINFO_PRESENT, POLICY.evaluateTunnel("arena.ai@evil.example", 443).reason());
        assertEquals(BlockReason.USERINFO_PRESENT, POLICY.evaluateTunnel("user:pass@arena.ai", 443).reason());
        assertEquals(BlockReason.NON_ASCII_HOST, POLICY.evaluateTunnel("\u0430rena.ai", 443).reason());
        assertEquals(BlockReason.NON_ASCII_HOST, POLICY.evaluateTunnel("arena.ai\u3002", 443).reason());
        assertEquals(BlockReason.NON_ASCII_HOST, POLICY.evaluateTunnel("\uff41rena.ai", 443).reason());
    }

    @Test
    public void emptyAndNullHostsAreRefused() {
        assertEquals(BlockReason.EMPTY_HOST, POLICY.evaluateTunnel("", 443).reason());
        assertEquals(BlockReason.EMPTY_HOST, POLICY.evaluateTunnel(null, 443).reason());
        assertEquals("connect://:443", POLICY.evaluateTunnel(null, 443).rawUrl());
        PolicyResult noConfig = UrlPolicy.evaluateTunnel("arena.ai", 443, null);
        assertFalse(noConfig.isAllowed());
        assertEquals(BlockReason.INVALID_HOST, noConfig.reason());
    }

    @Test
    public void refusedResultsCarryEnoughForTheDiagnosticsReport() {
        PolicyResult result = POLICY.evaluateTunnel("arena.ai", 8443);
        assertEquals("arena.ai", result.canonicalHost());
        assertEquals("connect://arena.ai:8443", result.rawUrl());
        assertNotNull(result.describe());
        assertTrue(result.describe().contains("PORT_NOT_ALLOWED"));
    }

    /**
     * On every URL in the shared case table, whenever the tunnel refuses the URL's host, both navigation
     * and subresource requests refuse the URL as well.
     */
    @Test
    public void tunnelDecisionAgreesWithHostValidation() {
        List<PolicyCases.Case> cases = new java.util.ArrayList<PolicyCases.Case>(PolicyCases.all());
        cases.addAll(PolicyCases.withGoogleSignInAndCaptchaEnabled());
        int compared = 0;
        for (PolicyCases.Case c : cases) {
            String host = hostOf(c.url);
            if (host == null) {
                continue;
            }
            PolicyResult navigation = POLICY.evaluateNavigation(c.url);
            PolicyResult tunnel = POLICY.evaluateTunnel(host, 443);
            compared++;
            if (!tunnel.isAllowed()) {
                assertFalse("tunnel refused " + host + " but navigation allowed " + c.url, navigation.isAllowed());
            }
        }
        assertTrue("the case table should give plenty of comparisons, got " + compared, compared > 100);
    }

    /**
     * A naive host extraction for the table rows: enough to compare decisions, deliberately not the policy's.
     * Tabs and newlines are removed first because that is what the browser does before it connects, so the
     * host the proxy sees never contains them.
     */
    private static String hostOf(String raw) {
        if (raw == null) {
            return null;
        }
        String url = raw.replace("\t", "").replace("\n", "").replace("\r", "");
        int scheme = url.indexOf("://");
        if (scheme < 0) {
            return null;
        }
        String rest = url.substring(scheme + 3);
        int end = rest.length();
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c == '/' || c == '?' || c == '#' || c == '\\') {
                end = i;
                break;
            }
        }
        String authority = rest.substring(0, end);
        int at = authority.lastIndexOf('@');
        if (at >= 0) {
            authority = authority.substring(at + 1);
        }
        if (authority.startsWith("[")) {
            return authority;
        }
        int colon = authority.lastIndexOf(':');
        return colon >= 0 ? authority.substring(0, colon) : authority;
    }
}
