# 控制中心柔光材质

Preview3 根据用户的材质反馈重新设计控制中心表面。参考材料为用户提供的 ElbowHitColor 配置与截图：配置使用零色散、轻模糊、透明底色，横向磁贴隐藏独立图标背景。没有复制该软件实现，也没有将用户配置原件、截图或 APK 放入仓库。

## 视觉实现

- 控制中心按钮、滑条轨道和媒体卡片沿用宿主的几何与圆角。
- 新 surfaceSoftness uniform 只对控制中心注册的表面启用。通知、桌面与其他场景保持原材质路径。
- 将宽白色加性高光改为约 2–4 px 的薄边缘光，正反两侧光照强度不同，叠加轻微上沿面光。屏幕混合保留背景色，不再直接累加出宽白圈。
- Preview5 恢复对应原参数的完整折射幅度；柔光只调整边缘反光，仍可通过原有参数调整模糊、折射与色彩。
- 材质页“柔光玻璃材质”开关可切回原来的材质路径，应用内预览使用同一套 shader 与 uniform。

## 横向按钮图标

SystemUI 17.99.02 的 `OplusQSIconView` 将独立的背景 `iconBgView: ImageView` 与真正的图标作为两个子 View。仅当祖先是 `OplusQSResizeableTileViewTwoXOne` 时隐藏前者，不改变图标、标题、测量、触摸或无障碍。

Hook 只接入该宿主具体类的 onLayout / onAttachedToWindow / updateIconBgDrawable / onIconDrawableUpdate，均先完成原逻辑再更新背景层。1x1 圆形按钮不受此图标装饰开关影响。关闭此开关或暂停玻璃后，在后续布局或状态回调中恢复原 visibility。

材质开关和图标背景开关都默认为开启；不覆盖用户原来的高光、折射等参数。读取配置有 500ms 缓存；首次更新 Hook 实现需要用户重启 SystemUI。

## 验证边界

实际 SystemUI DEX 校验图标背景字段、四个回调签名和 2x1 类。静态合约通过不能证明 GPU 输出或最终观感，需普通 ADB 安装后检查应用内预览，再由用户手动重启 SystemUI 验证控制中心。
