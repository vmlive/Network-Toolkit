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
- Android Lint：通过。
- 应用 APK 与设备测试 APK：构建成功。
- `dist/network-toolkit-1.0.0-debug.apk`：通过 Android apksigner 签名校验，开发测试签名。
- SHA-256：见 `dist/SHA256SUMS`，在 `dist` 目录运行 `sha256sum -c SHA256SUMS`。

核心用例使用真实 localhost DNS、TCP、HTTP 和 UDP，验证端口开放/关闭、4 次连接、HTTP 状态/跳转/64 KiB 上限、WOL 魔术包、取消回调、CIDR 边界和编码摘要向量；没有把模拟 UI 数据当作网络结果。

## 设备验证

设备测试共 8 项，覆盖界面导航、搜索、Base64 结果与历史、子网与收藏、错误状态、本机接口信息、Android localhost HTTP/TCP 与中途取消。

初次 API 30 Google APIs 模拟器在无 KVM 的软件 CPU 环境中发生系统框架重启；当时尚未完成测试，不计为通过。正在尝试精简 AOSP 镜像。最终结果将在本文件更新；构建成功不代表设备测试通过。

## 尚未验证的范围

- 真机 Wi-Fi / 蜂窝 / VPN、公网 IP 服务、外网测速、真实目标 WOL 成功唤醒，以及 Android 8 和 Android 15 真机表现未作实测。
- `api.ipify.org` 为 IPv4 查询服务，纯 IPv6 网络可能不可达。
- 默认测速消耗约 5 MB，单次读取最多 10 MiB / 20 秒，包含连接开销，不代表峰值带宽。
- 参考应用页面被网络策略拦截，截图待用户提供，未验证完整功能或 UI 一致性。见 `REFERENCE.md`。
- GitHub Actions 工作流已提供；其远端运行结果未在本地构建中得到验证。
- 云环境草稿读取与保存接口返回 `INVALID_ARGUMENT`，平台配置持久化未获确认。可复用的引导和构建脚本已保存在仓库，当前机器安装及构建已验证；尚未验证环境发布或新任务恢复。
