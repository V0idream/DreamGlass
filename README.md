# DreamGlass

**For ColorOS 17** · 版本 **1.1.1**

可调整的系统玻璃材质应用。提供独立工作台、材质工作室、运行状态、分组参数页与离线开源许可页。显示名称是 DreamGlass；For ColorOS 17 只表示支持范围。

## 这个版本

- 1.1.1：同步上游至 `5beac91`，补齐音量窗口 SurfaceControl 排除/清理、通知绘制宿主绑定、负一屏独立开关与共享三档低分辨率采样缓存。
- 新增「设置 → 锁屏玻璃」：大时钟和日期、右侧组件背景、底部手电筒/相机背景三组独立控制；总开关默认关闭，首次开启后手动重启 SystemUI。
- 字形由系统绘制，GPU 负责透光和边缘；卡片与快捷按钮保留前景和点击。仅使用当前用户可取得的静态锁屏壁纸，取不到时保留原生外观；不处理指纹、密码和 AOD。
- 锁屏玻璃是实验性接入：普通模拟器已验证渲染、恢复与前景/点击保留，尚未在用户具体时钟主题上确认命中。详细记录见 TEST_REPORT.md。


- 同步 ColorOS16-LiquidGlass 0.3.0 及 `10d40ce` 修复：按类圆角、语义组件预设、桌面搜索/页码/小组件、三个应用组件与日志采集；保留 DreamGlass 的 COS17 绘制入口、品牌、IOS风预设及原有配置。
- 默认硬件背景模糊：RenderNode + RenderEffect + HardwareRenderer，结果直接作为硬件纹理使用。一次滤波服务多个按钮，不逐按钮重复计算；高精度 CPU/F16 为独立选择，硬件失败自动退避并回退。
- 背景变化独立于手势检测：动态最高约30Hz，静止约6–12Hz，细小变化采用更密的批量行指纹；不为屏幕内容持久化。熄屏/隐藏仍停止周期任务。
- COS17 独立音量窗保留原生材质，记录实际窗口根并从背景抓取中排除，防止展开面板反复采到自身。COS16 的后绘制覆盖只允许在已识别的16版启用。
- 新增「空间效果 → 删除通知时粒子飘散」，默认关闭，速度0.35×–3×。粒子绘制完全使用 GPU，不更改原始删除回调；不影响解锁、面板收起或普通通知到期。
- 精简首页、运行状态和参数页；移除相册测试探针入口与重复测试清单。完整诊断按需展开，配置与重启入口保持可控。

模拟器已验证 GPU/F16 背景绑定、粒子快照与清理、画质和异常恢复；**没有测量 ColorOS 真机功耗或验收此次厂商 Hook/音量面板**。更新内容及边界见 CHANGELOG.md 和 TEST_REPORT.md。

## 支持范围

Android 13+；目标 ColorOS 17 样本为 SystemUI 17.99.02、Launcher 17.3.12。模块功能需要支持 LibXposed API 102 的兼容框架与对应作用域。部分动画仍受 ROM 限制，见 COMPATIBILITY.md。

安装后从应用保存配置，在框架中选择作用域，再按自己的方式重新加载目标进程。软件不会自动请求 Root、重启进程、修改系统分区或清除用户数据。隐藏桌面图标后，可通过 LSPosed 的模块设置入口打开应用。

## 开源与来源

DreamGlass 是 [AYwlilwYA / ColorOS16-LiquidGlass](https://github.com/AYwlilwYA/ColorOS16-LiquidGlass) 的派生开发，整体按 **GNU GPLv3** 发布，提供对应完整源码；部分着色算法移植自 [Kyant0 / AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass)，保留 **Apache-2.0** 声明。独立 UI 和新增适配不代表整个代码库从零编写。

完整说明：[THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)、[NOTICE](NOTICE)、[MODIFICATIONS.md](MODIFICATIONS.md)、[LICENSE](LICENSE)。实际依赖清单在 licenses/，应用内也可离线阅读。

每次发行均同时提供 APK、对应源码 ZIP、许可文档与校验和。社区转发应将源码及许可与 APK 一同提供，或提供同等可访问的源码下载，不应仅指向私有仓库。

## 构建与验证

构建说明见 [docs/BUILDING.md](docs/BUILDING.md)。当前验证记录见 TEST_REPORT.md。Android 模拟器验证应用界面；它不能替代 ColorOS 真机上的模块加载、绘制或稳定性验收。
