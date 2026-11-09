package com.vmlive.networktoolkit.core;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.IDN;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.net.ssl.HttpsURLConnection;

/** Network operations run off the UI thread. TLS and hostname verification use platform defaults. */
public final class NetworkTools {
    private NetworkTools() {}
    public interface Progress { void onProgress(String line); }
    private interface CancelAction { void close(); }
    private static final ThreadFactory DAEMON = r -> {
        Thread t = new Thread(r, "network-tool");
        t.setDaemon(true);
        return t;
    };
    // DNS resolution itself cannot be interrupted on every JVM. Bound the workers and queue.
    private static final ExecutorService RESOLVERS = new ThreadPoolExecutor(2, 2, 30,
            TimeUnit.SECONDS, new ArrayBlockingQueue<>(16), DAEMON);

    public static final class Cancellation {
        public interface Registration extends AutoCloseable { @Override void close(); }
        private volatile boolean cancelled;
        private final Set<CancelAction> actions = Collections.newSetFromMap(new ConcurrentHashMap<>());
        public void cancel() {
            cancelled = true;
            for (CancelAction action : actions) closeSafely(action);
            actions.clear();
        }
        public boolean isCancelled() { return cancelled; }
        public void check() {
            if (cancelled || Thread.currentThread().isInterrupted()) throw new CancellationException("操作已取消");
        }
        /** Register an idempotent cleanup hook; closing the registration only removes the hook. */
        public Registration onCancel(Runnable closeAction) {
            if (closeAction == null) throw new IllegalArgumentException("取消回调不能为空");
            CancelAction action = closeAction::run;
            try { register(action); }
            catch (CancellationException cancelled) { closeSafely(action); throw cancelled; }
            return () -> remove(action);
        }
        private void closeSafely(CancelAction action) {
            try { action.close(); } catch (RuntimeException ignored) { /* Other resources must still close. */ }
        }
        private void register(CancelAction action) {
            check();
            actions.add(action);
            if (cancelled) { actions.remove(action); closeSafely(action); check(); }
        }
        private void remove(CancelAction action) { actions.remove(action); }
    }

    public static String execute(String toolId, String input, Cancellation cancellation, Progress progress) throws Exception {
        Cancellation c = cancellation == null ? new Cancellation() : cancellation;
        c.check();
        String text = input == null ? "" : input.trim();
        switch (toolId) {
            case "dns": return dns(text, c);
            case "tcp": {
                HostPort target = HostPort.parse(text, 443);
                return tcpLatency(target.host, target.port, 4, 3000, c, progress);
            }
            case "ports": {
                String[] parts = text.split("\\s+", 2);
                HostPort target = HostPort.parse(parts[0], 443);
                return scanPorts(target.host, parsePorts(parts.length == 2 ? parts[1] : "22,53,80,443,8080"), c, progress);
            }
            case "http": return http(text, c);
            case "tls": return tls(text, c);
            case "subnet": return subnet(text);
            case "wol": {
                String[] p = text.split("\\s+");
                if (p.length > 3) throw new IllegalArgumentException("格式：MAC 地址 [广播 IPv4] [端口]");
                return wakeOnLan(p[0], p.length > 1 ? p[1] : "255.255.255.255",
                        p.length > 2 ? parsePort(p[2]) : 9, c);
            }
            default: return CodecTools.execute(toolId, input == null ? "" : input);
        }
    }

    private static InetAddress[] resolve(String host, Cancellation c) throws Exception {
        String checked = HostPort.checkHost(host);
        c.check();
        Future<InetAddress[]> future = RESOLVERS.submit(() -> InetAddress.getAllByName(checked));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
        try {
            while (true) {
                c.check();
                if (System.nanoTime() >= deadline) throw new TimeoutException("DNS 查询超时（6 秒）");
                try { return future.get(100, TimeUnit.MILLISECONDS); }
                catch (TimeoutException wait) { /* poll cancellation */ }
                catch (ExecutionException error) {
                    Throwable cause = error.getCause();
                    if (cause instanceof Exception) throw (Exception) cause;
                    throw error;
                }
            }
        } finally { future.cancel(true); }
    }

