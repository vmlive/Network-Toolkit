package com.vmlive.networktoolkit.core;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.PortUnreachableException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Actual ICMP/TTL and UDP diagnostics, without shell interpolation or raw-socket privileges. */
public final class AdvancedDiagnostics {
    private AdvancedDiagnostics() {}
    private static final ThreadFactory DAEMON = work -> {
        Thread thread = new Thread(work, "advanced-network-tool"); thread.setDaemon(true); return thread;
    };
    private static final ExecutorService IO = new ThreadPoolExecutor(4, 4, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(16), DAEMON);
    private static final Pattern REPLY_TIME = Pattern.compile("time[=<]\\s*([0-9.]+)\\s*ms", Pattern.CASE_INSENSITIVE);
    private static final Pattern SOURCE = Pattern.compile("\\bfrom\\s+([^\\s(]+)(?:\\s+\\(([^)]+)\\))?", Pattern.CASE_INSENSITIVE);

    /** ping/trace: host [count=N] [family=auto|4|6] [maxHops=N]; UDP: host:port [payload]. */
    public static String execute(String id, String input, NetworkTools.Cancellation cancellation,
                                 NetworkTools.Progress progress) throws Exception {
        NetworkTools.Cancellation c = cancellation == null ? new NetworkTools.Cancellation() : cancellation;
        c.check();
        if ("udp".equals(id)) return udp(input, c, progress);
        Config config = Config.parse(input);
        switch (id) {
            case "ping": return ping(config, c, progress);
            case "trace": return trace(config, c, progress);
            case "diagnose": return diagnose(config, c, progress);
            default: throw new IllegalArgumentException("未知诊断工具：" + id);
        }
    }

    static final class Config {
        final String host, family;
        final int count, maxHops;
        Config(String host, int count, String family, int maxHops) {
            this.host = host; this.count = count; this.family = family; this.maxHops = maxHops;
        }
        static Config parse(String input) {
            String[] words = (input == null ? "" : input.trim()).split("\\s+");
            if (words.length == 0 || words[0].isEmpty()) throw new IllegalArgumentException("请输入域名或 IP 地址");
            String host = NetworkTools.HostPort.parse(words[0], 443).host;
            // These tools operate on IP hosts; reject host:port rather than silently ignoring it.
            if (words[0].startsWith("[") && !words[0].endsWith("]") ||
                    words[0].indexOf(':') == words[0].lastIndexOf(':') && words[0].contains(":"))
                throw new IllegalArgumentException("此工具只需要主机名或 IP，请勿附加端口");
            int count = 3, hops = 16, positional = 0;
            String family = "auto";
            for (int i = 1; i < words.length; i++) {
                String word = words[i];
                if (word.startsWith("count=")) count = integer(word.substring(6), "发包数", 1, 20);
                else if (word.startsWith("family=")) family = family(word.substring(7));
                else if (word.startsWith("maxHops=")) hops = integer(word.substring(8), "最大跳数", 1, 16);
                else if (positional++ == 0) count = integer(word, "发包数", 1, 20);
                else if (positional == 2) family = family(word);
                else if (positional == 3) hops = integer(word, "最大跳数", 1, 16);
                else throw new IllegalArgumentException("格式：主机 count=3 family=auto maxHops=16");
            }
            return new Config(host, count, family, hops);
        }
        private static String family(String text) {
            if (text.equals("auto") || text.equals("4") || text.equals("6")) return text;
            if (text.equalsIgnoreCase("IPv4")) return "4";
            if (text.equalsIgnoreCase("IPv6")) return "6";
            throw new IllegalArgumentException("IP 协议必须为 auto、4 或 6");
        }
        private static int integer(String text, String label, int low, int high) {
            if (!text.matches("[0-9]{1,3}")) throw new IllegalArgumentException(label + "格式无效");
            int value = Integer.parseInt(text);
            if (value < low || value > high) throw new IllegalArgumentException(label + "必须为 " + low + "–" + high);
            return value;
        }
    }

    private static InetAddress resolve(Config config, NetworkTools.Cancellation c) throws Exception {
        Future<InetAddress[]> task = IO.submit(() -> InetAddress.getAllByName(config.host));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
        try {
            while (true) {
                c.check();
                if (System.nanoTime() > deadline) throw new TimeoutException("DNS 解析超过 6 秒时限");
                try {
                    for (InetAddress address : task.get(100, TimeUnit.MILLISECONDS)) {
                        if (config.family.equals("auto") || config.family.equals("4") && address instanceof Inet4Address ||
                                config.family.equals("6") && address instanceof Inet6Address) return address;
                    }
                    throw new IOException("目标没有可用的 IPv" + config.family + " 地址");
                } catch (TimeoutException wait) { /* bounded cancellation polling */ }
                catch (ExecutionException error) { throw unwrap(error); }
            }
        } finally { task.cancel(true); }
    }

