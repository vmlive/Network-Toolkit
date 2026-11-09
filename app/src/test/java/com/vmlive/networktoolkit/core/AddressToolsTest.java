package com.vmlive.networktoolkit.core;

import static org.junit.Assert.*;
import java.net.InetAddress;
import java.util.concurrent.CancellationException;
import org.junit.Test;

public final class AddressToolsTest {
    @Test public void macFormatsNormalizeIdentically() {
        for (String input : new String[]{"aa:bb:cc:dd:ee:ff", "AA-BB-CC-DD-EE-FF", "aabb.ccdd.eeff", "aabbccddeeff"})
            assertEquals("AA:BB:CC:DD:EE:FF", AddressTools.normalizeMac(input));
    }

    @Test public void macBitsAndLimitsAreExplainedWithoutGuessedVendor() throws Exception {
        String local = AddressTools.inspect("02:11:22:33:44:55", new NetworkTools.Cancellation());
        assertTrue(local, local.contains("单播地址"));
        assertTrue(local, local.contains("本地管理位"));
        assertTrue(local, local.contains("未内置厂商数据库"));
        String group = AddressTools.inspect("01:11:22:33:44:55", null);
        assertTrue(group, group.contains("组播地址"));
        assertTrue(group, group.contains("全局管理位"));
        assertTrue(AddressTools.inspect("FF:FF:FF:FF:FF:FF", null).contains("广播地址"));
    }

    @Test public void invalidMacDoesNotSilentlyNormalize() {
        for (String invalid : new String[]{"AA:BB-CC:DD:EE:FF", "AA:BB:CC:DD:EE", "GG:BB:CC:DD:EE:FF"}) {
            try { AddressTools.normalizeMac(invalid); fail(invalid); }
            catch (IllegalArgumentException expected) { }
        }
    }

    @Test public void hostValidationRejectsUrlPortsAndLegacyIpv4Shorthand() {
        for (String invalid : new String[]{"", "https://example.com", "example.com:443", "user@example.com", "1.2.3", "256.0.0.1", "example..com", "[::1]:443"}) {
            try { AddressTools.validatedHost(invalid); fail(invalid); }
            catch (IllegalArgumentException expected) { }
        }
        assertEquals("example.com", AddressTools.validatedHost("example.com"));
        assertEquals("::1", AddressTools.validatedHost("[::1]"));
        assertEquals("xn--fsqu00a.xn--0zwm56d", AddressTools.validatedHost("例子.测试"));
    }

    @Test public void classifiesLocalIpv4AndSpecialUseRanges() throws Exception {
        assertTrue(classify(127,0,0,1).contains("回环"));
        assertTrue(classify(192,168,1,1).contains("私有"));
        assertTrue(classify(169,254,1,1).contains("链路"));
        assertTrue(classify(100,64,0,1).contains("CGNAT"));
        assertTrue(classify(192,0,2,1).contains("文档"));
        assertTrue(classify(198,51,100,1).contains("文档"));
        assertTrue(classify(203,0,113,1).contains("文档"));
        assertTrue(classify(224,0,0,1).contains("组播"));
        assertTrue(classify(198,18,0,1).contains("基准"));
        assertTrue(classify(8,8,8,8).contains("普通"));
    }

    @Test public void classifiesIpv6UlaAndDocumentation() throws Exception {
        byte[] ula = new byte[16]; ula[0] = (byte) 0xfd; ula[15] = 1;
        assertTrue(AddressTools.classify(InetAddress.getByAddress(ula)).contains("唯一本地"));
        byte[] docs = new byte[16]; docs[0] = 0x20; docs[1] = 0x01; docs[2] = 0x0d; docs[3] = (byte) 0xb8;
        assertTrue(AddressTools.classify(InetAddress.getByAddress(docs)).contains("文档"));
    }

    @Test public void localhostInspectionActuallyResolves() throws Exception {
        String result = AddressTools.inspect("localhost", new NetworkTools.Cancellation());
        assertTrue(result, result.contains("回环"));
        assertTrue(result, result.contains("127.0.0.1") || result.contains("0:0:0:0:0:0:0:1") || result.contains("::1"));
        assertTrue(result, result.contains("未查询国家"));
    }

    @Test public void cancellationPreventsEvenLocalInspection() throws Exception {
        NetworkTools.Cancellation c = new NetworkTools.Cancellation(); c.cancel();
        try { AddressTools.inspect("AA:BB:CC:DD:EE:FF", c); fail("cancelled"); }
        catch (CancellationException expected) { }
    }

    private static String classify(int a, int b, int c, int d) throws Exception {
        return AddressTools.classify(InetAddress.getByAddress(new byte[]{(byte)a,(byte)b,(byte)c,(byte)d}));
    }
}
