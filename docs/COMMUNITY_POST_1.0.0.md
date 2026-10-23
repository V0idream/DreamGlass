# DreamGlass 1.0.0 · For ColorOS 17

做了一个可以调整系统玻璃材质的模块，正式名字叫 DreamGlass，For ColorOS 17 是当前支持范围。

这一版重新做了工作台、材质工作室、运行状态和设置页面，能实时预览材质、备份配置、隐藏桌面图标。保留通知/控制中心玻璃能力，并针对滑杆方底、横向按钮多余内圈、过硬边缘和独立音量窗口做了修补。

目前针对 SystemUI 17.99.02、Launcher 17.3.12 适配，需要支持 LibXposed API102 的兼容框架。同签名覆盖安装可保留配置；启用后按自己的方式重新加载作用域。不会自动申请 Root 或重启进程。

1.0.0 已完成构建、26 项单元测试、9 项 ROM 接口合约及模拟器应用界面检查。系统玻璃曾在前期适配版得到用户确认，本版材质细节和独立音量窗口仍需实机反馈，暂不承诺所有 ColorOS 17 机型一致。配图为应用模拟器截图，不是系统控制中心效果图。

开源说明：DreamGlass 基于 AYwlilwYA 的 ColorOS16-LiquidGlass 派生开发，整体以 GNU GPLv3 发布；部分着色算法来源于 Copyright 2025 Kyant / Kyant0 的 AndroidLiquidGlass（Apache-2.0）。新界面和新增适配不代表整个代码库完全从零编写。原作者与依赖作者的版权和许可均保留在应用「关于 DreamGlass」和发布包中。

- 派生基础：https://github.com/AYwlilwYA/ColorOS16-LiquidGlass
- 算法来源：https://github.com/Kyant0/AndroidLiquidGlass
- 项目地址：https://github.com/V0idream/DreamGlass

下载请使用随帖上传的 DreamGlass-1.0.0-release-bundle.zip，内含 APK、对应完整项目与依赖源码、构建说明、许可和 SHA-256。后续转发请保留这些文件，或同时提供公众可访问的对应源码下载。

---

发布者备注（发帖时可移除）：GitHub 目前为私有仓库，公众无法仅凭项目地址取得源码；应上传上述完整发布包。正文中的两项来源已写明，其余库完整清单在安装包和源码中。Elbow 只作处理思路参考，未使用其实现/资源，不作为已复制代码的授权来源。法律要求核心在分发物中保留版权、许可、修改说明及交付对应源码，而非要求每条宣传贴列出全部 57 个库。许可原文：https://www.gnu.org/licenses/gpl-3.0.html 、https://www.apache.org/licenses/LICENSE-2.0 。
