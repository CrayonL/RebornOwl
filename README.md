<p align="center"><img src="src/res/drawable-nodpi/reborn_owl.png" width="128" alt="重生猫头鹰"></p>

# 重生猫头鹰 · RebornOwl

基于 OwlAuxiliaryR 1.1 继续维护的多邻国 LSPosed 模块。原作者停止维护后，重新适配、修复并开放源码。

**公开版本：0.0.1** · 包名 `com.owl.reborn` · [下载 APK](https://github.com/CrayonL/RebornOwl/releases/latest)

## 适配范围

| 项目 | 版本 / 要求 |
| --- | --- |
| 多邻国 | **6.103.6（versionCode 2488）**，包名 `com.duolingo` |
| 实测设备 | 小米 13，Android 17 / API 37 |
| 实测框架 | LSPosed IT 2.2.0-it（7892） |
| 框架接口 | libxposed API 101；框架须提供旧 Hook API 兼容支持 |
| Android 最低声明 | Android 8.0 / API 26；较低版本未做真机验收 |
| 作用域 | 静态作用域，仅 `com.duolingo` |

仅以上多邻国版本经过目标类与方法契约核对。升级多邻国后，混淆名、参数和界面资源可能变化，请先停用模块并等待适配。它不是适用于所有版本的通用补丁。

## 功能

| 功能 | 行为与当前验证范围 |
| --- | --- |
| 悬浮入口 | 可拖动的猫头鹰图标，点击打开贴边设置；参数在对应开关下方展开。图标、面板与位置保存已实机检查。 |
| 会员档位 | Super / Super Max 客户端档位切换，提示随最新点击替换旧提示。Max 涉及 AI 入口；**不解除服务端订阅与 AI 权限校验**。 |
| 直接通关 | 设置宿主的课程长度参数，由宿主自身结束、结算。仅一节已完成的普通英语复习课经过实机验证；不保证新课、故事、音乐、数学等类型。 |
| 日期时区 | 紧凑日历选择今天或过去日期，禁止未来日期；显示 `yyyy/MM/dd`。时区默认中国上海，已有选择会保留；选择时区即保存。只改宿主时钟，不改手机系统时间。 |
| 无限时间 | 修改宿主相关计时状态。目标契约已核对，所有计时题型尚未逐一实测。 |
| 强制正确 | 适配当前判题模型的正确率与正确字段。单元测试和目标契约通过，所有题型尚未逐一实测。 |
| 音乐课程解锁 | 修改客户端音乐访问状态；实际音乐服务访问尚未验收。 |
| 调试模式 | 开放宿主调试菜单入口及部分中文标签；各调试项未逐一验收。 |
| 解锁关卡 | **当前版本尚未适配**，保留原开关；不应视为可用功能。 |
| 重启 | 面板右下角提交宿主启动任务后结束旧进程，重新加载配置；建议在课程外操作，未提交进度可能丢失。 |
| 桌面入口 | 模块独立设置页，可关闭桌面图标；隐藏后可在 LSPosed 中打开模块设置恢复。 |
| 开源入口 | 显示 GitHub 项目，优先打开已安装的 GitHub App，否则使用网页。 |

开关、会员档位和日期时区更改后，需要点击面板中的 **重启** 才会重新安装对应 Hook。Toast 表示选择了模块档位，不等于服务端已开通订阅。历史日期是否被服务端接受、补签及连胜修复均未验证。

## 安装与使用

1. 在支持 API 101 的 LSPosed 环境中安装 [Release APK](https://github.com/CrayonL/RebornOwl/releases/latest)。
2. 启用“重生猫头鹰”。静态作用域只包含多邻国，不需要选择其他应用。
3. 若装有旧包 `com.owl.auxiliary`，先停用旧模块，避免重复 Hook。无需清除多邻国数据。
4. 重新启动多邻国，点击猫头鹰图标设置功能。

模块桌面页只管理桌面入口和源码链接；业务功能在多邻国悬浮面板内设置。隐藏桌面图标后，在 LSPosed 的模块条目中选择打开设置即可恢复。

从私用测试版升级时保留已有配置。公开版版本名重新从 `0.0.1` 开始，内部版本码继续递增，以支持覆盖安装；LSPosed 仓库的 Release 标签使用 `版本码-版本名`。

## 构建

需要 PowerShell 7、JDK 21、Android SDK Platform 35 / Build Tools 36.1.0。Python 测试只使用标准库。不使用 Gradle，也不会自动全局安装工具。

```powershell
# 下载官方编译 API 并核对 SHA-256；不会打入 APK。
pwsh -File .\prepare-dependencies.ps1

# 使用自己的签名密钥，口令通过隐藏输入读取。
pwsh -File .\build.ps1 -SdkRoot 'D:\Android\SDK' -JdkRoot 'D:\Java\jdk-21' -Keystore 'D:\keys\owl.jks' -KeyAlias 'owl'
```

产物在 `out/`。脚本拒绝覆盖同名 APK。签名私钥、口令、原始框架反编译 JAR、宿主 APK/DEX、账号配置和手机日志不随源码公开。自行构建的不同签名 APK 不能直接覆盖官方签名版本。

## 验证

```powershell
pwsh -File .\test.ps1 -SdkRoot 'D:\Android\SDK' -JdkRoot 'D:\Java\jdk-21' -DexDirectory 'D:\duolingo-6.103.6-dex' -PythonPath 'C:\Python\python.exe' -DeviceSerial '你的ADB序列号'
```

`DexDirectory` 由自己合法取得的目标安装包中提取 `classes*.dex`，仓库不附宿主文件。入口测试需要手机 LSPosed 的 `/data/adb/modules/zygisk_lsposed/framework.dex`。

- 七项 Java 回归：DEX 扫描、判题、会员工厂、关卡契约、日期及课程结束参数。
- 两项真实宿主 DEX 契约测试。
- Toast 连续切换、重复点击仅保留最新提示的回归。
- GitHub 可用/不可用的 Intent 路由受控测试。
- ART 入口参数、包名过滤与真实宿主课程结束判断测试，不创建课程或发送学习请求。

每个测试子进程限时 60 秒。`ALL_TESTS_PASS` 只表示这些检查通过，不能代替所有功能的真机验收。大字体、深色模式、旋转、多窗口以及其他 ROM 尚未全面验收。

## 项目来源与许可

原始代码来自 `OwlAuxiliaryR_code_1.1.zip`，在原代码权利人的授权下继续维护并以 [MIT](LICENSE) 发布。第三方 API 和 GitHub 图标分别遵守其原许可，见 [THIRD_PARTY.md](THIRD_PARTY.md)。本项目与 Duolingo、GitHub 及 LSPosed 官方无隶属关系。

作者倡议：**免费软件，仅限个人使用，请勿倒卖。** 这不是对 MIT 增加的许可限制；代码的复制、修改、分发等权利以 LICENSE 为准。
