package com.vmlive.networktoolkit;

import com.vmlive.networktoolkit.core.NetworkTools.Cancellation;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Independent HTTPS IP reports. Missing or failed provider data is never guessed. */
public final class IpDiagnostics {
    private IpDiagnostics() {}
    public enum Format { PLAIN, IP_SB_JSON, CLOUDFLARE_TRACE, PING0_TEXT }
    public interface Observer { void onResult(Result result); }
    private static final int BODY_LIMIT = 65536;
    private static final ThreadFactory THREADS = work -> {
        Thread thread = new Thread(work, "ip-diagnostic"); thread.setDaemon(true); return thread;
    };
    // Shared limits remain bounded even if a platform DNS lookup cannot be interrupted.
    private static final ThreadPoolExecutor REQUESTS = new ThreadPoolExecutor(3, 3, 30,
            TimeUnit.SECONDS, new ArrayBlockingQueue<>(15), THREADS);
    private static final ScheduledExecutorService DEADLINES = Executors.newScheduledThreadPool(2, THREADS);

    public static final class Provider {
        public final String id, name, url;
        public final Format format;
        /** Expected address family: 4, 6, or 0 for either. */
        public final int family;
        public Provider(String id, String name, String url, Format format, int family) {
            if (id == null || !id.matches("[a-zA-Z0-9_-]{1,60}") || name == null || name.trim().isEmpty() ||
                    url == null || format == null || family != 0 && family != 4 && family != 6)
                throw new IllegalArgumentException("IP 服务定义无效");
            this.id = id; this.name = name; this.url = url; this.format = format; this.family = family;
        }
    }
    public static final List<Provider> PROVIDERS = Collections.unmodifiableList(Arrays.asList(
        new Provider("ipify4", "ipify · IPv4", "https://api.ipify.org", Format.PLAIN, 4),
        new Provider("ipify6", "ipify · IPv6", "https://api6.ipify.org", Format.PLAIN, 6),
        new Provider("ipsb", "IP.SB", "https://api.ip.sb/geoip", Format.IP_SB_JSON, 0),
        new Provider("cloudflare", "Cloudflare Trace", "https://www.cloudflare.com/cdn-cgi/trace", Format.CLOUDFLARE_TRACE, 0),
        new Provider("ping0", "Ping0 · 当前出口", "https://ping0.cc/geo", Format.PING0_TEXT, 0)
    ));

    public static final class Result {
        public final String id, name, source, error;
        public final boolean success;
        public final Map<String, String> fields;
        public final long elapsedMs;
        private Result(Provider provider, Map<String, String> values, String error, long ms) {
            this.id = provider.id; this.name = provider.name; this.source = provider.url;
            this.success = error == null; this.error = error == null ? "" : error;
            this.fields = Collections.unmodifiableMap(new LinkedHashMap<>(values)); this.elapsedMs = Math.max(0, ms);
        }
        public String format() {
            StringBuilder out = new StringBuilder(name).append('\n').append(source).append('\n');
            if (success) for (Map.Entry<String, String> field : fields.entrySet()) out.append(field.getKey()).append("：").append(field.getValue()).append('\n');
            else out.append("查询失败：").append(error).append('\n');
            return out.append("耗时：").append(elapsedMs).append(" ms").toString();
        }
    }

    /** At most three concurrent providers; returns in provider order with independent outcomes. */
    public static List<Result> run(Cancellation cancellation, Observer observer) {
        return runProviders(PROVIDERS, cancellation, observer, false);
    }
    public static Result fetch(Provider provider, Cancellation cancellation) {
        return fetchOne(provider, cancellation, false);
    }
    // HTTP is permitted only for real loopback fixtures, never through the public methods.
    static Result fetchForTest(Provider provider, Cancellation cancellation) { return fetchOne(provider, cancellation, true); }
    static List<Result> runForTest(List<Provider> providers, Cancellation cancellation, Observer observer) {
        return runProviders(providers, cancellation, observer, true);
    }

