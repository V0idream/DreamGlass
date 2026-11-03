# DreamGlass 2.0 · For ColorOS 17

本次按用户要求，将 1.1.9-debug5 的现有实现发布为正式版本 2.0。版本名称为 2.0，versionCode 为 2000000；使用 Release 构建，沿用既有升级签名。

## 本版内容

- 共用 GPU 液态玻璃材质、经典／新版光学方案、预设与组件参数。
- 通知／后台任务卡片粒子、应用与通知倾斜，以及锁屏、侧边栏、负一屏、相册等组件适配。
- 清理按钮在文字前直接绘制，有原生输入时使用共用玻璃；缺源或透明输入时保留可见底色，不改原生清理回调。
- 相册保存面板补充所属窗口关联和跨窗口坐标；实时内容引用避免背景截图与父子递归。
- 保留计算器适配撤除、GPU-only 生产模糊、离线许可说明和会员分组。会员确认行为本次没有调整。

## 已知问题与验证边界

用户已反馈：相册保存大面板效果仍未达到要求；清理按钮观感仍需优化，进入多任务时可能延后显示；背景变化时材质更新有延迟；同一应用的常驻通知与普通通知并存时，清除普通通知可能不播放粒子。本次先发布当前实现，这些问题会在后续版本继续修复。

debug5 已完成 265 项 JVM 回归及 11 项隔离 Android GPU 核心断言。2.0 的构建、签名、DEX 和打包检查另见 [验证记录](validation/2.0/README.md)。普通模拟器检查不能代替 ColorOS 厂商 Hook、画面、功耗和长期稳定性验收。2.0 正式 APK 未在实体设备安装或验收。

覆盖安装保留配置，更新后需要重新加载对应作用域。软件不自动申请 Shell Root 或重启进程。

## 来源与源码

DreamGlass 基于 [AYwlilwYA / ColorOS16-LiquidGlass](https://github.com/AYwlilwYA/ColorOS16-LiquidGlass)，整体按 GNU GPLv3 发布。部分着色算法来自 [Kyant0 / AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass)，保留 Apache-2.0 声明。

下载同时提供 APK、对应完整源码、58 份依赖源码附件、完整许可及校验和。详见 LICENSE、NOTICE、THIRD-PARTY-NOTICES.md、MODIFICATIONS.md。会员与赞赏安排不改变这些开源许可。
