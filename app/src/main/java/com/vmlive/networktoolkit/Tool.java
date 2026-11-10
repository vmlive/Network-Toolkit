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
        new Tool("ip_diagnostics", "公网 IP 诊断", "多来源 IPv4 / IPv6 与网络画像", "IP 与网络画像", 2, "无需输入", "", "主动向所列第三方查询出口 IP、IPv6 可达性和服务实际提供的归属信息。不同出口、分流及数据库可能导致差异；不凭差异推断 VPN 或风险分数。"),
        new Tool("skk_site", "IP.SKK.MOE", "应用内完整 IP 与连通性检测网站", "IP 与网络画像", 2, "无需输入", "", "在应用内连接 ip.skk.moe 原始 HTTPS 网站，保留其当前功能。网站会看到出口 IP，并可能连接自己的检测服务；由网站自行提供结果。"),
        new Tool("ping0_site", "Ping0.cc", "应用内完整 IP 画像与检测网站", "IP 与网络画像", 13, "无需输入", "", "在应用内连接 ping0.cc 原始 HTTPS 网站。风险、原生 IP 等专有信息由原站提供，可能有其自己的服务条件；应用不编造评分或绕过登录。"),
        new Tool("diagnose", "一键诊断", "DNS · Ping · TCP · 路由追踪", "网络层工具", 10, "输入域名或 IP 地址", "", "依次执行 DNS 查询、ICMP Ping、TCP 80/443 和路由追踪。系统或路由器不响应 ICMP 时如实显示未响应，不使用模拟结果。"),
        new Tool("ping", "Ping", "ICMP 连通性与丢包", "网络层工具", 2, "输入域名或 IP 地址", "www.qq.com", "使用 Android 系统 ping 发送 ICMP。部分设备或网络限制 ICMP，将显示错误或超时；不把 TCP 延迟当作 Ping。"),
        new Tool("trace", "Traceroute", "逐跳路由追踪", "网络层工具", 11, "输入域名或 IP 地址", "www.qq.com", "使用逐级 TTL 的 ICMP 探测，最多 16 跳、总时限 120 秒。未返回 TTL 超时消息的节点显示 *，不推测路由或归属地。"),
        new Tool("udp", "UDP", "UDP 数据包发送与响应", "网络层工具", 12, "主机:端口 可选文本", "192.168.1.1:9 Hello", "向指定目标发送 UDP 数据并等待响应。UDP 没有握手，未响应不等于端口关闭。仅检测有授权的目标。"),
        new Tool("lan", "局域网扫描", "设备探测与服务发现", "网络层工具", 6, "IPv4/CIDR，例如 192.168.1.0/24", "", "检测当前局域网中最多 256 个地址，可能因防火墙、访客网络或系统限制漏报。扫描前需确认你有管理或检测权限。"),
        new Tool("bonjour", "Bonjour", "Apple / 打印机 / AirPlay", "服务发现", 11, "无需输入", "", "使用 Android NSD 发现局域网 HTTP、打印机、AirPlay 与 Cast 服务；需要服务广播和本地网络允许多播。"),
        new Tool("ssdp", "SSDP", "智能电视 / 路由器 / NAS", "服务发现", 6, "无需输入", "", "通过局域网 SSDP M-SEARCH 发现 UPnP 设备。没有响应不表示局域网中没有设备。"),
        new Tool("sites", "延迟测试", "分类站点 HTTP HEAD 延迟", "应用层工具", 13, "无需输入", "", "向所选站点发送 HTTPS HEAD，统计包含 DNS、TCP、TLS 的连接与响应耗时。不是 ICMP 延迟；不会填入示例测量数据。"),
        new Tool("connection", "连接测试", "DNS 与 TCP 连接诊断", "应用层工具", 14, "域名或 IP:端口", "www.qq.com:443", "解析目标并连续建立 TCP 连接。成功仅代表指定端口可达，不代表应用业务正常。"),
        new Tool("lookup", "IP/MAC 查询", "地址分类、反向 DNS 与 MAC 标志", "地址工具", 16, "输入 IP、域名或 MAC", "192.168.1.1", "显示真实地址分类、可用反向 DNS 及 MAC 标志；不推测归属地、运营商或厂商。"),
        new Tool("network", "网络信息", "连接状态与本机地址", "网络诊断", 15, "无需输入", "", "读取当前连接、接口地址、DNS 和路由信息。无需位置权限；系统隐藏的 Wi-Fi 信息不会推测展示。"),
        new Tool("tcp", "TCP 延迟", "连接耗时 · 连通性测试", "网络诊断", 18, "域名或 IP:端口", "example.com:443", "连续建立 4 次 TCP 连接，统计连接耗时与失败率。这是 TCP 握手测试，不是 ICMP Ping。"),
        new Tool("dns", "DNS 查询", "记录类型与自定义 DNS 服务器", "网络诊断", 4, "例如 www.qq.com", "www.qq.com", "系统默认的 A/AAAA 使用 Android 解析器（可能有缓存）。其他记录类型使用当前网络 DNS 的 UDP 53，不继承私密 DNS/DoT；也可选择公共或自定义服务器。保留响应 TTL、记录类型与错误，不虚构运营商归属地。"),
        new Tool("ports", "端口检测", "指定 TCP 端口连通性", "网络诊断", 3, "主机 端口列表，例如 192.168.1.1 22,80,443", "192.168.1.1 22,53,80,443,8080", "仅检测你管理或获授权的主机。一次最多 128 个端口，并发上限 16；未连接可能为关闭、过滤或超时。"),
        new Tool("http", "HTTP 检测", "状态码、响应头与正文", "开发工具", 2, "完整的 http:// 或 https:// 地址", "https://example.com", "发送 GET 请求，展示状态码、响应头和最多 64 KiB 正文。保留 TLS 验证；不自动跟随跳转，以便观察原始响应。"),
        new Tool("tls", "证书检查", "HTTPS 证书与有效期", "开发工具", 5, "HTTPS 地址或域名", "https://example.com", "检查受信任的 HTTPS 连接及证书链。不会绕过自签名、过期证书或主机名校验错误。"),
        new Tool("subnet", "子网计算", "IPv4 / CIDR 地址规划", "地址工具", 6, "IPv4/CIDR，例如 192.168.1.10/24", "192.168.1.10/24", "计算网络地址、广播地址、子网掩码和可用主机范围。/31 按点对点链路处理，/32 为单个主机。离线可用。"),
        new Tool("wol", "远程唤醒", "向局域网发送魔术包", "地址工具", 7, "MAC 广播地址 端口", "AA:BB:CC:DD:EE:FF 192.168.1.255 9", "设备需启用 Wake-on-LAN，并处于可达局域网。发送成功仅代表魔术包已交给网络，不代表目标已经开机。"),
        new Tool("public_ip", "公网 IP", "查询当前 IPv4 出口地址", "网络诊断", 0, "无需输入", "", "仅在点击开始时请求 api.ipify.org，服务会看到你的出口 IPv4。结果取决于当前 Wi-Fi、蜂窝网络或 VPN 路由；纯 IPv6 网络可能无法访问该服务。"),
        new Tool("speed", "网速测试", "下载 · 上传 · 延迟 · 抖动", "应用层工具", 1, "HTTPS 下载地址", "https://speed.cloudflare.com/__down?bytes=5000000", "主动下载及上传测量，默认约 5 MB 下载 + 1 MB 上传。线路、服务端与样本大小都会影响结果；开始前确认流量消耗。"),
        new Tool("base64_encode", "Base64 编码", "文本转 Base64", "编码工具", 8, "输入 UTF-8 文本", "Hello, 网络工具箱", "使用标准 Base64 编码 UTF-8 文本。所有计算在本机完成。"),
        new Tool("base64_decode", "Base64 解码", "Base64 还原 UTF-8 文本", "编码工具", 8, "输入 Base64 字符串", "SGVsbG8=", "解码为 UTF-8 文本，非法输入会返回错误。所有计算在本机完成。"),
        new Tool("url_encode", "URL 编码", "UTF-8 表单参数编码", "编码工具", 4, "输入待编码文本", "网络工具箱", "使用 application/x-www-form-urlencoded 规则，空格编码为 +。所有计算在本机完成。"),
        new Tool("url_decode", "URL 解码", "还原 URL 编码文本", "编码工具", 4, "例如 %E7%BD%91%E7%BB%9C", "%E7%BD%91%E7%BB%9C", "按 UTF-8 表单规则解码，+ 还原为空格。非法百分号编码会返回错误。"),
        new Tool("sha256", "SHA-256", "计算文本摘要", "编码工具", 9, "输入需要计算摘要的文本", "Hello, world!", "对 UTF-8 文本计算 SHA-256 摘要。所有计算在本机完成。"),
        new Tool("md5", "MD5", "兼容性校验摘要", "编码工具", 9, "输入需要计算摘要的文本", "Hello, world!", "仅供旧系统兼容与非安全性校验，MD5 不适合存储密码或安全签名。所有计算在本机完成。")
    ));
    static Tool find(String id) { for (Tool t : ALL) if (t.id.equals(id)) return t; return ALL.get(0); }
}
