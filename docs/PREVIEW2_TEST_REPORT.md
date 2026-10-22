# DreamGlassforCOS17 Preview2 验证报告

日期：2026-10-22。版本 0.1.1-COS17-Preview2，versionCode 10002。

| 检查 | 结果 |
| --- | --- |
| assembleDebug / testDebugUnitTest / lintDebug | BUILD SUCCESSFUL |
| JVM 类族解析测试 | 2 项通过，0 失败 |
| 实际 APK 与设备框架合约 | 5 项通过 |
| Android Lint | 0 错误，182 警告；未全局忽略 |
| APK CRC / DEX 校验 / 元数据 | 通过 |
| APK 签名 | v2 验证通过，与 Preview1 同一调试证书 |
| 普通 ADB 覆盖安装 | 成功，保留配置 |
| 子菜单返回 | 真机确认回到 DreamGlass 设置页 |
| 图标开关 | 真机 LAUNCHER 隐藏/恢复通过，MODULE_SETTINGS 保留 |
| 设备功能验收 | 用户反馈“测试通过” |
| 长时间稳定性 / 帧率 / 多 ROM | 未完成专项测试 |

APK SHA-256：`29d426c2a12486ae334f6f491d6ad71aed847c5df2bc65f323e6679909b5b694`

证书 SHA-256：`12db3813c08e0d85d97884f7bf87e337d91c9e376e470a41e086a1f682402a96`

本版修复 API 37 截屏类族和只读宿主配置导致的运行记录丢失；加入本应用 Root 快速重启、可隐藏图标及返回路径修复。具体证据与测试边界见 docs/DEVICE_DIAGNOSIS_2026-10-22.md。GPL 与第三方声明完整保留。旧 Preview1 报告见 docs/PREVIEW1_TEST_REPORT.md。
