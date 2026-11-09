package com.vmlive.networktoolkit;

import android.test.InstrumentationTestCase;
import com.vmlive.networktoolkit.core.NetworkTools;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Exercises the actual Android socket/HTTP implementation using deterministic local fixtures. */
@SuppressWarnings("deprecation")
public final class AndroidNetworkTest extends InstrumentationTestCase {
    public void testHttpResponseOnAndroid() throws Exception {
        ExecutorService server = Executors.newSingleThreadExecutor();
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            Future<?> fixture = server.submit(() -> {
                try (Socket socket = listener.accept()) {
                    socket.setSoTimeout(3000);
                    readHeaders(socket);
                    byte[] body = "android-network-fixture".getBytes(StandardCharsets.UTF_8);
                    socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().write(body);
                    socket.getOutputStream().flush();
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            String result = NetworkTools.execute("http", "http://127.0.0.1:" + listener.getLocalPort(), new NetworkTools.Cancellation(), null);
            assertTrue(result, result.contains("200 OK"));
            assertTrue(result, result.contains("android-network-fixture"));
            fixture.get(5, TimeUnit.SECONDS);
        } finally { server.shutdownNow(); }
    }

    public void testTcpHandshakeOnAndroid() throws Exception {
        ExecutorService server = Executors.newSingleThreadExecutor();
        try (ServerSocket listener = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))) {
            Future<?> fixture = server.submit(() -> {
                try {
                    listener.setSoTimeout(5000);
                    for (int i = 0; i < 4; i++) listener.accept().close();
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            String result = NetworkTools.execute("tcp", "127.0.0.1:" + listener.getLocalPort(), new NetworkTools.Cancellation(), null);
            assertTrue(result, result.contains("连接成功：4/4"));
            fixture.get(5, TimeUnit.SECONDS);
        } finally { server.shutdownNow(); }
    }

    public void testHttpCancellationClosesAndroidConnection() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch request = new CountDownLatch(1);
        NetworkTools.Cancellation cancel = new NetworkTools.Cancellation();
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            Future<?> fixture = workers.submit(() -> {
                try (Socket socket = listener.accept()) {
                    socket.setSoTimeout(5000);
                    readHeaders(socket);
                    socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 100\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                    request.countDown();
                    // Withhold body until cancellation closes the client's end of this socket.
                    try {
                        int end = socket.getInputStream().read();
                        if (end != -1) throw new java.io.IOException("Expected the cancelled client to close the connection");
                    } catch (java.net.SocketException reset) {
                        // Android may abort with TCP RST rather than a graceful EOF.
                        // Both prove closure; a timeout or reset before cancellation still fails.
                        if (!cancel.isCancelled()) throw reset;
                    }
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            Future<Boolean> call = workers.submit(() -> {
                try {
                    NetworkTools.execute("http", "http://127.0.0.1:" + listener.getLocalPort(), cancel, null);
                    return false;
                } catch (Exception expected) { return cancel.isCancelled(); }
            });
            assertTrue("The fixture did not receive the request", request.await(5, TimeUnit.SECONDS));
            long start = System.nanoTime();
            cancel.cancel();
            assertTrue("The request should be cancelled", call.get(3, TimeUnit.SECONDS));
            assertTrue("Cancellation should interrupt the response body read", System.nanoTime() - start < 3_000_000_000L);
            fixture.get(5, TimeUnit.SECONDS);
        } finally { cancel.cancel(); workers.shutdownNow(); }
    }

    private static void readHeaders(Socket socket) throws Exception {
        BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
        String line;
        while ((line = input.readLine()) != null && !line.isEmpty()) { /* until header terminator */ }
    }
}
