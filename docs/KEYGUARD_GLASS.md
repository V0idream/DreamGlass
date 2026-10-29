# 锁屏玻璃（1.1.1 实验性）

设置 → 锁屏玻璃。总开关默认关闭，首次启用后手动重启SystemUI；三组区域默认选中，可分别关闭。
覆盖大时钟/日期、可识别的组件卡片，以及底部手电筒/相机按钮背景。

## 实现

- View.attach 实际对象发现 + 已确认宿主的子树扫描。无需把主题插件类加载到错误的SystemUI ClassLoader。
  COS17本地类清单的实际时钟基类为 com.oplus.keyguard.ui.OplusKeyguardStyleClock，旧常量缺少 `.ui`。
- TextView仍由系统布局和绘制，RenderEffect把原RenderNode透明度当字形遮罩。HDR span清空字体paint.shader不影响此路径。
  参数及纹理绑定后生成RenderEffect；它会快照shader状态，不在空uniform时提前创建。
- 卡片和快捷按钮替换背景drawable，保留文字、图标、事件与padding；可读原生轮廓时使用其圆角，其他情况采用有界默认值。
  不增加Window或绘制跨层遮罩，因此保留原有壁纸遮挡层级。未知插件结构仍可能漏识别。
- 壁纸来自当前用户的静态 FLAG_LOCK 文件；无独立锁屏图时才回退静态 FLAG_SYSTEM。
  使用SystemUI原有权限，不要求模块应用或Shell申请Root。不能可靠建立当前用户上下文则不读取。
- 有界后台解码，长边约1600px内；GPU RenderEffect/HardwareRenderer模糊，直接使用硬件纹理。
  解码不是CPU模糊；生产没有逐像素回读或持续抓屏。固定壁纸/参数共用背景，熄屏不重复处理。
- 分组关闭、全局关闭、detach、熄屏/省电和无背景恢复原生；保留已观察到的原生RenderEffect与背景替换。
 认证视图、PIN、密码、指纹和AOD均不纳入识别。异常退避，不能承诺拦住厂商native驱动崩溃。

## 验证边界

普通模拟器能验证真实AGSL、字体透明度、壁纸/边缘响应、原生effect alpha、卡片前景、点击和逐像素恢复。
它没有用户的主题插件与权限，不能证明该主题每个红圈区域均已命中，不能代替真实锁屏或功耗验收。
取不到可靠静态壁纸（包括不支持的动态壁纸）会保持原生外观。生产不会持久化锁屏图像。
调试对照来自代码生成的背景，未使用或打包用户图片。

API依据：[Android RenderEffect](https://developer.android.com/reference/android/graphics/RenderEffect)、
[WallpaperManager](https://developer.android.com/reference/android/app/WallpaperManager)。
