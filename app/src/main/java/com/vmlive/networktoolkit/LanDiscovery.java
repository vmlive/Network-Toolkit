package com.vmlive.networktoolkit;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.RouteInfo;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import com.vmlive.networktoolkit.core.NetworkTools.Cancellation;
import com.vmlive.networktoolkit.core.NetworkTools.Progress;
import java.io.BufferedReader;
import java.io.FileReader;
import java.net.ConnectException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;

/** Real local-network discovery. Missing responses and inaccessible MAC addresses remain unknown. */
public final class LanDiscovery {
    private LanDiscovery() {}
    private static final int[] PROBE_PORTS = {80, 443, 22, 445};
    private static final String[] SERVICE_TYPES = {"_http._tcp.", "_ipp._tcp.", "_airplay._tcp.", "_googlecast._tcp."};

    public static String networkSummary(Context context) {
        Info info = current(context);
        if (info.ip == null) return "当前网络未提供 IPv4 地址\n请连接需要检测的 Wi-Fi 或以太网后刷新。";
        return "本机 IP  " + info.ip + "\n子网掩码  " + text(info.mask) +
                "\n网关  " + text(info.gateway) + "\n接口  " + text(info.iface) +
                "\n扫描范围  " + defaultRange(info) +
                (info.prefix < 24 ? "\n当前网段大于 /24，默认只检查本机所在的 /24。" : "");
    }

    /** Empty when there is no suitable IPv4 network; never invents an active network. */
    public static String defaultCidr(Context context) {
        Info info = current(context);
        return info.ip == null ? "" : defaultRange(info);
    }

    private static String defaultRange(Info info) {
        return info.ip + "/" + Math.max(24, info.prefix);
    }

    private static String text(String value) { return value == null || value.isEmpty() ? "系统未提供" : value; }

    private static Info current(Context context) {
        Info info = new Info();
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return info;
        LinkProperties lp = cm.getLinkProperties(cm.getActiveNetwork());
        if (lp == null) return info;
        info.iface = lp.getInterfaceName();
        for (LinkAddress address : lp.getLinkAddresses()) {
            if (!(address.getAddress() instanceof Inet4Address)) continue;
            info.ip = address.getAddress().getHostAddress(); info.prefix = address.getPrefixLength();
            long mask = info.prefix == 0 ? 0 : (0xffffffffL << (32 - info.prefix)) & 0xffffffffL;
            info.mask = ipText(mask); break;
        }
        for (RouteInfo route : lp.getRoutes()) {
            if (route.isDefaultRoute() && route.getGateway() instanceof Inet4Address) {
                info.gateway = route.getGateway().getHostAddress(); break;
            }
        }
        return info;
    }

    private static final class Info { String ip, mask, gateway, iface; int prefix; }

