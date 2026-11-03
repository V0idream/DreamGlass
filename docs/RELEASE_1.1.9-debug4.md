# DreamGlass 1.1.9-debug4 · 本地修复候选

针对 debug3 的三处反馈调整背景输入与坐标归属，尚未完成 ColorOS 真机视觉验收。

- 后台清理按钮：保留原生 LayerBlurDrawable、GPU 取样与位置更新，只在原生录制阶段接入共用玻璃着色；不再用透明背景替换原生材质。材质尚未就绪时保留原生底色，不修改点击或任务清理流程。
- 相册首页底栏：使用实际 ViewPager2 兄弟内容的 RenderNode，玻璃在自身局部坐标绘制，宿主偏移只应用一次。各胶囊沿用原生范围与共用预设，不增加整个工具栏外框。
- 相册保存确认：大面板引用其所属 Activity 的独立窗口内容，处理系统调暗及原生灰色 overlay。拒绝祖先／子孙内容循环，缺源或异常时恢复原生；保留保存按钮和保存流程。

生产路径不截屏、不生成背景位图、不使用 CPU 模糊。实时内容引用按已有界面帧刷新，不增加持续刷新定时器；独立窗口订阅在卸载时释放。本轮没有新增依赖。

## 验证范围

265 项 JVM 测试、Debug／Release 构建和 Lint 通过（352 Warning，0 Error／Fatal）。隔离 Android 模拟器使用生产 RenderNode 核心完成动态内容、原生节点移动、宿主偏移、裁剪和 Paint 异常恢复检查，结果见 [验证记录](validation/1.1.9-debug4/README.md)。

模拟器没有 ColorOS 的 ViewWrapper 和桌面取样组件，不能证明相册内容发现入口、厂商背景 shader 或真机显示正确。本轮未连接、安装、重启或操作实体手机；不声称三处已获真机验收，也未测量实际功耗与长期稳定性。

取消每版会员确认、仅启用会员功能时提示的需求仍按之前约定延后，本候选未调整。

## 安装与来源

覆盖安装保留配置；目标进程需要重新加载才能运行新代码。软件不自动申请 Shell Root、不自动重启。此 APK 为调试候选，源码包与许可同时提供。

DreamGlass 基于 AYwlilwYA / ColorOS16-LiquidGlass，整体遵循 GNU GPLv3；部分光学着色算法来自 Kyant0 / AndroidLiquidGlass（Apache-2.0）。完整声明见 LICENSE、NOTICE、THIRD-PARTY-NOTICES.md、MODIFICATIONS.md。
