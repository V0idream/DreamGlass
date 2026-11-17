# DreamGlass 3.0.1 · For ColorOS 17

本版正式发布此前的桌面动画与实时图标修复，沿用已有覆盖安装签名。`versionName=3.0.1`，`versionCode=3000003`，可覆盖此前 3.0.0 与 3.0.1 调试包。

- 保留系统图标动画的缩放、平移、裁剪和透明度，修正切换桌面时的抖动问题。
- 实时玻璃在原生动画的内容入口按当前位置与壁纸偏移绘制，屏蔽可能覆盖它的静态图层；预制图继续作为实时效果不可用时的回退。
- 减少重复准备、图标绑定和反射查找；可见文件夹关闭时分片预准备首屏缓存，减少展开时的临时工作。
- 保留预制／实时两种模式、可调模糊与折射、会员菜单入口、在线定制库及自定义图片／ZIP 导入。定制图标仍按需下载，不默认内置。
- 没有增加抓屏、壁纸解码循环或持续刷新定时器。

覆盖安装保留配置。更新后按自己的方式重新加载相应作用域；软件不会自动申请 Root 或重启桌面／SystemUI。

## 验证范围

正式 Release 构建、397 项 Release JVM 测试、Lint（0 错误）及升级签名检查通过，核对非 debuggable 标记、调试入口排除及许可资产。用户确认此前候选已消除抖动；本版不宣称已测量真机帧率、延迟、功耗或长期稳定性。详见 [验证记录](validation/3.0.1/README.md)。

## 源码与许可

基于 [AYwlilwYA / ColorOS16-LiquidGlass](https://github.com/AYwlilwYA/ColorOS16-LiquidGlass)，整体按 GNU GPLv3 发布；部分着色算法来自 [Kyant0 / AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass)，保留 Apache-2.0 声明。下载同时提供 APK、对应完整源码、依赖源码附件、许可及 SHA-256 校验和。定制图标库见 [DreamGlass-Icons](https://github.com/V0idream/DreamGlass-Icons)。
