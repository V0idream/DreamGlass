# DreamGlassforCOS17 Preview4 验证报告

日期：2026-10-22。版本 0.1.3-COS17-Preview4，versionCode 10004。

本次修复按音量键弹出的独立面板被控制中心绘制 Hook 误处理的问题，保留其系统原生采样、遮罩和进度分层。Preview3 的柔光控制中心与横向磁贴去小圈功能继续保留。

| 检查 | 结果 |
| --- | --- |
| assembleDebug / testDebugUnitTest / lintDebug | BUILD SUCCESSFUL |
| JVM 回归测试 | 7 项通过，0 失败 |
| 实际 ROM / 框架 / APK 合约 | 7 项通过 |
| Android Lint | 0 错误，182 警告 |
| APK 签名 | v2 通过，与已验收 Preview2 同证书 |
| 本版安装及视觉验收 | 未执行；用户选择自行安装，当前普通 ADB 无在线设备 |

APK SHA-256：`08d6ae9010e9a67c7edc1f044a8f8bcfc052661d2c3872e78be9b0a4efacf512`

修复证据与边界见 docs/VOLUME_PANEL_FIX.md。源码、实际测试输出与对应签名 APK 均已打包。未使用 ADB Shell Root，未自动安装或重启作用域。Preview2 的用户验收不替代本版视觉验收。
