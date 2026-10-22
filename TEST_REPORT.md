# Preview2 Refine1：仅边缘与音量遮罩的小修版

基线为最早上传 GitHub、用户确认测试通过的 `v0.1.1-preview2`，提交 `4819d6216c54326d6a9984aaac867bdfa98b8f12`。此分支直接从该提交创建，没有合入后续 Preview3、4、5 的材质与场景改动。

## 唯二业务改动

1. `LiquidGlassShader` 只把原高光带宽度乘 0.75，并相应内移带中心。高光强度、折射算法与强度、色散、模糊、鲜艳度、背景采样保持原样。
2. `BlurDrawHook.suppressPlatformBlurMaskColor` 增加三行条件：识别独立音量窗口的实际宿主时保留原遮罩，继续原始绘制。新增 VolumeMaskGuard 只负责这项识别；没有替换音量玻璃管线，没有修改层名过滤，没有增加全局/线程渲染旁路。

其余应用主源码只更新两个版本文字。Manifest、设置功能、UI 布局、预设、控制中心按钮、桌面、截屏适配和作用域全部沿用基线。没有引入柔光开关、42% 折射衰减、后续去小圈 Hook 等改动。

版本名称 `0.1.1-COS17-Refine1`；versionCode 为 10006，使已经安装后续实验版的设备也可以同签名直接覆盖，不需要清数据。

## 验证

- assembleDebug / testDebugUnitTest / lintDebug：BUILD SUCCESSFUL。
- JVM 测试 4 项通过，ROM 合约 6 项通过。
- Lint 0 错误，182 警告。
- v2 签名验证通过，证书与最早上传版一致。
- 已核对与基线的差异，两个绘制文件的完整差异保存在 docs/validation/refine1/preview2-render-diff.patch。
- 按用户选择未自动安装或重启手机；本修订的音量面板观感与边缘效果尚待用户安装后验证。

APK SHA-256：`65dd80d1f60d532122002f811d7440183f9f0cc8071e448280c4200e52d3ee10`。
