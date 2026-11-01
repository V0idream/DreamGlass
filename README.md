# DreamGlass

**For ColorOS 17** · 版本 **1.1.5**

可调整的系统玻璃材质应用，提供材质预览、分组参数、运行状态与离线开源许可。DreamGlass 是正式名称；For ColorOS 17 表示支持范围。

## 这个版本

- 默认恢复 DreamGlass 1.0.7 的宽幅折射与边缘体积；材质页新增“新版渲染方案”开关，可切回较新的紧凑镜片。切换保留现有参数和预设，继续使用当前 GPU／F16 模糊及线性采样。
- 音乐卡片保留原生 Drawable 边界，避免局部裁剪或滚动负坐标将玻璃圆角移入卡片内部；保留系统播放按钮布局和点击范围。
- 统一背景模糊覆盖通知长按菜单、桌面图标菜单与负一屏等匹配场景；修正 0% 时桌面图标仍有独立模糊的路径，菜单自身玻璃继续保留。
- 窗口倾斜补齐 COS17 逐目标回调、原生矩阵布局、事务合并与最终提交；通知倾斜跟随原生进度，在可见的动画中段达到峰值。**倾斜仍为实验功能，最终真机可见效果尚未确认。**
- 锁屏增加主题字形绘制出口与原生相机按钮层适配。相机按钮已有真机玻璃证据；主题时钟仍可能偏平，不能保证全部主题支持。
- 继续保留通知／多任务删除 GPU 粒子、一键清除短暂等待及已确认的侧边栏材质。COS17 独立音量窗继续使用原生材质。

本地验证：148 项单元测试，Debug／Release 构建与 Lint 通过；双渲染方案、媒体裁剪及动画等 GPU 回归详见 [验证记录](docs/validation/1.1.5/README.md)。正式包已通过普通 ADB 覆盖安装并核对文件哈希；音乐修正和倾斜尚未完成宿主重启后的视觉验收，此前桌面菜单 0% 背景有真机截图证据。未测量新版本功耗或长期稳定性。

## 支持范围

Android 13+；目标 ColorOS 17 样本为 SystemUI 17.99.02、Launcher 17.3.12。模块功能需要支持 LibXposed API 102 的兼容框架与对应作用域。部分动画仍受 ROM 限制，见 COMPATIBILITY.md。

安装后从应用保存配置，在框架中选择作用域，再按自己的方式重新加载目标进程。软件不会自动请求 Root、重启进程、修改系统分区或清除用户数据。隐藏桌面图标后，可通过 LSPosed 的模块设置入口打开应用。

## 开源与来源

DreamGlass 是 [AYwlilwYA / ColorOS16-LiquidGlass](https://github.com/AYwlilwYA/ColorOS16-LiquidGlass) 的派生开发，整体按 **GNU GPLv3** 发布，提供对应完整源码；部分着色算法移植自 [Kyant0 / AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass)，保留 **Apache-2.0** 声明。独立 UI 和新增适配不代表整个代码库从零编写。

完整说明：[THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)、[NOTICE](NOTICE)、[MODIFICATIONS.md](MODIFICATIONS.md)、[LICENSE](LICENSE)。实际依赖清单在 licenses/，应用内也可离线阅读。

每次发行均同时提供 APK、对应源码 ZIP、许可文档与校验和。社区转发应将源码及许可与 APK 一同提供，或提供同等可访问的源码下载，不应仅指向私有仓库。

## 构建与验证

构建说明见 [docs/BUILDING.md](docs/BUILDING.md)。当前验证记录见 TEST_REPORT.md。Android 模拟器验证应用界面；它不能替代 ColorOS 真机上的模块加载、绘制或稳定性验收。