    private static Result fetchOne(Provider provider, Cancellation cancellation, boolean fixture) {
        if (provider == null) throw new IllegalArgumentException("IP 服务不能为空");
        Cancellation parent = cancellation == null ? new Cancellation() : cancellation;
        parent.check(); Cancellation group = new Cancellation();
        long started = System.nanoTime(); Future<Result> task = null;
        try (Cancellation.Registration registration = parent.onCancel(group::cancel)) {
            task = REQUESTS.submit(() -> request(provider, group, fixture));
            while (elapsed(started) < 8000) {
                parent.check();
                try { return task.get(100, TimeUnit.MILLISECONDS); }
                catch (java.util.concurrent.TimeoutException waiting) { /* cancellation polling */ }
                catch (java.util.concurrent.ExecutionException error) {
                    parent.check();
                    return failure(provider, message(error.getCause()), elapsed(started));
                }
            }
            return failure(provider, "请求超过 8 秒时限", elapsed(started));
        } catch (CancellationException stopped) { throw stopped; }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new CancellationException("查询已停止"); }
        catch (RuntimeException unavailable) { parent.check(); return failure(provider, "请求队列繁忙或服务不可用：" + message(unavailable), elapsed(started)); }
        finally { group.cancel(); if (task != null) task.cancel(true); }
    }

    private static List<Result> runProviders(List<Provider> providers, Cancellation cancellation, Observer observer, boolean fixture) {
        Cancellation parent = cancellation == null ? new Cancellation() : cancellation;
        parent.check(); Cancellation group = new Cancellation();
        List<Provider> selected = new ArrayList<>(providers);
        if (selected.size() > 10) throw new IllegalArgumentException("每次最多查询 10 个 IP 服务");
        Map<String, Result> results = new LinkedHashMap<>();
        for (Provider p : selected) if (p == null || results.containsKey(p.id)) throw new IllegalArgumentException("IP 服务不能为空或重复"); else results.put(p.id, null);
        CompletionService<Result> completion = new ExecutorCompletionService<>(REQUESTS);
        List<Future<Result>> tasks = new ArrayList<>(); long started = System.nanoTime(); int pending = 0;
        try (Cancellation.Registration registration = parent.onCancel(group::cancel)) {
            for (Provider p : selected) {
                parent.check();
                try { tasks.add(completion.submit(() -> request(p, group, fixture))); pending++; }
                catch (java.util.concurrent.RejectedExecutionException busy) { deliver(results, failure(p, "请求队列繁忙，请稍后重试", elapsed(started)), observer); }
            }
            while (pending > 0 && elapsed(started) < 20000) {
                parent.check(); Future<Result> completed = completion.poll(100, TimeUnit.MILLISECONDS);
                if (completed == null) continue;
                pending--;
                try { deliver(results, completed.get(), observer); }
                catch (java.util.concurrent.ExecutionException error) { parent.check(); /* unreturned entry gets explicit failure below */ }
            }
            parent.check();
            for (Provider p : selected) if (results.get(p.id) == null) deliver(results, failure(p, "查询超过 20 秒总时限或服务未完成", elapsed(started)), observer);
            return Collections.unmodifiableList(new ArrayList<>(results.values()));
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new CancellationException("查询已停止"); }
        finally { group.cancel(); for (Future<Result> task : tasks) task.cancel(true); }
    }
    private static void deliver(Map<String, Result> results, Result result, Observer observer) {
        results.put(result.id, result); if (observer != null) observer.onResult(result);
    }
    private static Result request(Provider provider, Cancellation parent, boolean fixture) {
        long started = System.nanoTime(); Cancellation active = new Cancellation(); AtomicBoolean timedOut = new AtomicBoolean();
        ScheduledFuture<?> expiry = null;
        try (Cancellation.Registration registration = parent.onCancel(active::cancel)) {
            parent.check();
            expiry = DEADLINES.schedule(() -> { timedOut.set(true); active.cancel(); }, 8, TimeUnit.SECONDS);
            String body = read(provider, active, fixture);
            Map<String, String> fields = parse(provider, body); active.check();
            return new Result(provider, fields, null, elapsed(started));
        } catch (Exception error) {
            parent.check();
            return failure(provider, timedOut.get() ? "请求超过 8 秒时限" : message(error), elapsed(started));
        } finally { if (expiry != null) expiry.cancel(false); active.cancel(); }
    }

    private static String read(Provider provider, Cancellation cancellation, boolean fixture) throws Exception {
        URL url = new URL(provider.url);
        boolean loopback = url.getProtocol().equals("http") && fixture && Arrays.asList("127.0.0.1", "[::1]", "::1").contains(url.getHost());
        if ((!url.getProtocol().equals("https") && !loopback) || url.getHost().isEmpty() || url.getUserInfo() != null || url.getRef() != null)
            throw new IllegalArgumentException("IP 服务仅允许 HTTPS，且不能包含账号、密码或片段");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(8000); connection.setReadTimeout(2000); connection.setInstanceFollowRedirects(false);
        connection.setUseCaches(false); connection.setRequestProperty("Accept", provider.format == Format.IP_SB_JSON ? "application/json" : "text/plain, */*;q=0.1");
        connection.setRequestProperty("Accept-Encoding", "identity"); connection.setRequestProperty("User-Agent", "NetworkToolkit-Android/1.2.0");
        try (Cancellation.Registration registration = cancellation.onCancel(connection::disconnect)) {
            cancellation.check(); int code = connection.getResponseCode();
            if (code != 200) throw new IOException("服务返回 HTTP " + code + "（不自动跟随跳转）");
            if (connection.getContentLengthLong() > BODY_LIMIT) throw new IOException("响应超过 64 KiB 上限");
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            try (InputStream in = connection.getInputStream()) {
                byte[] buffer = new byte[4096];
                while (true) {
                    cancellation.check(); int n = in.read(buffer, 0, Math.min(buffer.length, BODY_LIMIT + 1 - body.size()));
                    if (n < 0) break; body.write(buffer, 0, n);
                    if (body.size() > BODY_LIMIT) { connection.disconnect(); throw new IOException("响应超过 64 KiB 上限"); }
                }
            }
            cancellation.check(); return new String(body.toByteArray(), StandardCharsets.UTF_8).trim();
        } finally { connection.disconnect(); }
    }

    static Map<String, String> parse(Provider provider, String text) throws Exception {
        if (text == null || text.trim().isEmpty()) throw new IOException("服务返回空响应");
        String lead = text.trim().toLowerCase(Locale.ROOT);
        if (lead.startsWith("<") || lead.contains("<html") || lead.contains("<!doctype"))
            throw new IOException("服务返回 HTML 页面，未作为 IP 数据解析");
        Map<String, String> fields = new LinkedHashMap<>();
        switch (provider.format) {
            case PLAIN: putIp(fields, text.trim(), provider.family); break;
            case IP_SB_JSON: {
                JSONObject json = new JSONObject(text); Object ip = json.opt("ip");
                if (!(ip instanceof String)) throw new IOException("服务没有返回字符串 IP 地址");
                putIp(fields, (String) ip, provider.family);
                jsonField(fields, "国家", json, "country"); jsonField(fields, "地区", json, "region"); jsonField(fields, "城市", json, "city");
                jsonField(fields, "ASN", json, "asn"); jsonField(fields, "组织", json, "organization"); jsonField(fields, "ISP", json, "isp");
                jsonField(fields, "时区", json, "timezone"); break;
            }
            case CLOUDFLARE_TRACE: {
                Map<String, String> trace = new LinkedHashMap<>();
                for (String line : text.split("\\r?\\n")) {
                    int equals = line.indexOf('='); if (equals < 1) continue;
                    String key = line.substring(0, equals).trim(), value = line.substring(equals + 1).trim();
                    if (trace.put(key, value) != null) throw new IOException("Trace 响应包含重复字段");
                }
                putIp(fields, trace.get("ip"), provider.family);
                field(fields, "地区代码", trace.get("loc")); field(fields, "Cloudflare 节点", trace.get("colo"));
                field(fields, "TLS", trace.get("tls")); field(fields, "HTTP", trace.get("http"));
                field(fields, "WARP", trace.get("warp")); field(fields, "SNI", trace.get("sni")); break;
            }
            case PING0_TEXT: {
                List<String> lines = new ArrayList<>(); for (String line : text.split("\\r?\\n")) if (!line.trim().isEmpty()) lines.add(line.trim());
                if (lines.size() != 4) throw new IOException("Ping0 响应不符合当前出口四行格式");
                putIp(fields, lines.get(0), provider.family); field(fields, "位置", lines.get(1)); field(fields, "ASN", lines.get(2)); field(fields, "组织", lines.get(3));
                // Free endpoint does not provide risk/native/IDC scores. Do not fabricate them.
                break;
            }
            default: throw new IOException("不支持的 IP 响应格式");
        }
        return fields;
    }
    private static void putIp(Map<String, String> fields, String input, int expectedFamily) throws Exception {
        if (input == null) throw new IOException("服务未提供 IP 地址");
        String ip = input.trim(); int family;
        if (ip.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) {
            for (String part : ip.split("\\.")) if (Integer.parseInt(part) > 255 || part.length() > 1 && part.charAt(0) == '0') throw new IOException("IP 地址格式无效");
            family = 4;
        } else if (ip.length() <= 45 && ip.contains(":") && ip.matches("[0-9a-fA-F:.]+")) {
            // The character/colon guard guarantees this is numeric parsing, never hostname resolution.
            try { InetAddress.getByName(ip); } catch (Exception invalid) { throw new IOException("IPv6 地址格式无效"); }
            family = 6;
        } else throw new IOException("响应不是有效的 IP 字面地址");
        if (expectedFamily != 0 && family != expectedFamily) throw new IOException("服务返回了错误的 IP 地址族，期望 IPv" + expectedFamily);
        fields.put("IP", ip); fields.put("地址族", "IPv" + family);
    }
    private static void jsonField(Map<String, String> out, String label, JSONObject json, String key) throws Exception {
        Object value = json.opt(key);
        if (value instanceof String || value instanceof Number) field(out, label, String.valueOf(value));
    }
    private static void field(Map<String, String> out, String label, String value) throws Exception {
        if (value == null || value.trim().isEmpty()) return;
        String clean = value.trim();
        if (clean.length() > 2048 || clean.matches("(?s).*[<>\\p{Cntrl}].*")) throw new IOException("服务元数据格式异常");
        out.put(label, clean);
    }
    private static Result failure(Provider provider, String error, long ms) { return new Result(provider, Collections.emptyMap(), error, ms); }
    private static long elapsed(long start) { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start); }
    private static String message(Throwable error) { return error == null || error.getMessage() == null ? "服务请求失败" : error.getClass().getSimpleName() + ": " + error.getMessage(); }
}
