# DreamGlass 1.0.5 验证记录

日期：2026-10-25。包名 com.dreamglass.cos17；versionCode 1000005；沿用原签名。

## 功能与范围

- 「文字与可读性 → 按钮图标自动反色」是独立开关，默认关闭。中性色图标按各自按钮区域的背景选择黑色或白色；复用文字的线性 sRGB 背景亮度图和最小玻璃区域判定。
- 图标开关独立于文字反色。只有图标启用时，也会异步构建/更新亮度图。UI 绘制查询不可变亮度图，不读 Bitmap 像素。
- 新 Hook 作用于 ImageView.onDraw(Canvas)。要求祖先属于 QS、控件 ID 表示图标/按钮、尺寸受限且颜色是可识别的中性色 tint。状态栏、通知、媒体、独立音量、滑杆、背景图和头像排除；彩色状态、未知颜色/滤镜、动画 Drawable 和明确禁用图标保持原样。
- 支持 ImageView 的 ColorStateList、公共 BlendModeColorFilter，以及通过 ImageView/Drawable 公共整数 setColorFilter 参数观察到的 PorterDuff 滤镜。PorterDuff 的隐藏 getter/私有字段未反射读取。未知自绘图标或不经过这些通道的 tint 可能保持原色，未声称覆盖所有 ROM 图标。
- 仅在目标 RGB 有变化时，用小范围 Canvas 图层替换前景 RGB；图标背景不在 ImageView.onDraw 内，因此不随图标染色。不改写 View/Drawable 的 tint、状态列表、颜色滤镜或 alpha。
- 替换 tint 使用不透明黑白，已有绘制 alpha 保留，避免半透明状态被再次乘 alpha。原始绘制只执行一次；finally 恢复 Canvas 层，异常也恢复。
- 弱引用跟踪最多 512 个图标，单独缓存黑/白 Paint；主线程之外的 View 绘制保持原样。面板可见时 500ms 检查颜色配置/背景变化，面板收起后停止周期检查；切换关闭时使已跟踪图标重画原样。性能开销及复杂 ROM 动画仍待实机测量。

API 依据：[ImageView tint](https://developer.android.com/reference/android/widget/ImageView#setImageTintList(android.content.res.ColorStateList))、[Canvas.saveLayer](https://developer.android.com/reference/android/graphics/Canvas#saveLayer(float,%20float,%20float,%20float,%20android.graphics.Paint))。实现为本项目新增代码。

## 验证

- Release/Debug 构建成功；54 项 JVM 测试通过。新增六项测试覆盖中性色、状态色排除、原 alpha、与文字同一黑白策略、相邻深浅区域及 QS 归属/排除规则。
- Lint 0 错误、198 警告；同签名、非 debuggable、版本/包名、原布局 ID、57 项依赖及 12 项保留声明校验通过。
- 独立 API33 模拟器使用实际生产渲染器绘制六组图标：深浅背景黑白选择、蓝色状态保留、50% alpha 保留、无 tint 红色图片保留、缺少背景保持原色、禁用灰色图标保留。关闭开关后同一批 View 恢复原绘制；Canvas 异常恢复、只绘制一次和原对象/tint 引用不变的断言通过。
- 正式 APK 模拟器验证默认关闭、开关操作、文字开关未联动及返回路径。无 LibXposed，故未证明框架配置持久化或 Hook 本身已在 SystemUI 命中。
- 调试 IconContrastProbeActivity/SamplingProbeActivity 不在发行 DEX；探针 APK 不加入分发包。截图为测试图形，不是 ColorOS 系统截图。
- 原 ROM APK 仍未在提供路径，9 项 DEX 合约未重跑，历史报告不计作本次新功能的运行证据。新增 Hook 仅使用 Android SDK 的 ImageView/Drawable 方法，不新增厂商私有反射入口。

## 边界

未操作实体手机、申请 Root 或重启作用域。需要更新模块后由用户按自己的方式重新加载 SystemUI，并开启该选项进行设备验收。生产渲染器已通过 Android 绘制测试；真实 LSPosed Hook 命中、ROM 特殊图标的 tint 通道、动态画质、功耗及稳定性仍未真机确认。

证据：docs/validation/1.0.5/。APK SHA-256：f9ec89c2c0f1d242abf779e68d7391cd21bf7a9f70516925cecfe43abc8c5c1f
