# 网络工具箱 · Network Toolkit

原生 Android 网络工具应用，支持 Android 8.0+。1.1.0 根据用户提供的“网络魔法箱”界面截图重做导航、首页和主要工具页，保留无需账号、无广告、无分析 SDK 的实现。

## 下载与界面

- [下载 1.1.0 测试版 APK](dist/network-toolkit-1.1.0-debug.apk?raw=true)
- [安装包与 SHA-256 校验](dist/)
- [实际测试记录与未验证范围](docs/VALIDATION.md)

以下为本项目 Android 应用的实际运行截图；参考来源及尚未核对的部分见 [参考说明](docs/REFERENCE.md)。

<img src="docs/screenshots/v1.1-home-dark.png" width="230" alt="1.1 深色首页"> <img src="docs/screenshots/v1.1-home-light.png" width="230" alt="1.1 浅色首页">

<img src="docs/screenshots/v1.1-dns.png" width="230" alt="1.1 DNS 查询"> <img src="docs/screenshots/v1.1-speed.png" width="230" alt="1.1 网速测试">

<img src="docs/screenshots/v1.1-scan.png" width="230" alt="1.1 局域网扫描"> <img src="docs/screenshots/v1.1-diagnose.png" width="230" alt="1.1 一键诊断">

[查看平板首页](docs/screenshots/v1.1-tablet-home.png)

APK 为开发测试签名版本。下载安装时需允许相应来源安装应用；正式发行应使用开发者长期保管的 release 签名密钥。旧版 1.0.0 APK 保留在 `dist` 供对照。

## 界面与工具

应用统一为“本地测、测速、扫描、IP/MAC、我的”五个入口：手机使用底部浮动分段导航，平板使用顶部导航。首页包含蓝紫渐变“一键诊断”、三列分类工具、圆形彩色图标，支持深浅主题。详情页提供快捷域名后缀、最近目标、终端式结果、复制与分享；“我的”保留全部工具搜索、收藏和本机历史。

当前工具目录共 **26 项**：

| 功能 | 实际行为 |
| --- | --- |
| 一键诊断 | 顺序执行 DNS、真实 ICMP Ping、TCP 80/443 和路由追踪，分别报告结果或错误 |
| Ping / Traceroute | 调用 Android 系统 ICMP 探测；支持包数、IPv4/IPv6 选择；Traceroute 递增 TTL，未响应节点显示 `*` |
| DNS 查询 | A、AAAA、CNAME、MX、TXT、NS、SOA、PTR；系统、腾讯、阿里、114、Google、Cloudflare 或自定义 DNS；查询记录卡片与 dig 风格文本输出 |
| TCP 延迟 / 连接测试 / 端口检测 | 实际 TCP 握手；端口扫描最多 128 个端口、16 并发；不会当作 ICMP Ping |
| UDP | 向指定目标发送文本数据包并等待响应；无响应不等于端口关闭 |
| 局域网扫描 | 本机地址、掩码、网关、扫描进度及真实响应设备；范围限私有或链路本地 IPv4 CIDR，最多 256 个地址 |
| Bonjour / SSDP | Android NSD 服务发现，以及局域网 UPnP M-SEARCH；显示收到的服务信息 |
| 网速测试 | 实际 HTTPS 下载、上传、5 次 HEAD 响应延迟与抖动；默认约 5 MB 下载 + 1 MB 上传，开始前明确确认流量；可选 10/20 MB 下载样本 |
| 分类站点延迟 | 26 个 AI、社交、视频、游戏、电商、云服务站点，HTTPS HEAD 实测，最多 6 并发；可单独检测、添加或删除自定义站点 |
| HTTP / TLS 证书 | GET 状态、响应头、最多 64 KiB 正文预览；HTTPS 证书链、有效期、指纹及系统信任验证 |
| IP/MAC / 公网 IPv4 | 地址解析、分类、可用反向 DNS；MAC 格式、OUI 前缀、单播/组播和本地管理标志；公网 IPv4 主动请求 ipify |
| 本机信息 | 当前连接、网络接口、地址、DNS、路由、系统与设备信息；受系统限制的信息保持未知 |
| 子网计算 / 远程唤醒 | IPv4 CIDR 规划（含 /0、/31、/32）；发送 WOL 魔术包，发送成功不代表目标已开机 |
| 编码与摘要 | 本机 Base64 编解码、URL 编解码、SHA-256、MD5；MD5 仅用于非安全性兼容校验 |

网络操作在后台执行，可停止；不使用截图中的示例数字冒充结果。Android 系统、路由器或目标服务可能限制 ICMP、多播、MAC 读取、端口或 HEAD 请求，失败和未响应会如实展示，不等于设备离线。IP、路由和 MAC 结果不附加猜测的地理位置、运营商或厂商。

### DNS 与测速语义

