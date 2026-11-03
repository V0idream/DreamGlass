# DreamGlass

**For ColorOS 17** · 正式版本 **2.0** · 本地候选 **2.0.1-debug1**

基础材质完全免费。粒子飘散、应用／通知倾斜、锁屏玻璃，以及其他应用与组件玻璃集中在“会员功能”，采用五元诚信付款，不联网核验支付。未在会员页确认当前版本时，会员开关在设置与实际 Hook 中均保持关闭；旧配置、导入配置和旧版确认不会自动授权。页面只提供“我已支付，请继续”和“暂不支付，退出”。

版本名或版本号变更后需要重新确认；已支持过的用户无需重复付款。确认只解锁设置入口，不自动恢复原有会员开关。配置备份不携带确认标记，免费功能不受会员状态影响。

## 这个版本

[2.0 已正式发布](https://github.com/V0idream/DreamGlass/releases/tag/v2.0)，附带 APK、源码、许可与校验和。

2.0.1-debug1 是发布后的本地修复候选：相册保存大面板改用子控件之前的实时绘制入口；清理按钮提前准备并采用共用预设边缘；混合常驻组的普通子通知增加集合级删除粒子。275 项回归及 15 项隔离 GPU 断言通过，厂商真机效果仍待确认。

背景刷新延迟的修改已按最新要求撤回，相关逻辑与 2.0 一致。详见 [候选说明](docs/RELEASE_2.0.1-debug1.md)。计算器适配保持移除，生产模糊使用 GPU，会员确认行为本次未调整。

诚信付款是应用的支持方式，不修改本项目及第三方代码所附的开源许可与完整源码提供方式。

## 支持范围

Android 13+；目标 ColorOS 17 样本为 SystemUI 17.99.02、Launcher 17.3.12。模块功能需要支持 LibXposed API 102 的兼容框架与对应作用域。部分动画仍受 ROM 限制，见 COMPATIBILITY.md。

安装后从应用保存配置，在框架中选择作用域，再按自己的方式重新加载目标进程。软件不会自动请求 Root、重启进程、修改系统分区或清除用户数据。隐藏桌面图标后，可通过 LSPosed 的模块设置入口打开应用。

## 开源与来源

DreamGlass 是 [AYwlilwYA / ColorOS16-LiquidGlass](https://github.com/AYwlilwYA/ColorOS16-LiquidGlass) 的派生开发，整体按 **GNU GPLv3** 发布，提供对应完整源码；部分着色算法移植自 [Kyant0 / AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass)，保留 **Apache-2.0** 声明。独立 UI 和新增适配不代表整个代码库从零编写。

完整说明：[THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)、[NOTICE](NOTICE)、[MODIFICATIONS.md](MODIFICATIONS.md)、[LICENSE](LICENSE)。实际依赖清单在 licenses/，应用内也可离线阅读。

每次发行均同时提供 APK、对应源码 ZIP、许可文档与校验和。社区转发应将源码及许可与 APK 一同提供，或提供同等可访问的源码下载，不应仅指向私有仓库。

## 构建与验证

构建说明见 [docs/BUILDING.md](docs/BUILDING.md)。当前验证记录见 TEST_REPORT.md。Android 模拟器验证应用界面；它不能替代 ColorOS 真机上的模块加载、绘制或稳定性验收。
