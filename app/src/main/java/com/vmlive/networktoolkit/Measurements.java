package com.vmlive.networktoolkit;

import com.vmlive.networktoolkit.core.NetworkTools.Cancellation;
import com.vmlive.networktoolkit.core.NetworkTools.Progress;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.IDN;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/** Actual HTTPS transfer measurements. No global TLS or trust configuration is changed. */
public final class Measurements {
    private Measurements() {}
    private static final ThreadFactory THREADS = r -> {
        Thread t = new Thread(r, "network-measurement"); t.setDaemon(true); return t;
    };
    private static final ScheduledExecutorService DEADLINES = Executors.newScheduledThreadPool(2, THREADS);
    private static final long MB = 1_000_000L;

    public static final class Site {
        public final String id, category, name, host;
        public Site(String id, String category, String name, String host) {
            if (id == null || !id.matches("[A-Za-z0-9_-]{1,80}")) throw new IllegalArgumentException("站点 ID 无效");
            if (category == null || name == null || host == null) throw new IllegalArgumentException("站点信息不完整");
            String h = host.trim();
            try {
                URL u = checkedUrl("https://" + h, false);
                if (!u.getPath().isEmpty() || u.getQuery() != null || u.getRef() != null)
                    throw new IllegalArgumentException("请输入不含路径的主机名");
            } catch (IOException e) { throw new IllegalArgumentException("主机名无效", e); }
            this.id = id; this.category = category; this.name = name; this.host = h;
        }
    }

    public static final List<Site> CATALOG = Collections.unmodifiableList(Arrays.asList(
        new Site("kimi", "AI 服务", "Kimi", "kimi.moonshot.cn"),
        new Site("chatglm", "AI 服务", "智谱清言", "chatglm.cn"),
        new Site("yuanbao", "AI 服务", "元宝", "yuanbao.tencent.com"),
        new Site("doubao", "AI 服务", "豆包", "doubao.com"),
        new Site("yiyan", "AI 服务", "文心一言", "yiyan.baidu.com"),
        new Site("tongyi", "AI 服务", "通义千问", "tongyi.aliyun.com"),
        new Site("zhihu", "社交媒体", "知乎", "zhihu.com"),
        new Site("weibo", "社交媒体", "微博", "weibo.com"),
        new Site("douyin", "社交媒体", "抖音", "douyin.com"),
        new Site("xiaohongshu", "社交媒体", "小红书", "xiaohongshu.com"),
        new Site("wechat", "社交媒体", "微信", "weixin.qq.com"),
        new Site("mangotv", "视频平台", "芒果 TV", "mgtv.com"),
        new Site("iqiyi", "视频平台", "爱奇艺", "iqiyi.com"),
        new Site("youku", "视频平台", "优酷", "youku.com"),
        new Site("bilibili", "视频平台", "哔哩哔哩", "bilibili.com"),
        new Site("tencentvideo", "视频平台", "腾讯视频", "v.qq.com"),
        new Site("mihoyo", "游戏平台", "米哈游", "mihoyo.com"),
        new Site("tencentgames", "游戏平台", "腾讯游戏", "game.qq.com"),
        new Site("neteasegames", "游戏平台", "网易游戏", "game.163.com"),
        new Site("taptap", "游戏平台", "TapTap", "taptap.cn"),
        new Site("jd", "购物电商", "京东", "jd.com"),
        new Site("taobao", "购物电商", "淘宝", "taobao.com"),
        new Site("tmall", "购物电商", "天猫", "tmall.com"),
        new Site("pinduoduo", "购物电商", "拼多多", "pinduoduo.com"),
        new Site("aliyun", "云服务", "阿里云", "aliyun.com"),
        new Site("huawei", "云服务", "华为云", "huaweicloud.com")
    ));

    /** User confirmation must precede this call. Default 5 MB download plus 1 MB upload. */
    public static String speed(String downloadUrl, String uploadUrl, int megabytes, Cancellation c, Progress p) throws Exception {
        return speedInternal(downloadUrl, uploadUrl, megabytes, c, p, false);
    }

    // The only non-HTTPS route is a package-private localhost fixture used by JVM tests.
    static String speedForTest(String downloadUrl, String uploadUrl, int megabytes, Cancellation c, Progress p) throws Exception {
        return speedInternal(downloadUrl, uploadUrl, megabytes, c, p, true);
    }