    private static String ping(Config config, NetworkTools.Cancellation c, NetworkTools.Progress progress) throws Exception {
        InetAddress target = resolve(config, c);
        String address = target.getHostAddress();
        StringBuilder out = new StringBuilder("$ ping ").append(config.host).append("\n目标：").append(address)
                .append("\n协议：IPv").append(target instanceof Inet6Address ? "6" : "4")
                .append("  发包数：").append(config.count).append("\n\n");
        emit(progress, out.toString());
        ProcessResult result;
        try {
            result = runPing(target, config.count, 0, Math.min(30, config.count + 5), c,
                    line -> emit(progress, line));
        } catch (IOException unavailable) {
            throw new IOException("系统 ICMP Ping 不可用：" + message(unavailable) + "。未替换为 TCP 测试。", unavailable);
        }
        out.append(result.output.trim());
        if (result.timedOut) out.append("\n[达到本次 Ping 时限，进程已结束]");
        String unsupported = unsupported(result);
        if (unsupported != null) out.append("\n\nICMP 能力受系统限制：").append(unsupported).append("\n未使用 TCP 数据替代 ICMP 结果。");
        else if (result.exit != 0) out.append("\n\n未收到足够的 ICMP 回包。防火墙、网络或目标禁用 ICMP 均可能导致此结果；不能据此断言目标离线。");
        return out.toString();
    }

    private static String trace(Config config, NetworkTools.Cancellation c, NetworkTools.Progress progress) throws Exception {
        InetAddress target = resolve(config, c);
        String targetIp = target.getHostAddress();
        StringBuilder out = new StringBuilder("$ traceroute ").append(config.host).append("\n目标：").append(targetIp)
                .append("\n最大跳数：").append(config.maxHops).append("  每跳发包：").append(config.count)
                .append("\n使用实际 TTL / Hop Limit 递增的 ICMP 探测。\n\n#   IP / 响应路由器        延时 / 响应\n");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
        boolean reached = false;
        for (int hop = 1; hop <= config.maxHops; hop++) {
            c.check();
            int remaining = (int) Math.max(0, TimeUnit.NANOSECONDS.toSeconds(deadline - System.nanoTime()));
            if (remaining < 1) { out.append("\n[达到 120 秒总时限，保留已完成跳数]\n"); break; }
            ProcessResult result;
            try {
                result = runPing(target, config.count, hop, Math.min(config.count + 3, remaining), c, null);
            } catch (IOException error) {
                out.append("\n系统路由探测不可用：").append(message(error)).append("\n未生成推测路径。");
                return out.toString();
            }
            String unavailable = unsupported(result);
            if (unavailable != null) {
                out.append("\n当前系统不支持此 ICMP / TTL 探测：").append(unavailable).append("\n未生成推测路径。");
                return out.toString();
            }
            String lower = result.output.toLowerCase(Locale.ROOT);
            if (lower.contains("network is unreachable") || lower.contains("no route to host")) {
                out.append(hop).append("   *   网络不可达\n").append(result.output.trim()); break;
            }
            List<String> routers = replySources(result.output);
            String router = routers.isEmpty() ? "*" : joinUnique(routers);
            List<Double> times = replyTimes(result.output);
            boolean ttlExpired = lower.contains("time to live exceeded") || lower.contains("time exceeded") || lower.contains("hop limit");
            String timing = times.isEmpty() ? (routers.isEmpty() ? "请求超时" :
                    ttlExpired ? "TTL 超时响应；系统未提供 RTT" : "收到 ICMP 反馈；系统未提供 RTT") : formatTimes(times);
            String line = String.format(Locale.ROOT, "%2d  %s\n    %s  响应 %d/%d\n", hop, router, timing, routers.size(), config.count);
            out.append(line); emit(progress, out.toString());
            if (!ttlExpired && !times.isEmpty() && containsAddress(routers, target)) {
                reached = true; break;
            }
            if (lower.contains("destination host unreachable") || lower.contains("destination net unreachable") ||
                    lower.contains("administratively prohibited")) {
                out.append("网络返回 ICMP 不可达 / 禁止转发反馈，停止本次路径探测。\n"); break;
            }
        }
        out.append(reached ? "\n已到达目标。" : "\n本次探测未确认到达目标。* 表示未收到可展示的 ICMP 回应，不等于该跳离线。");
        out.append("\n归属地未查询；不会用推测地址填充路径或地区。");
        return out.toString();
    }

