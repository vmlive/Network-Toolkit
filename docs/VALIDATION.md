# 验证记录

## 1.2.0：原生 IP、应用内网站与新图标

本版为新增功能预览，versionName `1.2.0`、versionCode `3`、minSdk `26`、targetSdk `35`。没有替换 1.1.0 正式 Release 的标签或附件。

- 执行 `./scripts/cloud-gradle.sh clean testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest`，随后对空资源目录清理运行 `lintDebug`。
- **99 项 JVM 测试通过，0 失败、0 错误、0 跳过**，见 `test-results/unit-summary-v1.2.txt`；Android Lint 为 **No issues found**。
- 新增 IP 解析测试使用真实 localhost HTTP 夹具，覆盖 IPv4/IPv6、IP.SB JSON、Cloudflare Trace、Ping0 四行文本、错误/跳转/HTML、64 KiB 上限、并发及中途取消。生产路径保留 HTTPS 验证；仅包内测试入口允许数字回环 HTTP。
- AOSP Android 11（API 30）完整批次执行 **28 项**：首次 26 项通过，2 项原有弹窗 UI 用例被系统的 `Process system` / `System UI` 无响应对话框阻挡。记录见 `test-results/android-instrumentation-v1.2-initial.txt`。
- 只恢复模拟器系统界面，对系统进程选择 Wait、对 SystemUI 选择 Close app；未修改应用断言或关闭 ANR 报告。前台焦点恢复后，**DNS 类型和测速确认两个用例均复测通过**，耗时 27.405 秒，见 `test-results/android-instrumentation-v1.2-retest.txt`。因此本版 **28 个不同设备用例均有通过结果，分批执行**，没有把首次失败算作通过。
- 新增设备用例实际执行 Chromium JavaScript、DOM、本地存储与第一方 Cookie，验证没有原生 JS 桥及文件/混合内容权限；还执行 Android 原生 HTTP/JSON、取消连接、网站 Activity 路由和 Android 自适应启动图标加载/渲染。网站入口路由测试拦截实际导航，不会自动对真实网站发起请求。
- 当前模拟器的真实 WebView 提供程序为 `com.android.webview 83.0.4103.120`，relro 初始化完成。网页能力测试使用无外部资源的内联测试文档，不能据此声称两个实时站点已经完成端到端验证。

### 本版现场验证边界

- 运行环境的网络策略仍为 restricted，所需新增域名未允许。`ip.skk.moe`、`ping0.cc`、App Store 页面均返回策略拒绝；未通过代理、关闭 TLS 或 CI 抓取绕过该限制。
- 原生服务的当前公网可用性、两站的真实页面/登录/验证码/收费能力、系统 WebView 版本兼容性，仍需允许网络后或在实际手机上验证。网站菜单提供系统浏览器入口；缺少或失效的 WebView 会显示原生提示和重试入口。
- Ping0 原生适配仅解析公开资料描述的四行当前出口响应，不包含付费风险/原生 IP/机房字段；Cloudflare WARP 状态不被扩展成通用 VPN 结论，多来源 IP 差异也不被当作泄漏证明。
- UISP 原图尚未读取到，新图标是已展示的原创蓝白网络 N 暂定方向，不声称已与 UISP 最新图标精确对齐。实际 Android 遮罩见 `screenshots/v1.2-launcher.png`。
- 本版 APK 沿用同一开发测试签名，支持覆盖升级；正式渠道签名与此前正式 GitHub Release 的区别仍适用。安装包校验文件随 APK 保存在 `dist`。

## 1.1.0 GitHub 正式发布