    private static String speedInternal(String down, String up, int megabytes, Cancellation c, Progress p, boolean fixture) throws Exception {
        if (megabytes < 1 || megabytes > 20) throw new IllegalArgumentException("下载流量需为 1–20 MB");
        if (c == null) c = new Cancellation();
        c.check();
        long downloadLimit = megabytes * MB;
        long uploadLimit = Math.min(MB, downloadLimit / 5);
        URL download = checkedUrl(down == null || down.trim().isEmpty() ? "https://speed.cloudflare.com/__down?bytes=" + downloadLimit : down, fixture);
        URL upload = checkedUrl(up == null || up.trim().isEmpty() ? "https://speed.cloudflare.com/__up" : up, fixture);
        StringBuilder result = new StringBuilder("网速测试\n\n");
        List<Double> latencies = new ArrayList<>();
        emit(p, "phase|latency");
        long latencyDeadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
        for (int i = 0; i < 5; i++) {
            c.check();
            long remaining = TimeUnit.NANOSECONDS.toMillis(latencyDeadline-System.nanoTime());
            if (remaining <= 0) break;
            try { latencies.add(head(download, c, (int)Math.min(5000,remaining)).milliseconds); }
            catch (Exception e) { c.check(); /* Individual sample failure is retained in the sample count. */ }
        }
        if (!latencies.isEmpty()) {
            double[] values = new double[latencies.size()];
            for (int i = 0; i < values.length; i++) values[i] = latencies.get(i);
            double[] stats = latencyStats(values);
            emit(p, String.format(Locale.ROOT, "latency|%.3f|%.3f", stats[0], Double.isNaN(stats[1]) ? -1 : stats[1]));
            result.append(String.format(Locale.CHINA, "HTTP HEAD 延迟：%.2f ms（成功 %d/5）\n", stats[0], values.length));
            result.append(Double.isNaN(stats[1]) ? "抖动：样本不足\n" : String.format(Locale.CHINA, "抖动：%.2f ms\n", stats[1]));
        } else result.append("HTTP HEAD 延迟：失败（0/5 样本）\n抖动：无有效样本\n");
        long actualDownload = 0, actualUpload = 0;
        emit(p, "phase|download");
        try {
            Transfer t = download(download, downloadLimit, c, p);
            actualDownload = t.bytes;
            result.append(String.format(Locale.CHINA, "\n下载：%.2f Mbps（%.2f MB/s）\n下载数据：%.3f MB\n下载耗时：%.2f 秒%s\n", t.mbps(), t.mbps()/8, t.bytes/(double)MB, t.seconds, t.capped ? "（达到单次流量上限）" : ""));
        } catch (Exception e) { c.check(); emit(p, "download_error|" + errorName(e)); result.append("\n下载失败：").append(errorName(e)).append('\n'); }
        emit(p, "phase|upload");
        try {
            Transfer t = upload(upload, uploadLimit, c, p);
            actualUpload = t.bytes;
            result.append(String.format(Locale.CHINA, "\n上传：%.2f Mbps（%.2f MB/s）\n上传数据：%.3f MB\n上传耗时：%.2f 秒\n", t.mbps(), t.mbps()/8, t.bytes/(double)MB, t.seconds));
        } catch (Exception e) { c.check(); emit(p, "upload_error|" + errorName(e)); result.append("\n上传失败：").append(errorName(e)).append('\n'); }
        c.check();
        return result.append(String.format(Locale.CHINA, "\n成功阶段合计数据：%.3f MB\n", (actualDownload+actualUpload)/(double)MB))
            .append("失败阶段仍可能产生部分流量，实际计费还包含协议开销。\nHTTP HEAD 延迟包含建连与服务器响应，不是 ICMP；抖动为相邻成功样本耗时差的平均绝对值。\n传输速率包含 DNS / TCP / TLS 建连和服务器响应时间，仅代表本次请求；HTTPS 校验保持开启。\n下载：")
            .append(download).append("\n上传：").append(upload).toString();
    }

    static double[] latencyStats(double[] values) {
        if (values.length == 0) throw new IllegalArgumentException("没有有效样本");
        double total = 0, difference = 0;
        for (int i = 0; i < values.length; i++) {
            if (!Double.isFinite(values[i]) || values[i] < 0) throw new IllegalArgumentException("延迟样本无效");
            total += values[i]; if (i > 0) difference += Math.abs(values[i]-values[i-1]);
        }
        return new double[]{total/values.length, values.length < 2 ? Double.NaN : difference/(values.length-1)};
    }