    private static String diagnose(Config config, NetworkTools.Cancellation c, NetworkTools.Progress progress) throws Exception {
        StringBuilder out = new StringBuilder("$ diagnose ").append(config.host).append("\n一键诊断\n");
        stage(out, "1 / 5  DNS 查询", () -> NetworkTools.dns(config.host, c), c, progress);
        stage(out, "2 / 5  ICMP Ping", () -> ping(new Config(config.host, 3, config.family, 16), c, progress), c, progress);
        stage(out, "3 / 5  TCP 80", () -> NetworkTools.tcpLatency(config.host, 80, 1, 2500, c, progress), c, progress);
        stage(out, "4 / 5  TCP 443", () -> NetworkTools.tcpLatency(config.host, 443, 1, 2500, c, progress), c, progress);
        stage(out, "5 / 5  路由追踪", () -> trace(new Config(config.host, 1, config.family, 16), c, progress), c, progress);
        return out.append("\n\n诊断结束。各协议可能被独立过滤；请结合 DNS、ICMP 和 TCP 结果判断。").toString();
    }
    private interface Stage { String run() throws Exception; }
    private static void stage(StringBuilder out, String title, Stage action, NetworkTools.Cancellation c,
                              NetworkTools.Progress progress) throws Exception {
        c.check(); out.append("\n\n──── ").append(title).append(" ────\n"); emit(progress, out.toString());
        try { out.append(action.run()); }
        catch (CancellationException stopped) { throw stopped; }
        catch (Exception error) { c.check(); out.append("未完成：").append(message(error)); }
        emit(progress, out.toString());
    }

