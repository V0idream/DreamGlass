# DreamGlassforCOS17 测试与交付报告

日期：2026-10-22（Asia/Taipei）。版本：0.1.0-COS17-Preview1，versionCode 10001。

## 实际结果

| 检查 | 结果 |
| --- | --- |
| 未修改上游源码构建 | 通过；补齐 SDK 路径、Platform 36 后 BUILD SUCCESSFUL；基线 aa96e1aeb98f4e3777f4bced265a1b86bf3e10cd |
| 最终源码构建 | 通过；使用交付的 Gradle Wrapper 8.14.2 运行 :app:assembleDebug :app:lintDebug |
| 官方 Wrapper 校验 | 发行包及官方 .sha256 均为 7197a12f450794931532469d4ff21a59ea2c1cd59a3ec3f89c035c3c420a6999 |
| Android Lint | 0 错误，177 警告；完整报告保留，未设置全局忽略或 baseline |
| ROM 与打包合约测试 | 4/4 通过：Launcher 实际字段及签名、SystemUI 核心签名、产品类/入口、APK/DEX/作用域 |
| APK ZIP 完整性 | CRC 检查通过 |
| DEX 完整性 | 每个 classes*.dex 的 SHA-1 头部校验通过；模块入口、主页、渲染与诊断类存在 |
| API 打包边界 | LibXposed API 保持 compileOnly，未将 io.github.libxposed.api.XposedModule 打入模块 |
| Manifest 身份 | com.dreamglass.cos17；新主页导出，高级设置不导出；日志/文件/框架 Provider authority 均为新包名 |
| LSPosed 元数据 | java_init.list、module.prop、scope.list 均存在，API 102，仅两个目标作用域 |
| 应用网络权限 | 最终 Manifest 没有 INTERNET；本轮未上传任何用户 APK 或日志 |
| APK 签名 | apksigner verify 通过，v2 签名，RSA 2048，本机 Android Debug 证书 |
| 真机或模拟器安装 | 未执行 |
| LSPosed 实际识别和注入 | 未验证 |
| GPU shader 编译与实际显示 | 未验证；源码/DEX 合约不能证明 GPU 输出 |
| 系统稳定性、性能、长时间资源增长 | 未验证 |

APK 大小：16173327 bytes。

```text
APK SHA-256: 72041dce377f6f2dc912a9be50077cf96c1057384b31ffd2715db16e0613aeab
调试证书 SHA-256: 12db3813c08e0d85d97884f7bf87e337d91c9e376e470a41e086a1f682402a96
```

## Lint 处理与剩余警告

首轮构建产生 APK，但 Lint 报 151 错误。148 项是最低 SDK 26 与使用的现代图形接口不一致，现最低 SDK 明确为 33；1 项是旧调试广播未声明导出策略，已设为不导出；2 项壁纸权限检查位于宿主代码，现增加宿主授权检查并在这两个函数单独说明宿主权限语义。用户提供的两个宿主 Manifest 都声明 READ_WALLPAPER_INTERNAL；授权不满足时回退。未通过索取无关模块权限或全局忽略来绕过检查。

剩余警告按 ID 汇总：

- AndroidGradlePluginVersion: 2
- DiscouragedApi: 4
- DiscouragedPrivateApi: 2
- ExportedContentProvider: 1
- GradleDependency: 1
- HardcodedText: 4
- InternalInsetResource: 1
- MonochromeLauncherIcon: 1
- NewerVersionAvailable: 1
- ObsoleteSdkInt: 13
- PrivateApi: 55
- SdCardPath: 1
- SetTextI18n: 1
- StaticFieldLeak: 4
- TooManyViews: 1
- UnusedResources: 3
- UseKtx: 38
- UseSwitchCompatOrMaterialCode: 22
- UseSwitchCompatOrMaterialXml: 22

这些警告涉及宿主私有接口、现有控件、长期存在的反射和资源等；保留为后续审计依据。通过 Lint 不等于没有运行风险。

## 修改与验证范围

- 桌面实际反射字段 M/f/h/i/F/a/w 通过所给 DEX 校验，并核对 b.a(d) 和 d → b 继承关系。
- 修正宿主版本早期读取分支，先核验结构再选择适配；不存在的 Zuyqa 扩展不会报告为正常。
- 系统原绘制准备和脏标记处理保留，玻璃录制仅发生在原生 RenderNode 已有有效内容时；恢复路径有线程内重入保护。
- SystemUI 的 drawBlurShader、onDrawContent、getShaderOrNull 与 onBlurReady 精确描述符存在，主逻辑复用，没有通过强制成功返回伪造效果。
- 原创界面和三类预设已编译进入最终 DEX；没有图形设备，本轮没有做可见 UI 截图、交互或原生分辨率 QA。
- 63 个注册函数建立了静态审计索引。动态、外部或尚未确认的目标没有标为设备通过。

## 设备边界与下一步

本机 ADB 只读查询返回空设备列表。没有安装 APK、启用作用域、重启进程、修改系统分区、删除旧模块或清除系统数据。

下一步需连接已授权的目标设备，按 README 的 Launcher → SystemUI 顺序验证。需观察框架 API 102 支持、运行阶段、背景采样、圆角/裁剪/覆盖关系、暂停恢复、流畅度和长时间内存表现。流体云需实际承载插件类的 APK；候选包和路径尚未在设备上验证。

当前可以验收本地源码、构建、签名和静态兼容修复；P0 的实际安装与稳定性、P1 的可见效果仍待设备验收。

完整原始检查记录保存在 docs/validation/，兼容范围见 COMPATIBILITY.md，架构和阶段见 docs/DEVELOPMENT.md。
