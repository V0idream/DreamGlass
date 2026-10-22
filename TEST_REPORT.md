# Preview2 Refine3：按钮与通知卡片绘制接入修复

交付日期：2026-10-23。版本 `0.1.1-COS17-Refine3` / versionCode `10008`，包名 `com.dreamglass.cos17`。本版针对用户确认的“按钮和通知卡片的玻璃效果无效”，不是整体背景模糊强度。

## 修复依据与改动

1. **生命周期**：参考分支已核验的 COS17 生命周期目标，精确挂接 `com.android.systemui.application.impl.SystemUIApplicationImpl.onCreate()`，等原方法成功结束后安装效果 Hook。旧版声明了 `onCreate()` 的 `SystemUIApplication` 作为后备。避免包加载阶段应用 Context 未就绪便初始化所有效果；门控避免重复或重入安装，失败不会永久锁死门控。Launcher 安装路径保持原样；已有热重载回调仍直接重装，不等待已经执行过的 onCreate。
2. **控件内容录制**：所给 SystemUI 的 `BlendDrawable.onDrawContent` 先检查原生背景图像；图像为空或已回收时画 placeholder，只有另一分支才可能调用 `drawBlurShader`。原代码仅凭“系统 shader 存在且开启”就放行，因此模块即使已有独立背景源，也可能漏过自己的玻璃接管。
3. **新接入顺序**：在 `BaseDrawable/BlendDrawable.onDrawContent` 先尝试既有模块背景源、几何、场景开关与玻璃 shader。满足条件时直接录制玻璃；未满足或模块接管失败时原生分支只执行一次，保留原返回值及异常。整个面板的 Scrim 有独立策略，不走新的控件内容接管。
4. **诊断**：增加初始化和内容绘制记录，分别记录通知与按钮首次选择玻璃/回退路径。详细日志关闭时仍可读取这些有界诊断；已记录阶段不会每帧反复拼接详情。绘制分发 inline，避免新路径每次录制分配三个回调对象。

参考的是分支已确认的接口与接入思路，新的路由代码由本项目实现。**没有复制 Elbow 的实现、原生库或其配置**；本包仍使用原有 AGSL 玻璃算法。

这些静态数据流问题可以确认，但本次尚不能确认它们是否覆盖用户真机无效的全部原因。

## 保留范围

基线为首次 GitHub 发布的 Preview2：`4819d6216c54326d6a9984aaac867bdfa98b8f12`；本次接续 Refine2 提交 `fa1a3d5dc10d0d71489a106efb4d45af388109d7`。

AGSL 玻璃算法、折射/模糊参数、背景采样、预设、Manifest、作用域和 Root 流程保持原样。Refine1 的高光边缘/独立音量遮罩处理、Refine2 的七参数进度工厂与内层对象匹配继续保留。已统一行尾核对 shader、Manifest、进度兼容类和音量遮罩类与 Refine2 内容一致。

## 已完成验证

- assembleDebug、testDebugUnitTest、lintDebug：BUILD SUCCESSFUL。
- JVM 18 项通过：含现代/旧版生命周期解析、重复与重入安装门控、安装失败重试、模块背景先于原生占位绘制、背景未就绪时一次回退、原返回/异常保持，以及已有截图、进度层和音量测试。
- 8 项真实 ROM DEX 合约通过：目标为所给 SystemUI 17.99.02、Launcher 17.3.12 和此前保存的 API37 framework.jar；检查实际生命周期 owner、绘制方法、进度层工厂和内层解析链。
- Android/SystemUI JVM 夹具未打入模块 APK。
- Lint 0 错误，182 警告。
- APK ZIP/DEX 完整性、v2 签名校验通过；证书与首次发布相同。versionCode 10008 可覆盖此前 10007。
- 记录保存在 `docs/validation/refine3/`。

JVM 内容路由测试使用显式宿主替身模拟已读到的原生分支，不执行 Android GPU。DEX 合约也不能替代真实框架的 Hook 调用及像素效果验收。

## 真机验收状态

按照用户最新要求，本版后续仅做离线修复。此 Refine3 未通过本轮工具安装、重新加载、重启或视觉复验，不声称通知卡片和按钮已经实际恢复。此前读到的是旧版历史记录，不能作为此包的效果证明；不将用户正在使用其他插件的状态判定为作用域异常。

需要复测时，同签名覆盖安装此 APK，保留现有参数，不用重置或重新导入配置。按原来的方式手动加载 DreamGlass 到 SystemUI；诊断中的“宿主模块版本”应为 `0.1.1-COS17-Refine3`。重点比较控制中心选中/未选中按钮、通知卡片与展开收起，再查看初始化、内容绘制及各类别的玻璃/回退记录。路径记录仍不等于像素验收。

APK SHA-256：`a50af0172cd23f3a7dabf430d622dc08c16c2637ff8c57577672523da3ca8b1f`。

签名证书 SHA-256：`12db3813c08e0d85d97884f7bf87e337d91c9e376e470a41e086a1f682402a96`。