“系统默认”的 A/AAAA 查询使用 Android 解析器，可能受系统缓存和私密 DNS 设置影响。系统默认的其他记录类型使用当前网络提供的 DNS 服务器；选择公共或自定义服务器时，直接使用 **UDP 53** 查询，**不会继承 Android 私密 DNS / DoT，也不是 DoH**。公共 DNS 名称是服务器地址选项，不代表采用其加密协议；网络若阻断 UDP 53，应保留并检查实际错误。

测速中的延迟与分类站点延迟采用 HTTP HEAD，耗时包含 DNS、TCP、TLS 与服务端响应，不能等同 ICMP 延迟。抖动为相邻成功样本耗时差的平均绝对值。下载使用 GET、上传使用 POST，包含连接开销，默认小样本不保证测到线路峰值；失败阶段也可能产生部分流量，计费还包含协议和网络缓冲开销。可在“我的 → 测速设置”选择样本及 HTTPS 端点。

## 参考核对范围

当前可读取的素材为 **8 张图片，未见可读取的视频附件**。本版已按图片中的首页、DNS、扫描、分类延迟、Traceroute、一键诊断和测速布局实现，并统一图片中存在差异的四入口/五入口导航。原图没有展开 IP/MAC、“我的”和所有交互状态，因此不宣称与原应用全部功能或每个像素一致。图标由本项目绘制，仓库截图来自本项目运行结果。详细对照见 [REFERENCE.md](docs/REFERENCE.md)。

## 本地开发

工具链：Android SDK Platform 35、Android Gradle Plugin 8.7.3、Gradle 8.9；云环境使用 JDK 21，最低运行版本 Android 8.0（API 26）。Gradle Wrapper 包含官方分发包 SHA-256 校验。

```bash
git clone https://github.com/vmlive/Network-Toolkit.git
cd Network-Toolkit
# 已安装 SDK 时设置 ANDROID_HOME，或使用本机 local.properties。
./gradlew testDebugUnitTest lintDebug assembleDebug
```

无 SDK 的 Linux x86_64 云环境可执行：

```bash
./scripts/bootstrap-android.sh
./scripts/cloud-gradle.sh testDebugUnitTest lintDebug assembleDebug
```

脚本从官方源下载并校验工具，不保存代理凭据。缓存默认位于 `/workspace/.toolchains`（无 `/workspace` 时使用用户缓存目录）。APK 输出为 `app/build/outputs/apk/debug/app-debug.apk`。直接使用既有 checkout；每个云任务已隔离，无需另建 Git worktree。

设备测试需要已经启动的 Android 模拟器或已连接设备：

```bash
./scripts/cloud-gradle.sh connectedDebugAndroidTest
```

构建、单元测试、设备测试和真机外网测试是不同结果；本次实际执行情况统一记录在 [VALIDATION.md](docs/VALIDATION.md)，不以构建成功替代设备验证。GitHub Actions 运行单元测试、Lint 和 APK 构建并上传 artifact，远端结果可在仓库 Actions 页面查看。

## 隐私与权限

应用仅声明 INTERNET、ACCESS_NETWORK_STATE、ACCESS_WIFI_STATE 和 CHANGE_WIFI_MULTICAST_STATE，不申请 GPS/位置、通讯录或存储权限。Wi-Fi 多播权限用于局域网服务发现，未支持的系统信息不会虚构。

公网 IPv4 使用 `api.ipify.org`；默认下载和上传分别使用 `https://speed.cloudflare.com/__down`、`https://speed.cloudflare.com/__up`。分类站点仅在点击开始或指定卡片后访问；其他工具连接输入的目标、所选 DNS 或当前局域网。HTTPS 保持系统证书与主机名验证；HTTP 诊断为兼容旧服务支持明文 HTTP。详见 [PRIVACY.md](docs/PRIVACY.md)。

收藏、自定义站点、DNS 设置和历史仅在本机保存。历史最多 50 条、每条结果最多约 16 KiB，可能含网址参数、目标和输入文本，可关闭新增记录并清空；Android 备份和设备迁移已排除应用数据，卸载后删除本机数据。

## 代码结构

- `MainActivity.java`、`Tool.java`：原生界面、导航、工具目录、收藏、历史与异步执行。
- `DeviceTools.java`、`LanDiscovery.java`：Android 网络信息、公网 IP、局域网与服务发现。
- `Measurements.java`：实际下载/上传、HEAD 延迟与站点测量。
- `core/`：DNS 协议、ICMP/TTL 诊断、IP/MAC、TCP/HTTP、编码等逻辑。
- `app/src/test/`、`app/src/androidTest/`：JVM 及 Android 设备测试。
- `docs/VALIDATION.md`：验证证据、平台限制与未验证范围。

本项目采用 [MIT 许可证](LICENSE)。