    private static Transfer download(URL url, long limit, Cancellation c, Progress p) throws Exception {
        HttpURLConnection connection = connect(url, "GET");
        long start = System.nanoTime(), count = 0, last = start;
        try (Scope scope = new Scope(connection, c, 20000)) {
            scope.check(); int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw new IOException("HTTP " + status);
            InputStream in = connection.getInputStream();
            try {
                byte[] buffer = new byte[16384];
                while (count < limit) {
                    scope.check(); int n = in.read(buffer, 0, (int)Math.min(buffer.length, limit-count));
                    if (n < 0) break; count += n;
                    long now = System.nanoTime();
                    if (now-last >= 200_000_000L) { emitTransfer(p, "download", count, start); last = now; }
                }
            } finally {
                // Disconnect before close: HTTP stacks may otherwise drain unread body bytes
                // to reuse a connection, defeating the application-level read cap.
                connection.disconnect();
                try { in.close(); } catch (IOException ignored) { /* Disconnect deliberately aborts unread data. */ }
            }
            scope.check(); if (count == 0) throw new IOException("下载内容为空");
            emitTransfer(p, "download", count, start);
            return new Transfer(count, seconds(start), count == limit);
        } finally { connection.disconnect(); }
    }

    private static Transfer upload(URL url, long limit, Cancellation c, Progress p) throws Exception {
        HttpURLConnection connection = connect(url, "POST");
        connection.setDoOutput(true); connection.setFixedLengthStreamingMode(limit);
        connection.setRequestProperty("Content-Type", "application/octet-stream");
        long start = System.nanoTime(), count = 0, last = start;
        try (Scope scope = new Scope(connection, c, 20000)) {
            try (OutputStream out = connection.getOutputStream()) {
                byte[] buffer = new byte[16384];
                // Fixed pseudo-random bytes avoid compressibility without collecting device data.
                long v = 0x45d9f3b;
                for (int i=0; i<buffer.length; i++) { v = v*1103515245 + 12345; buffer[i]=(byte)(v>>>16); }
                while (count < limit) {
                    scope.check(); int n = (int)Math.min(buffer.length, limit-count); out.write(buffer, 0, n); count += n;
                    long now = System.nanoTime();
                    if (now-last >= 200_000_000L) { emitTransfer(p, "upload", count, start); last = now; }
                }
                out.flush();
            }
            scope.check(); int status = connection.getResponseCode(); scope.check();
            if (status < 200 || status >= 300) throw new IOException("HTTP " + status);
            emitTransfer(p, "upload", count, start);
            return new Transfer(count, seconds(start), true);
        } finally { connection.disconnect(); }
    }

    private static void emitTransfer(Progress p, String phase, long bytes, long start) {
        emit(p, String.format(Locale.ROOT, "%s|%.3f|%d", phase, bytes*8.0/seconds(start)/MB, bytes));
    }
    private static double seconds(long start) { return Math.max(0.000001, (System.nanoTime()-start)/1e9); }
    private static final class Transfer {
        final long bytes; final double seconds; final boolean capped;
        Transfer(long bytes, double seconds, boolean capped) { this.bytes=bytes; this.seconds=seconds; this.capped=capped; }
        double mbps() { return bytes*8/seconds/MB; }
    }
    private static final class Head {
        final double milliseconds; final int status;
        Head(double milliseconds, int status) { this.milliseconds=milliseconds; this.status=status; }
    }
    private static Head head(URL url, Cancellation c, int timeout) throws Exception {
        HttpURLConnection connection = connect(url, "HEAD");
        long start = System.nanoTime();
        try (Scope scope = new Scope(connection, c, timeout)) {
            scope.check(); int status = connection.getResponseCode(); scope.check();
            if (status < 100) throw new IOException("无有效 HTTP 响应");
            return new Head(seconds(start)*1000, status);
        } finally { connection.disconnect(); }
    }

