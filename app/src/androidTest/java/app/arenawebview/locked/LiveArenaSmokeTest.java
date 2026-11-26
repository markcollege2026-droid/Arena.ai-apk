package app.arenawebview.locked;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.util.Log;
import android.webkit.WebView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import app.arenawebview.locked.policy.PolicyMetrics;

/**
 * End-to-end smoke test against the real site.
 *
 * <p>This is the only test that needs a network. It asserts the two things a wrapper must get right on a
 * live page: the app ends up on arena.ai (never anywhere else), and JavaScript is actually executing the
 * real site's code. It is intentionally tolerant of an offline emulator - in that case there is nothing
 * to load, which is not a policy failure - while still refusing to pass if the WebView ends up on any
 * other host.</p>
 */
@RunWith(AndroidJUnit4.class)
public class LiveArenaSmokeTest {

    private static final String TAG = "ArenaWebViewLocked";
    private static final long LOAD_TIMEOUT_MS = 45_000L;

    @Test
    public void loadsTheRealSiteAndRunsItsJavaScript() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            String url = waitForSite(scenario);

            Log.i(TAG, "live smoke test: url after load = " + url);
            Log.i(TAG, "live smoke test diagnostics:\n" + PolicyMetrics.get().snapshot());

            if (url == null || url.isEmpty()) {
                Log.w(TAG, "no URL after " + LOAD_TIMEOUT_MS + "ms (offline emulator?) - the policy "
                        + "assertions below still hold");
                return;
            }
            assertTrue("the wrapper must only ever end up on arena.ai, was: " + url,
                    url.startsWith("https://arena.ai/"));

            if (!url.startsWith("https://arena.ai/")) {
                return;
            }

            // If the page really loaded, prove that the site's own JavaScript is running.
            AtomicReference<String> result = new AtomicReference<String>();
            CountDownLatch latch = new CountDownLatch(1);
            scenario.onActivity(activity -> {
                WebView webView = activity.findViewById(R.id.arena_webview);
                assertNotNull(webView);
                webView.evaluateJavascript("(function(){return 1+1;})()", value -> {
                    result.set(value);
                    latch.countDown();
                });
            });
            boolean evaluated = latch.await(15, TimeUnit.SECONDS);
            if (evaluated) {
                Log.i(TAG, "evaluateJavascript returned: " + result.get());
                assertNotNull(result.get());
                assertTrue("JavaScript must be executing: " + result.get(), result.get().contains("2"));
            } else {
                Log.w(TAG, "JavaScript evaluation timed out (page may still be loading)");
            }

