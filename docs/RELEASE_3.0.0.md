# DreamGlass 3.0.0 · For ColorOS 17

本版汇总此前桌面图标开发版本，以 Release 构建发布；versionName 为 `3.0.0`，versionCode 为 `3000000`，沿用既有覆盖安装签名。

- 桌面玻璃图标提供预制和实时渲染两种模式，预制缓存同时作为实时模式的回退；保留文件夹、Dock、小布建议、动态时钟与日历的适配。
- 图标曲边使用共享清晰壁纸缓存做折射，中央保留可调磨砂；定制图案统一为浅色分层玻璃，补充左上定向高光、右下边缘阴影以及接触和投射阴影，突出应用主体。此次材质改动没有增加抓屏或持续 CPU 任务。
- 桌面图标设置统一位于“会员功能 → 桌面图标玻璃”，包含渲染模式、模糊、通透、折射与厚度控制，以及定制库和自定义入口。沿用已有诚信确认，不自动开启开关。
- 定制图标由 [DreamGlass-Icons](https://github.com/V0idream/DreamGlass-Icons) 按需下载，不默认内置。支持为所选应用上传图片，也支持导入图片以应用包名命名的 ZIP。
- 保留此前锁屏壁纸来源、流体云、通知玻璃、通知粒子及按需采样相关实现。

覆盖安装保留配置；更新后需按自己的方式重新加载对应作用域。此包不会自动申请 Root 或重启桌面／SystemUI。

## 验证范围

Release 构建、389 项 Release JVM 测试与 Lint（0 错误）通过；APK 版本、非 debuggable 标记、升级签名、许可资产及调试入口排除已核对。生产 AGSL 在桌面 Skia 上完成六组离线渲染、透明边角及透明度检查，并以接近手机共享壁纸比例的纹理验证曲边清晰采样有效。离线结果不等同于厂商真机视觉验收。

本版未安装到实体设备，未测量功耗、帧率或延迟，也未完成 ColorOS 长期稳定性验证。此前真机截图反馈的折射与风格问题已在本版调整，最终观感仍需真机确认。具体记录见 [validation/3.0.0](validation/3.0.0/README.md)。

## 源码与许可

基于 [AYwlilwYA / ColorOS16-LiquidGlass](https://github.com/AYwlilwYA/ColorOS16-LiquidGlass)，整体按 GNU GPLv3 发布；部分着色算法来自 [Kyant0 / AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass)，保留 Apache-2.0 声明。下载同时提供 APK、对应完整源码、依赖源码附件、许可及 SHA-256 校验和。详见 LICENSE、NOTICE、THIRD-PARTY-NOTICES.md 和 MODIFICATIONS.md。
