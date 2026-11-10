# Android 安装包

当前开发预览为 **1.2.0**：增加原生公网 IP 诊断、应用内 ip.skk.moe / ping0.cc 网站入口及原创蓝白网络图标。**本次没有发布 1.2.0 正式 Release，也没有替换 1.1.0 的稳定标签和附件。**

1.1.0 已作为 [正式 GitHub Release](https://github.com/vmlive/Network-Toolkit/releases/tag/v1.1.0) 发布并标记为 Latest，附带 APK、SHA-256 校验及对应标签源码归档。附件沿用验收过的同一开发测试签名 APK；正式 Release 标记不改变其 Android 签名类型。

| 文件 | 版本 | 用途 |
| --- | --- | --- |
| [network-toolkit-1.2.0-debug.apk](network-toolkit-1.2.0-debug.apk?raw=true) | 1.2.0 预览 | 29 项工具：保留 1.1.0 的 26 项，新增五来源原生 IP 诊断与两个应用内完整网站入口；更新自适应和单色主题图标 |
| [network-toolkit-1.1.0-debug.apk](network-toolkit-1.1.0-debug.apk?raw=true) | 1.1.0 | 按用户提供的 8 张界面图片更新的版本：五入口导航、三列首页、DNS、真实 ICMP/TTL 诊断、局域网发现、下载/上传与分类 HEAD 延迟 |
| [network-toolkit-1.0.0-debug.apk](network-toolkit-1.0.0-debug.apk?raw=true) | 1.0.0 | 保留的早期基础版，便于对照 |

这些 APK 支持 Android 8.0（API 26）及以上，均为可安装的开发测试签名版本。下载后需允许相应来源安装应用；同一签名、递增 versionCode 的包可覆盖更新，换用其他机器重新生成的 debug 签名可能无法直接升级。正式渠道发行应使用开发者长期保管的 release 签名密钥。

下载后校验：

```bash
cd dist
sha256sum -c SHA256SUMS
```

校验文件包含仓库 APK 的 SHA-256；1.2.0 单包校验也可下载对应 `.sha256` 文件。源码与构建步骤见仓库 [README](../README.md)，实际测试结果与未验证范围见 [VALIDATION.md](../docs/VALIDATION.md)。

1.2.0 的两个原站及 UISP App Store 页面受云网络策略限制，未完成实时外网/图标对照。原生 IP 字段只取实际服务响应，不生成付费风险值；完整网页的数据、登录、广告、收费和隐私政策由第三方管理。网页需要可用的 WebView/Chrome，菜单可清除应用内网站数据。图标为原创蓝白网络 N 暂定设计，没有复制 Ubiquiti U 标识。详细来源见 [REFERENCE.md](../docs/REFERENCE.md)。

1.1.0 则依据此前可读取的 8 张图片实现；未见可读取的视频，IP/MAC、“我的”和全部交互状态也未在素材中展开，因此不宣称与原应用所有功能或每个像素完全一致。
