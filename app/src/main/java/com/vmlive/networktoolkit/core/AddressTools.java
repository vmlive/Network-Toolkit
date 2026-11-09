package com.vmlive.networktoolkit.core;

import java.net.IDN;
import java.net.InetAddress;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Local MAC inspection and bounded system-DNS address lookup; no geolocation service. */
public final class AddressTools {
    private AddressTools() {}
    // Platform DNS is not always interruptible. Cap its threads and queue as well as caller wait.
    private static final ThreadPoolExecutor DNS = new ThreadPoolExecutor(2, 2, 30,
            TimeUnit.SECONDS, new ArrayBlockingQueue<>(16), r -> {
                Thread thread = new Thread(r, "address-inspection-dns");
                thread.setDaemon(true);
                return thread;
            });

    public static String inspect(String input, NetworkTools.Cancellation cancellation) throws Exception {
        NetworkTools.Cancellation c = cancellation == null ? new NetworkTools.Cancellation() : cancellation;
        c.check();
        String value = input == null ? "" : input.trim();
        if (looksLikeMac(value)) return inspectMac(value);
        String host = validatedHost(value);
        long started = System.nanoTime();
        long overallDeadline = started + TimeUnit.SECONDS.toNanos(8);
        InetAddress[] addresses = await(() -> InetAddress.getAllByName(host),
                Math.min(overallDeadline, started + TimeUnit.SECONDS.toNanos(6)), c);
        StringBuilder out = new StringBuilder("IP / 域名信息\n目标：").append(value)
                .append("\n解析主机：").append(host).append("\n\n");
        int count = Math.min(addresses.length, 16);
        for (int i = 0; i < count; i++) {
            c.check();
            InetAddress address = addresses[i];
            out.append(address.getAddress().length == 4 ? "IPv4  " : "IPv6  ")
                    .append(address.getHostAddress()).append('\n')
                    .append("类型：").append(classify(address)).append('\n');
            // Limit reverse work: no more than four addresses and eight seconds for the operation.
            if (i < 4 && System.nanoTime() < overallDeadline) {
                try {
                    long reverseDeadline = Math.min(overallDeadline,
                            System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1000));
                    String reverse = await(address::getCanonicalHostName, reverseDeadline, c);
                    out.append("反向名称：").append(reverse.equalsIgnoreCase(address.getHostAddress())
                            ? "系统未返回 PTR 名称" : reverse).append('\n');
                } catch (CancellationException cancelled) { throw cancelled; }
                catch (Exception unavailable) { out.append("反向名称：不可用或查询超时\n"); }
            } else out.append("反向名称：未查询（数量或时限上限）\n");
            out.append('\n');
        }
        if (addresses.length > count) out.append("[仅显示前 16 个地址]\n");
        c.check();
        return out.append(String.format(Locale.ROOT, "耗时：%.1f ms\n", (System.nanoTime() - started) / 1e6))
                .append("\n地址分类不代表可达性。反向名称使用系统解析器，可能来自缓存或 hosts；不作为设备身份认证。\n")
                .append("未查询国家、运营商或精确位置；不会根据 IP 推测归属地。")
                .toString();
    }

    private static <T> T await(Callable<T> operation, long deadline,
                               NetworkTools.Cancellation c) throws Exception {
        c.check();
        Future<T> future;
        try { future = DNS.submit(operation); }
        catch (RejectedExecutionException busy) { throw new java.io.IOException("系统 DNS 查询队列已满，请稍后重试", busy); }
        try {
            while (true) {
                c.check();
                long left = deadline - System.nanoTime();
                if (left <= 0) throw new TimeoutException("系统 DNS 查询超过时限");
                try { return future.get(Math.min(left, TimeUnit.MILLISECONDS.toNanos(100)), TimeUnit.NANOSECONDS); }
                catch (TimeoutException poll) { /* bounded cancellation polling */ }
                catch (ExecutionException error) {
                    if (error.getCause() instanceof Exception) throw (Exception) error.getCause();
                    throw error;
                }
            }
        } finally { future.cancel(true); }
    }

    private static boolean looksLikeMac(String text) {
        return text.matches("(?i)[0-9a-f]{12}") || text.matches("(?i)([0-9a-f]{4}\\.){2}[0-9a-f]{4}")
                || text.matches("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}")
                || text.matches("(?i)([0-9a-f]{2}-){5}[0-9a-f]{2}");
    }

    static String normalizeMac(String text) {
        if (!looksLikeMac(text)) throw new IllegalArgumentException("MAC 格式示例：AA:BB:CC:DD:EE:FF");
        String hex = text.replace(":", "").replace("-", "").replace(".", "").toUpperCase(Locale.ROOT);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < 12; i += 2) {
            if (i > 0) out.append(':');
            out.append(hex, i, i + 2);
        }
        return out.toString();
    }

    private static String inspectMac(String input) {
        String mac = normalizeMac(input);
        int first = Integer.parseInt(mac.substring(0, 2), 16);
        boolean broadcast = mac.equals("FF:FF:FF:FF:FF:FF");
        boolean empty = mac.equals("00:00:00:00:00:00");
        String type = broadcast ? "广播地址" : (first & 1) == 0 ? "单播地址（I/G=0）" : "组播地址（I/G=1）";
        String administration = (first & 2) == 0 ? "全局管理位（U/L=0）" : "本地管理位（U/L=1，可能为随机 MAC）";
        return "MAC 地址信息\n\n标准格式：" + mac + "\n紧凑格式：" + mac.replace(":", "")
                + "\nOUI 前缀：" + mac.substring(0, 8) + "\n地址类型：" + type
                + "\n管理类型：" + administration
                + (empty ? "\n注意：全零地址通常是缺省值或不可用地址。" : "")
                + "\n\n未内置厂商数据库，不根据前缀猜测厂商。对于本地管理或随机 MAC，前缀通常不能用于厂商识别。"
                + "\nMAC 信息来自输入文本，不代表已发现局域网中的实体设备。";
    }

    static String validatedHost(String input) {
        if (input == null || input.isEmpty() || input.length() > 253 || input.matches(".*[\\s/@?#\\\\].*"))
            throw new IllegalArgumentException("请输入 IP、域名或 MAC，不要包含 URL、端口和路径");
        String text = input;
        if (text.startsWith("[") && text.endsWith("]")) text = text.substring(1, text.length() - 1);
        if (text.contains(":")) {
            // A literal IPv6 address cannot trigger arbitrary hostname resolution.
            if (!text.matches("[0-9a-fA-F:]+(?:%[A-Za-z0-9_.-]+)?") &&
                    !text.matches("[0-9a-fA-F:]+[0-9.]+(?:%[A-Za-z0-9_.-]+)?"))
                throw new IllegalArgumentException("IPv6 地址格式无效");
            try {
                InetAddress parsed = InetAddress.getByName(text);
                if (parsed.getAddress().length != 16 && !text.toLowerCase(Locale.ROOT).startsWith("::ffff:"))
                    throw new IllegalArgumentException("IPv6 地址格式无效");
            } catch (java.net.UnknownHostException invalid) { throw new IllegalArgumentException("IPv6 地址格式无效", invalid); }
            return text;
        }
        if (text.matches("[0-9.]+")) {
            long ipv4 = NetworkTools.ipv4(text);
            return ((ipv4 >>> 24) & 255) + "." + ((ipv4 >>> 16) & 255) + "." + ((ipv4 >>> 8) & 255) + "." + (ipv4 & 255);
        }
        String ascii;
        try { ascii = IDN.toASCII(text, IDN.USE_STD3_ASCII_RULES); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("域名格式无效", invalid); }
        if (ascii.isEmpty() || ascii.length() > 253) throw new IllegalArgumentException("域名长度无效");
        String labels = ascii.endsWith(".") ? ascii.substring(0, ascii.length() - 1) : ascii;
        for (String part : labels.split("\\.", -1))
            if (part.isEmpty() || part.length() > 63 || !part.matches("[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?"))
                throw new IllegalArgumentException("域名标签格式无效");
        return ascii;
    }

    static String classify(InetAddress address) {
        if (address.isAnyLocalAddress()) return "未指定地址";
        if (address.isLoopbackAddress()) return "本机回环地址";
        if (address.isMulticastAddress()) return "组播地址";
        if (address.isLinkLocalAddress()) return "链路本地地址";
        byte[] bytes = address.getAddress();
        if (bytes.length == 16) {
            if ((bytes[0] & 0xfe) == 0xfc) return "IPv6 唯一本地地址（ULA）";
            if (address.isSiteLocalAddress()) return "IPv6 站点本地地址（已弃用）";
            if ((bytes[0] & 255) == 0x20 && (bytes[1] & 255) == 0x01 &&
                    (bytes[2] & 255) == 0x0d && (bytes[3] & 255) == 0xb8)
                return "IPv6 文档示例地址";
            return "IPv6 其他地址（是否可公网路由需实际网络确认）";
        }
        int a = bytes[0] & 255, b = bytes[1] & 255, c = bytes[2] & 255;
        if (address.isSiteLocalAddress()) return "IPv4 私有地址（RFC 1918）";
        if (a == 100 && b >= 64 && b <= 127) return "运营商共享地址（CGNAT，100.64.0.0/10）";
        if (a == 192 && b == 0 && c == 2 || a == 198 && b == 51 && c == 100 || a == 203 && b == 0 && c == 113)
            return "IPv4 文档示例地址";
        if (a == 198 && (b == 18 || b == 19)) return "基准测试专用地址";
        if (a == 0 || a >= 240 || a == 192 && b == 0 && c == 0 || a == 192 && b == 88 && c == 99)
            return "特殊用途 / 保留地址（需进一步确认）";
        return "IPv4 普通地址（不在本工具列出的本地/保留网段；可达性需实际确认）";
    }
}