    public static String sites(List<Site> sites, Cancellation c, Progress p) throws Exception {
        return sitesInternal(sites, c, p, false);
    }
    static String sitesForTest(List<Site> sites, Cancellation c, Progress p) throws Exception {
        return sitesInternal(sites, c, p, true);
    }
    private static String sitesInternal(List<Site> sites, Cancellation c, Progress p, boolean fixture) throws Exception {
        if (sites == null || sites.isEmpty() || sites.size() > 64) throw new IllegalArgumentException("每次可测试 1–64 个站点");
        sites = new ArrayList<>(sites);
        if (c == null) c = new Cancellation(); c.check();
        Map<String,Site> unique = new LinkedHashMap<>();
        for (Site s : sites) { if (s == null || unique.put(s.id,s) != null) throw new IllegalArgumentException("站点 ID 需唯一"); }
        Cancellation local = new Cancellation();
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(6,sites.size()),THREADS);
        CompletionService<SiteResult> completed = new ExecutorCompletionService<>(pool);
        List<Future<SiteResult>> futures = new ArrayList<>(); Map<String,SiteResult> outcomes = new LinkedHashMap<>();
        long deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(45);
        try (Cancellation.Registration registration = c.onCancel(local::cancel)) {
            for (Site site : sites) futures.add(completed.submit(() -> measureSite(site, local, fixture)));
            while (outcomes.size() < sites.size() && System.nanoTime()<deadline) {
                c.check(); Future<SiteResult> ready = completed.poll(100,TimeUnit.MILLISECONDS);
                if (ready == null) continue;
                SiteResult r = ready.get(); outcomes.put(r.site.id,r); emit(p,r.wire());
            }
            c.check();
            if (outcomes.size() < sites.size()) {
                local.cancel();
                for (Site site : sites) if (!outcomes.containsKey(site.id)) {
                    SiteResult r=new SiteResult(site,-1,0,"整体测试超过 45 秒"); outcomes.put(site.id,r); emit(p,r.wire());
                }
            }
            StringBuilder result=new StringBuilder("站点延迟测试（HTTPS HEAD）\n最大并发 6，未自动跟随跳转。\n\n");
            String category="";
            for (Site site : sites) {
                if (!category.equals(site.category)) { category=site.category; result.append(category).append('\n'); }
                SiteResult r=outcomes.get(site.id);
                result.append(site.name).append(" · ").append(site.host).append("\n  ");
                if (r.ms < 0) result.append("失败 / 未确认：").append(r.error);
                else result.append(String.format(Locale.CHINA,"%.1f ms  HTTP %d",r.ms,r.status));
                result.append('\n');
            }
            return result.append("\n耗时包含 DNS、TCP、TLS 与服务器 HEAD 响应，不代表 ICMP 延迟；失败不等于站点离线。服务可能限制 HEAD 请求。").toString();
        } finally { local.cancel(); for(Future<?> f:futures) f.cancel(true); pool.shutdownNow(); }
    }
    private static SiteResult measureSite(Site site,Cancellation c,boolean fixture) throws Exception {
        c.check();
        try {
            Head h=head(checkedUrl((fixture?"http://":"https://")+site.host,fixture),c,8000);
            return new SiteResult(site,h.milliseconds,h.status,"");
        } catch (Exception e) { c.check(); return new SiteResult(site,-1,0,errorName(e)); }
    }
    private static final class SiteResult {
        final Site site; final double ms; final int status; final String error;
        SiteResult(Site site,double ms,int status,String error) { this.site=site;this.ms=ms;this.status=status;this.error=error; }
        String wire() { return ms<0?"site|"+site.id+"|-1|"+error:String.format(Locale.ROOT,"site|%s|%.3f|%d",site.id,ms,status); }
    }

    private static HttpURLConnection connect(URL url,String method) throws IOException {
        HttpURLConnection c=(HttpURLConnection)url.openConnection();
        c.setConnectTimeout(5000); c.setReadTimeout(2500); c.setInstanceFollowRedirects(false);
        c.setRequestMethod(method); c.setRequestProperty("User-Agent","NetworkToolkit-Android/1.1");
        c.setRequestProperty("Accept-Encoding","identity"); c.setRequestProperty("Connection","close");
        c.setUseCaches(false); return c;
    }
    private static URL checkedUrl(String input,boolean fixture) throws IOException {
        URL u=new URL(input.trim()); String host=u.getHost();
        boolean local=host.equals("127.0.0.1") || host.equals("localhost") || host.equals("[::1]") || host.equals("::1");
        if ((!u.getProtocol().equals("https") && !(fixture && local && u.getProtocol().equals("http"))) || host.isEmpty() || u.getUserInfo()!=null || u.getRef()!=null || u.getPort()==0 || u.getPort()>65535)
            throw new IllegalArgumentException("请使用不含账号密码的 HTTPS 地址");
        if (!host.contains(":")) IDN.toASCII(host,IDN.USE_STD3_ASCII_RULES);
        return u;
    }
    private static final class Scope implements AutoCloseable {
        final long deadline; final HttpURLConnection connection; final Cancellation c;
        final Cancellation.Registration registration; final ScheduledFuture<?> timer;
        Scope(HttpURLConnection connection,Cancellation c,int milliseconds) {
            this.connection=connection; this.c=c; deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(milliseconds);
            registration=c.onCancel(connection::disconnect);
            timer=DEADLINES.schedule(connection::disconnect,milliseconds,TimeUnit.MILLISECONDS);
        }
        void check() throws SocketTimeoutException { c.check(); if(System.nanoTime()>=deadline) throw new SocketTimeoutException("请求超过阶段时限"); }
        public void close() { timer.cancel(false); registration.close(); }
    }
    private static void emit(Progress p,String value) { if(p!=null)p.onProgress(value); }
    private static String errorName(Exception e) {
        String message=e.getMessage();
        // No URL echo in the event wire: URLs can contain query parameters or delimiters.
        return e instanceof IOException && message!=null && message.matches("HTTP [0-9]{3}") ? message : e.getClass().getSimpleName();
    }
}
