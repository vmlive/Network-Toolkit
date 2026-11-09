# Android 安装包

`network-toolkit-1.0.0-debug.apk` 支持 Android 8.0+，为可安装的开发测试签名版本。

下载后在 Android 上允许安装该来源的应用。正式发行应由开发者使用长期保管的 release 签名密钥构建；不同机器生成的 debug 签名可能不同，不能保证覆盖安装。

校验：

```bash
cd dist
sha256sum -c SHA256SUMS
```

源码、测试、构建步骤和已知验证限制见仓库 README 和 `docs/VALIDATION.md`。此版本为独立实现的功能基础版，参考应用的完整 UI 和功能清单仍待截图核对。
