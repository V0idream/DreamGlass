# DreamGlass 2.0.1-debug7 · For ColorOS 17

本次为修复候选预发布，保留 2.0 正式版。versionCode 2000007，沿用现有升级签名，同签名覆盖安装可保留配置。

## 本次修改

- 修正旧区域快照挡住已就绪全屏新帧的问题；过期缓存排队更新，展开流体云在原生挂载与布局完成后补挂背景和捕获需求。
- 展开流体云和轻打扰横幅接入共用 GPU 背景准备。玻璃成功绘制后停用残留原生模糊区域；缺帧或禁用时恢复原生背景，保留进场透明度。
- 保护通知与流体云共用背景包装器的轮廓、状态、可见性、热点等原生回调，增加绘制重入防护；轮廓查询不再移动原生背景。
- 保留可见场景按需抓屏、GPU 模糊和独立锁屏壁纸来源，不恢复普通页面切换时持续抓屏。

## 验证与限制

338 项 JVM 单元测试、Debug 构建及 Release Lint 通过（358 Warning、0 Error／Fatal）。本地隔离 Android 模拟器执行 27 项生产背景包装器 GPU 契约，覆盖原生接口异常、缺帧回退、模糊层交接、透明度、重入以及实际生产 AGSL 材质绘制。签名和包内版本校验通过。

**本次未操作实体手机，未取得新通知崩溃堆栈。上述检查不能确认 ColorOS 真机崩溃的唯一原因，也不能代表真机画面或功耗验收。** 安装后请自行重新加载 SystemUI，再确认通知到达与流体云展开表现。模块不会自动申请 Shell Root 或重启作用域。

详情见 [验证记录](validation/2.0.1-debug7/README.md)。audit.json 中 uploaded=false 记录的是本地验证完成时的状态。

## 来源与许可

DreamGlass 基于 [AYwlilwYA / ColorOS16-LiquidGlass](https://github.com/AYwlilwYA/ColorOS16-LiquidGlass)，整体按 GNU GPLv3 发布；部分着色算法来自 [Kyant0 / AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass)，保留 Apache-2.0 声明。本轮无新增第三方依赖。

附件提供对应完整源码、58 份依赖源码与校验和；许可、版权和修改声明见 LICENSE、NOTICE、THIRD-PARTY-NOTICES.md、MODIFICATIONS.md。会员及赞赏安排不改变开源许可。不包含厂商 APK、反编译源码或用户私人截图。
