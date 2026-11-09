package com.vmlive.networktoolkit;

import static org.junit.Assert.*;
import com.vmlive.networktoolkit.core.NetworkTools.Cancellation;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public class MeasurementsTest {
    @Test public void catalogMatchesProvidedCategoriesWithoutDuplicateIds() {
        assertEquals(26,Measurements.CATALOG.size());
        java.util.Set<String> ids=new java.util.HashSet<>(), categories=new java.util.HashSet<>();
        for(Measurements.Site site:Measurements.CATALOG) { assertTrue(ids.add(site.id)); categories.add(site.category); }
        assertEquals(6,categories.size());
    }
    @Test public void siteFieldsRejectCredentialAndProgressWireInjection() {
        for(String host:Arrays.asList("example.com/path","user:password@example.com","example.com?q=x","example.com#x","")) {
            try { new Measurements.Site("test","自定义","测试",host); fail(host); } catch(IllegalArgumentException expected){}
        }
        try { new Measurements.Site("test|x","自定义","测试","example.com"); fail(); } catch(IllegalArgumentException expected){}
    }
    @Test public void publicSpeedRejectsCleartextBeforeSendingRequests() throws Exception {
        try { Measurements.speed("http://127.0.0.1/","https://example.com/",1,new Cancellation(),null); fail(); }
        catch(IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("HTTPS")); }
    }
    @Test public void invalidTrafficLimitsRejected() throws Exception {
        for(int mb:new int[]{0,-1,21,200}) {
            try { Measurements.speed("","",mb,new Cancellation(),null); fail(); } catch(IllegalArgumentException expected){}
        }
    }
    @Test public void latencyMeanAndConsecutiveJitterUseRealSamples() {
        assertArrayEquals(new double[]{20,15},Measurements.latencyStats(new double[]{10,30,20}),.0001);
        assertTrue(Double.isNaN(Measurements.latencyStats(new double[]{10})[1]));
        try { Measurements.latencyStats(new double[]{-1}); fail(); } catch(IllegalArgumentException expected){}
    }
    @Test public void actualDownloadIsCappedAndUploadHasExactFixedLength() throws Exception {
        AtomicInteger heads=new AtomicInteger(); AtomicLong uploaded=new AtomicLong();
        try(Fixture fixture=new Fixture((request,socket)->{
            if(request.method.equals("HEAD")) { heads.incrementAndGet(); respond(socket,200,1200000,null); }
            else if(request.method.equals("POST")) { uploaded.set(request.bodyBytes); respond(socket,200,0,null); }
            else respond(socket,200,1200000,new byte[1200000]);
        })) {
            List<String> events=Collections.synchronizedList(new ArrayList<>());
            String result=Measurements.speedForTest(fixture.url(),fixture.url(),1,new Cancellation(),events::add);
            assertEquals(5,heads.get()); assertEquals(200000,uploaded.get());
            assertTrue(result,result.contains("下载数据：1.000 MB"));
            assertTrue(result,result.contains("上传数据：0.200 MB"));
            assertTrue(result,result.contains("成功 5/5"));
            assertTrue(result,result.contains("达到单次流量上限"));
            assertTrue(events.stream().anyMatch(e->e.startsWith("latency|")));
            assertTrue(events.stream().anyMatch(e->e.startsWith("download|")&&e.endsWith("|1000000")));
            assertTrue(events.stream().anyMatch(e->e.startsWith("upload|")&&e.endsWith("|200000")));
        }
    }
    @Test public void transferFailureDoesNotFabricateZeroMbpsOrFollowRedirect() throws Exception {
        AtomicInteger gets=new AtomicInteger();
        try(Fixture fixture=new Fixture((request,socket)->{
            if(request.method.equals("GET")) { gets.incrementAndGet(); respond(socket,302,0,null); }
            else if(request.method.equals("POST")) respond(socket,500,0,null);
            else respond(socket,200,0,null);
        })) {
            List<String> events=new ArrayList<>();
            String result=Measurements.speedForTest(fixture.url(),fixture.url(),1,new Cancellation(),events::add);
            assertEquals(1,gets.get()); assertTrue(result,result.contains("下载失败：HTTP 302"));
            assertTrue(result,result.contains("上传失败：HTTP 500")); assertFalse(result,result.contains("0.00 Mbps"));
            assertTrue(events.contains("download_error|HTTP 302")); assertTrue(events.contains("upload_error|HTTP 500"));
        }
    }
    @Test public void siteHeadReportsRealStatusAndFailureSeparately() throws Exception {
        AtomicInteger heads=new AtomicInteger();
        try(Fixture fixture=new Fixture((request,socket)->{
            assertEquals("HEAD",request.method); heads.incrementAndGet(); respond(socket,403,0,null);
        }); ServerSocket closed=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))) {
            int closedPort=closed.getLocalPort(); closed.close();
            List<String> events=new ArrayList<>();
            List<Measurements.Site> sites=Arrays.asList(new Measurements.Site("good","测试","HEAD 服务",fixture.host()),new Measurements.Site("closed","测试","关闭的端口","127.0.0.1:"+closedPort));
            String result=Measurements.sitesForTest(sites,new Cancellation(),events::add);
            assertEquals(1,heads.get()); assertEquals(2,events.size());
            assertTrue(result,result.contains("HTTP 403")); assertTrue(result,result.contains("失败 / 未确认"));
            assertTrue(events.stream().anyMatch(e->e.startsWith("site|good|")&&e.endsWith("|403")));
            assertTrue(events.stream().anyMatch(e->e.startsWith("site|closed|-1|")));
        }
    }
    @Test public void siteScanUsesAtMostSixConcurrentRequests() throws Exception {
        AtomicInteger active=new AtomicInteger(),max=new AtomicInteger();
        try(Fixture fixture=new Fixture((request,socket)->{
            int n=active.incrementAndGet(); max.accumulateAndGet(n,Math::max);
            try { Thread.sleep(40); respond(socket,200,0,null); } finally { active.decrementAndGet(); }
        })) {
            List<Measurements.Site> sites=new ArrayList<>();
            for(int i=0;i<15;i++) sites.add(new Measurements.Site("site"+i,"测试","服务"+i,fixture.host()));
            List<String> events=new ArrayList<>(); Measurements.sitesForTest(sites,new Cancellation(),events::add);
            assertEquals(15,events.size()); assertTrue("Actual concurrency "+max.get(),max.get()<=6); assertTrue(max.get()>1);
        }
    }
    @Test public void duplicateSiteIdsRejectedBeforeNetworking() throws Exception {
        Measurements.Site s=new Measurements.Site("same","测试","服务","example.com");
        try { Measurements.sites(Arrays.asList(s,s),new Cancellation(),null); fail(); } catch(IllegalArgumentException expected){}
    }
    @Test public void alreadyCancelledMeasurementDoesNotContactTarget() throws Exception {
        Cancellation cancel=new Cancellation(); cancel.cancel();
        try { Measurements.speed("","",1,cancel,null); fail(); } catch(java.util.concurrent.CancellationException expected){}
        try { Measurements.sites(Measurements.CATALOG,cancel,null); fail(); } catch(java.util.concurrent.CancellationException expected){}
    }
    @Test public void cancellationInterruptsBlockedDownloadAndPreventsUpload() throws Exception {
        Cancellation cancel=new Cancellation(); CountDownLatch downloading=new CountDownLatch(1);
        AtomicInteger posts=new AtomicInteger(); ExecutorService worker=Executors.newSingleThreadExecutor();
        try(Fixture fixture=new Fixture((request,socket)->{
            if(request.method.equals("GET")) {
                respond(socket,200,1000000,null); downloading.countDown();
                try { socket.getInputStream().read(); } catch(java.net.SocketException closed) { if(!cancel.isCancelled())throw closed; }
            } else if(request.method.equals("POST")) { posts.incrementAndGet(); respond(socket,200,0,null); }
            else respond(socket,200,0,null);
        })) {
            Future<String> call=worker.submit(()->Measurements.speedForTest(fixture.url(),fixture.url(),1,cancel,null));
            assertTrue(downloading.await(3,TimeUnit.SECONDS)); cancel.cancel();
            try { call.get(3,TimeUnit.SECONDS); fail(); }
            catch(ExecutionException expected) { assertTrue(expected.getCause().toString(),expected.getCause() instanceof java.util.concurrent.CancellationException); }
            assertEquals(0,posts.get());
        } finally { cancel.cancel(); worker.shutdownNow(); }
    }

    private interface Handler { void handle(Request request,Socket socket) throws Exception; }
    private static final class Request {
        final String method; final long bodyBytes;
        Request(String method,long bodyBytes){this.method=method;this.bodyBytes=bodyBytes;}
    }
    private static final class Fixture implements AutoCloseable {
        final ServerSocket server; final ExecutorService workers;
        final AtomicReference<Throwable> failure=new AtomicReference<>();
        Fixture(Handler handler) throws Exception {
            server=new ServerSocket(0,32,InetAddress.getByName("127.0.0.1"));
            workers=Executors.newCachedThreadPool(r->{Thread t=new Thread(r,"measurement-fixture");t.setDaemon(true);return t;});
            workers.submit(()->{
                while(!server.isClosed()) {
                    try {
                        Socket socket=server.accept();
                        workers.submit(()->{
                            try(Socket peer=socket) { peer.setSoTimeout(4000); handler.handle(readRequest(peer),peer); }
                            catch(IOException ignored) { /* Peer disconnect is normal for bounded reads/cancellation. */ }
                            catch(Throwable unexpected) { failure.compareAndSet(null,unexpected); }
                        });
                    } catch(IOException done) { break; }
                }
            });
        }
        String host(){return "127.0.0.1:"+server.getLocalPort();}
        String url(){return "http://"+host()+"/measure";}
        public void close() throws Exception {
            server.close(); workers.shutdownNow(); workers.awaitTermination(2,TimeUnit.SECONDS);
            if(failure.get()!=null)throw new AssertionError("HTTP fixture failed",failure.get());
        }
    }
    private static Request readRequest(Socket socket) throws Exception {
        InputStream in=socket.getInputStream(); ByteArrayOutputStream headers=new ByteArrayOutputStream();
        int state=0;
        while(headers.size()<16384 && state<4) {
            int b=in.read(); if(b<0)throw new IOException("Request ended early"); headers.write(b);
            if(state==0 && b=='\r')state=1;else if(state==1 && b=='\n')state=2;else if(state==2 && b=='\r')state=3;else if(state==3 && b=='\n')state=4;else state=0;
        }
        String[] lines=new String(headers.toByteArray(),StandardCharsets.US_ASCII).split("\r\n");
        long length=0;
        for(String line:lines)if(line.toLowerCase(Locale.ROOT).startsWith("content-length:"))length=Long.parseLong(line.substring(15).trim());
        byte[] buffer=new byte[8192]; long read=0;
        while(read<length) { int n=in.read(buffer,0,(int)Math.min(buffer.length,length-read)); if(n<0)throw new IOException("Short body"); read+=n; }
        return new Request(lines[0].split(" ")[0],read);
    }
    private static void respond(Socket socket,int status,int length,byte[] body) throws IOException {
        socket.getOutputStream().write(("HTTP/1.1 "+status+" Test\r\nContent-Length: "+length+"\r\nConnection: close\r\nLocation: /unexpected-redirect\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        if(body!=null)socket.getOutputStream().write(body); socket.getOutputStream().flush();
    }
}
