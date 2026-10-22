# DreamGlassforCOS17 Preview3 验证报告

日期：2026-10-22。版本 0.1.2-COS17-Preview3，versionCode 10003。

Preview2 用户反馈测试通过，已发布到 GitHub v0.1.1-preview2。此报告只描述新的控制中心柔光材质版本，不将旧版验收视为新版视觉验收。

| 检查 | 结果 |
| --- | --- |
| assembleDebug / testDebugUnitTest / lintDebug | BUILD SUCCESSFUL |
| JVM 截屏类族测试 | 2 项通过 |
| 实际 ROM / 框架 / APK 合约 | 6 项通过，新增 2x1 图标背景字段与回调核验 |
| Android Lint | 0 错误，182 警告 |
| APK 签名 | v2 通过，与 Preview2 同一证书 |
| 新版覆盖安装及 GPU 输出 | 待本次安装授权和真机验证 |
| 新版按钮观感 / 图标小圈 | 待用户手动重启 SystemUI 后验收 |

APK SHA-256：`fdd339cfbf1dcca3e3818cb938cc8883b12e4c6198dc603f02561ba6b8389d1a`

APK：DreamGlassforCOS17-0.1.2-Preview3.apk。

已实现：控制中心薄边缘光、柔和面光、收敛折射；横向 2x1 按钮仅隐藏独立图标背景层；两个可关闭开关；MODULE_SETTINGS 补 DEFAULT 以支持隐式恢复启动。

技术说明见 docs/SOFT_CONTROL_SURFACES.md，原有设备故障诊断见 docs/DEVICE_DIAGNOSIS_2026-10-22.md。旧版本验收报告单独保留。没有自动申请 Root、授予 Shell Root 或重启作用域。
