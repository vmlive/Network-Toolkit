package com.vmlive.networktoolkit.core;

import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

public final class DnsClientTest {
    private static final int ID = 0x4321;
    private static final String NAME = "example.test.";
    private byte[] query(int type) throws Exception { return DnsClient.buildQuery(NAME, type, ID); }

    private byte[] answer(byte[] question, int type, byte[] data, long ttl) throws Exception {
        byte[] prefix = question.clone(); prefix[2] = (byte) 0x81; prefix[3] = (byte) 0x80; prefix[7] = 1;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); bytes.write(prefix);
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeShort(0xc00c); out.writeShort(type); out.writeShort(1); out.writeInt((int) ttl); out.writeShort(data.length); out.write(data);
        return bytes.toByteArray();
    }
    private String parsed(int type, byte[] data) throws Exception { return DnsClient.parseResponse(answer(query(type), type, data, 120), ID, NAME, type); }

    @Test public void parsesCompressedAWithUnsignedTtl() throws Exception {
        String result = DnsClient.parseResponse(answer(query(1), 1, new byte[]{(byte)203, 0, 113, 7}, 0xffffffffL), ID, NAME, 1);
        assertTrue(result, result.contains("4294967295\tIN\tA\t203.0.113.7"));
    }

    @Test public void parsesAAAARecord() throws Exception {
        String result = parsed(28, InetAddress.getByName("2001:db8::42").getAddress());
        assertTrue(result, result.contains("\tAAAA\t2001:db8:"));
    }

    @Test public void parsesCnameMxAndTxtRecords() throws Exception {
        assertTrue(parsed(5, new byte[]{(byte)0xc0, 12}).contains("\tCNAME\texample.test."));
        assertTrue(parsed(15, new byte[]{0, 10, (byte)0xc0, 12}).contains("\tMX\t10 example.test."));
        assertTrue(parsed(16, new byte[]{3, 'f', 'o', 'o', 3, 'b', 'a', 'r'}).contains("\tTXT\t\"foo\" \"bar\""));
    }

    @Test public void parsesSoaRecord() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes);
        out.writeShort(0xc00c); out.writeShort(0xc00c);
        for (int value : new int[]{1, 3600, 600, 86400, 300}) out.writeInt(value);
        assertTrue(parsed(6, bytes.toByteArray()).contains("SOA\texample.test. example.test. 1 3600 600 86400 300"));
    }

    @Test public void convertsBothFamiliesToReverseNames() throws Exception {
        assertEquals("7.113.0.203.in-addr.arpa.", DnsClient.questionName("203.0.113.7", "PTR"));
        assertEquals("1.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.ip6.arpa.", DnsClient.questionName("::1", "PTR"));
    }

    @Test public void normalizesUnicodeAndAllowsServiceLabels() throws Exception {
        assertEquals("xn--fiqs8s.example.", DnsClient.questionName("中国.example", "A"));
        assertEquals("_sip._tcp.example.test.", DnsClient.questionName("_sip._tcp.example.test", "SRV"));
    }

    @Test public void rejectsWrongTransactionAndQuestion() throws Exception {
        byte[] response = answer(query(1), 1, new byte[]{127,0,0,1}, 1);
        invalid(response, ID+1, NAME, 1, "ID");
        invalid(response, ID, "different.test", 1, "question");
        invalid(response, ID, NAME, 28, "question");
    }

    @Test public void rejectsTruncatedAndMalformedData() throws Exception {
        invalid(new byte[11], ID, NAME, 1, "长度");
        byte[] badAddress = answer(query(1), 1, new byte[]{127,0,0}, 1);
        invalid(badAddress, ID, NAME, 1, "长度");
        byte[] valid = answer(query(1), 1, new byte[]{127,0,0,1}, 1);
        invalid(Arrays.copyOf(valid, valid.length-1), ID, NAME, 1, "截断");
        valid[2] |= 2; invalid(valid, ID, NAME, 1, "TC=1");
    }

    @Test public void rejectsCompressionLoopAndWrongClass() throws Exception {
        byte[] q = query(5); int rdata = q.length + 12;
        byte[] response = answer(q, 5, new byte[]{(byte) (0xc0 | (rdata >>> 8)), (byte) rdata}, 1);
        invalid(response, ID, NAME, 5, "指针");
        byte[] wrongClass = answer(query(1), 1, new byte[]{127,0,0,1}, 1);
        wrongClass[query(1).length + 5] = 3;
        invalid(wrongClass, ID, NAME, 1, "class");
    }

    @Test public void preservesNxDomainAndNoDataDistinction() throws Exception {
        byte[] response = query(1); response[2] = (byte) 0x81; response[3] = (byte) 0x83;
        invalid(response, ID, NAME, 1, "NXDOMAIN");
        response[3] = (byte) 0x80;
        assertTrue(DnsClient.parseResponse(response, ID, NAME, 1).contains("NODATA"));
    }

    @Test public void rejectsExtendedEdnsErrorsInsteadOfReportingNoError() throws Exception {
        byte[] prefix = query(1); prefix[2] = (byte) 0x81; prefix[3] = (byte) 0x80; prefix[11] = 1;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); bytes.write(prefix); DataOutputStream out = new DataOutputStream(bytes);
        out.writeByte(0); out.writeShort(41); out.writeShort(1232); out.writeInt(0x01000000); out.writeShort(0);
        invalid(bytes.toByteArray(), ID, NAME, 1, "EDNS");
    }

    @Test public void queriesRealLocalUdpServer() throws Exception {
        ExecutorService workers = Executors.newSingleThreadExecutor();
        try (DatagramSocket server = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(3000);
            Future<?> fixture = workers.submit(() -> {
                try {
                    DatagramPacket request = new DatagramPacket(new byte[512], 512); server.receive(request);
                    byte[] question = Arrays.copyOf(request.getData(), request.getLength());
                    byte[] response = answer(question, 1, new byte[]{(byte)203,0,113,7}, 120);
                    server.send(new DatagramPacket(response, response.length, request.getSocketAddress()));
                } catch (Exception error) { throw new RuntimeException(error); }
            });
            String result = DnsClient.query(NAME, "A", "127.0.0.1:"+server.getLocalPort(), new NetworkTools.Cancellation());
            assertTrue(result, result.contains("203.0.113.7")); assertTrue(result, result.contains("120\tIN\tA"));
            assertTrue(result, result.contains("TRANSPORT: UDP")); fixture.get(3, TimeUnit.SECONDS);
        } finally { workers.shutdownNow(); }
    }

    @Test public void cancellationClosesInFlightUdpReceive() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(2); CountDownLatch received = new CountDownLatch(1);
        NetworkTools.Cancellation cancel = new NetworkTools.Cancellation();
        try (DatagramSocket server = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(3000);
            Future<?> fixture = workers.submit(() -> {
                try { server.receive(new DatagramPacket(new byte[512], 512)); received.countDown(); }
                catch (IOException error) { throw new RuntimeException(error); }
            });
            Future<Boolean> query = workers.submit(() -> {
                try { DnsClient.query(NAME, "A", "127.0.0.1:"+server.getLocalPort(), cancel); return false; }
                catch (CancellationException expected) { return true; }
            });
            assertTrue(received.await(3, TimeUnit.SECONDS)); cancel.cancel();
            assertTrue(query.get(1, TimeUnit.SECONDS)); fixture.get(1, TimeUnit.SECONDS);
        } finally { cancel.cancel(); workers.shutdownNow(); }
    }

    @Test public void systemResolverFiltersRequestedFamilyAndHonorsLimitations() throws Exception {
        String result = DnsClient.query("localhost", "A", "", new NetworkTools.Cancellation());
        assertTrue(result, result.contains("IN\tA\t")); assertFalse(result, result.contains("IN\tAAAA\t"));
        try { DnsClient.query(NAME, "MX", "", new NetworkTools.Cancellation()); fail(); }
        catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("DNS 服务器")); }
        try { DnsClient.query(NAME, "A", "dns.example.test", new NetworkTools.Cancellation()); fail(); }
        catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("数字地址")); }
    }

    private void invalid(byte[] response, int id, String name, int type, String message) throws Exception {
        try { DnsClient.parseResponse(response, id, name, type); fail("Expected malformed DNS response rejection"); }
        catch (IOException expected) { assertTrue(expected.getMessage(), expected.getMessage().contains(message)); }
    }
}
