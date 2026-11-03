# DreamGlass

**For ColorOS 17** · 开发候选 **1.1.9-debug2**

基础材质完全免费。粒子飘散、应用／通知倾斜、锁屏玻璃，以及其他应用与组件玻璃集中在“会员功能”，采用五元诚信付款，不联网核验支付。未在会员页确认当前版本时，会员开关在设置与实际 Hook 中均保持关闭；旧配置、导入配置和旧版确认不会自动授权。页面只提供“我已支付，请继续”和“暂不支付，退出”。

版本名或版本号变更后需要重新确认；已支持过的用户无需重复付款。确认只解锁设置入口，不自动恢复原有会员开关。配置备份不携带确认标记，免费功能不受会员状态影响。

## 这个版本

- 保留相册保存菜单玻璃，修正原生按钮背景转发可能形成的循环图层；不移除功能，保存逻辑保持原生。
- 修正相册导航的局部坐标／固定蒙版裁剪，补齐单图页顶部和底部的真实材质宿主。
- 负一屏三个顶部控件连同原生配对模糊层一起按 Path 裁剪，避免矩形底色；小布建议接入其专用背景，不再当作大文件夹。
- 多任务“全部清除”补上 COS17 堆叠逐卡动画入口，保持原生锁定任务过滤、GPU 快照及有界粒子渲染。
- 计算器适配和 COS17 无效旧功能已移除，会员按版本确认与 GPU-only 生产模糊保留。

248 项 JVM 测试、Debug／Release 构建及 Lint 通过，具体数量见审计。另有实际 AGSL 的桌面离线栅格渲染对照。**本轮未连接 ADB、未安装，未验证厂商 GPU 合成或真机保存流程**。详见 [候选说明](docs/RELEASE_1.1.9-debug2.md)。

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
