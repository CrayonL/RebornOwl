# 第三方组件

| 组件 | 来源 | 许可 / 使用方式 |
| --- | --- | --- |
| Xposed 编译 API 82 | [官方 Maven 仓库](https://api.xposed.info/de/robv/android/xposed/api/82/api-82.jar)，[XposedBridge](https://github.com/rovo89/XposedBridge) | Apache-2.0。仅本地构建时下载，不随源码或 APK 打包。 |
| libxposed 编译 API 101.0.1 | [Maven Central](https://repo.maven.apache.org/maven2/io/github/libxposed/api/101.0.1/api-101.0.1.aar)，[libxposed/api](https://github.com/libxposed/api) | Apache-2.0。仅本地构建时下载，不随源码或 APK 打包。 |
| GitHub SVG 图标 | [Simple Icons](https://github.com/simple-icons/simple-icons/blob/develop/icons/github.svg) | [CC0-1.0](https://github.com/simple-icons/simple-icons/blob/develop/LICENSE.md)。路径转换为 Android VectorDrawable，见 `src/res/drawable/ic_github.xml`；商标权不在 CC0 授权内。 |

模块自己的猫头鹰 Logo 由 AI 生成，与模块代码一同提供。GitHub 图标仅标识源码链接。

运行时框架由用户安装的 LSPosed 提供，本项目不分发框架实现。`prepare-dependencies.ps1` 固定两个编译依赖的 SHA-256，并拒绝使用不匹配的文件。
