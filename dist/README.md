# Android 安装包

| 文件 | 版本 | 用途 |
| --- | --- | --- |
| [network-toolkit-1.1.0-debug.apk](network-toolkit-1.1.0-debug.apk?raw=true) | 1.1.0 | 按用户提供的 8 张界面图片更新的版本：五入口导航、三列首页、DNS、真实 ICMP/TTL 诊断、局域网发现、下载/上传与分类 HEAD 延迟 |
| [network-toolkit-1.0.0-debug.apk](network-toolkit-1.0.0-debug.apk?raw=true) | 1.0.0 | 保留的早期基础版，便于对照 |

两者支持 Android 8.0（API 26）及以上，均为可安装的开发测试签名版本。下载后需允许相应来源安装应用。正式发行应使用开发者长期保管的 release 签名密钥；不同机器生成的 debug 签名可能不同，不能保证直接覆盖安装。

下载后校验：

```bash
cd dist
sha256sum -c SHA256SUMS
```

校验文件包含实际发布 APK 的 SHA-256。源码与构建步骤见仓库 [README](../README.md)，实际测试结果与未验证范围见 [VALIDATION.md](../docs/VALIDATION.md)。1.1.0 依据当前可读取的图片实现；未见可读取的视频，IP/MAC、“我的”和全部交互状态也未在素材中展开，因此不宣称与原应用所有功能或每个像素完全一致。
