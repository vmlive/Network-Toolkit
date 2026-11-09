package com.vmlive.networktoolkit.core;

import static org.junit.Assert.*;
import org.junit.Test;

public class CodecToolsTest {
    @Test public void base64RoundTripsUtf8AndWhitespace() throws Exception {
        String text = "网络工具箱 🌐\n hello ";
        assertEquals(text, CodecTools.execute("base64_decode", CodecTools.execute("base64_encode", text)));
    }
    @Test public void invalidBase64IsReported() throws Exception {
        try { CodecTools.execute("base64_decode", "??not-base64??"); fail("invalid input"); }
        catch (IllegalArgumentException expected) {}
    }
    @Test public void urlFormEncodingRoundTrips() throws Exception {
        String text = "https://example.com/?q=你好 &p=+";
        assertEquals(text, CodecTools.execute("url_decode", CodecTools.execute("url_encode", text)));
        assertEquals("a b", CodecTools.execute("url_decode", "a+b"));
    }
    @Test public void knownHashVectors() throws Exception {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", CodecTools.execute("sha256", "abc"));
        assertEquals("900150983cd24fb0d6963f7d28e17f72", CodecTools.execute("md5", "abc"));
    }
}
