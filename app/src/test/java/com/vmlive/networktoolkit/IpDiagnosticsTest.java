package com.vmlive.networktoolkit;

import com.vmlive.networktoolkit.core.NetworkTools.Cancellation;
import org.junit.Test;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public final class IpDiagnosticsTest {
    private static IpDiagnostics.Provider provider(String id, String url, IpDiagnostics.Format format, int family) {
        return new IpDiagnostics.Provider(id, id, url, format, family);
    }
    @Test public void plainIpv4ActuallyRequestsFixture() throws Exception {
        try (Fixture f = new Fixture((socket, path) -> reply(socket, 200, "198.51.100.24\n"))) {
            IpDiagnostics.Result r = IpDiagnostics.fetchForTest(provider("v4", f.url("/ip"), IpDiagnostics.Format.PLAIN, 4), new Cancellation());
            assertTrue(r.error, r.success); assertEquals("198.51.100.24", r.fields.get("IP")); assertEquals("IPv4", r.fields.get("地址族"));
            assertTrue(r.format().contains(f.url("/ip"))); assertTrue(r.elapsedMs >= 0);
        }
    }
    @Test public void ipv6IsLiteralAndFamilyChecked() throws Exception {
        try (Fixture f = new Fixture((socket, path) -> reply(socket, 200, "2001:db8::1234"))) {
            assertTrue(IpDiagnostics.fetchForTest(provider("v6", f.url("/"), IpDiagnostics.Format.PLAIN, 6), new Cancellation()).success);
            IpDiagnostics.Result mismatch = IpDiagnostics.fetchForTest(provider("v4", f.url("/"), IpDiagnostics.Format.PLAIN, 4), new Cancellation());
            assertFalse(mismatch.success); assertTrue(mismatch.error.contains("地址族")); assertTrue(mismatch.fields.isEmpty());
        }
    }
    @Test public void ipSbJsonKeepsRealFieldsOnly() throws Exception {
        String json = "{\"ip\":\"203.0.113.8\",\"country\":\"测试地区\",\"region\":\"Region\",\"city\":\"City\",\"asn\":64500,\"organization\":\"Fixture Org\",\"isp\":null}";
        try (Fixture f = new Fixture((socket, path) -> reply(socket, 200, json))) {
            IpDiagnostics.Result r = IpDiagnostics.fetchForTest(provider("json", f.url("/"), IpDiagnostics.Format.IP_SB_JSON, 0), new Cancellation());
            assertTrue(r.error, r.success); assertEquals("64500", r.fields.get("ASN")); assertEquals("Fixture Org", r.fields.get("组织"));
            assertFalse(r.fields.containsKey("ISP")); assertFalse(r.fields.containsKey("风险值"));
        }
    }
    @Test public void cloudflareTracePreservesReportedValues() throws Exception {
        try (Fixture f = new Fixture((socket, path) -> reply(socket, 200, "ip=2001:db8::5\ncolo=SJC\nloc=US\ntls=TLSv1.3\nhttp=h2\nwarp=off\nsni=plaintext\n"))) {
            IpDiagnostics.Result r = IpDiagnostics.fetchForTest(provider("trace", f.url("/"), IpDiagnostics.Format.CLOUDFLARE_TRACE, 0), new Cancellation());
            assertTrue(r.error, r.success); assertEquals("SJC", r.fields.get("Cloudflare 节点")); assertEquals("off", r.fields.get("WARP")); assertEquals("TLSv1.3", r.fields.get("TLS"));
            assertFalse(r.fields.containsKey("城市"));
        }
    }
    @Test public void ping0FourLinesDoNotInventPaidScores() throws Exception {
        try (Fixture f = new Fixture((socket, path) -> reply(socket, 200, "203.0.113.19\r\n测试地区\r\nAS64501\r\nFixture Network\r\n"))) {
            IpDiagnostics.Result r = IpDiagnostics.fetchForTest(provider("ping0", f.url("/geo"), IpDiagnostics.Format.PING0_TEXT, 0), new Cancellation());
            assertTrue(r.error, r.success); assertEquals("AS64501", r.fields.get("ASN")); assertEquals("Fixture Network", r.fields.get("组织"));
            assertEquals(5, r.fields.size()); assertFalse(r.format().contains("纯净度"));
        }
    }
    @Test public void errorStatusNeverBecomesSuccessfulIp() throws Exception {
        try (Fixture f = new Fixture((socket, path) -> reply(socket, 403, "198.51.100.1"))) {
            IpDiagnostics.Result r = IpDiagnostics.fetchForTest(provider("denied", f.url("/"), IpDiagnostics.Format.PLAIN, 4), new Cancellation());
            assertFalse(r.success); assertTrue(r.error.contains("403")); assertTrue(r.fields.isEmpty());
        }
    }
    @Test public void redirectsAreNotFollowed() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        try (Fixture f = new Fixture((socket, path) -> {
            requests.incrementAndGet(); socket.getOutputStream().write("HTTP/1.1 302 Found\r\nLocation: /different\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        })) {
            IpDiagnostics.Result r = IpDiagnostics.fetchForTest(provider("redirect", f.url("/"), IpDiagnostics.Format.PLAIN, 4), new Cancellation());
            assertFalse(r.success); assertTrue(r.error.contains("302")); assertEquals(1, requests.get());
        }
    }
    @Test public void htmlMalformedJsonAndWrongPing0SchemaAreRejected() throws Exception {
        assertInvalid(IpDiagnostics.Format.PLAIN, "<html>198.51.100.1</html>");
        assertInvalid(IpDiagnostics.Format.IP_SB_JSON, "{\"message\":\"denied\"}");
        assertInvalid(IpDiagnostics.Format.IP_SB_JSON, "{\"ip\":1234}");
        assertInvalid(IpDiagnostics.Format.PING0_TEXT, "198.51.100.1\n<bad>\nAS1\nOrg");
        assertInvalid(IpDiagnostics.Format.PING0_TEXT, "198.51.100.1\nLocation\nAS1\nOrg\nextra");
        assertInvalid(IpDiagnostics.Format.CLOUDFLARE_TRACE, "ip=198.51.100.1\nip=198.51.100.2");
        assertInvalid(IpDiagnostics.Format.CLOUDFLARE_TRACE, "<!DOCTYPE html>\nip=198.51.100.1\n<html>error</html>");
    }
    @Test public void invalidAddressFormsAreNotResolvedAsHostnames() throws Exception {
        for (String bad : Arrays.asList("localhost", "127.1", "1.2.3.999", "01.2.3.4", "2001:bad", "2001::db8::1", "fe80::1%wlan0", "198.51.100.1 extra")) assertInvalid(IpDiagnostics.Format.PLAIN, bad);
    }
    @Test public void publicMethodsRequireHttpsEvenForLocalhost() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        try (Fixture f = new Fixture((socket, path) -> { requests.incrementAndGet(); reply(socket, 200, "198.51.100.1"); })) {
            IpDiagnostics.Result r = IpDiagnostics.fetch(provider("blocked", f.url("/"), IpDiagnostics.Format.PLAIN, 4), new Cancellation());
            assertFalse(r.success); assertTrue(r.error.contains("HTTPS")); assertEquals(0, requests.get());
        }
    }
    @Test public void responseCapAppliesWithAndWithoutContentLength() throws Exception {
        String body = String.join("", Collections.nCopies(65537, "x"));
        try (Fixture f = new Fixture((socket, path) -> {
            if (path.equals("/declared")) reply(socket, 200, body);
            else { socket.getOutputStream().write("HTTP/1.1 200 OK\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII)); socket.getOutputStream().write(body.getBytes(StandardCharsets.US_ASCII)); }
        })) {
            for (String path : Arrays.asList("/declared", "/stream")) {
                IpDiagnostics.Result r = IpDiagnostics.fetchForTest(provider("limit", f.url(path), IpDiagnostics.Format.PLAIN, 4), new Cancellation());
                assertFalse(r.success); assertTrue(r.error, r.error.contains("64 KiB"));
            }
        }
    }
    @Test public void providersFailIndependentlyWithAtMostThreeConcurrentRequests() throws Exception {
        AtomicInteger active = new AtomicInteger(), maximum = new AtomicInteger();
        try (Fixture f = new Fixture((socket, path) -> {
            int now = active.incrementAndGet(); maximum.accumulateAndGet(now, Math::max);
            try { Thread.sleep(70); } finally { active.decrementAndGet(); }
            int id = Integer.parseInt(path.substring(1)); reply(socket, id == 2 ? 503 : 200, "198.51.100." + (id + 1));
        })) {
            List<IpDiagnostics.Provider> providers = new ArrayList<>();
            for (int i = 0; i < 5; i++) providers.add(provider("p" + i, f.url("/" + i), IpDiagnostics.Format.PLAIN, 4));
            List<IpDiagnostics.Result> observations = new ArrayList<>();
            List<IpDiagnostics.Result> results = IpDiagnostics.runForTest(providers, new Cancellation(), observations::add);
            assertEquals(5, results.size()); assertEquals(5, observations.size()); assertTrue(maximum.get() <= 3);
            for (int i = 0; i < 5; i++) { assertEquals("p" + i, results.get(i).id); assertEquals(i != 2, results.get(i).success); }
            try { results.clear(); fail("Results must be immutable"); } catch (UnsupportedOperationException expected) { }
            try { results.get(0).fields.put("fake", "value"); fail("Fields must be immutable"); } catch (UnsupportedOperationException expected) { }
        }
    }
    @Test public void cancellationActuallyClosesPendingResponse() throws Exception {
        CountDownLatch arrived = new CountDownLatch(1), closed = new CountDownLatch(1); ExecutorService caller = Executors.newSingleThreadExecutor(); Cancellation cancel = new Cancellation();
        try (Fixture f = new Fixture((socket, path) -> {
            socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 100\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII)); socket.getOutputStream().flush(); arrived.countDown();
            try { if (socket.getInputStream().read() == -1) closed.countDown(); } catch (java.net.SocketException reset) { closed.countDown(); }
        })) {
            Future<?> task = caller.submit(() -> IpDiagnostics.fetchForTest(provider("cancel", f.url("/"), IpDiagnostics.Format.PLAIN, 4), cancel));
            assertTrue(arrived.await(3, TimeUnit.SECONDS)); cancel.cancel();
            try { task.get(2, TimeUnit.SECONDS); fail("Cancellation must propagate"); } catch (ExecutionException expected) { assertTrue(expected.getCause() instanceof java.util.concurrent.CancellationException); }
            assertTrue("The response socket must really close", closed.await(2, TimeUnit.SECONDS));
        } finally { cancel.cancel(); caller.shutdownNow(); }
    }
    @Test public void preCancelledRunDoesNotContactProviders() {
        Cancellation cancel = new Cancellation(); cancel.cancel();
        try { IpDiagnostics.run(cancel, result -> fail("No result callback after pre-cancel")); fail("Expected cancellation"); }
        catch (java.util.concurrent.CancellationException expected) { }
    }
    private static void assertInvalid(IpDiagnostics.Format format, String body) throws Exception {
        try (Fixture f = new Fixture((socket, path) -> reply(socket, 200, body))) {
            IpDiagnostics.Result result = IpDiagnostics.fetchForTest(provider("invalid", f.url("/"), format, 0), new Cancellation());
            assertFalse("Unexpectedly accepted: " + body, result.success); assertTrue(result.fields.isEmpty());
        }
    }
    private static void reply(Socket socket, int status, String body) throws Exception {
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        socket.getOutputStream().write(("HTTP/1.1 " + status + " Fixture\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: " + data.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().write(data); socket.getOutputStream().flush();
    }
    private interface Handler { void handle(Socket socket, String path) throws Exception; }
    private static final class Fixture implements AutoCloseable {
        final ServerSocket listener;
        final java.util.Set<Socket> sockets = ConcurrentHashMap.newKeySet();
        final ExecutorService workers = Executors.newCachedThreadPool(r -> { Thread t = new Thread(r); t.setDaemon(true); return t; });
        Fixture(Handler handler) throws Exception {
            listener = new ServerSocket(0, 20, InetAddress.getByName("127.0.0.1"));
            workers.submit(() -> {
                while (!listener.isClosed()) {
                    try {
                        Socket socket = listener.accept(); sockets.add(socket);
                        workers.submit(() -> { try (Socket own = socket) {
                            own.setSoTimeout(5000); BufferedReader reader = new BufferedReader(new InputStreamReader(own.getInputStream(), StandardCharsets.US_ASCII));
                            String first = reader.readLine(); if (first == null) return;
                            String line; while ((line = reader.readLine()) != null && !line.isEmpty()) { }
                            handler.handle(own, first.split(" ")[1]);
                        } catch (Exception ignored) { /* Client cancellation and cap closes are expected. */ }
                        finally { sockets.remove(socket); } });
                    } catch (Exception stopped) { break; }
                }
            });
        }
        String url(String path) { return "http://127.0.0.1:" + listener.getLocalPort() + path; }
        @Override public void close() throws Exception { listener.close(); for (Socket s : sockets) s.close(); workers.shutdownNow(); }
    }
}
