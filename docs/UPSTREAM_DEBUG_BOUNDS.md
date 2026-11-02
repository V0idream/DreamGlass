# 玻璃渲染区域调试（1.1.8-debug2 候选）

本轮核对上游至 `d6913e6`。参考 [ColorOS16-LiquidGlass 的 DebugOverlay](https://github.com/AYwlilwYA/ColorOS16-LiquidGlass/blob/468b40c16964f92970e6c63a80cc01201de65ab9/app/src/main/kotlin/com/coloros16/liquidglass/debug/DebugOverlay.kt) 中“实际玻璃区域与控件区域分别标记”的调试思路和青色约定，保留原项目来源声明及 GPLv3 许可。

DreamGlass 在 **设置 → 日志与诊断 → 显示玻璃渲染框（调试）** 提供开关，默认关闭。本次优先接入标框能力；上游悬浮球、点选固化、全局 UI 树工作台不包含在这个候选中，也没有宣称完成整个上游引擎同步。

青色边界由共用液态玻璃 AGSL 材质直接输出，跟随该材质的形状、圆角、融合掩码、Canvas 变换与最终裁剪。仅登记了候选 View、只画了装饰外框、尚未进入材质绘制的区域不会被标记。对于后续还有字形／局部遮罩的场景，标记也会一起被裁剪，不能将其解释为整张控件矩形。

切换后重新打开目标页面；系统缓存的 RenderNode／背景可能需要重启对应作用域。开关不自动重启，也不申请 Root、悬浮窗权限或额外抓屏。偏好读取按需缓存 500ms，无常驻定时器。关闭时走原来的材质输出。

验证：168 项 JVM 测试通过，Lint 0 Error。GPU 回读覆盖普通／融合两种 Shader 的胶囊、圆形、竖向滑杆，共六组；中心像素不变，裁剪之外保持透明，关闭调试后与原始输出逐像素相同。见 [GPU 结果](validation/1.1.8-debug2/glass-bounds-gpu.json)。这些是合成场景验证，尚未完成手机宿主标框验收。

![正常材质](validation/1.1.8-debug2/normal.png)

![开启青色渲染边界](validation/1.1.8-debug2/debug-bounds.png)
