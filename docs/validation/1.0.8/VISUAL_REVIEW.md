# 1.0.8 材质审阅与迭代

设计原则参考 Apple 的公开说明：[Meet Liquid Glass](https://developer.apple.com/videos/play/wwdc2025/219/) 和 [Materials](https://developer.apple.com/design/human-interface-guidelines/materials)。重点是沿边透镜、光线层次与背景联系。没有使用 Apple/Elbow 的新代码、图像、字体或控件资源；本版是工程近似，未声称复刻苹果私有着色器。

## 实际审阅

1. 首轮将原本覆盖半短边的强位移收敛到有限边缘，使用 C2 包络及平滑位移上限。渲染后背景重复拉扯减少，但浅背景轮廓偏平。
2. 将细反射核心向内移动到抗锯齿覆盖更完整的位置，提升主反光、降低均匀环形反射，保留较弱的次反光及内侧接触暗部。深/浅/多彩场景重新渲染并人工查看；before-preset-* 是这一阶段的相同默认参数对照。
3. 增加 IOS风 预设，将模糊降至 8、鲜艳度降至 1.12，协调折射、高光与厚度。多彩背景中过饱和和塑料感进一步减弱。
4. 最后一轮从生产 IosGlassPreset.kt 自动读取全部参数重渲染，避免图像与实际预设漂移。随后在 Android 上使用生产 F16 绑定及相同预设绘制旧/新光学；最终截图两列重复同一背景内容，源图按原像素绘制，不做画布拉伸。

## 图像含义

- dark/light/color-comparison.png：左为 1.0.7 着色器及固定基线参数，右为 1.0.8 + IOS风；为程序化模拟场景，不是设备系统截图。两列背景相同，前景图形仅为测试标记。
- before-preset-*：新旧光学使用相同参数，隔离着色器改动。
- material-optics-android.png：Android API33 软件 GPU；两列都使用同一 IOS 参数，左为旧光学，右为新光学。并非 ColorOS Hook 或 iOS 实机截图。
- 02-ios-preset.png / 04-original-restored.png：正式 APK 在隔离模拟器上的预设选择及返回原参数界面。

桌面输入预模糊使用 Pillow 生成固定测试背景，仅用来隔离光学；Android 对照使用真实生产浮点滤波器。GIF、模拟截图和 CPU 渲染都不能替代真实 ColorOS 动态画质与性能验收。