    /** A user-selected private/link-local IPv4 CIDR, bounded to 256 addresses and 18 seconds. */
    public static String scan(Context context, String cidr, Cancellation cancellation, Progress progress) throws Exception {
        final Cancellation c = cancellation == null ? new Cancellation() : cancellation;
        c.check();
        Info info = current(context);
        String selection = cidr == null ? "" : cidr.trim();
        if (selection.isEmpty()) {
            if (info.ip == null) throw new IllegalStateException("当前没有可用于局域网扫描的 IPv4 网络，请先连接 Wi-Fi 或以太网");
            selection = defaultRange(info);
        }
        Range range = parseCidr(selection);
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(18);
        ConcurrentSkipListMap<Long, String> found = new ConcurrentSkipListMap<>();
        ConcurrentHashMap<Long, Peer> peers = new ConcurrentHashMap<>();
        AtomicInteger checked = new AtomicInteger();
        AtomicLong lastProgress = new AtomicLong();
        AtomicBoolean ended = new AtomicBoolean();
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(16, range.hosts()), r -> {
            Thread thread = new Thread(r, "lan-probe"); thread.setDaemon(true); return thread;
        });
        List<Future<?>> pending = new ArrayList<>();
        long started = System.nanoTime();
        try {
            Long ownAddress = info.ip == null ? null : ipv4(info.ip);
            if (ownAddress != null && ownAddress >= range.first && ownAddress <= range.last) {
                Peer own = new Peer(info.ip, "本机", null, "local");
                peers.put(ownAddress, own);
                found.put(ownAddress, "本机\n" + info.ip + "  本机 · 当前网络接口");
                checked.incrementAndGet();
                emit(progress, own.event(""));
            }
            emit(progress, "scan|" + found.size() + "|" + checked.get() + "|" + range.hosts());
            for (long value = range.first; value <= range.last; value++) {
                if (ownAddress != null && value == ownAddress) continue;
                final long target = value;
                pending.add(pool.submit(() -> {
                    c.check();
                    if (System.nanoTime() >= deadline) return;
                    String ip = ipText(target);
                    Probe response = ip.equals(info.ip) ? new Probe("本机 · 当前网络接口", null) : probe(ip, c, deadline);
                    if (response != null) {
                        String name = ip.equals(info.ip) ? "本机" : ip.equals(info.gateway) ? "网关" : ip;
                        found.put(target, name + "\n" + ip + "  " + response.text);
                        Peer peer = new Peer(ip, name, response.ms, ip.equals(info.ip) ? "local" : ip.equals(info.gateway) ? "gateway" : "device");
                        peers.put(target, peer);
                        if (!ended.get()) emit(progress, peer.event(""));
                    }
                    int done = checked.incrementAndGet();
                    long now = System.nanoTime(), previous = lastProgress.get();
                    if (!ended.get() && progress != null && (done == range.hosts() || now - previous > 250_000_000L) && lastProgress.compareAndSet(previous, now)) {
                        progress.onProgress("scan|" + found.size() + "|" + checked.get() + "|" + range.hosts());
                    }
                }));
            }
            for (Future<?> task : pending) {
                while (!task.isDone()) {
                    c.check();
                    if (System.nanoTime() >= deadline) break;
                    try { task.get(75, TimeUnit.MILLISECONDS); }
                    catch (TimeoutException waiting) { /* cancellation/deadline polling */ }
                }
                if (task.isDone()) task.get();
                if (System.nanoTime() >= deadline) break;
            }
            c.check();
            boolean timedOut = checked.get() < range.hosts();
            Map<String, String> arp = readArp();
            for (Peer peer : peers.values()) {
                String mac = arp.get(peer.ip);
                if (mac != null && !peer.kind.equals("local")) emit(progress, peer.event(mac));
            }
            emit(progress, "scan|" + found.size() + "|" + checked.get() + "|" + range.hosts());
            ended.set(true);
            return scanResult(range, checked.get(), found, arp, timedOut, elapsed(started));
        } finally {
            ended.set(true);
            for (Future<?> task : pending) task.cancel(true);
            pool.shutdownNow();
        }
    }

    private static final class Probe {
        final String text; final Double ms;
        Probe(String text, Double ms) { this.text = text; this.ms = ms; }
    }
    private static final class Peer {
        final String ip, name, kind; final Double ms;
        Peer(String ip, String name, Double ms, String kind) { this.ip = ip; this.name = name; this.ms = ms; this.kind = kind; }
        String event(String mac) { return "device|" + ip + "|" + name + "|" + (ms == null ? "" : String.format(Locale.ROOT, "%.1f", ms)) + "|" + kind + "|" + mac; }
    }

    private static Probe probe(String ip, Cancellation c, long deadline) {
        c.check();
        try {
            InetAddress address = InetAddress.getByAddress(ipBytes(ipv4(ip)));
            long start = System.nanoTime();
            if (address.isReachable(250)) {
                double ms = elapsed(start);
                return new Probe(String.format(Locale.CHINA, "ICMP / 系统可达探测  %.1f ms", ms), ms);
            }
            for (int port : PROBE_PORTS) {
                c.check();
                if (System.nanoTime() >= deadline) return null;
                try (Socket socket = new Socket(); Cancellation.Registration registration = c.onCancel(() -> closeSocket(socket))) {
                    start = System.nanoTime();
                    try {
                        socket.connect(new InetSocketAddress(address, port), 180);
                        c.check();
                        double ms = elapsed(start);
                        return new Probe(String.format(Locale.CHINA, "TCP %d 开放响应  %.1f ms", port, ms), ms);
                    } catch (ConnectException rejected) {
                        c.check();
                        if (isRefused(rejected.getMessage())) {
                            double ms = elapsed(start);
                            return new Probe(String.format(Locale.CHINA, "TCP %d 拒绝响应  %.1f ms", port, ms), ms);
                        }
                    } catch (Exception unavailable) { c.check(); }
                }
            }
        } catch (CancellationException cancelled) { throw cancelled; }
        catch (Exception unavailable) { c.check(); }
        return null;
    }

    static boolean isRefused(String message) {
        return message != null && (message.contains("ECONNREFUSED") || message.toLowerCase(Locale.ROOT).contains("connection refused"));
    }

    private static void closeSocket(Socket socket) { try { socket.close(); } catch (Exception ignored) {} }

    private static String scanResult(Range range, int done, Map<Long, String> found, Map<String, String> arp,
                                     boolean timedOut, double ms) {
        StringBuilder out = new StringBuilder("局域网扫描\n范围：").append(range.cidr)
                .append("\n在线设备：").append(found.size()).append("\n已扫描：").append(done).append('/').append(range.hosts())
                .append("\n进度：").append(done * 100 / range.hosts()).append("%\n\n");
        for (Map.Entry<Long, String> entry : found.entrySet()) {
            out.append(entry.getValue()).append('\n');
            if (arp != null) {
                String mac = arp.get(ipText(entry.getKey()));
                out.append("MAC：").append(mac == null ? "系统未提供" : mac).append('\n');
            }
            out.append('\n');
        }
        if (found.isEmpty()) out.append("尚未发现可确认响应的设备。\n\n");
        if (timedOut) out.append("达到 18 秒时限，未完成的地址没有计入已扫描。\n");
        return out.append(String.format(Locale.CHINA, "耗时：%.2f 秒\n", ms / 1000))
                .append("只记录本机、系统可达探测或实际 TCP 开放/拒绝响应；未响应不等于离线。\nAndroid 可能限制 ARP/MAC 信息，不推测厂商或设备类型。").toString();
    }

    private static Map<String, String> readArp() {
        Map<String, String> result = new LinkedHashMap<>();
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/net/arp"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] pieces = line.trim().split("\\s+");
                if (pieces.length >= 4 && pieces[2].equals("0x2") && pieces[3].matches("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}") &&
                        !pieces[3].equalsIgnoreCase("00:00:00:00:00:00")) result.put(pieces[0], pieces[3].toUpperCase(Locale.ROOT));
            }
        } catch (Exception unavailable) { /* Android 10+ normally denies ARP access. */ }
        return result;
    }

    /** Discovers actual NSD/mDNS services for six seconds, with serialized bounded resolution. */
    @SuppressWarnings("deprecation")
    public static String bonjour(Context context, Cancellation cancellation, Progress progress) throws Exception {
        final Cancellation c = cancellation == null ? new Cancellation() : cancellation;
        c.check();
        NsdManager manager = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
        if (manager == null) throw new IllegalStateException("此设备未提供 NSD / Bonjour 服务发现接口");
        Map<String, String> found = new ConcurrentHashMap<>();
        Map<String, String> errors = new ConcurrentHashMap<>();
        List<NsdManager.DiscoveryListener> listeners = new CopyOnWriteArrayList<>();
        AtomicBoolean ended = new AtomicBoolean();
        ExecutorService resolver = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(64), r -> { Thread thread = new Thread(r, "nsd-resolve"); thread.setDaemon(true); return thread; });
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
        Runnable stop = () -> {
            ended.set(true);
            for (NsdManager.DiscoveryListener listener : listeners) {
                try { manager.stopServiceDiscovery(listener); } catch (Exception ignored) {}
            }
            resolver.shutdownNow();
        };
        try (Cancellation.Registration registration = c.onCancel(stop)) {
            for (String serviceType : SERVICE_TYPES) {
                c.check();
                NsdManager.DiscoveryListener listener = new NsdManager.DiscoveryListener() {
                    @Override public void onDiscoveryStarted(String type) { if (c.isCancelled() || ended.get()) try { manager.stopServiceDiscovery(this); } catch (Exception ignored) {} }
                    @Override public void onDiscoveryStopped(String type) {}
                    @Override public void onStartDiscoveryFailed(String type, int error) { errors.put(type, "启动失败 " + error); }
                    @Override public void onStopDiscoveryFailed(String type, int error) { errors.put(type, "停止失败 " + error); }
                    @Override public void onServiceFound(NsdServiceInfo info) {
                        if (c.isCancelled() || ended.get() || System.nanoTime() >= deadline) return;
                        String key = info.getServiceName() + "|" + info.getServiceType();
                        synchronized (found) {
                            if (found.containsKey(key)) return;
                            if (found.size() >= 64) { errors.put("发现上限", "已保留 64 个服务，忽略其他服务"); return; }
                            found.put(key, "名称：" + info.getServiceName() + "\n类型：" + info.getServiceType() + "\n端点：等待系统解析");
                        }
                        emit(progress, serviceResult("Bonjour / mDNS", found, errors));
                        try { resolver.submit(() -> {
                            if (c.isCancelled() || ended.get() || System.nanoTime() >= deadline) return;
                            CountDownLatch resolved = new CountDownLatch(1);
                            try {
                                manager.resolveService(info, new NsdManager.ResolveListener() {
                                    @Override public void onResolveFailed(NsdServiceInfo service, int error) {
                                        if (!c.isCancelled() && !ended.get() && System.nanoTime() < deadline) found.replace(key, "名称：" + info.getServiceName() + "\n类型：" + info.getServiceType() + "\n端点：系统解析失败 " + error);
                                        resolved.countDown();
                                    }
                                    @Override public void onServiceResolved(NsdServiceInfo service) {
                                        if (!c.isCancelled() && !ended.get() && System.nanoTime() < deadline) {
                                            found.replace(key, "名称：" + service.getServiceName() + "\n类型：" + service.getServiceType() +
                                                    "\n端点：" + (service.getHost() == null ? "系统未提供" : service.getHost().getHostAddress()) + ":" + service.getPort());
                                            emit(progress, serviceResult("Bonjour / mDNS", found, errors));
                                        }
                                        resolved.countDown();
                                    }
                                });
                                resolved.await(Math.max(1, Math.min(1200, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()))), TimeUnit.MILLISECONDS);
                            } catch (Exception unavailable) { errors.put(serviceType, "解析不可用：" + unavailable.getClass().getSimpleName()); }
                        }); } catch (java.util.concurrent.RejectedExecutionException rejected) {
                            if (!ended.get() && !c.isCancelled()) found.replace(key, "名称：" + info.getServiceName() + "\n类型：" + info.getServiceType() + "\n端点：解析队列已满");
                        }
                    }
                    @Override public void onServiceLost(NsdServiceInfo info) { found.remove(info.getServiceName() + "|" + info.getServiceType()); }
                };
                listeners.add(listener);
                try { manager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener); }
                catch (Exception unavailable) { errors.put(serviceType, unavailable.getClass().getSimpleName() + ": " + unavailable.getMessage()); }
            }
            while (System.nanoTime() < deadline) { c.check(); Thread.sleep(75); }
            c.check();
            return serviceResult("Bonjour / mDNS", found, errors) + "\n\n发现窗口：6 秒。仅列出系统实际报告的服务；未发现不代表网络中没有设备。";
        } finally { stop.run(); }
    }

    private static String serviceResult(String heading, Map<String, String> found, Map<String, String> errors) {
        StringBuilder out = new StringBuilder(heading).append("\n服务数量：").append(found.size()).append("\n\n");
        for (String value : new TreeMap<>(found).values()) out.append(value).append("\n\n");
        if (found.isEmpty()) out.append("尚未发现服务。\n");
        for (Map.Entry<String, String> error : new TreeMap<>(errors).entrySet()) out.append(error.getKey()).append("：").append(error.getValue()).append('\n');
        return out.toString();
    }

    /** SSDP M-SEARCH: multicast request, unicast replies, four seconds and 64 unique responses. */
    public static String ssdp(Context context, Cancellation cancellation, Progress progress) throws Exception {
        final Cancellation c = cancellation == null ? new Cancellation() : cancellation;
        c.check();
        WifiManager wifi = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        WifiManager.MulticastLock lock = null;
        if (wifi != null) {
            lock = wifi.createMulticastLock("network-toolkit-ssdp"); lock.setReferenceCounted(false); lock.acquire();
        }
        Map<String, String> found = new LinkedHashMap<>();
        try (DatagramSocket socket = new DatagramSocket(); Cancellation.Registration registration = c.onCancel(socket::close)) {
            socket.setSoTimeout(250);
            byte[] request = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: ssdp:all\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
            socket.send(new DatagramPacket(request, request.length, InetAddress.getByName("239.255.255.250"), 1900));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
            int received = 0, truncated = 0;
            while (System.nanoTime() < deadline && received < 128 && found.size() < 64) {
                c.check(); byte[] buffer = new byte[8192]; DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try { socket.receive(packet); }
                catch (SocketTimeoutException wait) { continue; }
                catch (SocketException closed) { c.check(); throw closed; }
                received++;
                if (packet.getLength() == buffer.length) { truncated++; continue; }
                Map<String, String> headers = parseSsdpResponse(new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8));
                if (headers.isEmpty()) continue;
                String usn = headers.get("usn"), st = headers.get("st"), location = headers.get("location");
                String key = packet.getAddress().getHostAddress() + "|" + text(usn) + "|" + text(st);
                found.put(key, "地址：" + packet.getAddress().getHostAddress() + "\n服务：" + text(st) +
                        "\n标识：" + text(usn) + "\n描述地址：" + text(location) + "\n服务端：" + text(headers.get("server")));
                emit(progress, serviceResult("SSDP / UPnP", found, Collections.emptyMap()));
            }
            c.check();
            return serviceResult("SSDP / UPnP", found, Collections.emptyMap()) +
                    (truncated == 0 ? "" : "\n已忽略 " + truncated + " 个达到 8 KiB 接收上限、可能截断的报文。") +
                    "\n\n发现窗口：4 秒。仅显示实际响应；不自动请求设备描述地址。";
        } finally { if (lock != null && lock.isHeld()) lock.release(); }
    }

    static Map<String, String> parseSsdpResponse(String packet) {
        if (packet == null || packet.length() >= 8192) return Collections.emptyMap();
        String[] lines = packet.split("\\r?\\n");
        if (lines.length == 0 || !lines[0].matches("(?i)HTTP/1\\.[01] 200(?: .*)?")) return Collections.emptyMap();
        Map<String, String> fields = new LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isEmpty()) break;
            int split = lines[i].indexOf(':');
            if (split > 0 && split < 128) fields.put(lines[i].substring(0, split).trim().toLowerCase(Locale.ROOT), lines[i].substring(split + 1).trim());
        }
        if (!fields.containsKey("st") && !fields.containsKey("usn") && !fields.containsKey("location")) return Collections.emptyMap();
        return fields;
    }

    static Range parseCidr(String cidr) {
        if (cidr == null) throw new IllegalArgumentException("请输入局域网 IPv4/CIDR");
        String[] parts = cidr.trim().split("/", -1);
        if (parts.length != 2 || !parts[1].matches("[0-9]{1,2}")) throw new IllegalArgumentException("格式示例：192.168.1.0/24");
        int prefix = Integer.parseInt(parts[1]);
        if (prefix < 24 || prefix > 32) throw new IllegalArgumentException("局域网扫描仅接受 /24 至 /32，一次最多 256 个地址");
        long address = ipv4(parts[0]), mask = (0xffffffffL << (32 - prefix)) & 0xffffffffL;
        long network = address & mask, broadcast = network | (~mask & 0xffffffffL);
        if (!isPrivate(network) || !isPrivate(broadcast)) throw new IllegalArgumentException("局域网扫描仅接受私有或链路本地 IPv4 地址");
        return new Range(ipText(network) + "/" + prefix, prefix < 31 ? network + 1 : network, prefix < 31 ? broadcast - 1 : broadcast);
    }

    static final class Range {
        final String cidr; final long first, last;
        Range(String cidr, long first, long last) { this.cidr = cidr; this.first = first; this.last = last; }
        int hosts() { return (int) (last - first + 1); }
    }

    private static boolean isPrivate(long address) {
        return address >>> 24 == 10 || address >>> 20 == 0xac1 || address >>> 16 == 0xc0a8 || address >>> 16 == 0xa9fe;
    }

    private static long ipv4(String value) {
        String[] octets = value.split("\\.", -1);
        if (octets.length != 4) throw new IllegalArgumentException("IPv4 地址无效");
        long result = 0;
        for (String octet : octets) {
            if (!octet.matches("[0-9]{1,3}")) throw new IllegalArgumentException("IPv4 地址无效");
            int n = Integer.parseInt(octet); if (n > 255) throw new IllegalArgumentException("IPv4 地址每段必须是 0–255");
            result = result * 256 + n;
        }
        return result;
    }

    private static byte[] ipBytes(long value) { return new byte[]{(byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value}; }
    private static String ipText(long value) { return ((value >>> 24) & 255) + "." + ((value >>> 16) & 255) + "." + ((value >>> 8) & 255) + "." + (value & 255); }
    private static double elapsed(long start) { return (System.nanoTime() - start) / 1_000_000.0; }
    private static void emit(Progress progress, String message) { if (progress != null) progress.onProgress(message); }
}
