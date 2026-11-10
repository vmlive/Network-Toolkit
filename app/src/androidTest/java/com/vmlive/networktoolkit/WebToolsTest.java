package com.vmlive.networktoolkit;

import android.content.Intent;
import android.test.ActivityInstrumentationTestCase2;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Actual Android Chromium execution using inline local fixtures, never a live external request. */
@SuppressWarnings("deprecation")
public final class WebToolsTest extends ActivityInstrumentationTestCase2<WebToolsActivity> {
    private WebToolsActivity activity;
    public WebToolsTest() { super(WebToolsActivity.class); }

    @Override protected void setUp() throws Exception {
        super.setUp();
        setActivityIntent(new Intent(Intent.ACTION_MAIN).putExtra("site", "skk").putExtra("open", false));
        activity = getActivity(); getInstrumentation().waitForIdleSync();
    }

    public void testOpeningDisclosureDoesNotFetchAndSettingsKeepHttpsBoundary() {
        getInstrumentation().runOnMainSync(() -> {
            WebView web = activity.getWebViewForTest(); assertNotNull(web);
            assertTrue("No site request before explicit opening", web.getUrl() == null || web.getUrl().equals("about:blank"));
            WebSettings settings = web.getSettings();
            assertTrue(settings.getJavaScriptEnabled()); assertTrue(settings.getDomStorageEnabled());
            assertFalse(settings.getAllowFileAccess()); assertFalse(settings.getAllowContentAccess());
            assertFalse(settings.getAllowFileAccessFromFileURLs()); assertFalse(settings.getAllowUniversalAccessFromFileURLs());
            assertEquals(WebSettings.MIXED_CONTENT_NEVER_ALLOW, settings.getMixedContentMode());
            assertTrue(settings.getSafeBrowsingEnabled()); assertFalse(CookieManager.getInstance().acceptThirdPartyCookies(web));
        });
    }

    public void testActualJavascriptDomStorageAndFirstPartyCookies() throws Exception {
        getInstrumentation().runOnMainSync(() -> activity.getWebViewForTest().loadDataWithBaseURL("https://ip.skk.moe/fixture", "<!doctype html><html><body><div id='result'>waiting</div><script>localStorage.setItem('toolkit_fixture','stored');document.cookie='toolkit_fixture=present; path=/; Secure; SameSite=Strict';document.getElementById('result').textContent='ready:'+localStorage.getItem('toolkit_fixture');</script></body></html>", "text/html", "UTF-8", null));
        awaitValue("document.getElementById('result')&&document.getElementById('result').textContent", "\"ready:stored\"");
        assertEquals("true", evaluate("document.cookie.indexOf('toolkit_fixture=present')>=0"));
        assertEquals("\"undefined\"", evaluate("typeof window.Android"));
        assertEquals("\"stored\"", evaluate("localStorage.getItem('toolkit_fixture')"));
        evaluate("localStorage.removeItem('toolkit_fixture');document.cookie='toolkit_fixture=; Max-Age=0; path=/; Secure'");
    }

    public void testStorageSurvivesRealDocumentReloadWithinTheSameOrigin() throws Exception {
        getInstrumentation().runOnMainSync(() -> activity.getWebViewForTest().loadDataWithBaseURL("https://ip.skk.moe/fixture-one", "<html><body id='first'>one<script>localStorage.setItem('toolkit_fixture_reload','retained')</script></body></html>", "text/html", "UTF-8", null));
        awaitValue("localStorage.getItem('toolkit_fixture_reload')", "\"retained\"");
        getInstrumentation().runOnMainSync(() -> activity.getWebViewForTest().loadDataWithBaseURL("https://ip.skk.moe/fixture-two", "<html><body id='second'>two</body></html>", "text/html", "UTF-8", null));
        awaitValue("!!document.getElementById('second')", "true");
        assertEquals("\"retained\"", evaluate("localStorage.getItem('toolkit_fixture_reload')"));
        evaluate("localStorage.removeItem('toolkit_fixture_reload')");
    }

    private String evaluate(String script) throws Exception {
        CountDownLatch complete = new CountDownLatch(1); AtomicReference<String> result = new AtomicReference<>();
        getInstrumentation().runOnMainSync(() -> activity.getWebViewForTest().evaluateJavascript(script, value -> { result.set(value); complete.countDown(); }));
        assertTrue("Chromium did not complete JavaScript", complete.await(8, TimeUnit.SECONDS)); return result.get();
    }
    private void awaitValue(String script, String expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) { if (expected.equals(evaluate(script))) return; Thread.sleep(100); }
        fail("Actual Chromium result did not become " + expected);
    }
}
