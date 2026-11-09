package com.vmlive.networktoolkit;

import java.util.List;
import java.util.Arrays;
import java.util.Collections;

final class Tool {
    final String id, name, description, category, placeholder, initial, help;
    final int icon;
    Tool(String id, String name, String description, String category, int icon,
         String placeholder, String initial, String help) {
        this.id = id; this.name = name; this.description = description; this.category = category;
        this.icon = icon; this.placeholder = placeholder; this.initial = initial; this.help = help;
    }
    static final List<Tool> ALL = Collections.unmodifiableList(Arrays.asList(
        new Tool("network", "网络信息", "连接状态与本机地址", "网络诊断", 0, "无需输入", "", "读取当前连接、接口地址、DNS 和路由信息。无需位置权限；系统隐藏的 Wi-Fi 信息不会推测展示。"),
        new Tool("tcp", "TCP 延迟", "连接耗时 · 连通性测试", "网络诊断", 1, "域名或 IP:端口", "example.com:443", "连续建立 4 次 TCP 连接，统计连接耗时与失败率。这是 TCP 握手测试，不是 ICMP Ping。"),
        new Tool("dns", "DNS 查询", "解析域名的 IPv4 / IPv6", "网络诊断", 2, "例如 example.com", "example.com", "使用设备当前网络的系统解析器查询 A / AAAA 地址。系统可能使用 DNS 缓存。"),
        new Tool("ports", "端口检测", "指定 TCP 端口连通性", "网络诊断", 3, "主机 端口列表，例如 192.168.1.1 22,80,443", "192.168.1.1 22,53,80,443,8080", "仅检测你管理或获授权的主机。一次最多 128 个端口，并发上限 16；未连接可能为关闭、过滤或超时。"),
        new Tool("http", "HTTP 检测", "状态码、响应头与正文", "开发工具", 4, "完整的 http:// 或 https:// 地址", "https://example.com", "发送 GET 请求，展示状态码、响应头和最多 64 KiB 正文。保留 TLS 验证；不自动跟随跳转，以便观察原始响应。"),
        new Tool("tls", "证书检查", "HTTPS 证书与有效期", "开发工具", 5, "HTTPS 地址或域名", "https://example.com", "检查受信任的 HTTPS 连接及证书链。不会绕过自签名、过期证书或主机名校验错误。"),
        new Tool("subnet", "子网计算", "IPv4 / CIDR 地址规划", "地址工具", 6, "IPv4/CIDR，例如 192.168.1.10/24", "192.168.1.10/24", "计算网络地址、广播地址、子网掩码和可用主机范围。/31 按点对点链路处理，/32 为单个主机。离线可用。"),
        new Tool("wol", "远程唤醒", "向局域网发送魔术包", "地址工具", 7, "MAC 广播地址 端口", "AA:BB:CC:DD:EE:FF 192.168.1.255 9", "设备需启用 Wake-on-LAN，并处于可达局域网。发送成功仅代表魔术包已交给网络，不代表目标已经开机。"),
        new Tool("public_ip", "公网 IP", "查询当前 IPv4 出口地址", "网络诊断", 0, "无需输入", "", "仅在点击开始时请求 api.ipify.org，服务会看到你的出口 IPv4。结果取决于当前 Wi-Fi、蜂窝网络或 VPN 路由；纯 IPv6 网络可能无法访问该服务。"),
        new Tool("speed", "下载测速", "实际下载 · 吞吐率", "网络诊断", 1, "HTTPS 下载地址", "https://speed.cloudflare.com/__down?bytes=5000000", "默认从 Cloudflare 下载 5 MB；单次最多读取 10 MiB，限时 20 秒，结果包含连接耗时。会消耗流量；小文件结果不代表线路峰值带宽。"),
        new Tool("base64_encode", "Base64 编码", "文本转 Base64", "编码工具", 8, "输入 UTF-8 文本", "Hello, 网络工具箱", "使用标准 Base64 编码 UTF-8 文本。所有计算在本机完成。"),
        new Tool("base64_decode", "Base64 解码", "Base64 还原 UTF-8 文本", "编码工具", 8, "输入 Base64 字符串", "SGVsbG8=", "解码为 UTF-8 文本，非法输入会返回错误。所有计算在本机完成。"),
        new Tool("url_encode", "URL 编码", "UTF-8 表单参数编码", "编码工具", 4, "输入待编码文本", "网络工具箱", "使用 application/x-www-form-urlencoded 规则，空格编码为 +。所有计算在本机完成。"),
        new Tool("url_decode", "URL 解码", "还原 URL 编码文本", "编码工具", 4, "例如 %E7%BD%91%E7%BB%9C", "%E7%BD%91%E7%BB%9C", "按 UTF-8 表单规则解码，+ 还原为空格。非法百分号编码会返回错误。"),
        new Tool("sha256", "SHA-256", "计算文本摘要", "编码工具", 9, "输入需要计算摘要的文本", "Hello, world!", "对 UTF-8 文本计算 SHA-256 摘要。所有计算在本机完成。"),
        new Tool("md5", "MD5", "兼容性校验摘要", "编码工具", 9, "输入需要计算摘要的文本", "Hello, world!", "仅供旧系统兼容与非安全性校验，MD5 不适合存储密码或安全签名。所有计算在本机完成。")
    ));
    static Tool find(String id) { for (Tool t : ALL) if (t.id.equals(id)) return t; return ALL.get(0); }
}
