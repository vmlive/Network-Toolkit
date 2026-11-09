package com.vmlive.networktoolkit;

import org.junit.Test;
import java.util.Map;
import static org.junit.Assert.*;

public final class LanDiscoveryTest {
    @Test public void cidrNormalizesAndExcludesSubnetBroadcast() {
        LanDiscovery.Range range = LanDiscovery.parseCidr("192.168.31.55/24");
        assertEquals("192.168.31.0/24", range.cidr);
        assertEquals(254, range.hosts());
        assertEquals(0xc0a81f01L, range.first);
        assertEquals(0xc0a81ffeL, range.last);
    }
    @Test public void pointToPointAndHostRetainEveryAddress() {
        LanDiscovery.Range pair = LanDiscovery.parseCidr("10.0.0.1/31");
        assertEquals(2, pair.hosts());
        assertEquals(0x0a000000L, pair.first);
        LanDiscovery.Range host = LanDiscovery.parseCidr("169.254.10.20/32");
        assertEquals(1, host.hosts()); assertEquals(host.first, host.last);
    }
    @Test public void private172RangeAcceptedAtBothEnds() {
        assertEquals(254, LanDiscovery.parseCidr("172.16.0.1/24").hosts());
        assertEquals(254, LanDiscovery.parseCidr("172.31.255.254/24").hosts());
    }
    @Test public void scanRejectsPublicLoopbackOversizedAndMalformedNetworks() {
        for (String value : new String[]{"8.8.8.8/32", "127.0.0.1/32", "172.15.0.1/24", "172.32.0.1/24", "192.168.1.0/23", "10.0.0.0/8", "169.253.0.1/24", "10.0.0.1/33", "192.168.1.999/24", "foo/24"}) {
            try { LanDiscovery.parseCidr(value); fail("Should reject " + value); }
            catch (IllegalArgumentException expected) { /* scope and input boundary */ }
        }
    }
    @Test public void ssdpParsesHeaderNamesCaseInsensitivelyAndPreservesLocationPort() {
        Map<String, String> result = LanDiscovery.parseSsdpResponse("HTTP/1.1 200 OK\r\nST: upnp:rootdevice\r\nUSN: uuid:fixture\r\nLocation: http://192.168.1.2:1234/device.xml\r\nSERVER: test/1.0\r\n\r\n");
        assertEquals("upnp:rootdevice", result.get("st"));
        assertEquals("uuid:fixture", result.get("usn"));
        assertEquals("http://192.168.1.2:1234/device.xml", result.get("location"));
        assertEquals("test/1.0", result.get("server"));
    }
    @Test public void ssdpIgnoresNotifyErrorsUnrelatedAndOversizedPackets() {
        assertTrue(LanDiscovery.parseSsdpResponse("NOTIFY * HTTP/1.1\r\nNT: ssdp:alive\r\n\r\n").isEmpty());
        assertTrue(LanDiscovery.parseSsdpResponse("HTTP/1.1 404 Not Found\r\nST: ssdp:all\r\n\r\n").isEmpty());
        assertTrue(LanDiscovery.parseSsdpResponse("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\n\r\n").isEmpty());
        assertTrue(LanDiscovery.parseSsdpResponse(new String(new char[8193])).isEmpty());
        assertTrue(LanDiscovery.parseSsdpResponse(new String(new char[8192])).isEmpty());
    }
    @Test public void onlyExplicitRefusedResponseIsEvidenceOfPeer() {
        assertTrue(LanDiscovery.isRefused("connect failed: ECONNREFUSED (Connection refused)"));
        assertTrue(LanDiscovery.isRefused("Connection refused"));
        assertFalse(LanDiscovery.isRefused("Network is unreachable"));
        assertFalse(LanDiscovery.isRefused("timeout"));
        assertFalse(LanDiscovery.isRefused(null));
    }
}
