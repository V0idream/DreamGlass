# DreamGlass

**For ColorOS 17** · 版本 **1.1.3**

1.1.3 修复通知粒子 Hook 中断、按真实卡片高度截图和滑动衔接；一键清除时短暂等待粒子再收起，最长 900ms。侧边栏复用原生 GPU 背景并避免重复模糊。

锁屏玻璃继续作为实验功能：数字的独立 GPU 渲染有效，但当前主题中的实际外观仍偏平，右下角相机按钮尚未正确玻璃化；本版不宣称解决。其余验证见 [1.1.3 记录](docs/validation/1.1.3/README.md)。

可调整的系统玻璃材质应用。提供独立工作台、材质工作室、运行状态、分组参数页与离线开源许可页。显示名称是 DreamGlass；For ColorOS 17 只表示支持范围。

## 这个版本

- 通知粒子修复抽象方法造成的 Hook 中断，接入真实的确认/取消入口，按实际卡片高度生成 GPU 硬件纹理，从当前滑动位置衔接。最多同时处理三张可见通知。
- 一键清除通知时，粒子仍在播放则短暂保留通知中心，最长等待 900ms；手动收起、界面销毁和关闭特效有收尾保护。普通删除和不兼容的收起回调保留系统行为。
- 侧边栏接入原生 GPU 背景纹理，保留系统裁剪与前景，模糊跟随统一材质预设，避免重复滤波。全局、IOS风及组件预设继续保留。
- 运行状态增加分进程阶段记录，区分“入口已安装”“对象已绑定”和“实际绘制”，便于发现部分兼容问题。
- COS17 独立音量窗继续保留原生材质并从背景抓取中排除，防止展开面板采到自身；本版不宣称新增了音量窗玻璃。
- 锁屏玻璃、动态倾斜仍为实验功能。当前主题中的锁屏时钟和右下角相机按钮尚未达到预期；不会用孤立 GPU 对照或模拟器画面代替真机可见效果。

验证：120 项 JVM、9 组普通模拟器 GPU／生命周期回归通过，Lint 0 Error。普通 ADB 确认 debug8 在实际 COS17 加载，通知粒子及清空等待路径执行；侧边栏已有用户效果确认。未测量功耗或长时间 SystemUI 稳定性。完整边界见 [TEST_REPORT.md](TEST_REPORT.md)。

## 支持范围

Android 13+；目标 ColorOS 17 样本为 SystemUI 17.99.02、Launcher 17.3.12。模块功能需要支持 LibXposed API 102 的兼容框架与对应作用域。部分动画仍受 ROM 限制，见 COMPATIBILITY.md。

安装后从应用保存配置，在框架中选择作用域，再按自己的方式重新加载目标进程。软件不会自动请求 Root、重启进程、修改系统分区或清除用户数据。隐藏桌面图标后，可通过 LSPosed 的模块设置入口打开应用。

## 开源与来源

DreamGlass 是 [AYwlilwYA / ColorOS16-LiquidGlass](https://github.com/AYwlilwYA/ColorOS16-LiquidGlass) 的派生开发，整体按 **GNU GPLv3** 发布，提供对应完整源码；部分着色算法移植自 [Kyant0 / AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass)，保留 **Apache-2.0** 声明。独立 UI 和新增适配不代表整个代码库从零编写。

完整说明：[THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)、[NOTICE](NOTICE)、[MODIFICATIONS.md](MODIFICATIONS.md)、[LICENSE](LICENSE)。实际依赖清单在 licenses/，应用内也可离线阅读。

每次发行均同时提供 APK、对应源码 ZIP、许可文档与校验和。社区转发应将源码及许可与 APK 一同提供，或提供同等可访问的源码下载，不应仅指向私有仓库。

## 构建与验证

构建说明见 [docs/BUILDING.md](docs/BUILDING.md)。当前验证记录见 TEST_REPORT.md。Android 模拟器验证应用界面；它不能替代 ColorOS 真机上的模块加载、绘制或稳定性验收。
