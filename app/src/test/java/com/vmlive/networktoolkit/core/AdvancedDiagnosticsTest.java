package com.vmlive.networktoolkit.core;

import org.junit.Test;
import static org.junit.Assert.*;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class AdvancedDiagnosticsTest {
    @Test public void parsesScreenshotPacketCountsAndProtocol() {
        for (int n : new int[]{3, 5, 10, 20}) {
            AdvancedDiagnostics.Config c = AdvancedDiagnostics.Config.parse("example.com count=" + n + " family=6 maxHops=16");
            assertEquals(n, c.count); assertEquals("6", c.family); assertEquals(16, c.maxHops); assertEquals("example.com", c.host);
        }
        assertEquals("4", AdvancedDiagnostics.Config.parse("127.0.0.1 3 IPv4 4").family);
    }
    @Test public void preservesIpv6Literal() {
        assertEquals("2001:db8::1", AdvancedDiagnostics.Config.parse("[2001:db8::1] count=3").host);
    }
    @Test public void rejectsUnsafeOrUnboundedArguments() {
        for (String text : new String[]{"", "--help", "example.com;touch", "example.com count=21", "example.com count=0", "example.com family=evil", "example.com maxHops=17", "example.com:443", "[::1]:443"}) {
            try { AdvancedDiagnostics.Config.parse(text); fail(text); }
            catch (IllegalArgumentException expected) { assertNotNull(expected.getMessage()); }
        }
    }
    @Test public void parsesActualPingAndTimeExceededForms() {
        String text = "PING example.com (192.0.2.1)\nFrom 192.0.2.254 icmp_seq=1 Time to live exceeded\n64 bytes from 192.0.2.1: icmp_seq=2 ttl=64 time=12.3 ms\n64 bytes from ::1: icmp_seq=3 ttl=64 time<1 ms\n";
        assertEquals(Arrays.asList("192.0.2.254", "192.0.2.1", "::1"), AdvancedDiagnostics.replySources(text));
        assertEquals(Arrays.asList(12.3, 1.0), AdvancedDiagnostics.replyTimes(text));
    }
    @Test public void udpActuallySendsAndReceivesLocalDatagrams() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (DatagramSocket server = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(3000);
            Future<?> fixture = worker.submit(() -> {
                try {
                    DatagramPacket packet = new DatagramPacket(new byte[512], 512); server.receive(packet);
                    assertEquals("hello UDP", new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8));
                    byte[] body = "echo from fixture".getBytes(StandardCharsets.UTF_8);
                    server.send(new DatagramPacket(body, body.length, packet.getSocketAddress()));
                } catch (Exception error) { throw new RuntimeException(error); }
            });
            String result = AdvancedDiagnostics.execute("udp", "127.0.0.1:" + server.getLocalPort() + " hello UDP", new NetworkTools.Cancellation(), null);
            assertTrue(result, result.contains("echo from fixture"));
            assertTrue(result, result.contains("实际发送：9 字节")); fixture.get(3, TimeUnit.SECONDS);
        } finally { worker.shutdownNow(); }
    }
    @Test public void udpCancellationClosesPendingRead() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor(); NetworkTools.Cancellation cancel = new NetworkTools.Cancellation();
        CountDownLatch sent = new CountDownLatch(1);
        try (DatagramSocket server = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"))) {
            Future<String> call = worker.submit(() -> AdvancedDiagnostics.execute("udp", "127.0.0.1:" + server.getLocalPort() + " wait", cancel, line -> sent.countDown()));
            assertTrue(sent.await(3, TimeUnit.SECONDS));
            long start = System.nanoTime(); cancel.cancel();
            try { call.get(1, TimeUnit.SECONDS); fail("Expected cancellation"); }
            catch (ExecutionException expected) { assertTrue(expected.getCause() instanceof CancellationException); }
            assertTrue(System.nanoTime() - start < 1_000_000_000L);
        } finally { cancel.cancel(); worker.shutdownNow(); }
    }
    @Test public void udpTimeoutIsNotReportedAsClosed() throws Exception {
        try (DatagramSocket silent = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"))) {
            String result = AdvancedDiagnostics.execute("udp", "127.0.0.1:" + silent.getLocalPort(), new NetworkTools.Cancellation(), null);
            assertTrue(result, result.contains("不能据此判定端口关闭"));
        }
    }
    @Test public void rejectsOversizedUdpMessageBeforeNetwork() throws Exception {
        char[] chars = new char[4097]; Arrays.fill(chars, 'x');
        try { AdvancedDiagnostics.execute("udp", "127.0.0.1:7 " + new String(chars), new NetworkTools.Cancellation(), null); fail(); }
        catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("4096")); }
    }
    @Test public void honoursPreCancelledWorkBeforeStartingProcesses() throws Exception {
        NetworkTools.Cancellation c = new NetworkTools.Cancellation(); c.cancel();
        try { AdvancedDiagnostics.execute("ping", "127.0.0.1", c, null); fail(); }
        catch (CancellationException expected) { assertTrue(c.isCancelled()); }
    }
}