    public static String dns(String host, Cancellation c) throws Exception {
        long started = System.nanoTime();
        InetAddress[] addresses = resolve(host, c);
        StringBuilder out = new StringBuilder("系统 DNS 查询\n主机：").append(host).append('\n');
        for (InetAddress address : addresses) {
            c.check();
            out.append(address.getAddress().length == 4 ? "A     " : "AAAA  ").append(address.getHostAddress()).append('\n');
        }
        return out.append(String.format(Locale.ROOT, "\n查询耗时：%.1f ms\n使用设备当前网络的 DNS 解析器。", elapsed(started))).toString();
    }

    public static String tcpLatency(String host, int port, int rounds, int timeoutMs,
                                    Cancellation c, Progress progress) throws Exception {
        checkPort(port);
        if (rounds < 1 || rounds > 20 || timeoutMs < 100 || timeoutMs > 10000)
            throw new IllegalArgumentException("次数需为 1–20，超时需为 100–10000 ms");
        InetAddress address = resolve(host, c)[0];
        StringBuilder out = new StringBuilder("TCP 连接延迟\n目标：").append(host).append(':').append(port)
                .append("\n地址：").append(address.getHostAddress()).append("\n\n");
        int succeeded = 0;
        double total = 0, min = Double.MAX_VALUE, max = 0;
        for (int i = 1; i <= rounds; i++) {
            c.check();
            long started = System.nanoTime();
            String line;
            try {
                double ms = connect(address, port, timeoutMs, c);
                succeeded++; total += ms; min = Math.min(min, ms); max = Math.max(max, ms);
                line = String.format(Locale.ROOT, "第 %d 次  %.2f ms", i, ms);
            } catch (java.io.IOException error) {
                c.check();
                line = String.format(Locale.ROOT, "第 %d 次  连接失败（%.0f ms）：%s", i, elapsed(started), safeMessage(error));
            }
            out.append(line).append('\n'); emit(progress, line);
        }
        out.append("\n连接成功：").append(succeeded).append('/').append(rounds);
        if (succeeded > 0) out.append(String.format(Locale.ROOT, "\n最短 / 平均 / 最长：%.2f / %.2f / %.2f ms", min, total / succeeded, max));
        return out.append("\n\n测量 TCP 三次握手时间，并非 ICMP Ping；拒绝或超时不表示设备离线。").toString();
    }

    private static double connect(InetAddress address, int port, int timeoutMs, Cancellation c) throws Exception {
        Socket socket = new Socket();
        CancelAction action = () -> { try { socket.close(); } catch (Exception ignored) {} };
        try {
            c.register(action);
            long started = System.nanoTime();
            socket.connect(new InetSocketAddress(address, port), timeoutMs);
            c.check();
            return elapsed(started);
        } finally { c.remove(action); action.close(); }
    }

    public static int[] parsePorts(String spec) {
        if (spec == null || spec.trim().isEmpty()) throw new IllegalArgumentException("请输入端口，例如 22,80,443 或 8000-8010");
        Set<Integer> ports = new LinkedHashSet<>();
        for (String group : spec.split(",", -1)) {
            String[] range = group.trim().split("-", -1);
            if (range.length < 1 || range.length > 2) throw new IllegalArgumentException("端口格式无效");
            int first = parsePort(range[0].trim());
            int last = range.length == 1 ? first : parsePort(range[1].trim());
            if (last < first || last - first >= 128) throw new IllegalArgumentException("每次最多扫描 128 个端口，范围需递增");
            for (int port = first; port <= last; port++) {
                ports.add(port);
                if (ports.size() > 128) throw new IllegalArgumentException("每次最多扫描 128 个端口");
            }
        }
        int[] out = new int[ports.size()]; int index = 0;
        for (int port : ports) out[index++] = port;
        Arrays.sort(out); return out;
    }

