# DreamGlassforCOS17

面向 ColorOS 17 的 LSPosed 玻璃材质软件，当前版本 **0.1.2-COS17-Preview3**。

提供原创概览、材质实验室、运行诊断、设置四个页面，以及完整高级参数控制。清透、柔雾、棱光三个预设使用现有 AGSL 能力，方便先预览再应用。参考渲染实现来自 ColorOS16-LiquidGlass，完整授权见 LICENSE 和 THIRD-PARTY-NOTICES.md。

Preview2 已通过目标手机测试并发布到 GitHub。当前 Preview3 新增柔光控制中心和横向图标背景开关，构建与合约检查通过，新版 GPU 输出和外观仍待真机验收。具体结果见 TEST_REPORT.md，支持范围见 COMPATIBILITY.md。

## 条件

- ColorOS 17，目标样本为 SystemUI 17.99.02、Launcher 17.3.12。
- Root 和支持 **LibXposed API 102** 的 LSPosed/兼容框架。旧框架只支持较低 API 时无法仅靠启用作用域解决。
- 模块 APK 最低 Android 13 / API 33。
- 新包名 `com.dreamglass.cos17`，与参考模块独立安装、独立配置。

## 安装和逐步启用

1. 安装 `DreamGlassforCOS17-0.1.2-Preview3.apk`，先不要启用 SystemUI 作用域。
2. 打开 DreamGlass，确认首页显示“框架配置通道已连接”。这只证明设置能写入框架，不证明 Hook 或效果已生效。
3. 在 LSPosed 中先选择 `com.android.launcher`。开启软件总开关，手动重启目标桌面进程/手机，让入口重新读取配置。
4. 从“材质”选一个预设应用，打开文件夹并检查玻璃、图标、关闭动画、壁纸切换等。Dock 是单独功能，需在桌面高级设置中启用。
5. 到“诊断”刷新，检查入口、配置、Hook 注册、系统调用、背景和绘制阶段。历史记录与绘制调用完成不能替代可见效果验证。
6. 桌面稳定后，再在 LSPosed 中选择 `com.android.systemui`，手动重启后分项测试通知、控制中心、媒体和亮度滑块。

如果同时运行多个针对同一材质的模块，可能互相覆盖。测试时可在 LSPosed 手动暂停其他相关模块，并保留原有配置。

软件不会自动安装、授权 Root、启用作用域、重启手机或修改系统分区。不要清除 SystemUI 或桌面数据来尝试恢复效果。

## Preview3 新增

- 控制中心按钮、滑条、媒体卡片使用薄边缘光与轻柔反光，降低厚塑料边框感。
- 网络、蓝牙、Clash 等横向 2x1 磁贴可去掉图标背后的小圈，保留图标与点击区域。
- 材质页两个独立开关，支持恢复原材质与小圈。
- 参考用户的 ElbowHitColor 配置方向，独立实现，详见 docs/SOFT_CONTROL_SURFACES.md。

## Preview2 修复

- 适配 Android API 37 的 ScreenCaptureInternal 截屏接口。
- 诊断回传独立于只读宿主配置，读取运行记录无需 Root。
- 设置页可隐藏桌面图标，LSPosed 模块设置入口仍可打开并恢复图标。
- 概览/诊断页可由用户申请本应用 Root，并分别确认重启桌面或 SystemUI；无需给 ADB Shell Root。
- 子菜单返回 DreamGlass，不再进入参考项目分类主页。

## 配置与恢复

- 默认总开关关、保留系统模糊开、持续抓屏关，实验性功能单独控制。
- 框架未连接时，首页总开关不可写；先检查模块是否启用及 API 102 支持。
- 材质预设只调整模糊、折射、色散、高光，并开启玻璃渲染，不自动开启总开关和实验特效。
- “暂停玻璃效果”让已加载的玻璃路径回退系统材质；“恢复玻璃效果”重新启用渲染。
- **完全关闭**：关闭总开关，在 LSPosed 撤销本模块作用域，手动重启对应目标进程或手机。
- 连续异常时优先在 LSPosed 中禁用模块并重启，或使用已配置的 LSPosed 安全模式/设备恢复手段。保持可用的 ADB/恢复入口后再启用 SystemUI。
- 总开关在进程加载时读取。配置通道使用框架 RemotePreferences，不通过可写公共文件传递。
- 日志导出由用户主动操作；诊断摘要只含宿主包名、PID、时间和阶段文本。

## 已知范围

主玻璃路径完成静态适配。17.3.12 的旧桌面弹簧参数和参考实现中的 Zuyqa 原生倾斜入口不可用，原系统动画保留。锁屏大时钟沿用参考项目的停用状态；流体云插件类未包含在本次系统包里，需要对应插件才能继续核查。详见 COMPATIBILITY.md。

## 构建

JDK 17，Gradle Wrapper 8.14.2，Android Platform 36、Build Tools 35.0.0。Wrapper 带官方发行包 SHA-256 校验。

在 `local.properties` 设置自己的 SDK 路径，或设置 ANDROID_HOME；不要提交本机配置和签名私钥。例如 Windows 的属性写法为 `sdk.dir=C\:/Android/Sdk`。

```powershell
.\gradlew.bat --no-daemon --console=plain :app:assembleDebug :app:lintDebug
# 或
.\scripts\build-preview.ps1
# 官方 Maven 在本地无法访问时，可显式选择镜像：
.\scripts\build-preview.ps1 -UseMavenMirror
```

产物：`app/build/outputs/apk/debug/app-debug.apk`。本次交付使用本机生成的 Android 调试证书，未使用原作者私钥。源码不附带签名私钥；其他环境重建的调试签名可能不同，覆盖安装前请核对证书。

ROM 合约测试在本机执行，不连接手机，不上传 APK：

```powershell
python scripts/check_rom_contracts.py --systemui PATH_TO_SYSTEMUI.apk --launcher PATH_TO_LAUNCHER.apk --module app/build/outputs/apk/debug/app-debug.apk
```

## 源码与记录

- `docs/DEVELOPMENT.md`：技术架构、实施阶段、具体修改及后续验证。
- `COMPATIBILITY.md`：样本、能力对应、已知限制和可选依赖。
- `TEST_REPORT.md`：实际构建、Lint、签名和静态测试结果。
- `docs/HOOK_AUDIT.md` / `docs/HOOK_MAP.csv`：注册函数级审计索引。
- `docs/DEX_TARGETS.md`：目标方法和字段的真实 DEX 描述符。
- `docs/UPSTREAM_README.md`：上游原始说明。
- `LICENSE` / `THIRD-PARTY-NOTICES.md` / `licenses/`：GPL-3.0、Apache-2.0 及来源声明；许可证也包含在 APK 中。

上游基线：[AYwlilwYA/ColorOS16-LiquidGlass](https://github.com/AYwlilwYA/ColorOS16-LiquidGlass)，commit `aa96e1aeb98f4e3777f4bced265a1b86bf3e10cd`。没有向 GitHub 推送或发布本次代码。
