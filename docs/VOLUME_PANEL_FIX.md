# Preview4：独立音量面板修复

用户明确指定异常位置为按音量键弹出的音量面板，不是控制中心的竖向音量滑条。

## 代码证据

SystemUI 17.99.02 的独立音量面板使用 VolumePlatformBlurHostHelper / VolumePlatformBlurSession 管理自己的采样、显示切换和进度材质。其 PlatformBlurDrawable.draw 先画内部 blurDrawable，再画 blurMaskColor 对应的 maskPaint。

原模块的 suppressPlatformBlurMaskColor 在所有 PlatformBlurDrawable 上清除 blurMaskColor，未区分独立窗口；内部 posteffect 绘制 Hook 也可能接管这些对象。原场景过滤依赖可缺失的层登记，并在黑名单前先匹配 media / notification 等白名单。这些是已确认的代码缺陷；当前没有在线 ADB 设备，未据此宣称已复现用户全部视觉症状。

## 修正

- 从平台包装对象的 getHostViewName **Function0 字段**读取宿主身份，反射调用 invoke，避免将它当成不存在的 getter，也避免依赖宿主与模块的 Kotlin 类型同一性。
- 独立音量面板保留系统自己的背景采样、遮罩、进度层与显示动画。不是关闭系统音量面板，而是避免把控制中心的截图与玻璃管线套到它上面。
- 将内部绘制对象以弱引用标记为原生材质；未取得层名时也不再默认允许玻璃接管。
- 包装对象整个原始 draw 运行于线程局部原生绘制作用域，嵌套、异常退出均能恢复；不影响同线程后续磁贴或其他绘制线程。
- 注册普通模糊 View 时，按独立音量宿主及祖先识别排除，不把它加入控制中心几何映射。
- 黑名单优先于 media 等白名单；控制中心的 OplusQsVerticalSeekBar 保持原路径。

## 验证

7 项 JVM 单元测试通过（含原有 2 项截屏测试）：覆盖字段回调、无层登记、混合层名优先级、嵌套异常退出及线程隔离。7 项实际 ROM 合约检查通过，含平台字段/方法与真实音量滑条类。构建、Lint 与同证书签名验证通过。

按用户选择未自动安装、未操作 Root 或重启作用域。实际音量面板的拖动、展开、收起和背景观感仍待用户安装后验证。
