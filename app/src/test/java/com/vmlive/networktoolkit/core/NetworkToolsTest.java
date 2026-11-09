package com.vmlive.networktoolkit.core;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class NetworkToolsTest {
    private NetworkTools.Cancellation token() { return new NetworkTools.Cancellation(); }

    @Test public void normalSubnet() {
        String result = NetworkTools.subnet("192.168.8.42/24");
        assertTrue(result.contains("网络地址：192.168.8.0"));
        assertTrue(result.contains("192.168.8.255（广播地址）"));
        assertTrue(result.contains("192.168.8.1 – 192.168.8.254"));
        assertTrue(result.contains("可用主机：254"));
    }
    @Test public void zeroPrefixDoesNotOverflow() {
        String result = NetworkTools.subnet("10.1.2.3/0");
        assertTrue(result.contains("地址总数：4294967296"));
        assertTrue(result.contains("网络地址：0.0.0.0"));
        assertTrue(result.contains("255.255.255.255（广播地址）"));
    }
    @Test public void pointToPointSubnetUsesBothAddresses() {
        String result = NetworkTools.subnet("10.0.0.1/31");
        assertTrue(result.contains("10.0.0.0 – 10.0.0.1"));
        assertTrue(result.contains("可用主机：2"));
    }
    @Test public void singleAddressSubnet() {
        String result = NetworkTools.subnet("255.255.255.255/32");
        assertTrue(result.contains("255.255.255.255 – 255.255.255.255"));
        assertTrue(result.contains("可用主机：1"));
    }
    @Test public void invalidSubnetsRejected() {
        for (String input : new String[]{"256.1.1.1/24", "1.2.3/24", "1.2.3.4/33", "1.2.3.4/-1", "1.2.3.4", "a.b.c.d/24"}) {
            try { NetworkTools.subnet(input); fail(input); } catch (IllegalArgumentException expected) {}
        }
    }
    @Test public void portRangesSortedAndDeduplicated() {
        assertArrayEquals(new int[]{22,80,81,82,443}, NetworkTools.parsePorts("443,80-82,22,80"));
        assertEquals(128, NetworkTools.parsePorts("1-128").length);
    }
    @Test public void excessiveAndInvalidPortsRejected() {
        for (String input : new String[]{"1-129", "0", "65536", "80-20", "80,", "", "1-2-3"}) {
            try { NetworkTools.parsePorts(input); fail(input); } catch (IllegalArgumentException expected) {}
        }
    }
    @Test public void hostPortSupportsIpv6AndInternationalNames() {
        NetworkTools.HostPort v6 = NetworkTools.HostPort.parse("[::1]:8443", 443);
        assertEquals("::1", v6.host); assertEquals(8443, v6.port);
        assertEquals("xn--fsqu00a.xn--0zwm56d", NetworkTools.HostPort.parse("例子.测试", 443).host);
        assertEquals(443, NetworkTools.HostPort.parse("example.com", 443).port);
    }
    @Test public void urlsAndInvalidHostPortsRejected() {
        for (String input : new String[]{"https://example.com", "example.com/path", "", "[::1", "host:0", "host:65536", "host:abc", "host name"}) {
            try { NetworkTools.HostPort.parse(input, 443); fail(input); } catch (IllegalArgumentException expected) {}
        }
    }
    @Test public void dnsResolvesLocalAddressWithoutInternet() throws Exception {
        assertTrue(NetworkTools.dns("127.0.0.1", token()).contains("A     127.0.0.1"));
    }
    @Test public void tcpMeasuresActualLocalConnection() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 16, InetAddress.getLoopbackAddress())) {
            List<String> progress = new ArrayList<>();
            String result = NetworkTools.tcpLatency("127.0.0.1", server.getLocalPort(), 2, 1000, token(), progress::add);
            assertTrue(result.contains("连接成功：2/2"));
            assertTrue(result.contains("并非 ICMP"));
            assertEquals(2, progress.size());
        }
    }
    @Test public void scanDetectsOpenLocalPort() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 16, InetAddress.getLoopbackAddress())) {
            int port = server.getLocalPort();
            String result = NetworkTools.scanPorts("127.0.0.1", new int[]{port}, token(), null);
            assertTrue(result.contains(port + "/tcp  开放"));
            assertTrue(result.contains("开放：1"));
        }
    }
    @Test public void cancelledOperationDoesNotConnect() throws Exception {
        NetworkTools.Cancellation cancellation = token(); cancellation.cancel();
        try { NetworkTools.execute("tcp", "127.0.0.1:1", cancellation, null); fail("must cancel"); }
        catch (CancellationException expected) { assertTrue(cancellation.isCancelled()); }
    }
    @Test public void cancellationStopsBetweenTcpRounds() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 16, InetAddress.getLoopbackAddress())) {
            NetworkTools.Cancellation cancellation = token();
            List<String> progress = new ArrayList<>();
            try {
                NetworkTools.tcpLatency("127.0.0.1", server.getLocalPort(), 5, 1000, cancellation,
                        line -> { progress.add(line); cancellation.cancel(); });
                fail("must cancel remaining connections");
            } catch (CancellationException expected) { assertEquals(1, progress.size()); }
        }
    }
    @Test public void cancellationInvokesAndUnregistersCleanupHooks() {
        NetworkTools.Cancellation cancellation = token();
        AtomicInteger called = new AtomicInteger();
        cancellation.onCancel(called::incrementAndGet);
        NetworkTools.Cancellation.Registration removed = cancellation.onCancel(() -> called.addAndGet(10));
        removed.close();
        cancellation.cancel();
        assertEquals(1, called.get());
        cancellation.cancel();
        assertEquals(1, called.get());
    }
    @Test public void registeringAfterCancellationClosesResource() {
        NetworkTools.Cancellation cancellation = token(); cancellation.cancel();
        AtomicInteger called = new AtomicInteger();
        try { cancellation.onCancel(called::incrementAndGet); fail("must report cancellation"); }
        catch (CancellationException expected) { assertEquals(1, called.get()); }
    }
    @Test public void failingCleanupDoesNotPreventOtherResourcesClosing() {
        NetworkTools.Cancellation cancellation = token();
        AtomicInteger called = new AtomicInteger();
        cancellation.onCancel(() -> { throw new IllegalStateException("already closed"); });
        cancellation.onCancel(called::incrementAndGet);
        cancellation.cancel();
        assertEquals(1, called.get());
    }
    @Test public void httpReadsActualLocalResponseAndPreservesErrorStatus() throws Exception {
        String result = serveHttp("HTTP/1.1 418 Teapot\r\nContent-Length: 5\r\nContent-Type: text/plain\r\nX-Test: present\r\nConnection: close\r\n\r\nhello".getBytes(StandardCharsets.US_ASCII));
        assertTrue(result.contains("418 Teapot"));
        assertTrue(result.contains("X-Test: present"));
        assertTrue(result.contains("hello"));
    }
    @Test public void httpBodyIsBounded() throws Exception {
        byte[] bytes = new byte[70000]; java.util.Arrays.fill(bytes, (byte)'x');
        ByteArrayOutputStream response = new ByteArrayOutputStream();
        response.write(("HTTP/1.1 200 OK\r\nContent-Length: " + bytes.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        response.write(bytes);
        String result = serveHttp(response.toByteArray());
        assertTrue(result.contains("[已截断]"));
        assertTrue(result.length() < 67000);
    }
    @Test public void httpDoesNotSilentlyFollowRedirects() throws Exception {
        String result = serveHttp("HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1:1/\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        assertTrue(result.contains("302 Found"));
        assertTrue(result.contains("未自动跟随重定向"));
    }
    private String serveHttp(byte[] response) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (ServerSocket server = new ServerSocket(0, 4, InetAddress.getLoopbackAddress())) {
            server.setSoTimeout(5000);
            Future<?> responder = executor.submit(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(5000); InputStream in = socket.getInputStream();
                    int state = 0, next;
                    while ((next = in.read()) != -1) {
                        int expected = state % 2 == 0 ? '\r' : '\n';
                        state = next == expected ? state + 1 : 0;
                        if (state == 4) break;
                    }
                    socket.getOutputStream().write(response); socket.getOutputStream().flush();
                } catch (Exception error) { throw new RuntimeException(error); }
            });
            String result = NetworkTools.http("http://127.0.0.1:" + server.getLocalPort(), token());
            responder.get(5, TimeUnit.SECONDS); return result;
        } finally { executor.shutdownNow(); }
    }
    @Test public void httpRejectsUnsupportedSchemeAndCredentials() throws Exception {
        for (String input : new String[]{"file:///etc/passwd", "http://user:pass@example.com"}) {
            try { NetworkTools.http(input, token()); fail(input); } catch (IllegalArgumentException expected) {}
        }
    }
    @Test public void tlsRejectsPlaintextUrl() throws Exception {
        try { NetworkTools.tls("http://127.0.0.1", token()); fail("must reject cleartext"); }
        catch (IllegalArgumentException expected) {}
    }
    @Test public void magicPacketRepeatsMacSixteenTimes() {
        byte[] packet = NetworkTools.magicPacket("01:23:45:67:89:AB");
        assertEquals(102, packet.length);
        for (int i = 0; i < 6; i++) assertEquals((byte)0xff, packet[i]);
        byte[] mac = new byte[]{1,0x23,0x45,0x67,(byte)0x89,(byte)0xab};
        for (int i = 0; i < 16; i++) assertArrayEquals(mac, java.util.Arrays.copyOfRange(packet, 6 + 6*i, 12 + 6*i));
    }
    @Test public void wolSendsActualUdpPacket() throws Exception {
        try (DatagramSocket receiver = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            receiver.setSoTimeout(3000);
            String result = NetworkTools.wakeOnLan("01:23:45:67:89:AB", "127.0.0.1", receiver.getLocalPort(), token());
            DatagramPacket packet = new DatagramPacket(new byte[128], 128); receiver.receive(packet);
            assertEquals(102, packet.getLength());
            assertTrue(result.contains("发送成功不表示设备已唤醒"));
        }
    }
    @Test public void invalidMacRejected() {
        for (String input : new String[]{"abc", "01:23:45:67:89:ZZ", "01:23:45:67:89", ""}) {
            try { NetworkTools.magicPacket(input); fail(input); } catch (IllegalArgumentException expected) {}
        }
    }
}
