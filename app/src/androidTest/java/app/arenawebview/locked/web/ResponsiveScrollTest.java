package app.arenawebview.locked.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.FrameLayout;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import app.arenawebview.locked.policy.ArenaAllowlist;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * The enlarged Agent Mode page must remain scrollable, including its sidebar, on the screen sizes the
 * app is used on. CI runs this test at phone portrait, phone landscape and tablet dimensions.
 *
 * <p>The probe measures the *real* scrolling elements (documentElement and the sidebar container),
 * scrolls both to the bottom and checks that the last conversation is reachable. It deliberately does
 * not assume a fixed page height: the live site grows and shrinks, so scrollability is measured.</p>
 */
@RunWith(AndroidJUnit4.class)
public class ResponsiveScrollTest {

    private static final String TAG = "ArenaWebViewLocked";

    @Test
    public void enlargedPageAndSidebarScrollFullyToTheBottom() throws Exception {
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicReference<String> probe =
                new java.util.concurrent.atomic.AtomicReference<String>();

        final String html = """
                <!doctype html>
                <html><head><meta name="viewport" content="width=device-width,initial-scale=1"></head>
                <body style="margin:0;">
                  <main>
                    <aside data-sidebar="sidebar" style="height:60vh;min-height:300px;max-height:420px;display:flex;flex-direction:column;overflow:hidden;">
                      <nav data-sidebar="header"><a href="/agent">Agent</a></nav>
                      <div style="height:180px;"><h4>Try Agent Mode</h4><button aria-label="Dismiss">×</button></div>
                      <div id="chat-list" data-sidebar="content" style="display:flex;flex-direction:column;overflow:hidden;min-height:0;flex:1;">
                        <div data-sidebar="group" style="display:flex;flex-direction:column;">
                          <div data-sidebar="group-content" style="display:flex;flex-direction:column;">
                            <a href="/c/1" style="height:52px;display:block;">one</a>
                            <a href="/c/2" style="height:52px;display:block;">two</a>
                            <a href="/c/3" style="height:52px;display:block;">three</a>
                            <a href="/c/4" style="height:52px;display:block;">four</a>
                            <a href="/c/5" style="height:52px;display:block;">five</a>
                          </div>
                        </div>
                        <div data-sidebar="group" style="display:flex;flex-direction:column;">
                          <div data-sidebar="group-content" style="display:flex;flex-direction:column;">
                            <a href="/c/6" style="height:52px;display:block;">six</a>
                            <a href="/c/7" style="height:52px;display:block;">seven</a>
                            <a href="/c/8" style="height:52px;display:block;">eight</a>
                            <a href="/c/9" style="height:52px;display:block;">nine</a>
                            <a href="/c/10" style="height:52px;display:block;">ten</a>
                          </div>
                        </div>
                      </div>
                      <div data-sidebar="footer"><a href="https://www.youtube.com/watch?v=x">Watch on YouTube</a></div>
                    </aside>
                    <section id="long-content" style="height:2400px;">main page content</section>
                    <footer id="page-end">end of page</footer>
                  </main>
                </body></html>
                """;

        InstrumentationRegistry.getInstrumentation().runOnMainSync(new Runnable() {
            @Override
            public void run() {
                final android.content.Context context =
                        InstrumentationRegistry.getInstrumentation().getTargetContext();
                final android.util.DisplayMetrics metrics =
                        context.getResources().getDisplayMetrics();
                final int width = metrics.widthPixels;
                final int height = metrics.heightPixels;

                final WebView view = new WebView(context);
                FrameLayout container = new FrameLayout(context);
                container.addView(view, new FrameLayout.LayoutParams(width, height));
                container.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
                container.layout(0, 0, width, height);

                final ArenaWebViewClient client = new ArenaWebViewClient(new RecordingGuardHost(
                        ArenaAllowlist.productionConfig()));
                view.setWebViewClient(new android.webkit.WebViewClient() {
                    @Override
                    public void onPageFinished(WebView webView, String url) {
                        client.injectSidebarGuard(webView);
                        webView.evaluateJavascript("""
                                (function() {
                                  var doc = document.documentElement;
                                  var sidebar = document.getElementById('chat-list');
                                  var first = document.querySelector('[data-sidebar="group"]');
                                  var groups = document.querySelectorAll('[data-sidebar="group"]');
                                  var a = first.getBoundingClientRect();
                                  var b = groups[1].getBoundingClientRect();
                                  var noOverlap = b.top >= a.bottom - 1;
                                  window.scrollTo(0, 100000);
                                  var pageBottom = (window.scrollY + window.innerHeight) >= doc.scrollHeight - 2;
                                  var sidebarScrollable = sidebar.scrollHeight > sidebar.clientHeight + 2;
                                  sidebar.scrollTop = 100000;
                                  var sidebarBottom = (sidebar.scrollTop + sidebar.clientHeight)
                                          >= sidebar.scrollHeight - 2;
                                  var box = sidebar.getBoundingClientRect();
                                  var last = document.querySelector('a[href="/c/10"]').getBoundingClientRect();
                                  var lastVisible = last.bottom <= box.bottom + 2 && last.top >= box.top - 2;
                                  return [
                                    screen.width + 'x' + screen.height,
                                    window.innerWidth + 'x' + window.innerHeight,
                                    String(doc.scrollHeight),
                                    String(doc.clientHeight),
                                    pageBottom ? 'page-bottom-ok' : 'page-bottom-cut',
                                    sidebarScrollable ? 'sidebar-scrollable' : 'sidebar-fixed',
                                    sidebarBottom ? 'sidebar-bottom-ok' : 'sidebar-bottom-cut',
                                    noOverlap ? 'no-overlap' : 'overlap',
                                    lastVisible ? 'last-visible' : 'last-cut'
                                  ].join('|');
                                })()
                                """, value -> {
                            probe.set(value);
                            latch.countDown();
                        });
                    }
                });
                view.loadDataWithBaseURL("https://arena.ai/", html, "text/html", "utf-8", null);
            }
        });

        assertTrue("responsive probe timed out", latch.await(20, java.util.concurrent.TimeUnit.SECONDS));
        String result = probe.get();
        Log.i(TAG, "responsive probe: " + result);
        String[] parts = result.replace("\"", "").split("\\|");
        assertEquals("nine measurements", 9, parts.length);
        assertTrue("page must be taller than the viewport: " + result,
                Integer.parseInt(parts[2]) > Integer.parseInt(parts[3]));
        assertEquals("page must scroll all the way to the end: " + result, "page-bottom-ok", parts[4]);
        assertEquals("sidebar must have its own scrollbar: " + result, "sidebar-scrollable", parts[5]);
        assertEquals("sidebar must reach its last conversation: " + result, "sidebar-bottom-ok", parts[6]);
        assertEquals("thread groups must not overlap: " + result, "no-overlap", parts[7]);
        assertEquals("the last conversation must be visible after scrolling: " + result,
                "last-visible", parts[8]);
    }
}
