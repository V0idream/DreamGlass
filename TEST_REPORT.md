# DreamGlassforCOS17 Preview5 验证报告

日期：2026-10-22。版本 0.1.4-COS17-Preview5，versionCode 10005。

用户反馈控制中心玻璃感明显减弱并提供截图。本版恢复完整折射、增强薄边缘反光，同时收紧独立音量窗口身份判断，撤回 Preview4 的宽泛层名排除。横向按钮去小圈保留。

| 检查 | 结果 |
| --- | --- |
| assembleDebug / testDebugUnitTest / lintDebug | BUILD SUCCESSFUL |
| JVM 回归测试 | 7 项通过，0 失败；含 QS 资源名不能当音量宿主的反例 |
| 实际 ROM / 框架 / APK 合约 | 7 项通过；增加 PlatformBlurHelper.hostView 字段校验 |
| Android Lint | 0 错误，182 警告 |
| APK 签名 | v2 通过，与 Preview2 同证书 |
| 本版安装 / GPU / 实际外观 | 未执行，用户选择自行安装，普通 ADB 无在线设备 |

APK SHA-256：`445981188174d42a80b1e00c887b58dc5df268f26d2687fe45713845d43e24cb`

新增 control_uniforms 诊断阶段记录实际下发的首次折射、高光和柔光参数，不能据此宣称最终视觉已通过。具体变更见 docs/CONTROL_GLASS_REGRESSION.md。没有自动安装、使用 Shell Root 或重启作用域。