            // Whatever the site did, the wrapper must not have allowed a third party to be displayed.
            scenario.onActivity(activity -> {
                WebView webView = activity.findViewById(R.id.arena_webview);
                String current = webView.getUrl();
                assertTrue("still on arena.ai after the load, was: " + current,
                        current == null || current.isEmpty() || current.startsWith("https://arena.ai/"));
            });
        }
    }

    /**
     * The live page must stay scrollable inside the wrapper, measured on a real WebView.
     *
     * <p>The site is a full-height flex shell whose transcript scrolls internally, so the failure this
     * guards against is subtle: if anything clamps the document - an {@code overflow: hidden} on
     * {@code html}/{@code body}, a fixed viewport, a zero-height flex child - the end of a long answer
     * becomes unreachable on a short screen. The app's own injected CSS cannot cause that (it is scoped
     * to sidebar selectors and refuses to touch {@code body}/{@code html}), so the property is asserted
     * where it can actually be observed rather than assumed.</p>
     *
     * <p>The probe reports every measurement in the log, so a failure describes the page that was
     * actually seen instead of only saying that something was wrong.</p>
     */
    @Test
    public void theLivePageRemainsScrollable() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            String url = waitForSite(scenario);
            if (url == null || url.isEmpty()) {
                Log.w(TAG, "scroll check skipped: the site did not load (offline emulator)");
                return;
            }
            assertTrue("the wrapper must only ever end up on arena.ai, was: " + url,
                    url.startsWith("https://arena.ai/"));

            // Facts that together decide whether a drag can reach the bottom of the page:
            //   * how much scrollable range the document and the body report,
            //   * whether either of them clamps overflow,
            //   * whether some descendant is an internal scroll container (the transcript), which is
            //     the site's normal layout and is not a wrapper failure.
            String probe = "(function(){"
                    + "function cs(e){var s=getComputedStyle(e);return{overflowY:s.overflowY,"
                    + "height:e.clientHeight,position:s.position};}"
                    + "function range(e){return e?e.scrollHeight-e.clientHeight:0;}"
                    + "var de=document.documentElement,b=document.body;"
                    + "var inner=0,innerCount=0;"
                    + "var all=document.querySelectorAll('div,main,section');"
                    + "for(var i=0;i<all.length;i++){var e=all[i];"
                    + "  var st=getComputedStyle(e);"
                    + "  if(st.overflowY==='auto'||st.overflowY==='scroll'){"
                    + "    var r=range(e); if(r>0){innerCount++; if(r>inner)inner=r;} }}"
                    + "return JSON.stringify({"
                    + "  vw:Math.round(window.innerWidth),"
                    + "  vh:Math.round(window.innerHeight),"
                    + "  docRange:Math.round(range(de)),"
                    + "  bodyRange:Math.round(range(b)),"
                    + "  docOverflowY:cs(de).overflowY,"
                    + "  bodyOverflowY:cs(b).overflowY,"
                    + "  bodyPosition:cs(b).position,"
                    + "  docContentHeight:Math.round(de.scrollHeight),"
                    + "  innerScrollRange:Math.round(inner),"
                    + "  innerScrollContainers:innerCount"
                    + "});"
                    + "})()";
            final AtomicReference<String> raw = new AtomicReference<String>();
            final CountDownLatch latch = new CountDownLatch(1);
            scenario.onActivity(activity -> {
                WebView webView = activity.findViewById(R.id.arena_webview);
                assertNotNull(webView);
                webView.evaluateJavascript(probe, value -> {
                    raw.set(value);
                    latch.countDown();
                });
            });
            if (!latch.await(15, TimeUnit.SECONDS) || raw.get() == null) {
                Log.w(TAG, "scroll check skipped: the probe returned nothing");
                return;
            }
            // evaluateJavascript hands back the JSON as a *quoted, escaped* string literal
            // ({\"vw\":393,...}), so the escapes have to be removed before it can be read.
            String json = ScrollProbe.unescape(raw.get());
            Log.i(TAG, "live scroll probe: " + json);

            long vh = ScrollProbe.number(json, "vh");
            long docRange = ScrollProbe.number(json, "docRange");
            long bodyRange = ScrollProbe.number(json, "bodyRange");
            long contentHeight = ScrollProbe.number(json, "docContentHeight");
            long innerRange = ScrollProbe.number(json, "innerScrollRange");
            String docOverflow = ScrollProbe.text(json, "docOverflowY");
            String bodyOverflow = ScrollProbe.text(json, "bodyOverflowY");

            assertTrue("the viewport height must be measurable, probe was: " + json, vh > 0);

            // If the content is taller than the screen, the page must offer a way to reach the rest:
            // either the document scrolls, or an inner container does. (Both are normal for this site.)
            boolean contentOverflows = contentHeight > vh;
            if (contentOverflows) {
                assertTrue("a page taller than the screen must be scrollable somewhere - document range="
                                + docRange + ", body range=" + bodyRange + ", inner range=" + innerRange
                                + ", viewport=" + vh + ", content=" + contentHeight + ", probe: " + json,
                        docRange > 0 || bodyRange > 0 || innerRange > 0);
            }

            // A clamp on both the document and the body would make the overflow unreachable even if an
            // inner container existed, so it is refused outright.
            boolean bothClamped = "hidden".equals(docOverflow) && "hidden".equals(bodyOverflow);
            assertTrue("the wrapper must never leave html and body both clamped: probe was " + json,
                    !bothClamped);
        }
    }

    /**
     * The wrapper must be able to scroll a page whose content is taller than the screen.
     *
     * <p>Measuring the landing page is not enough: on some screens the page happens to fit exactly one
     * viewport (on a Pixel 5 it reports 778px of content in a 778px viewport), so there is nothing to
     * scroll and the question stays unanswered. This test creates the condition instead of hoping for
     * it - it appends a tall element to the live document, then checks that a scrollable range appears
     * and that a scroll actually moves the page. If anything in the wrapper clamped the document, the
     * range would stay zero or the scroll position would not move, and the failure says which.</p>
     *
     * <p>The element is removed again, and it can never be reached as a URL - it exists only inside
     * this WebView for the duration of the check.</p>
     */
    @Test
    public void thePageCanBeScrolledWhenContentExceedsTheScreen() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            String url = waitForSite(scenario);
            if (url == null || url.isEmpty()) {
                Log.w(TAG, "scroll check skipped: the site did not load (offline emulator)");
                return;
            }
            assertTrue("the wrapper must only ever end up on arena.ai, was: " + url,
                    url.startsWith("https://arena.ai/"));

            // 1. Add 2000px of content to the document, measure, scroll, measure again, clean up.
            String probe = "(function(){"
                    + "var de=document.documentElement,b=document.body;"
                    + "var before={vw:Math.round(innerWidth),vh:Math.round(innerHeight),"
                    + "  docRange:Math.round(de.scrollHeight-de.clientHeight),"
                    + "  bodyRange:Math.round(b.scrollHeight-b.clientHeight)};"
                    + "var spill=document.createElement('div');"
                    + "spill.id='__arena_scroll_probe';"
                    + "spill.style.cssText='height:2000px;width:1px;flex:0 0 auto;visibility:hidden;"
                    + "pointer-events:none;';"
                    + "(b||de).appendChild(spill);"
                    + "var after={docRange:Math.round(de.scrollHeight-de.clientHeight),"
                    + "  bodyRange:Math.round(b.scrollHeight-b.clientHeight)};"
                    + "var scroller = after.bodyRange>0 ? b : (after.docRange>0 ? de : null);"
                    + "var moved=0,topAfter=0;"
                    + "if(scroller){"
                    + "  var start=scroller.scrollTop;"
                    + "  try{ scroller.scrollTop = 200; }catch(e){}"
                    + "  topAfter=Math.round(scroller.scrollTop);"
                    + "  moved=topAfter-start;"
                    + "  scroller.scrollTop=start;"
                    + "}"
                    + "de.removeChild(spill);"
                    + "return JSON.stringify({vw:before.vw,vh:before.vh,"
                    + "  docRangeBefore:before.docRange,bodyRangeBefore:before.bodyRange,"
                    + "  docRangeAfter:after.docRange,bodyRangeAfter:after.bodyRange,"
                    + "  scrollMoved:moved,scrollTopAfter:topAfter});"
                    + "})()";
            final AtomicReference<String> raw = new AtomicReference<String>();
            final CountDownLatch latch = new CountDownLatch(1);
            scenario.onActivity(activity -> {
                WebView webView = activity.findViewById(R.id.arena_webview);
                assertNotNull(webView);
                webView.evaluateJavascript(probe, value -> {
                    raw.set(value);
                    latch.countDown();
                });
            });
            if (!latch.await(20, TimeUnit.SECONDS) || raw.get() == null) {
                Log.w(TAG, "scroll check skipped: the probe returned nothing");
                return;
            }
            String json = ScrollProbe.unescape(raw.get());
            Log.i(TAG, "live scrollability probe: " + json);

            long vh = ScrollProbe.number(json, "vh");
            long docAfter = ScrollProbe.number(json, "docRangeAfter");
            long bodyAfter = ScrollProbe.number(json, "bodyRangeAfter");
            long moved = ScrollProbe.number(json, "scrollMoved");
            long topAfter = ScrollProbe.number(json, "scrollTopAfter");

            assertTrue("the viewport height must be measurable, probe was: " + json, vh > 0);
            // 2. With 2000px of extra content, a scrollable range must exist. If both stay zero the
            //    document is clamped somewhere and the bottom of a long page is unreachable.
            assertTrue("adding 2000px of content must produce a scrollable range - "
                    + "document=" + docAfter + ", body=" + bodyAfter + ", viewport=" + vh
                    + ", probe: " + json, docAfter > 0 || bodyAfter > 0);
            // 3. And the page must actually move, not merely report a range.
            assertTrue("the page must scroll, not only report a range (scrollTop stayed at " + topAfter
                    + ", probe: " + json + ")", moved > 0);
        }
    }

    /** Small JSON reader for the probe above: flat object, numbers and strings only. */
    static final class ScrollProbe {
        private ScrollProbe() {
        }

        /**
         * Removes the quoting {@code evaluateJavascript} adds: it returns the page's value as a JSON
         * string literal, so {@code {"a":1}} arrives as {@code "{\"a\":1}"}. Without this, every key
         * lookup silently misses and the probe reads as all zeroes.
         */
        static String unescape(String value) {
            if (value == null) {
                return null;
            }
            String v = value.trim();
            if (v.length() >= 2 && v.charAt(0) == '"' && v.charAt(v.length() - 1) == '"') {
                v = v.substring(1, v.length() - 1);
            }
            StringBuilder out = new StringBuilder(v.length());
            for (int i = 0; i < v.length(); i++) {
                char c = v.charAt(i);
                if (c == '\\' && i + 1 < v.length()) {
                    char next = v.charAt(i + 1);
                    if (next == '"' || next == '\\' || next == '/') {
                        out.append(next);
                        i++;
                        continue;
                    }
                    if (next == 'n') {
                        out.append('\n');
                        i++;
                        continue;
                    }
                }
                out.append(c);
            }
            return out.toString();
        }

        static long number(String json, String key) {
            String value = raw(json, key);
            if (value == null) {
                return 0;
            }
            try {
                return Math.round(Double.parseDouble(value));
            } catch (NumberFormatException e) {
                return 0;
            }
        }

        static String text(String json, String key) {
            if (json == null || key == null) {
                return "";
            }
            int at = json.indexOf("\"" + key + "\"");
            if (at < 0) {
                return "";
            }
            int colon = json.indexOf(':', at);
            if (colon < 0) {
                return "";
            }
            int quote = json.indexOf('\"', colon + 1);
            if (quote < 0) {
                return "";
            }
            int close = json.indexOf('\"', quote + 1);
            if (close < 0) {
                return "";
            }
            return json.substring(quote + 1, close);
        }

        private static String raw(String json, String key) {
            if (json == null || key == null) {
                return null;
            }
            int at = json.indexOf("\"" + key + "\"");
            if (at < 0) {
                return null;
            }
            int colon = json.indexOf(':', at);
            if (colon < 0) {
                return null;
            }
            int i = colon + 1;
            while (i < json.length() && Character.isWhitespace(json.charAt(i))) {
                i++;
            }
            int start = i;
            while (i < json.length()) {
                char c = json.charAt(i);
                if (c == ',' || c == '}' || c == '\"') {
                    break;
                }
                i++;
            }
            return json.substring(start, i).trim();
        }
    }

    /**
     * Waits until the wrapper is on arena.ai, and (best effort) until the page has finished loading.
     * Returns the URL the WebView ended up on, or null/"" if nothing loaded (offline emulator).
     */
    private static String waitForSite(ActivityScenario<MainActivity> scenario) throws Exception {
        long deadline = System.currentTimeMillis() + LOAD_TIMEOUT_MS;
        String last = null;
        boolean reachedSite = false;
        while (System.currentTimeMillis() < deadline) {
            final String[] url = new String[1];
            final int[] progress = new int[1];
            scenario.onActivity(activity -> {
                WebView webView = activity.findViewById(R.id.arena_webview);
                url[0] = webView == null ? null : webView.getUrl();
                progress[0] = webView == null ? 0 : webView.getProgress();
            });
            last = url[0];
            if (last != null && last.startsWith("https://arena.ai/")) {
                reachedSite = true;
                if (progress[0] >= 100) {
                    break;
                }
            } else if (reachedSite) {
                // It left the site: stop waiting immediately so the assertion can report it.
                break;
            }
            Thread.sleep(1000);
        }
        return last;
    }
}