    private static String udp(String input, NetworkTools.Cancellation c, NetworkTools.Progress progress) throws Exception {
        String[] parts = (input == null ? "" : input.trim()).split("\\s+", 2);
        if (parts.length == 0 || parts[0].isEmpty()) throw new IllegalArgumentException("请输入 主机:端口 [UTF-8 消息]");
        NetworkTools.HostPort target = NetworkTools.HostPort.parse(parts[0], 7);
        byte[] bytes = (parts.length > 1 ? parts[1] : "NetworkToolkit").getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 4096) throw new IllegalArgumentException("UDP 消息最多 4096 字节");
        InetAddress address = resolve(new Config(target.host, 1, "auto", 1), c);
        long started = System.nanoTime();
        try (DatagramSocket socket = new DatagramSocket();
             NetworkTools.Cancellation.Registration ignored = c.onCancel(socket::close)) {
            socket.connect(address, target.port); socket.setSoTimeout(2500); c.check();
            socket.send(new DatagramPacket(bytes, bytes.length));
            StringBuilder out = new StringBuilder("$ udp ").append(target.host).append(':').append(target.port)
                    .append("\n实际发送：").append(bytes.length).append(" 字节\n等待响应：2.5 秒\n");
            emit(progress, out.toString());
            byte[] response = new byte[4097]; DatagramPacket packet = new DatagramPacket(response, response.length);
            try {
                socket.receive(packet); c.check();
                int length = Math.min(packet.getLength(), 4096);
                out.append("\n收到 ").append(length).append(" 字节，来自 ").append(packet.getAddress().getHostAddress()).append(':').append(packet.getPort())
                        .append(String.format(Locale.ROOT, "\n往返耗时：%.2f ms\n\nUTF-8 预览\n", elapsed(started)))
                        .append(new String(response, 0, length, StandardCharsets.UTF_8));
                if (packet.getLength() > 4096) out.append("\n[响应预览已截断]");
            } catch (SocketTimeoutException timeout) {
                c.check(); out.append("\n未在时限内收到 UDP 响应。\nUDP 没有握手；目标可能忽略此消息、静默开放、被过滤或不可达，不能据此判定端口关闭。");
            } catch (PortUnreachableException unreachable) {
                c.check(); out.append("\n收到端口不可达反馈。目标或中间网络拒绝此 UDP 数据报。");
            } catch (IOException error) { c.check(); throw error; }
            return out.toString();
        }
    }

    private static final class ProcessResult {
        final String output; final int exit; final boolean timedOut;
        ProcessResult(String output, int exit, boolean timedOut) { this.output = output; this.exit = exit; this.timedOut = timedOut; }
    }
    private static ProcessResult runPing(InetAddress target, int count, int ttl, int seconds,
                                         NetworkTools.Cancellation c, NetworkTools.Progress progress) throws Exception {
        List<String> command = new ArrayList<>(); boolean ipv6 = target instanceof Inet6Address;
        String executable = pingExecutable(ipv6); command.add(executable);
        if (ipv6 && !new File(executable).getName().equals("ping6")) command.add("-6");
        command.add("-n"); command.add("-c"); command.add(Integer.toString(count));
        // Keep the platform's normal probe interval; older Android ping builds
        // reject fractional -i values for unprivileged app processes.
        command.add("-W"); command.add("2");
        command.add("-w"); command.add(Integer.toString(Math.max(1, seconds)));
        if (ttl > 0) { command.add("-t"); command.add(Integer.toString(ttl)); }
        command.add(target.getHostAddress());
        c.check(); Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        Future<String> output = IO.submit(() -> readOutput(process.getInputStream(), progress));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(Math.max(1, seconds));
        boolean timedOut = false;
        try (NetworkTools.Cancellation.Registration ignored = c.onCancel(process::destroy)) {
            while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
                c.check();
                if (System.nanoTime() >= deadline) { timedOut = true; process.destroy(); break; }
            }
            c.check();
            if (!process.waitFor(500, TimeUnit.MILLISECONDS)) process.destroyForcibly();
            String value;
            try { value = output.get(1, TimeUnit.SECONDS); }
            catch (TimeoutException wait) { output.cancel(true); value = "[读取系统 Ping 输出超时]"; }
            catch (ExecutionException error) { throw unwrap(error); }
            int exit; try { exit = process.exitValue(); } catch (IllegalThreadStateException running) { exit = -1; }
            return new ProcessResult(value, exit, timedOut);
        } finally { process.destroy(); output.cancel(true); }
    }
    private static String readOutput(InputStream stream, NetworkTools.Progress progress) throws IOException {
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                int remaining = 65536 - out.length(); if (remaining <= 0) break;
                String bounded = line.substring(0, Math.min(line.length(), remaining));
                out.append(bounded).append('\n'); emit(progress, bounded);
            }
        }
        return out.toString();
    }
    private static String pingExecutable(boolean ipv6) {
        if (ipv6 && new File("/system/bin/ping6").canExecute()) return "/system/bin/ping6";
        if (new File("/system/bin/ping").canExecute()) return "/system/bin/ping";
        if (ipv6 && new File("/bin/ping6").canExecute()) return "/bin/ping6";
        return "ping";
    }
    private static String unsupported(ProcessResult result) {
        String lower = result.output.toLowerCase(Locale.ROOT);
        if (lower.contains("operation not permitted") || lower.contains("permission denied")) return result.output.trim();
        if (lower.contains("invalid option") || lower.contains("unknown option") || lower.contains("invalid argument") || lower.contains("usage:")) return result.output.trim();
        return null;
    }
    static List<String> replySources(String text) {
        List<String> sources = new ArrayList<>();
        for (String line : text.split("\\r?\\n")) {
            Matcher matcher = SOURCE.matcher(line);
            if (!matcher.find()) continue;
            String value = matcher.group(2) != null ? matcher.group(2) : matcher.group(1);
            if (value.endsWith(":") && !value.endsWith("::")) value = value.substring(0, value.length() - 1);
            if (value.matches("[0-9a-fA-F:.%a-zA-Z0-9_-]+") && (value.contains(":") || value.matches("[0-9.]+"))) sources.add(value);
        }
        return sources;
    }
    static List<Double> replyTimes(String text) {
        List<Double> values = new ArrayList<>(); Matcher matcher = REPLY_TIME.matcher(text);
        while (matcher.find()) {
            try { double value = Double.parseDouble(matcher.group(1)); if (Double.isFinite(value) && value >= 0) values.add(value); }
            catch (NumberFormatException ignored) { /* malformed system output is not a measurement */ }
        }
        return values;
    }
    private static String formatTimes(List<Double> values) {
        double total = 0; for (double value : values) total += value;
        return String.format(Locale.ROOT, "平均 %.2f ms", total / values.size());
    }
    private static boolean containsAddress(List<String> replies, InetAddress target) {
        for (String reply : replies) {
            try {
                if (java.util.Arrays.equals(InetAddress.getByName(reply).getAddress(), target.getAddress())) return true;
            } catch (Exception malformed) { /* Only an actual matching numeric response confirms destination. */ }
        }
        return false;
    }
    private static String joinUnique(List<String> values) { return String.join(", ", new java.util.LinkedHashSet<>(values)); }
    private static Exception unwrap(ExecutionException error) {
        return error.getCause() instanceof Exception ? (Exception) error.getCause() : error;
    }
    private static void emit(NetworkTools.Progress progress, String value) { if (progress != null) progress.onProgress(value); }
    private static String message(Exception error) { return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(); }
    private static double elapsed(long start) { return (System.nanoTime() - start) / 1e6; }
}
