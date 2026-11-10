package com.vmlive.networktoolkit;

import org.junit.Test;
import static org.junit.Assert.*;

public final class WebSitePolicyTest {
    @Test public void fixedSitesUseOriginalHttpsOrigins() {
        assertEquals("https://ip.skk.moe/", WebSitePolicy.forId("skk").url);
        assertEquals("https://ping0.cc/", WebSitePolicy.forId("ping0").url);
    }
    @Test public void sameOriginPathsQueriesAndCanonicalWwwStayInternal() {
        assertEquals(WebSitePolicy.Navigation.INTERNAL, WebSitePolicy.classify(WebSitePolicy.SKK, "https://IP.SKK.MOE:443/path?q=a#result"));
        assertEquals(WebSitePolicy.Navigation.INTERNAL, WebSitePolicy.classify(WebSitePolicy.PING0, "https://www.ping0.cc/report?ip=192.0.2.1"));
    }
    @Test public void anotherSiteAndLookalikeHostsAreExternal() {
        for (String value : new String[]{"https://ping0.cc/", "https://ip.skk.moe.evil.example/", "https://evilip.skk.moe/"})
            assertEquals(value, WebSitePolicy.Navigation.EXTERNAL, WebSitePolicy.classify(WebSitePolicy.SKK, value));
    }
    @Test public void activePlaintextLocalAndCredentialSchemesAreBlocked() {
        for (String value : new String[]{"http://ip.skk.moe/", "javascript:alert(1)", "intent://ping0.cc", "file:///data/data/app/key", "content://user/private", "data:text/html,test", "https://user:pass@ip.skk.moe/", "https://ip.skk.moe\\@evil.example/", "https://ip.skk.moe:444/", "https://ip.skk.moe./", "//ip.skk.moe/", "https://ip.skk.moe%40evil.example/", "", null})
            assertEquals(String.valueOf(value), WebSitePolicy.Navigation.BLOCKED, WebSitePolicy.classify(WebSitePolicy.SKK, value));
    }
    @Test public void hostLabelDoesNotTrustPageTitlesOrUserInfo() {
        assertEquals("ip.skk.moe", WebSitePolicy.host("https://IP.SKK.MOE/a?secret=hidden"));
        assertEquals("", WebSitePolicy.host("https://secret@ip.skk.moe"));
        assertEquals("", WebSitePolicy.host("https://ip.skk.moe\\@evil.example"));
    }
    @Test(expected = IllegalArgumentException.class) public void unknownIntentSiteCannotSupplyAnArbitraryStartUrl() { WebSitePolicy.forId("https://evil.example"); }
}
