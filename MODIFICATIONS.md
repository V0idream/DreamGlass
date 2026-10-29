# DreamGlass 1.1.0 — 修改声明

修改者：DreamGlass contributors；维护者：V0idream。修改日期：2026-10-22 至 2026-10-29。

本作品基于 AYwlilwYA / ColorOS16-LiquidGlass，原始基础 `aa96e1aeb98f4e3777f4bced265a1b86bf3e10cd`，
已合并至 `10d40ce4ac1543f97e5ebbb7bbdd2f8ef6b45030`。整体按 GNU GPLv3 分发，原作者与第三方版权、
许可均保留。此次合并保留双方提交历史，兼容取舍见 docs/UPSTREAM_SYNC_0.3.md。

## 主要修改

- 独立产品名称、标记、应用ID与深色青绿UI：四页导航、材质工作室、分组参数卡与离线来源说明；
  替换旧联系/赞助/升级入口。源码明确披露派生关系，不将整个项目宣称为从零开发。
- COS17 初始化时机、内容绘制入口、抓屏坐标、滑杆轮廓、横向图标底层、通知/按钮玻璃与音量场景隔离。
- 有限厚度边缘透镜、方向性高光、IOS风可逆参数层、按局部区域文字/中性图标黑白适配。
- 高密度密集CPU浮点滤波与F16输出（1.0.6）；正反来源绑定修复（1.0.7）。
  1.1.0新增默认RenderEffect/HardwareRenderer硬件输出，可选精度模式，重用多样式缓存。
- 后台首帧抓屏、最新请求合并、异常隔离/退避、取消代次、内存压力清理与熄屏/隐藏停工；
  按实际背景变化而非只看手势调速，更密的批量行指纹，按需预览与诊断。
- 单文件GPU通知删除粒子、独立开关/速度、硬件纹理快照、有界任务和overlay清理。
- 保留需要认证的无Root状态/类清单回传，升级为五个宿主；日志采集转为用户显式启动logcat。
- 三个应用保留各自原生背景并用按类边缘装饰接入，不复活生产相册测试探针。
- 隐藏桌面入口、配置备份及用户确认的应用内Root重启；不自动申请Root或重启手机进程。
- 两份LibXposed AAR仅修改minCompileSdk元数据37→36；保留原组件版本、源码和版权附件。

本版独立修改与上游原始实现可通过公开Git提交与合并父提交逐文件比对。历史修改见CHANGELOG.md。
部分光学算法来自 Copyright 2025 Kyant / AndroidLiquidGlass（Apache-2.0），原许可头保留。
Android Developers/AOSP硬件位图示例顺序用于HardwareBackdropBlur和ParticleDissolveHook，已补来源与修改日期。
参考视频、手机截图、用户配置、签名私钥和本机构建路径不属于发行源码。
