package com.vmlive.networktoolkit;

import android.test.InstrumentationTestCase;
import com.vmlive.networktoolkit.core.NetworkTools.Cancellation;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Exercises Android's JSON and HTTP implementations; all requests stay on loopback. */
@SuppressWarnings("deprecation")
public final class IpDiagnosticsAndroidTest extends InstrumentationTestCase {
    public void testActualAndroidJsonTextTraceAndStrictAddressParsing() throws Exception {
        ExecutorService server = Executors.newSingleThreadExecutor();
        try (ServerSocket listener = new ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"))) {
            listener.setSoTimeout(8000);
            Future<?> fixture = server.submit(() -> {
                try {
                    for (int i = 0; i < 4; i++) try (Socket socket = listener.accept()) {
                        socket.setSoTimeout(5000); String path = requestPath(socket);
                        String body;
                        if (path.equals("/json")) body = "{\"ip\":\"203.0.113.24\",\"country\":\"测试地区\",\"asn\":64500,\"organization\":\"Android fixture\",\"isp\":null}";
                        else if (path.equals("/ping0")) body = "198.51.100.9\n测试位置\nAS64501\nAndroid network\n";
                        else if (path.equals("/trace")) body = "ip=2001:db8::9\nloc=US\ncolo=SJC\ntls=TLSv1.3\nwarp=off\n";
                        else body = "localhost";
                        byte[] data = body.getBytes(StandardCharsets.UTF_8);
                        socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: " + data.length + "\r\nContent-Type: text/plain; charset=utf-8\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                        socket.getOutputStream().write(data); socket.getOutputStream().flush();
                    }
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            String base = "http://127.0.0.1:" + listener.getLocalPort();
            IpDiagnostics.Result json = IpDiagnostics.fetchForTest(provider("json", base + "/json", IpDiagnostics.Format.IP_SB_JSON, 4), new Cancellation());
            assertTrue(json.error, json.success); assertEquals("203.0.113.24", json.fields.get("IP"));
            assertEquals("64500", json.fields.get("ASN")); assertEquals("测试地区", json.fields.get("国家")); assertFalse(json.fields.containsKey("ISP"));
            IpDiagnostics.Result ping0 = IpDiagnostics.fetchForTest(provider("ping0", base + "/ping0", IpDiagnostics.Format.PING0_TEXT, 4), new Cancellation());
            assertTrue(ping0.error, ping0.success); assertEquals("AS64501", ping0.fields.get("ASN")); assertFalse(ping0.fields.containsKey("风险值"));
            IpDiagnostics.Result trace = IpDiagnostics.fetchForTest(provider("trace", base + "/trace", IpDiagnostics.Format.CLOUDFLARE_TRACE, 6), new Cancellation());
            assertTrue(trace.error, trace.success); assertEquals("IPv6", trace.fields.get("地址族")); assertEquals("SJC", trace.fields.get("Cloudflare 节点"));
            IpDiagnostics.Result invalid = IpDiagnostics.fetchForTest(provider("invalid", base + "/invalid", IpDiagnostics.Format.PLAIN, 0), new Cancellation());
            assertFalse("A hostname must not be accepted as a provider's IP", invalid.success); assertTrue(invalid.fields.isEmpty());
            fixture.get(5, TimeUnit.SECONDS);
        } finally { server.shutdownNow(); }
    }

    public void testCancellationClosesActualAndroidHttpResponse() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(2);
        Cancellation cancel = new Cancellation(); CountDownLatch arrived = new CountDownLatch(1), closed = new CountDownLatch(1);
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            listener.setSoTimeout(5000);
            Future<?> fixture = workers.submit(() -> {
                try (Socket socket = listener.accept()) {
                    socket.setSoTimeout(5000); requestPath(socket);
                    socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 100\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush(); arrived.countDown();
                    try {
                        if (socket.getInputStream().read() == -1) closed.countDown();
                    } catch (SocketException reset) {
                        if (!cancel.isCancelled()) throw reset;
                        closed.countDown();
                    }
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            Future<IpDiagnostics.Result> request = workers.submit(() -> IpDiagnostics.fetchForTest(
                    provider("cancel", "http://127.0.0.1:" + listener.getLocalPort(), IpDiagnostics.Format.PLAIN, 4), cancel));
            assertTrue("The Android client must actually send the request", arrived.await(5, TimeUnit.SECONDS));
            long started = System.nanoTime();
            cancel.cancel();
            try { request.get(3, TimeUnit.SECONDS); fail("Cancellation must propagate instead of yielding an IP result"); }
            catch (ExecutionException expected) { assertTrue(expected.getCause() instanceof java.util.concurrent.CancellationException); }
            assertTrue("Cancellation must really close the response socket", closed.await(3, TimeUnit.SECONDS));
            assertTrue("Android cancellation must complete within three seconds", System.nanoTime() - started < 3_000_000_000L);
            fixture.get(5, TimeUnit.SECONDS);
        } finally { cancel.cancel(); workers.shutdownNow(); }
    }

    private static IpDiagnostics.Provider provider(String id, String url, IpDiagnostics.Format format, int family) {
        return new IpDiagnostics.Provider(id, id, url, format, family);
    }
    private static String requestPath(Socket socket) throws Exception {
        BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
        String line = input.readLine(); if (line == null) throw new java.io.IOException("Empty request");
        String path = line.split(" ")[1]; while ((line = input.readLine()) != null && !line.isEmpty()) { }
        return path;
    }
}