- [正式 Release v1.1.0](https://github.com/vmlive/Network-Toolkit/releases/tag/v1.1.0) 已公开为稳定版本，并确认页面显示 Latest。
- [发布工作流](https://github.com/vmlive/Network-Toolkit/actions/runs/42660464870) 执行成功，发布前校验上传附件的 SHA-256。
- 独立重新下载发布页 APK 及 `.sha256`，确认 APK 为 207316 字节，SHA-256 为 `cdf3754ec4b13681f0f2a4a5cfc2e2bb094da5f36d4d950edd54f106b14f2939`，与验收版本完全一致。
- 远端标签 `v1.1.0` 确认指向已验收源代码 `cac33f28201755fda0e9c62c477ad60f6bcf97f1`；源码 ZIP/TAR.GZ 链接及两项发布附件均已确认存在。
- 稳定 GitHub Release 与 Android 正式签名不同：本次附件保留原开发测试签名和 `debug` 文件名，没有重新构建或更换签名。发布说明见 `releases/v1.1.0.md`。

## 1.1.0：截图驱动新版

2026-11-09，沿用已校验的 JDK 21、Gradle 8.9、AGP 8.7.3 与 Android SDK 35，运行：

```bash
./scripts/cloud-gradle.sh testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
```

- **79 项 JVM 测试通过，0 失败、0 错误、0 跳过。** 见 `test-results/unit-summary-v1.1.txt`。
- Android Lint：**No issues found**。
- 应用与设备测试 APK 构建成功；`dist/network-toolkit-1.1.0-debug.apk` 通过 apksigner 签名校验。
- 1.1.0 与仓库保留的 1.0.0 使用同一开发测试签名，versionCode 从 1 增至 2；正式发行仍应改用长期管理的 release 签名。
- **AOSP Android 11（API 30）实际执行 20 项设备测试全部通过**，0 失败、0 错误，耗时 106.724 秒。见 `test-results/android-instrumentation-v1.1.txt`。
- 随后针对一键诊断的空目标禁用、输入后启用及真实回环 DNS/ICMP/TCP/TTL 五阶段，再运行新增专项用例 **1 项通过**（15.014 秒）。合计 **21 个不同的设备用例通过**，完整 20 项回归与该专项为分次执行，见 `test-results/diagnose-instrumentation-v1.1.txt`。

JVM 用例包含真实 localhost DNS UDP 报文、HTTP HEAD、定量下载与上传、端口与 UDP 收发；还验证 DNS 压缩/TTL/损坏报文、取消、有界并发、IP/MAC、CIDR 与编码。设备用例包含应用 UID 下的真实 ICMP 回环、TTL 路由回环、进程取消、DNS UDP 报文、Android HTTP/TCP/取消、本机 IPv4 的 /32 扫描、Bonjour/SSDP 预取消，以及 10 项界面交互。界面覆盖五分区导航、编码/历史、子网/收藏、错误提示、本机信息、DNS 类型和自定义服务器绑定、测速确认取消、扫描初始状态、MAC 实算和主题持久化。

首次界面运行有 1 项测试未正确点击原生 ListView 的 TXT 行；修正为行 ACTION_CLICK 或实际坐标触摸后，保留类型选择与后续查询断言，完整重跑 20 项通过，没有跳过该用例。

本版界面对照的是用户提供的 **8 张图片**。手机、平板、深色和浅色均由同一原生 Android 实现适配；本项目实际运行截图位于 `screenshots/v1.1-*.png`。未收到可读取的视频附件，未核对原应用隐藏页面或动画时序，详见 `REFERENCE.md`。

### 1.1.0 尚未实测的范围

- 真机 Wi-Fi/蜂窝/VPN、外网 Cloudflare 吞吐率、公网 IP、公共 DNS UDP 53、26 个真实外网站点的可达性，以及实际局域网对端的 Bonjour/SSDP 发现，尚未作端到端实测。单元测试使用真实本地传输夹具，设备测试不会自动向外网批量发包或启动收费流量测试。
- Android 8.0、Android 15 真机与不同厂商的 ICMP/多播/MAC 限制仍需真机覆盖；模拟器通过不保证所有网络均允许这些协议。
- 网络默认 A/AAAA 遵循系统解析器；自选 DNS 和高级类型的直接 UDP 查询不继承私密 DNS/DoT。系统未提供的 TTL、MAC、地理位置或厂商保持未知。
- 新版 GitHub Actions 的远端结果以仓库 Actions 页面为准；下方 1.0.0 中的历史成功记录不替代本版远端运行状态。

## 1.0.0 历史构建记录

2026-11-09，Linux x86_64 云环境：Eclipse Temurin 21.0.13+9、Gradle 8.9、Android Gradle Plugin 8.7.3、Android SDK Platform 35。SDK、JDK 和 Gradle 均从官方源获取并校验分发包；保持 TLS 验证。

执行：

```bash
./scripts/bootstrap-android.sh
./scripts/cloud-gradle.sh testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
```

- 引导脚本重复执行成功。
- 单元测试：**29 项通过，0 失败、0 跳过**（NetworkToolsTest 25、CodecToolsTest 4）。
- Android Lint：通过，`No issues found`。
- 应用 APK 与设备测试 APK：构建成功。
- `dist/network-toolkit-1.0.0-debug.apk`：通过 Android apksigner 签名校验，开发测试签名。
- SHA-256：见 `dist/SHA256SUMS`，在 `dist` 目录运行 `sha256sum -c SHA256SUMS`。

核心用例使用真实 localhost DNS、TCP、HTTP 和 UDP，验证端口开放/关闭、4 次连接、HTTP 状态/跳转/64 KiB 上限、WOL 魔术包、取消回调、CIDR 边界和编码摘要向量；没有把模拟 UI 数据当作网络结果。

## 设备验证

**AOSP Android 11（API 30）模拟器：8 项全部通过，0 失败、0 错误，耗时 79.66 秒。**

包含 3 项真实 Android 本地 HTTP、TCP 握手和中途取消测试，以及 5 项界面测试：导航、搜索、Base64 结果与历史、子网边界与收藏、错误状态、本机网络信息。记录见 `test-results/android-instrumentation.txt`。

首次取消用例的服务端夹具仅接受 EOF，Android 正常以 TCP RST 关闭连接时被误判为错误。夹具已修正为接受取消后的 EOF / TCP 重置，保留 3 秒取消时限、取消标记与连接确实关闭的验证，随后完整重跑 8 项通过。

无 KVM 的软件模拟器首次启动较慢，Google APIs 镜像出现系统框架重启，改用精简 AOSP 镜像后完成上述测试。软件模拟器仍可能显示系统进程无响应提示，不代表已验证所有真机性能。Android 8.0 仅支持浅色导航键，已为该版本保留深色导航栏背景。

## 尚未验证的范围

- 真机 Wi-Fi / 蜂窝 / VPN、公网 IP 服务、外网测速、真实目标 WOL 成功唤醒，以及 Android 8 和 Android 15 真机表现未作实测。
- `api.ipify.org` 为 IPv4 查询服务，纯 IPv6 网络可能不可达。
- 默认测速消耗约 5 MB，单次读取最多 10 MiB / 20 秒，包含连接开销，不代表峰值带宽。
- 参考应用页面被网络策略拦截，截图待用户提供，未验证完整功能或 UI 一致性。见 `REFERENCE.md`。
- GitHub Actions 已在远端实际通过 SDK 准备、29 项单元测试、Lint、APK 构建与产物上传：[运行 #2](https://github.com/vmlive/Network-Toolkit/actions/runs/42635588494)。首次失败位于第三方 SDK 安装动作，已替换为本仓库验证过的安装脚本；此后本地最终源码也完整重跑检查通过。后续运行可在仓库 Actions 页面查看。
- 云环境草稿读取与保存接口返回 `INVALID_ARGUMENT`，平台配置持久化未获确认。可复用的引导和构建脚本已保存在仓库，当前机器安装及构建已验证；尚未验证环境发布或新任务恢复。
