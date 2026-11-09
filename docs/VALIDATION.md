# 验证记录

## 当前构建

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
