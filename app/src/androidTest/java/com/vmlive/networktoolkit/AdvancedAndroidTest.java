package com.vmlive.networktoolkit;

import android.test.InstrumentationTestCase;
import com.vmlive.networktoolkit.core.AdvancedDiagnostics;
import com.vmlive.networktoolkit.core.DnsClient;
import com.vmlive.networktoolkit.core.NetworkTools;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Real ICMP subprocesses and DNS UDP run under the app UID, not the privileged adb shell. */
@SuppressWarnings("deprecation")
public final class AdvancedAndroidTest extends InstrumentationTestCase {
    public void testRealIcmpPingLoopbackUnderAppUid() throws Exception {
        String result = AdvancedDiagnostics.execute("ping", "127.0.0.1 count=1 family=4", new NetworkTools.Cancellation(), null);
        assertTrue("Expected an actual ICMP echo reply, not a TCP substitute: " + result,
                result.matches("(?s).*bytes from 127\\.0\\.0\\.1[^\\n]*time[=<].*"));
        assertFalse(result, result.contains("ICMP 能力受系统限制"));
    }

    public void testActualTtlProbeReachesLoopback() throws Exception {
        String result = AdvancedDiagnostics.execute("trace", "127.0.0.1 count=1 family=4 maxHops=2", new NetworkTools.Cancellation(), null);
        assertTrue(result, result.contains("已到达目标。"));
        assertTrue(result, result.contains("响应 1/1"));
        assertFalse(result, result.contains("系统路由探测不可用"));
    }

    public void testCancellationStopsActivePingProcess() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor(); NetworkTools.Cancellation cancel = new NetworkTools.Cancellation();
        CountDownLatch firstReply = new CountDownLatch(1);
        try {
            Future<String> work = worker.submit(() -> AdvancedDiagnostics.execute("ping", "127.0.0.1 count=20 family=4", cancel,
                    line -> { if (line.contains("bytes from") && line.contains("icmp_seq")) firstReply.countDown(); }));
            assertTrue("System ping did not produce an actual echo reply", firstReply.await(15, TimeUnit.SECONDS));
            long start = System.nanoTime(); cancel.cancel();
            try { work.get(3, TimeUnit.SECONDS); fail("Expected cancellation before the remaining 19 probes"); }
            catch (ExecutionException expected) { assertTrue(expected.getCause().toString(), expected.getCause() instanceof CancellationException); }
            assertTrue("Cancelling the subprocess must be prompt", System.nanoTime() - start < 3_000_000_000L);
        } finally { cancel.cancel(); worker.shutdownNow(); }
    }

    public void testDnsWireQueryOnAndroidWithLocalFixture() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (DatagramSocket server = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(5000);
            Future<?> fixture = worker.submit(() -> {
                try {
                    DatagramPacket query = new DatagramPacket(new byte[512], 512); server.receive(query);
                    byte[] question = Arrays.copyOfRange(query.getData(), query.getOffset(), query.getOffset() + query.getLength());
                    question[2] = (byte) 0x81; question[3] = (byte) 0x80;
                    question[6] = 0; question[7] = 1;
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream(); bytes.write(question);
                    DataOutputStream response = new DataOutputStream(bytes);
                    response.writeShort(0xc00c); response.writeShort(1); response.writeShort(1); response.writeInt(30);
                    response.writeShort(4); response.write(new byte[]{127, 0, 0, 42});
                    byte[] payload = bytes.toByteArray();
                    server.send(new DatagramPacket(payload, payload.length, query.getSocketAddress()));
                } catch (Exception error) { throw new RuntimeException(error); }
            });
            String result = DnsClient.query("device-fixture.test", "A", "127.0.0.1:" + server.getLocalPort(), new NetworkTools.Cancellation());
            assertTrue(result, result.contains("device-fixture.test.\t30\tIN\tA\t127.0.0.42"));
            assertTrue(result, result.contains("TRANSPORT: UDP"));
            fixture.get(5, TimeUnit.SECONDS);
        } finally { worker.shutdownNow(); }
    }
}