    public static String scanPorts(String host, int[] ports, Cancellation c, Progress progress) throws Exception {
        if (ports == null || ports.length == 0 || ports.length > 128) throw new IllegalArgumentException("端口数量需为 1–128");
        for (int port : ports) checkPort(port);
        InetAddress address = resolve(host, c)[0];
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(16, ports.length), DAEMON);
        List<Future<String>> futures = new ArrayList<>();
        long started = System.nanoTime();
        long deadline = started + TimeUnit.SECONDS.toNanos(20);
        try {
            for (int port : ports) {
                futures.add(pool.submit(() -> {
                    c.check();
                    if (System.nanoTime() >= deadline) throw new TimeoutException("扫描超过 20 秒时限");
                    try {
                        double ms = connect(address, port, 1200, c);
                        String line = String.format(Locale.ROOT, "%d/tcp  开放  %.1f ms", port, ms);
                        emit(progress, line); return line;
                    } catch (java.io.IOException error) { c.check(); return null; }
                }));
            }
            StringBuilder out = new StringBuilder("TCP 端口扫描\n目标：").append(host).append(" (")
                    .append(address.getHostAddress()).append(")\n\n");
            int open = 0;
            for (Future<String> future : futures) {
                String line;
                while (true) {
                    c.check();
                    if (System.nanoTime() >= deadline) throw new TimeoutException("扫描超过 20 秒时限");
                    try { line = future.get(100, TimeUnit.MILLISECONDS); break; }
                    catch (TimeoutException wait) { /* cancellation polling */ }
                    catch (ExecutionException error) {
                        if (error.getCause() instanceof Exception) throw (Exception) error.getCause();
                        throw error;
                    }
                }
                if (line != null) { out.append(line).append('\n'); open++; }
            }
            if (open == 0) out.append("未发现开放端口。\n");
            return out.append("\n已检查：").append(ports.length).append("  开放：").append(open)
                    .append("  关闭 / 过滤：").append(ports.length - open)
                    .append(String.format(Locale.ROOT, "\n耗时：%.2f 秒\n只检查用户指定目标；仅在有授权的网络中使用。", elapsed(started) / 1000)).toString();
        } finally { for (Future<String> f : futures) f.cancel(true); pool.shutdownNow(); }
    }

    private static URL checkedUrl(String input, boolean tlsOnly) throws Exception {
        String value = input.trim();
        if (!value.contains("://")) value = "https://" + value;
        URL url = new URL(value);
        if ((!url.getProtocol().equals("https") && !url.getProtocol().equals("http")) ||
                (tlsOnly && !url.getProtocol().equals("https")) || url.getHost().isEmpty() || url.getUserInfo() != null)
            throw new IllegalArgumentException(tlsOnly ? "请输入 HTTPS 地址或主机名" : "仅支持 HTTP / HTTPS 地址，不支持含账号密码的 URL");
        if (url.getPort() != -1) checkPort(url.getPort());
        return url;
    }

    public static String http(String input, Cancellation c) throws Exception {
        URL url = checkedUrl(input, false);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(5000); connection.setReadTimeout(5000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("User-Agent", "NetworkToolkit-Android/1.0");
        CancelAction action = connection::disconnect;
        long started = System.nanoTime();
        try {
            c.register(action); c.check();
            int status = connection.getResponseCode();
            StringBuilder out = new StringBuilder("HTTP GET\n").append(url).append("\n\n")
                    .append(status).append(' ').append(connection.getResponseMessage()).append('\n');
            for (Map.Entry<String, List<String>> h : connection.getHeaderFields().entrySet())
                if (h.getKey() != null) out.append(h.getKey()).append(": ").append(String.join(", ", h.getValue())).append('\n');
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            boolean truncated = false;
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (stream != null) {
                try (InputStream in = stream) {
                    byte[] buffer = new byte[4096];
                    while (true) {
                        c.check();
                        if (elapsed(started) > 15000) throw new TimeoutException("HTTP 读取超过 15 秒时限");
                        int n = in.read(buffer, 0, Math.min(buffer.length, 65537 - body.size()));
                        if (n < 0) break;
                        body.write(buffer, 0, n);
                        if (body.size() > 65536) { truncated = true; break; }
                    }
                }
            }
            c.check();
            byte[] bytes = body.toByteArray();
            out.append("\n响应正文（UTF-8 预览，最多 64 KiB）\n")
                    .append(new String(bytes, 0, Math.min(bytes.length, 65536), StandardCharsets.UTF_8));
            if (truncated) out.append("\n[已截断]");
            if (status >= 300 && status < 400) out.append("\n[未自动跟随重定向，可检查 Location 后重新查询]");
            return out.append(String.format(Locale.ROOT, "\n\n耗时：%.1f ms", elapsed(started))).toString();
        } finally { c.remove(action); connection.disconnect(); }
    }

    public static String tls(String input, Cancellation c) throws Exception {
        HttpsURLConnection connection = (HttpsURLConnection) checkedUrl(input, true).openConnection();
        connection.setConnectTimeout(5000); connection.setReadTimeout(5000);
        connection.setInstanceFollowRedirects(false); connection.setRequestMethod("HEAD");
        CancelAction action = connection::disconnect;
        try {
            c.register(action); c.check(); connection.connect(); c.check();
            StringBuilder out = new StringBuilder("TLS 证书检查\n地址：").append(connection.getURL())
                    .append("\n主机名与证书链验证：通过\n密码套件：").append(connection.getCipherSuite()).append('\n');
            Certificate[] chain = connection.getServerCertificates();
            for (int i = 0; i < chain.length; i++) {
                c.check();
                if (!(chain[i] instanceof X509Certificate)) continue;
                X509Certificate cert = (X509Certificate) chain[i];
                out.append("\n证书 ").append(i + 1).append("\n主体：").append(cert.getSubjectX500Principal().getName())
                        .append("\n颁发者：").append(cert.getIssuerX500Principal().getName())
                        .append("\n生效：").append(utc(cert.getNotBefore())).append("\n到期：").append(utc(cert.getNotAfter()))
                        .append("\nSHA-256：").append(hex(MessageDigest.getInstance("SHA-256").digest(cert.getEncoded()))).append('\n');
            }
            return out.toString();
        } finally { c.remove(action); connection.disconnect(); }
    }

    private static String utc(Date date) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.ROOT);
        f.setTimeZone(TimeZone.getTimeZone("UTC")); return f.format(date);
    }

    public static long ipv4(String text) {
        String[] octets = text.split("\\.", -1);
        if (octets.length != 4) throw new IllegalArgumentException("请输入有效的 IPv4 地址");
        long value = 0;
        for (String part : octets) {
            if (!part.matches("[0-9]{1,3}")) throw new IllegalArgumentException("IPv4 地址格式无效");
            int octet = Integer.parseInt(part);
            if (octet > 255) throw new IllegalArgumentException("IPv4 每段必须为 0–255");
            value = (value << 8) | octet;
        }
        return value;
    }

    private static String ipv4Text(long value) {
        return ((value >>> 24) & 255) + "." + ((value >>> 16) & 255) + "." + ((value >>> 8) & 255) + "." + (value & 255);
    }

    public static String subnet(String text) {
        String[] parts = text.trim().split("/", -1);
        if (parts.length != 2 || !parts[1].matches("[0-9]{1,2}")) throw new IllegalArgumentException("格式示例：192.168.1.10/24");
        long address = ipv4(parts[0]);
        int prefix = Integer.parseInt(parts[1]);
        if (prefix > 32) throw new IllegalArgumentException("前缀长度必须为 0–32");
        long mask = prefix == 0 ? 0 : (0xffffffffL << (32 - prefix)) & 0xffffffffL;
        long network = address & mask, last = network | (~mask & 0xffffffffL);
        long total = 1L << (32 - prefix), usable = prefix >= 31 ? total : total - 2;
        long firstHost = prefix >= 31 ? network : network + 1;
        long lastHost = prefix >= 31 ? last : last - 1;
        return "IPv4 子网计算\nIP 地址：" + ipv4Text(address) + "/" + prefix +
                "\n子网掩码：" + ipv4Text(mask) + "\n通配掩码：" + ipv4Text(~mask) +
                "\n网络地址：" + ipv4Text(network) + "\n地址末端：" + ipv4Text(last) +
                (prefix < 31 ? "（广播地址）" : "（/31、/32 不保留广播地址）") +
                "\n可用范围：" + ipv4Text(firstHost) + " – " + ipv4Text(lastHost) +
                "\n地址总数：" + total + "\n可用主机：" + usable +
                (prefix == 31 ? "\n/31 按 RFC 3021 点对点链路计算。" : "");
    }

    public static byte[] magicPacket(String mac) {
        if (mac == null || !mac.matches("(?i)([0-9a-f]{2}[:-]){5}[0-9a-f]{2}"))
            throw new IllegalArgumentException("MAC 格式示例：AA:BB:CC:DD:EE:FF");
        String[] pairs = mac.split("[:-]"); byte[] packet = new byte[102];
        Arrays.fill(packet, 0, 6, (byte) 0xff);
        for (int i = 6; i < packet.length; i++) packet[i] = (byte) Integer.parseInt(pairs[(i - 6) % 6], 16);
        return packet;
    }

    public static String wakeOnLan(String mac, String broadcast, int port, Cancellation c) throws Exception {
        byte[] packet = magicPacket(mac); checkPort(port); ipv4(broadcast); c.check();
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true); c.check();
            socket.send(new DatagramPacket(packet, packet.length, InetAddress.getByName(broadcast), port));
        }
        return "Wake-on-LAN\nMAC：" + mac.toUpperCase(Locale.ROOT) + "\n目标：" + broadcast + ":" + port +
                "\n已发送 102 字节魔术包。\n\nUDP 无确认机制；发送成功不表示设备已唤醒。设备需在同一可达网络并启用 WOL。";
    }

    public static final class HostPort {
        public final String host;
        public final int port;
        private HostPort(String host, int port) { this.host = host; this.port = port; }
        public static HostPort parse(String text, int defaultPort) {
            checkPort(defaultPort);
            String value = text == null ? "" : text.trim();
            String host; int port = defaultPort;
            if (value.startsWith("[")) {
                int end = value.indexOf(']');
                if (end < 0) throw new IllegalArgumentException("IPv6 地址需使用 [地址]:端口 格式");
                host = value.substring(1, end);
                String suffix = value.substring(end + 1);
                if (!suffix.isEmpty()) {
                    if (!suffix.startsWith(":")) throw new IllegalArgumentException("主机格式无效");
                    port = parsePort(suffix.substring(1));
                }
            } else {
                int colon = value.indexOf(':');
                if (colon >= 0 && colon == value.lastIndexOf(':')) {
                    host = value.substring(0, colon); port = parsePort(value.substring(colon + 1));
                } else host = value;
            }
            return new HostPort(checkHost(host), port);
        }
        private static String checkHost(String host) {
            if (host == null || host.isEmpty() || host.length() > 253 || host.matches(".*[\\s/@?#\\\\].*"))
                throw new IllegalArgumentException("请输入主机名或 IP 地址，不要包含协议和路径");
            if (host.contains(":")) {
                if (!host.matches("[0-9a-fA-F:.%a-zA-Z0-9_-]+")) throw new IllegalArgumentException("IPv6 地址格式无效");
                return host;
            }
            String ascii = IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES);
            if (ascii.isEmpty()) throw new IllegalArgumentException("主机名不能为空");
            return ascii;
        }
    }
    private static int parsePort(String text) {
        if (!text.matches("[0-9]{1,5}")) throw new IllegalArgumentException("端口必须为 1–65535");
        int port = Integer.parseInt(text); checkPort(port); return port;
    }
    private static void checkPort(int port) {
        if (port < 1 || port > 65535) throw new IllegalArgumentException("端口必须为 1–65535");
    }
    private static double elapsed(long start) { return (System.nanoTime() - start) / 1_000_000.0; }
    private static void emit(Progress progress, String line) { if (progress != null) progress.onProgress(line); }
    private static String safeMessage(Exception e) { return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }
    static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) out.append(String.format(Locale.ROOT, "%02x", b & 255));
        return out.toString();
    }
}
