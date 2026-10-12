package app.arenawebview.locked.policy;

import static app.arenawebview.locked.policy.UrlPolicy.TrafficClass.DOCUMENT;
import static app.arenawebview.locked.policy.UrlPolicy.TrafficClass.SUBRESOURCE;

import java.util.ArrayList;
import java.util.List;

/**
 * The table of URL cases the policy is verified against.
 *
 * <p>Kept in one place so the table can be read as documentation of the intended behaviour: every row
 * is an assertion that the wrapper either allows exactly this URL or refuses it for exactly this
 * reason. Rows are added for every bypass technique that was considered (scheme smuggling, userinfo,
 * look-alike domains, parser differentials, IP literals, ports, ...).</p>
 */
public final class PolicyCases {

    /** One expected outcome. */
    public static final class Case {
        public final String name;
        public final String url;
        public final UrlPolicy.TrafficClass trafficClass;
        public final boolean allowed;
        public final BlockReason reason;

        Case(String name, String url, UrlPolicy.TrafficClass trafficClass, boolean allowed, BlockReason reason) {
            this.name = name;
            this.url = url;
            this.trafficClass = trafficClass;
            this.allowed = allowed;
            this.reason = reason;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private PolicyCases() {
        // no instances
    }

    /** Every case, with the default (shipping) policy: first-party hosts only. */
    public static List<Case> all() {
        List<Case> cases = new ArrayList<Case>();

        // ---------------------------------------------------------------------------------------
        // 1. What must keep working
        // ---------------------------------------------------------------------------------------
        both(cases, "start page", "https://arena.ai/", true, null);
        both(cases, "start page without trailing slash", "https://arena.ai", true, null);
        both(cases, "deep link inside the site", "https://arena.ai/leaderboard/chat/text", true, null);
        both(cases, "query string", "https://arena.ai/history/search?q=hello%20world", true, null);
        both(cases, "query with a raw space (Chromium encodes it)", "https://arena.ai/?q=a b", true, null);
        both(cases, "fragment is client side", "https://arena.ai/#/chat/42", true, null);
        both(cases, "fragment containing another URL is harmless", "https://arena.ai/#https://evil.example/",
                true, null);
        both(cases, "explicit default HTTPS port", "https://arena.ai:443/chat", true, null);
        both(cases, "upper case scheme and host", "HTTPS://ARENA.AI/Chat", true, null);
        both(cases, "single trailing dot (fully qualified name)", "https://arena.ai./chat", true, null);
        both(cases, "www host", "https://www.arena.ai/leaderboard", true, null);
        both(cases, "auth service host", "https://auth.arena.ai/v1/client?_clerk_js_version=5", true, null);
        both(cases, "cdn host", "https://cdn.arena.ai/_next/static/chunks/main.js", true, null);
        both(cases, "agent host", "https://agent.arena.ai/session/1", true, null);
        both(cases, "code host", "https://code.arena.ai/artifact/1", true, null);
        both(cases, "blog host", "https://blog.arena.ai/post", true, null);
        both(cases, "help host", "https://help.arena.ai/article", true, null);
        both(cases, "status host", "https://status.arena.ai/", true, null);
        both(cases, "trust host", "https://trust.arena.ai/", true, null);
        both(cases, "non-ASCII characters in the path only", "https://arena.ai/caf\u00e9", true, null);
        both(cases, "percent-encoded path", "https://arena.ai/chat/%E6%97%A5%E6%9C%AC", true, null);
        both(cases, "dot-segments in the path cannot change the host", "https://arena.ai/../evil.example",
                true, null);
        both(cases, "leading and trailing whitespace around the URL",
                "  https://arena.ai/chat  ", true, null);
        both(cases, "tab inside the scheme (Chromium removes it too)", "ht\ttps://arena.ai/", true, null);
        both(cases, "newline inside the URL (Chromium removes it too)", "https://arena.ai\n/chat", true, null);

        // `about:blank` / `about:srcdoc` are inert browser-generated documents.
        doc(cases, "about:blank as a document", "about:blank", true, null);
        sub(cases, "about:srcdoc as a frame", "about:srcdoc", true, null);
        doc(cases, "about:srcdoc may not become the page", "about:srcdoc", false,
                BlockReason.UNSUPPORTED_SCHEME);
        doc(cases, "about:config style internal page", "about:config", false,
                BlockReason.UNSUPPORTED_SCHEME);
        sub(cases, "inline data: image", "data:image/png;base64,iVBORw0KGgo=", true, null);
        sub(cases, "inline data: iframe document", "data:text/html,<b>hi</b>", true, null);
        sub(cases, "blob belonging to the site itself", "blob:https://arena.ai/1a2b3c4d", true, null);

        // ---------------------------------------------------------------------------------------
        // 2. Inert schemes are not documents: they may never become the visible page
        // ---------------------------------------------------------------------------------------
        doc(cases, "data: as a document", "data:text/html,<script>alert(1)</script>", false,
                BlockReason.UNSUPPORTED_SCHEME);
        doc(cases, "blob: as a document", "blob:https://arena.ai/1a2b3c4d", false,
                BlockReason.UNSUPPORTED_SCHEME);
        sub(cases, "blob of a foreign origin", "blob:https://evil.example/1a2b3c4d", false,
                BlockReason.HOST_NOT_ALLOWED);
        sub(cases, "blob of the wrong sub-domain", "blob:https://mail.arena.ai/1a2b3c4d", false,
                BlockReason.HOST_NOT_ALLOWED);
        sub(cases, "blob with an opaque origin", "blob:null/1a2b3c4d", false,
                BlockReason.UNSUPPORTED_SCHEME);
        sub(cases, "blob with an http origin", "blob:http://arena.ai/1a2b3c4d", false,
                BlockReason.SCHEME_NOT_HTTPS);

        // ---------------------------------------------------------------------------------------
        // 3. Schemes that could leave the app or reach another app
        // ---------------------------------------------------------------------------------------
        both(cases, "javascript: URL", "javascript:alert(document.cookie)", false,
                BlockReason.UNSUPPORTED_SCHEME);
        both(cases, "javascript: URL split by a newline", "java\nscript:alert(1)", false,
                BlockReason.UNSUPPORTED_SCHEME);
        both(cases, "javascript: with mixed case", "JaVaScRiPt:alert(1)", false,
                BlockReason.UNSUPPORTED_SCHEME);
        both(cases, "javascript: with a leading space", "  javascript:alert(1)", false,
                BlockReason.UNSUPPORTED_SCHEME);
        both(cases, "intent: URL (would launch another app)", "intent://scan/#Intent;scheme=zxing;end", false,
                BlockReason.UNSUPPORTED_SCHEME);
        both(cases, "play store scheme", "market://details?id=com.example", false,
                BlockReason.UNSUPPORTED_SCHEME);
        both(cases, "mailto:", "mailto:someone@arena.ai", false, BlockReason.UNSUPPORTED_SCHEME);
        both(cases, "tel:", "tel:+15551234567", false, BlockReason.UNSUPPORTED_SCHEME);
        both(cases, "sms:", "sms:+15551234567", false, BlockReason.UNSUPPORTED_SCHEME);
        both(cases, "third-party app deep link", "whatsapp://send?text=hi", false,
                BlockReason.UNSUPPORTED_SCHEME);
        both(cases, "custom scheme", "myapp://open?url=https://arena.ai", false,
                BlockReason.UNSUPPORTED_SCHEME);
        both(cases, "file: URL", "file:///etc/hosts", false, BlockReason.UNSUPPORTED_SCHEME);
        both(cases, "file: URL to app assets", "file:///android_asset/index.html", false,
                BlockReason.UNSUPPORTED_SCHEME);
        both(cases, "content: URL", "content://com.android.providers.media.documents/document/1", false,
                BlockReason.UNSUPPORTED_SCHEME);
        both(cases, "chrome: URL", "chrome://settings", false, BlockReason.UNSUPPORTED_SCHEME);
        both(cases, "view-source: URL", "view-source:https://arena.ai/", false,
                BlockReason.UNSUPPORTED_SCHEME);
        both(cases, "filesystem: URL", "filesystem:https://arena.ai/temporary/x", false,
                BlockReason.UNSUPPORTED_SCHEME);
        both(cases, "cleartext http on the allowed host", "http://arena.ai/", false,
                BlockReason.SCHEME_NOT_HTTPS);
        both(cases, "cleartext http on a third party", "http://evil.example/", false,
                BlockReason.SCHEME_NOT_HTTPS);
        both(cases, "protocol-relative URL", "//arena.ai/", false, BlockReason.MISSING_SCHEME);
        both(cases, "bare host without a scheme", "arena.ai", false, BlockReason.MISSING_SCHEME);
        both(cases, "scheme starting with a digit", "1http://arena.ai/", false,
                BlockReason.INVALID_SCHEME);
        both(cases, "no authority after https:", "https:arena.ai", false,
                BlockReason.MALFORMED_AUTHORITY);
        both(cases, "https: with nothing else", "https:", false, BlockReason.MALFORMED_AUTHORITY);
        both(cases, "empty authority", "https:///", false, BlockReason.EMPTY_HOST);
        both(cases, "collapsed slashes (Chromium would collapse them)", "https:////arena.ai/", false,
                BlockReason.EMPTY_HOST);
        both(cases, "userinfo hiding the real host", "https://arena.ai@evil.example/", false,
                BlockReason.USERINFO_PRESENT);
        both(cases, "userinfo with credentials", "https://user:pass@arena.ai/", false,
                BlockReason.USERINFO_PRESENT);
        both(cases, "empty userinfo", "https://@arena.ai/", false, BlockReason.USERINFO_PRESENT);
        both(cases, "IPv6 literal", "https://[::1]/", false, BlockReason.IP_LITERAL_HOST);
        both(cases, "IPv6 literal with port", "https://[2001:db8::1]:443/", false,
                BlockReason.IP_LITERAL_HOST);
        both(cases, "non-default port", "https://arena.ai:8443/", false,
                BlockReason.PORT_NOT_ALLOWED);
        both(cases, "port 80", "https://arena.ai:80/", false, BlockReason.PORT_NOT_ALLOWED);
        both(cases, "empty port", "https://arena.ai:/", false, BlockReason.PORT_NOT_ALLOWED);
        both(cases, "double port", "https://arena.ai:443:443/", false, BlockReason.INVALID_HOST);

        // ---------------------------------------------------------------------------------------
        // 4. Hosts that are not exactly on the list (the whole point of the exercise)
        // ---------------------------------------------------------------------------------------
        both(cases, "plain third party", "https://evil.example/", false, BlockReason.HOST_NOT_ALLOWED);
        both(cases, "third party with default port", "https://evil.example:443/", false,
                BlockReason.HOST_NOT_ALLOWED);
        both(cases, "third party in the path of an allowed host", "https://arena.ai/evil.example", true, null);
        both(cases, "hyphen look-alike", "https://arena-ai.com/", false, BlockReason.HOST_NOT_ALLOWED);
        both(cases, "no-dot look-alike", "https://arenaai.com/", false, BlockReason.HOST_NOT_ALLOWED);
        both(cases, "plural look-alike", "https://arenas.ai/", false, BlockReason.HOST_NOT_ALLOWED);
        both(cases, "sub-domain look-alike", "https://arena.ai.evil.example/", false,
                BlockReason.HOST_NOT_ALLOWED);
        both(cases, "allowed host as a sub-domain of a third party", "https://arena.ai.evil.example/x", false,
                BlockReason.HOST_NOT_ALLOWED);
        both(cases, "unlisted sub-domain", "https://sub.arena.ai/", false, BlockReason.HOST_NOT_ALLOWED);
        both(cases, "mail infrastructure sub-domain", "https://mail.arena.ai/", false,
                BlockReason.HOST_NOT_ALLOWED);
        both(cases, "cPanel sub-domain", "https://cpanel.arena.ai/", false, BlockReason.HOST_NOT_ALLOWED);
        both(cases, "SSO sub-domain (needs an explicit decision)", "https://okta.arena.ai/", false,
                BlockReason.HOST_NOT_ALLOWED);
        both(cases, "non-production preview host", "https://api.preview.arena.ai/", false,
                BlockReason.HOST_NOT_ALLOWED);
        both(cases, "non-production portal host", "https://portal.api.preview.arena.ai/", false,
                BlockReason.HOST_NOT_ALLOWED);
        both(cases, "e-mail security host", "https://mta-sts.arena.ai/", false, BlockReason.HOST_NOT_ALLOWED);
        both(cases, "punycode look-alike", "https://xn--rena-4qa.ai/", false, BlockReason.HOST_NOT_ALLOWED);
        both(cases, "punycode of the real host", "https://xn--arena-2d0b.ai/", false,
                BlockReason.HOST_NOT_ALLOWED);
        both(cases, "Cyrillic look-alike 'a'", "https://\u0430rena.ai/", false, BlockReason.NON_ASCII_HOST);
        both(cases, "ideographic full stop", "https://arena\u3002ai/", false, BlockReason.NON_ASCII_HOST);
        both(cases, "zero-width joiner in the host", "https://aren\u200da.ai/", false,
                BlockReason.NON_ASCII_HOST);
        both(cases, "percent-encoded dot in the host", "https://arena.ai%2e/", false,
                BlockReason.INVALID_HOST);
        both(cases, "percent-encoded NUL in the host", "https://arena.ai%00/", false,
                BlockReason.INVALID_HOST);
        both(cases, "underscore in the host", "https://arena_ai.example/", false,
                BlockReason.INVALID_HOST);
        both(cases, "IPv4 literal", "https://127.0.0.1/", false, BlockReason.INVALID_HOST);
        both(cases, "decimal IP literal", "https://2130706433/", false, BlockReason.INVALID_HOST);
        both(cases, "hex IP literal", "https://0x7f.0.0.1/", false, BlockReason.INVALID_HOST);
        both(cases, "single label host", "https://arena/", false, BlockReason.INVALID_HOST);
        both(cases, "empty label", "https://arena..ai/", false, BlockReason.INVALID_HOST);
        both(cases, "leading dot", "https://.arena.ai/", false, BlockReason.INVALID_HOST);
        both(cases, "leading hyphen", "https://-arena.ai/", false, BlockReason.INVALID_HOST);
        both(cases, "trailing hyphen", "https://arena.ai-/", false, BlockReason.INVALID_HOST);
        both(cases, "numeric top level domain", "https://arena.123/", false, BlockReason.INVALID_HOST);
        both(cases, "backslash before an at-sign", "https://evil.example\\@arena.ai/", false,
                BlockReason.BACKSLASH_AMBIGUITY);
        both(cases, "backslash in the host", "https://arena.ai\\evil.example/", false,
                BlockReason.BACKSLASH_AMBIGUITY);
        both(cases, "backslash after the scheme", "https:\\\\evil.example/", false,
                BlockReason.BACKSLASH_AMBIGUITY);
        both(cases, "tab-separated look-alike host", "https://arena.ai\tevil.example/", false,
                BlockReason.HOST_NOT_ALLOWED);
        both(cases, "NUL in the path", "https://arena.ai/\u0000evil", false, BlockReason.CONTROL_CHARACTER);
        both(cases, "carriage return is removed, exactly like Chromium does", "https://arena.ai\r/chat",
                true, null);
        both(cases, "control character in the path", "https://arena.ai/\u0001evil", false,
                BlockReason.CONTROL_CHARACTER);
        both(cases, "control character in the host", "https://arena\u0001.ai/", false,
                BlockReason.CONTROL_CHARACTER);
        both(cases, "DEL in the URL", "https://arena.ai/\u007f", false,
                BlockReason.CONTROL_CHARACTER);
        both(cases, "colon in the middle of the authority", "https://arena.ai:443@evil.example/", false,
                BlockReason.USERINFO_PRESENT);

        // ---------------------------------------------------------------------------------------
        // 5. Degenerate input must never be "allowed"
        // ---------------------------------------------------------------------------------------
        both(cases, "null URL", null, false, BlockReason.NULL_URL);
        both(cases, "empty URL", "", false, BlockReason.EMPTY_URL);
        both(cases, "whitespace only URL", "   ", false, BlockReason.EMPTY_URL);
        both(cases, "padded empty URL", "\t\n", false, BlockReason.EMPTY_URL);
        both(cases, "colon only", ":", false, BlockReason.MISSING_SCHEME);
        both(cases, "scheme with no host", "https://?x", false, BlockReason.EMPTY_HOST);

        return cases;
    }

    /**
     * Cases that are only meaningful when the opt-in third-party widget entries are enabled. Kept
     * separate so the default build's strictness is asserted by {@link #all()}.
     */
    public static List<Case> withCapabilityEntriesEnabled() {
        List<Case> cases = new ArrayList<Case>();
        sub(cases, "reCAPTCHA widget script (enabled)", "https://www.google.com/recaptcha/api.js", true, null);
        sub(cases, "reCAPTCHA widget frame (enabled)", "https://www.google.com/recaptcha/enterprise/anchor",
                true, null);
        sub(cases, "reCAPTCHA static asset (enabled)", "https://www.gstatic.com/recaptcha/releases/x/r.js",
                true, null);
        sub(cases, "Turnstile widget (enabled)", "https://challenges.cloudflare.com/turnstile/v0/api.js",
                true, null);
        sub(cases, "Google search stays blocked even with widgets on", "https://www.google.com/search?q=x",
                false, BlockReason.PATH_NOT_ALLOWED);
        sub(cases, "Google accounts stay blocked even with widgets on", "https://www.google.com/accounts/x",
                false, BlockReason.PATH_NOT_ALLOWED);
        sub(cases, "other Google host stays blocked", "https://translate.google.com/", false,
                BlockReason.HOST_NOT_ALLOWED);
        sub(cases, "path traversal out of the widget path", "https://www.gstatic.com/recaptcha/../translate/x",
                false, BlockReason.PATH_NOT_ALLOWED);
        sub(cases, "percent-encoded path escape", "https://www.gstatic.com/recaptcha%2f..%2fx", false,
                BlockReason.PATH_NOT_ALLOWED);
        sub(cases, "Cloudflare host outside the widget path", "https://challenges.cloudflare.com/cdn-cgi/x",
                false, BlockReason.PATH_NOT_ALLOWED);
        doc(cases, "widget host may never become the page", "https://challenges.cloudflare.com/turnstile/v0/api.js",
                false, BlockReason.DOCUMENT_NOT_ALLOWED);
        doc(cases, "reCAPTCHA script may never become the page", "https://www.google.com/recaptcha/api.js",
                false, BlockReason.DOCUMENT_NOT_ALLOWED);
        return cases;
    }

    private static void doc(List<Case> out, String name, String url, boolean allowed, BlockReason reason) {
        out.add(new Case(name, url, DOCUMENT, allowed, reason));
    }

    private static void sub(List<Case> out, String name, String url, boolean allowed, BlockReason reason) {
        out.add(new Case(name, url, SUBRESOURCE, allowed, reason));
    }

    private static void both(List<Case> out, String name, String url, boolean allowed, BlockReason reason) {
        out.add(new Case(name + " (document)", url, DOCUMENT, allowed, reason));
        out.add(new Case(name + " (subresource)", url, SUBRESOURCE, allowed, reason));
    }
}
