package app.arenawebview.locked.policy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The connector window is the narrow exception to "a page is either Arena.ai or Google".
 *
 * <p>These tests pin the two halves of that exception: the provider login pages are real documents
 * inside the in-app OAuth window, and they can never become the app's own page. They also cover the
 * first-party staging hop that Arena writes into a blank pop-up before redirecting to the provider.</p>
 */
public class ConnectorWindowPolicyTest {

    private static final PolicyConfig CONFIG = ArenaAllowlist.productionConfig();

    @Test
    public void providerAuthorizeUrlsAreDocumentsInTheOAuthWindow() {
        String[] urls = {
                "https://github.com/login/oauth/authorize?client_id=arena",
                "https://github.com/login?client_id=arena",
                "https://api.notion.com/v1/oauth/authorize?client_id=arena",
                "https://app.notion.com/install-integration?client_id=arena",
                "https://linear.app/oauth/authorize?client_id=arena",
                "https://api.supabase.com/v1/oauth/authorize?client_id=arena",
                "https://vercel.com/oauth/authorize?client_id=arena",
                "https://www.dropbox.com/oauth2/authorize?client_id=arena",
        };
        for (String url : urls) {
            assertTrue("window must accept " + url, CONFIG.evaluateNavigation(url).isAllowed());
            assertTrue("router must recognise " + url, CONFIG.isConnectorNavigation(url));
        }
    }

    @Test
    public void noConnectorPageCanReplaceTheMainView() {
        String[] urls = {
                "https://github.com/login/oauth/authorize?client_id=arena",
                "https://github.com/login?client_id=arena",
                "https://api.notion.com/v1/oauth/authorize?client_id=arena",
                "https://app.notion.com/install-integration?client_id=arena",
                "https://www.notion.so/login",
                "https://linear.app/oauth/authorize?client_id=arena",
                "https://api.supabase.com/v1/oauth/authorize?client_id=arena",
                "https://supabase.com/login",
                "https://vercel.com/oauth/authorize?client_id=arena",
                "https://www.dropbox.com/oauth2/authorize?client_id=arena",
        };
        for (String url : urls) {
            PolicyResult main = CONFIG.evaluatePrimaryDocument(url);
            assertFalse("main WebView must refuse " + url, main.isAllowed());
            assertEquals(BlockReason.CONNECTOR_WINDOW_ONLY, main.reason());
            assertEquals("the host is still named in the refusal log",
                    CONFIG.evaluateNavigation(url).canonicalHost(), main.canonicalHost());
        }
        assertTrue("the product itself is unaffected",
                CONFIG.evaluatePrimaryDocument("https://arena.ai/agent").isAllowed());
        assertTrue("Google sign-in is still allowed for a redirect-based flow",
                CONFIG.evaluatePrimaryDocument("https://accounts.google.com/signin/v2/identifier").isAllowed());
    }

    @Test
    public void firstPartyStagingPredicateIsNarrow() {
        assertTrue(CONFIG.isHandoffStagingNavigation(
                "https://arena.ai/nextjs-api/sign-in/google/popup-complete?state=x"));
        assertTrue(CONFIG.isHandoffStagingNavigation(
                "https://arena.ai/api/connectors/github/start"));
        assertTrue(CONFIG.isHandoffStagingNavigation(
                "https://arena.ai/api/coding/github/connection"));
        assertTrue(CONFIG.isHandoffStagingNavigation(
                "https://arena.ai/?connector=github&state=abc"));

        assertFalse("ordinary product pages are not staging pages",
                CONFIG.isHandoffStagingNavigation("https://arena.ai/agent"));
        assertFalse("leaderboard links are not staging pages",
                CONFIG.isHandoffStagingNavigation("https://arena.ai/leaderboard/agent"));
        assertFalse("a chat link is not a staging page",
                CONFIG.isHandoffStagingNavigation("https://arena.ai/c/123"));
        assertFalse("a provider page is not a first-party staging page",
                CONFIG.isHandoffStagingNavigation("https://github.com/login/oauth/authorize"));
        assertFalse("a look-alike is never a staging page",
                CONFIG.isHandoffStagingNavigation("https://arena.ai.evil.example/oauth/start"));
        assertFalse("null is not a staging page", CONFIG.isHandoffStagingNavigation(null));
    }

    @Test
    public void connectorAssetsCannotBecomePages() {
        assertFalse("GitHub assets are resource-only",
                CONFIG.evaluateNavigation("https://github.githubassets.com/abc.js").isAllowed());
        assertTrue("but they can be fetched",
                CONFIG.evaluateRequest("https://github.githubassets.com/abc.js").isAllowed());
        assertFalse("an arbitrary host is not a connector page",
                CONFIG.isConnectorNavigation("https://slack.com/oauth/v2/authorize"));
        assertTrue("fundamental HTTPS restrictions still apply",
                CONFIG.evaluateNavigation("http://github.com/login/oauth/authorize").reason()
                        == BlockReason.SCHEME_NOT_HTTPS);
    }
}
