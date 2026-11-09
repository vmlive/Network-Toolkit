package com.vmlive.networktoolkit.core;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.IDN;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Locale;

/** Small, bounded DNS UDP client. It never substitutes invented answers for a failed query. */
public final class DnsClient {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int MAX_PACKET = 4096;
    private DnsClient() {}

    public static String query(String host, String type, String server, NetworkTools.Cancellation cancel) throws Exception {
        NetworkTools.Cancellation c = cancel == null ? new NetworkTools.Cancellation() : cancel;
        c.check();
        String recordType = type == null ? "A" : type.toUpperCase(Locale.ROOT).trim();
        int number = typeNumber(recordType);
        String name = questionName(host, recordType);
        if (server == null || server.trim().isEmpty()) return systemQuery(host.trim(), recordType, c);
        NetworkTools.HostPort endpoint = NetworkTools.HostPort.parse(server.trim(), 53);
        InetAddress address = numericAddress(endpoint.host);
        int id = RANDOM.nextInt(65536);
        byte[] request = buildQuery(name, number, id);
        long start = System.nanoTime();
        try (DatagramSocket socket = new DatagramSocket();
             NetworkTools.Cancellation.Registration hook = c.onCancel(socket::close)) {
            socket.connect(new InetSocketAddress(address, endpoint.port));
            socket.setSoTimeout(2000);
            for (int attempt = 0; attempt < 2; attempt++) {
                c.check();
                socket.send(new DatagramPacket(request, request.length));
                byte[] buffer = new byte[MAX_PACKET];
                DatagramPacket reply = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(reply);
                    c.check();
                    if (reply.getLength() == MAX_PACKET) throw new IOException("DNS 响应达到 4096 字节上限，无法确认完整性");
                    String answer = parseResponse(Arrays.copyOf(reply.getData(), reply.getLength()), id, name, number);
                    return "$ dig " + name + " " + recordType + " @" + server.trim() + "\n\n" + answer +
                            String.format(Locale.ROOT, "\n;; Query time: %.1f msec\n;; SERVER: %s\n;; TRANSPORT: UDP", (System.nanoTime() - start) / 1e6, server.trim());
                } catch (SocketTimeoutException timeout) {
                    c.check();
                    if (attempt == 1) throw new SocketTimeoutException("DNS UDP 查询超时（2 次，每次 2 秒）；服务器可能不可达或 UDP 53 被过滤");
                } catch (IOException error) { c.check(); throw error; }
            }
        }
        throw new IOException("DNS 查询未完成");
    }

    private static String systemQuery(String name, String type, NetworkTools.Cancellation c) throws Exception {
        if (!type.equals("A") && !type.equals("AAAA"))
            throw new IllegalArgumentException("系统解析器仅提供 A / AAAA 地址；请指定 DNS 服务器查询 " + type);
        long start = System.nanoTime();
        String system = NetworkTools.dns(name, c);
        StringBuilder out = new StringBuilder("$ system-dns ").append(name).append(' ').append(type)
                .append("\n\n;; ANSWER SECTION:\n");
        int matches = 0;
        for (String line : system.split("\n")) {
            String prefix = type.equals("A") ? "A     " : "AAAA  ";
            if (line.startsWith(prefix)) {
                out.append(name).append("\tTTL 未公开\tIN\t").append(type).append('\t').append(line.substring(prefix.length())).append('\n');
                matches++;
            }
        }
        if (matches == 0) out.append(";; 系统解析器未返回 ").append(type).append(" 地址（不等同于权威 NODATA 应答）。\n");
        return out.append(String.format(Locale.ROOT, "\n;; Query time: %.1f msec\n;; SERVER: 系统解析器\n;; 系统 API 不公开 TTL、CNAME 链或原始 DNS 报文。", (System.nanoTime() - start) / 1e6)).toString();
    }

    static int typeNumber(String type) {
        switch (type) {
            case "A": return 1;
            case "NS": return 2;
            case "CNAME": return 5;
            case "SOA": return 6;
            case "PTR": return 12;
            case "MX": return 15;
            case "TXT": return 16;
            case "AAAA": return 28;
            case "SRV": return 33;
            default: throw new IllegalArgumentException("不支持的 DNS 类型：" + type);
        }
    }

    private static String typeName(int type) {
        switch (type) {
            case 1: return "A"; case 2: return "NS"; case 5: return "CNAME"; case 6: return "SOA";
            case 12: return "PTR"; case 15: return "MX"; case 16: return "TXT"; case 28: return "AAAA"; case 33: return "SRV";
            default: return "TYPE" + type;
        }
    }

    static byte[] buildQuery(String name, int type, int id) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeShort(id); out.writeShort(0x0100); out.writeShort(1); out.writeShort(0); out.writeShort(0); out.writeShort(0);
        String normalized = normalize(name);
        if (!normalized.equals(".")) for (String label : normalized.substring(0, normalized.length() - 1).split("\\.")) {
            byte[] chars = label.getBytes(StandardCharsets.US_ASCII); out.writeByte(chars.length); out.write(chars);
        }
        out.writeByte(0); out.writeShort(type); out.writeShort(1);
        return bytes.toByteArray();
    }

    static String parseResponse(byte[] packet, int expectedId, String expectedName, int expectedType) throws IOException {
        if (packet == null || packet.length < 12 || packet.length > MAX_PACKET) throw new IOException("DNS 报文长度无效");
        Reader r = new Reader(packet);
        int id = r.u16(), flags = r.u16(), questions = r.u16(), answers = r.u16(), authority = r.u16(), additional = r.u16();
        if (id != expectedId) throw new IOException("DNS transaction ID 不匹配");
        if ((flags & 0x8000) == 0 || (flags & 0x7800) != 0) throw new IOException("不是标准 DNS 查询响应");
        if ((flags & 0x0200) != 0) throw new IOException("DNS 响应被截断（TC=1）；此 UDP 工具未自动改用 TCP");
        if (questions != 1 || answers + authority + additional > 512) throw new IOException("DNS 记录数量无效");
        String question = r.name(); int questionType = r.u16(), questionClass = r.u16();
        if (!question.equalsIgnoreCase(normalize(expectedName)) || questionType != expectedType || questionClass != 1)
            throw new IOException("DNS question 与请求不匹配");
        int rcode = flags & 15;
        if (rcode != 0) {
            String status;
            switch (rcode) { case 1: status = "FORMERR"; break; case 2: status = "SERVFAIL"; break; case 3: status = "NXDOMAIN"; break; case 4: status = "NOTIMP"; break; case 5: status = "REFUSED"; break; default: status = "RCODE " + rcode; }
            throw new IOException("DNS " + status + "：" + normalize(expectedName));
        }
        StringBuilder out = new StringBuilder(";; status: NOERROR\n;; ANSWER SECTION:\n");
        for (int i = 0; i < answers; i++) out.append(r.record()).append('\n');
        if (answers == 0) out.append(";; 无 ANSWER 记录（NOERROR；可能为 NODATA 或转介，需结合 AUTHORITY 判断）。\n");
        if (authority > 0) {
            out.append("\n;; AUTHORITY SECTION:\n");
            for (int i = 0; i < authority; i++) out.append(r.record()).append('\n');
        }
        for (int i = 0; i < additional; i++) r.record();
        if (r.offset != packet.length) throw new IOException("DNS 报文包含未解析的尾随数据");
        return out.toString();
    }

    private static InetAddress numericAddress(String host) throws IOException {
        if (host.indexOf(':') >= 0) {
            if (!host.matches("[0-9a-fA-F:.%]+")) throw new IllegalArgumentException("DNS 服务器必须是 IPv4 / IPv6 数字地址");
            return InetAddress.getByName(host);
        }
        try { NetworkTools.ipv4(host); }
        catch (IllegalArgumentException bad) { throw new IllegalArgumentException("DNS 服务器必须是 IPv4 / IPv6 数字地址"); }
        return InetAddress.getByName(host);
    }

    static String questionName(String host, String type) throws IOException {
        String value = host == null ? "" : host.trim();
        if (type.equals("PTR") && (value.indexOf(':') >= 0 || value.matches("[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+"))) {
            byte[] address = numericAddress(value).getAddress();
            StringBuilder reverse = new StringBuilder();
            if (address.length == 4) {
                for (int i = 3; i >= 0; i--) reverse.append(address[i] & 255).append('.');
                return reverse.append("in-addr.arpa.").toString();
            }
            for (int i = 15; i >= 0; i--) reverse.append(Integer.toHexString(address[i] & 15)).append('.').append(Integer.toHexString((address[i] >>> 4) & 15)).append('.');
            return reverse.append("ip6.arpa.").toString();
        }
        return normalize(value);
    }

    private static String normalize(String name) {
        String value = name == null ? "" : name.trim();
        if (value.equals(".")) return value;
        if (value.endsWith(".")) value = value.substring(0, value.length() - 1);
        if (value.isEmpty()) throw new IllegalArgumentException("请输入 DNS 查询名称");
        StringBuilder normalized = new StringBuilder();
        for (String label : value.split("\\.", -1)) {
            String ascii = IDN.toASCII(label).toLowerCase(Locale.ROOT);
            if (ascii.isEmpty() || ascii.length() > 63 || !ascii.matches("[a-z0-9_-]+")) throw new IllegalArgumentException("DNS 名称的标签格式无效");
            normalized.append(ascii).append('.');
        }
        if (normalized.length() > 254) throw new IllegalArgumentException("DNS 名称过长");
        return normalized.toString();
    }

    private static final class Reader {
        final byte[] data; int offset;
        Reader(byte[] data) { this.data = data; }
        void require(int length) throws IOException { if (length < 0 || offset < 0 || offset > data.length - length) throw new IOException("DNS 报文越界或截断"); }
        int u8() throws IOException { require(1); return data[offset++] & 255; }
        int u16() throws IOException { return (u8() << 8) | u8(); }
        long u32() throws IOException { return ((long) u16() << 16) | u16(); }
        String name() throws IOException {
            int p = offset, consumed = -1, total = 1; boolean[] visited = new boolean[data.length]; StringBuilder result = new StringBuilder();
            while (true) {
                if (p < 0 || p >= data.length || visited[p]) throw new IOException("DNS 压缩名称指针越界或循环");
                visited[p] = true; int size = data[p++] & 255;
                if (size == 0) { if (consumed < 0) consumed = p; break; }
                if ((size & 0xc0) == 0xc0) {
                    if (p >= data.length) throw new IOException("DNS 压缩指针截断");
                    int pointer = ((size & 63) << 8) | (data[p++] & 255);
                    if (pointer >= p - 2) throw new IOException("DNS 压缩指针未指向先前名称");
                    if (consumed < 0) consumed = p;
                    p = pointer; continue;
                }
                if ((size & 0xc0) != 0 || size > 63 || p > data.length - size) throw new IOException("DNS 名称标签越界");
                total += size + 1; if (total > 255) throw new IOException("DNS 名称超过 255 字节");
                for (int i = 0; i < size; i++) {
                    int b = data[p++] & 255;
                    if (b == '.' || b == '\\') result.append('\\').append((char) b);
                    else if (b < 33 || b > 126) result.append('\\').append(String.format(Locale.ROOT, "%03d", b));
                    else result.append((char) b);
                }
                result.append('.');
            }
            offset = consumed; return result.length() == 0 ? "." : result.toString();
        }
        String record() throws IOException {
            String owner = name(); int type = u16(), clazz = u16(); long ttl = u32(); int length = u16(); require(length);
            int end = offset + length;
            if (clazz != 1 && type != 41) throw new IOException("DNS 响应记录 class 不是 IN");
            if (type == 41 && (ttl >>> 24) != 0) throw new IOException("DNS EDNS 扩展 RCODE 错误：" + (ttl >>> 24));
            String value;
            switch (type) {
                case 1: case 28:
                    if (length != (type == 1 ? 4 : 16)) throw new IOException("DNS IP 地址记录长度无效");
                    value = InetAddress.getByAddress(Arrays.copyOfRange(data, offset, end)).getHostAddress(); offset = end; break;
                case 2: case 5: case 12: value = name(); break;
                case 15: value = u16() + " " + name(); break;
                case 6: value = name() + " " + name() + " " + u32() + " " + u32() + " " + u32() + " " + u32() + " " + u32(); break;
                case 33: value = u16() + " " + u16() + " " + u16() + " " + name(); break;
                case 16:
                    StringBuilder txt = new StringBuilder();
                    while (offset < end) {
                        int size = u8(); if (offset > end - size) throw new IOException("DNS TXT 记录越界");
                        if (txt.length() > 0) txt.append(' ');
                        txt.append('"').append(escapedText(new String(data, offset, size, StandardCharsets.UTF_8))).append('"'); offset += size;
                    }
                    value = txt.toString(); break;
                default:
                    value = "\\# " + length + " " + NetworkTools.hex(Arrays.copyOfRange(data, offset, Math.min(end, offset + 32))) + (length > 32 ? "…" : ""); offset = end;
            }
            if (offset != end) throw new IOException("DNS RDATA 长度与记录内容不匹配");
            return owner + "\t" + ttl + "\tIN\t" + typeName(type) + "\t" + value;
        }
    }
    private static String escapedText(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t");
    }
}
