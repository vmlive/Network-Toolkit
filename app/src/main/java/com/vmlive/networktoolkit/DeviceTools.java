package com.vmlive.networktoolkit;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import com.vmlive.networktoolkit.core.NetworkTools.Cancellation;
import com.vmlive.networktoolkit.core.NetworkTools.Progress;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Locale;

final class DeviceTools {
    static String connection(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
        if (nc == null) return "未连接网络";
        String type = nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? "Wi-Fi" :
            nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ? "蜂窝网络" :
            nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ? "以太网" : "网络";
        if (nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) type += " · VPN";
        return type + (nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ? " 已连接" : " · 互联网待验证");
    }
    static String network(Context context) throws Exception {
        StringBuilder s = new StringBuilder("当前连接\n" + connection(context) + "\n\n");
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        Network active = cm.getActiveNetwork();
        LinkProperties lp = cm.getLinkProperties(active);
        NetworkCapabilities nc = cm.getNetworkCapabilities(active);
        if (lp != null) {
            s.append("接口  ").append(lp.getInterfaceName()).append("\n");
            if (android.os.Build.VERSION.SDK_INT >= 29) s.append("MTU  ").append(lp.getMtu()).append('\n');
            else s.append("MTU  当前系统不支持读取\n");
            s.append("\nIP 地址\n");
            lp.getLinkAddresses().forEach(a -> s.append(a).append('\n'));
            s.append("\nDNS 服务器\n");
            lp.getDnsServers().forEach(a -> s.append(a.getHostAddress()).append('\n'));
            s.append("\n路由\n");
            lp.getRoutes().forEach(r -> s.append(r).append('\n'));
        }
        if (nc != null) s.append("\n按流量计费  ").append(nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) ? "否" : "是").append('\n');
        s.append("\n本机网络接口\n");
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        for (NetworkInterface ni : interfaces == null ? Collections.<NetworkInterface>emptyList() : Collections.list(interfaces)) {
            if (!ni.isUp()) continue;
            s.append(ni.getName()).append('\n');
            for (InetAddress a : Collections.list(ni.getInetAddresses())) s.append("  ").append(a.getHostAddress()).append('\n');
        }
        return s.toString();
    }
    static String publicIp(Cancellation cancel) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("https://api.ipify.org").openConnection();
        c.setConnectTimeout(8000); c.setReadTimeout(8000); c.setInstanceFollowRedirects(false);
        Cancellation.Registration registration = cancel.onCancel(c::disconnect);
        try {
            cancel.check();
            if (c.getResponseCode() != 200) throw new java.io.IOException("IP 服务 HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                byte[] data = new byte[64]; int n;
                while ((n = in.read(data)) != -1) {
                    cancel.check();
                    if (bytes.size() + n > 128) throw new java.io.IOException("IP 服务响应过长");
                    bytes.write(data, 0, n);
                }
                String value = bytes.toString(StandardCharsets.US_ASCII.name()).trim();
                if (value.indexOf(':') >= 0) {
                    if (!value.matches("[0-9a-fA-F:]+") || InetAddress.getByName(value).getAddress().length != 16)
                        throw new java.io.IOException("服务未返回有效 IPv6 地址");
                } else {
                    try { com.vmlive.networktoolkit.core.NetworkTools.ipv4(value); }
                    catch (IllegalArgumentException error) { throw new java.io.IOException("服务未返回有效 IPv4 地址", error); }
                }
                return "公网出口 IP\n\n" + value + "\n\n来源：api.ipify.org\n查询时间：" + new java.util.Date();
            }
        } finally { registration.close(); c.disconnect(); }
    }
    static String speed(String input, Cancellation cancel, Progress progress) throws Exception {
        URL url = new URL(input.trim());
        if (!url.getProtocol().equals("https") || url.getHost().isEmpty() || url.getUserInfo() != null)
            throw new IllegalArgumentException("测速地址必须使用 HTTPS，且不能包含账号密码");
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setConnectTimeout(8000); c.setReadTimeout(3000); c.setInstanceFollowRedirects(false);
        c.setRequestProperty("Accept-Encoding", "identity");
        Cancellation.Registration registration = cancel.onCancel(c::disconnect);
        long start = System.nanoTime(), count = 0, last = start; boolean capped = false;
        try {
            cancel.check();
            if (c.getResponseCode() != 200) throw new java.io.IOException("下载服务 HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[16384];
                while (count < 10 * 1024 * 1024 && (System.nanoTime() - start) < 20_000_000_000L) {
                    cancel.check();
                    long remainingMs = (20_000_000_000L - (System.nanoTime() - start)) / 1_000_000;
                    if (remainingMs <= 0) break;
                    c.setReadTimeout((int) Math.max(1, Math.min(3000, remainingMs)));
                    int n = in.read(buf, 0, (int) Math.min(buf.length, 10 * 1024 * 1024 - count));
                    if (n < 0) break;
                    count += n;
                    if (System.nanoTime() - last > 500_000_000L) {
                        if (progress != null) progress.onProgress(String.format(Locale.CHINA, "已下载 %.2f MB · %.2f Mbps", count / 1e6, count * 8000.0 / (System.nanoTime() - start)));
                        last = System.nanoTime();
                    }
                }
                capped = count >= 10 * 1024 * 1024 || System.nanoTime() - start >= 20_000_000_000L;
            }
            cancel.check();
            double seconds = (System.nanoTime() - start) / 1e9;
            if (count == 0) throw new java.io.IOException("下载内容为空");
            return String.format(Locale.CHINA, "平均下载速度\n\n%.2f Mbps\n%.2f MB/s\n\n下载数据  %.2f MB\n总耗时  %.2f 秒\n%s\n\n包含 DNS / TCP / TLS 建连时间；仅代表本次请求。", count * 8 / seconds / 1e6, count / seconds / 1e6, count / 1e6, seconds, capped ? "达到流量或时长上限，已停止" : "下载完成");
        } finally { registration.close(); c.disconnect(); }
    }
}
