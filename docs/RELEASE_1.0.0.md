# DreamGlass 1.0.0 · For ColorOS 17

DreamGlass 是可调整的系统玻璃材质应用。本版重新设计应用工作台、材质工作室、状态与设置页面，统一品牌、导航、参数卡片与离线开源许可入口。产品名是 DreamGlass，For ColorOS 17 说明当前适配范围。

延续 COS17 的通知/控制中心绘制接入、进度轮廓、横向按钮独立背景处理、材质边缘与独立音量窗口原生保护。本版品牌整理没有改动这些 Hook 与 AGSL 算法。

## 使用与支持

目标样本：ColorOS 17，SystemUI 17.99.02、Launcher 17.3.12。需要支持 LibXposed API102 的兼容框架。模块总开关默认关闭，配置保存后手动加载作用域。覆盖安装保留配置。应用不会自动申请 Root 或重启目标进程。

## 验证范围

Release 构建、26 项 JVM 测试、9 项 ROM DEX 合约、lint、APK 完整性和签名检查通过。独立 API33 模拟器验证应用页面、返回、图标隐藏/恢复、离线许可及大字体。未连接实体手机，模拟器界面截图不证明 ColorOS 系统玻璃或独立音量窗口的实机效果。已有 Refine3 的用户通过反馈不自动等同于本版本全面验收。

## 开源与分发

基于 AYwlilwYA / ColorOS16-LiquidGlass 派生开发，整体按 GNU GPLv3 发布；部分着色算法源自 Copyright 2025 Kyant / Kyant0 AndroidLiquidGlass（Apache-2.0）。所有原始版权、许可、修改声明和实际依赖清单均随 APK/源码提供。

发布附件中的 source.zip 是本版对应源码，包含 Gradle wrapper、本地 LibXposed AAR、57 个运行时组件及仅编译 API 的源码归档和构建说明。release-bundle.zip 同时包含 APK、源码、许可、校验和与社区说明。

GitHub 仓库保留原有私有可见性。向社区分享时应上传完整 release-bundle.zip，或同时提供 APK 和公众可下载的对应源码；不能仅给公众不可访问的私有仓库链接。普通宣传正文不需要逐一列出全部库，但下载物中的版权、许可与源码交付必须保留。

项目：https://github.com/V0idream/DreamGlass

上游：https://github.com/AYwlilwYA/ColorOS16-LiquidGlass

算法来源：https://github.com/Kyant0/AndroidLiquidGlass

许可依据：https://www.gnu.org/licenses/gpl-3.0.html 、https://www.apache.org/licenses/LICENSE-2.0
