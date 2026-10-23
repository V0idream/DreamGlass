# DreamGlass 1.0.2 · ColorOS 17 兼容性报告

本版将误实现的按钮透明度改为背景模糊控制，恢复 1.0.0 材质输出 alpha。保留紧凑参数页、局部文字反色及独立音量保护。36 项 JVM、9 项 ROM DEX 合约、release lint 和独立 API33 模拟器应用检查通过，详见 TEST_REPORT.md。

1.0.2 尚未进行 ColorOS 真机验证；模拟器应用预览和静态 ROM 合约不等于 SystemUI 作用域验收。下方历史入口审计继续保留。

## 样本信息

以下信息读取自 APK 内容，而非根据文件名推断。样本均未发现 split 声明或内嵌 APK。

| 样本 | 实际包名 | versionName / versionCode | min / target SDK | DEX / 定义类 / res 文件 |
| --- | --- | --- | --- | --- |
| SystemUI | com.android.systemui | 17.99.02 / 179902 | 37 / 37 | 9 / 73027 / 7647 |
| SystemLauncher | com.android.launcher | 17.3.12 / 170030012 | 35 / 37 | 7 / 35223 / 5367 |
| ElbowHitColor | com.treatangus.elbowhitcolor | 2.2.3-0818 / 64 | 26 / 36 | 3 / 21496 / 175 |

SHA-256：

```text
SystemUI       a06bf8e0046a5e6a19347e09064e6ce8f79e0e963c78c8ce65f0fbf56d770e62
SystemLauncher af89139f18857f5c9e130cd492f08dc1a3e26b131aec74490432b2c590ac972e
ElbowHitColor  f94f0fe3456029d7ebbbbfd7d14c41f3fbd3bf27cff1a0c3b3cf32e3acd70f4d
```

初版只检查 ElbowHitColor 的包信息；Refine3 参考分支对话已核验的生命周期接口分析。本项目没有复制 Elbow 的实现或原生库。所有系统 APK 分析在本机完成，未上传设备包、日志或私人文件。

## 加载与配置诊断

- 新式入口：META-INF/xposed/java_init.list → `com.coloros16.liquidglass.entry.XposedEntry`。
- 要求 **LibXposed API 102**。不能假定任意标为“LSPosed”的旧版本都支持它；API 不足时需有支持 102 的框架，不能靠修改 minApiVersion 声明解决。
- scope.list 仅 `com.android.systemui`、`com.android.launcher`，与本次样本一致。没有 android/system_server 或 BlurService 作用域。
- 原版总开关默认关闭，关闭状态直接跳过效果 Hook 安装。首次保存开启后，需要用户手动重启目标进程。
- 配置读写共用框架 RemotePreferences；界面显示连接成功只证明配置通道连通。
- 无硬编码的“只允许 ColorOS 16”总限制。桌面有版本分支，本轮补充了精确结构识别，避免启动阶段读不到版本时走错分支。
- 设备日志已确认截屏类族迁移与宿主配置只读两处故障，Preview2 已修复，详见设备诊断记录。

## 功能与 Hook 对照

下表未单独注明的视觉结果均待本版真机验收。初版函数级索引有 63 项，见 `docs/HOOK_AUDIT.md` / `docs/HOOK_MAP.csv`；Refine3 的新增生命周期入口和内容接入修订见 `docs/PREVIEW2_REFINE3.md`。历史方法描述符见 `docs/DEX_TARGETS.md`。

| 功能 | 参考目标 / 源码 | 本次样本与处理 | 状态 |
| --- | --- | --- | --- |
| SystemUI 初始化 | SystemUIApplicationImpl.onCreate() | 原方法完成后安装，声明方法精确匹配，门控避免重复安装 | DEX 与 JVM 通过；真机待验 |
| SystemUI 主玻璃 | BaseDrawable.drawBlurShader(Canvas)，BlurDrawHook.mountDrawBlurShader | 类和精确签名仍存在；与内容绘制接管配合 | Refine3 用户已确认；本版细节待实机复核 |
| 控件与卡片内容绘制 | BaseDrawable / BlendDrawable.onDrawContent(Canvas)，mountOnDrawContent | 模块背景与几何就绪时直接录制玻璃；不再依赖原生图像分支是否绘制；未就绪保留原生一次 | DEX 与离线路由测试通过；像素效果待验 |
| shader 取值 | DrawableShader.getShaderOrNull()、BaseDrawable.getDrawableShaderPaint() | 返回 RuntimeShader / Paint 的签名存在 | 静态匹配 |
| 背景完成回调 | BlurDrawableManager.onBlurReady(List, HardwareBuffer, int, float, Rect) | SystemUI 精确签名存在；不将模糊结果冒充未模糊原图 | 静态匹配 |
| 通知卡 / heads-up | AutoBlurDrawable、NotificationBackgroundView、ViewBlurProxy，BlurDrawHook | 主类存在；继续复用场景映射、截屏排除与回退逻辑 | 仍需实例映射和层级视觉验证 |
| 控制中心 / 快捷开关 | LightStyleStrokeDrawable、MixColorTileDrawable、OplusQSRootView | 对应类存在；核心后处理主链保留 | 待真机验证细部覆盖 |
| 媒体与亮度音量 | OplusMediaCarouselView、QsSeekBarBlurManager | 类存在；保留映射、前景与背景分层 | 待真机验证滑动、填充层与圆角 |
| 桌面主链 | 旧 BaseDrawable → `com.oplus.posteffect.drawable.b` | 新类继承 Drawable，含真实 M/h/i/a 等字段及静态 a(d)；修复展示别名反射错误并做类型校验 | 字节码合约已核对，运行待验 |
| 桌面绘制准备 | b.a(d) → e() / w → RenderNode | 保留系统 prepare 与脏标记处理，再录制玻璃；失败用重入保护恢复原生录制 | 代码级修复；资源与性能需真机验证 |
| 闭合文件夹 | OplusPreviewBackground / FolderRoundImageView，LauncherHook | 类及映射路径存在 | 静态部分匹配；需打开、关闭和拖动测试 |
| 文件夹卡片 / Dock | LayerBlurDrawable、OplusHotseat，LauncherHook | 类存在；目标内层来自 un.d → drawable.f → d → b | 字节码继承链确认；视觉待验 |
| 桌面模糊完成 | BlurDrawableManager，LauncherHook.mountOnBlurReady | 新回调为 f(List, HardwareBuffer, int, float)，保留现有签名分支 | 静态匹配 |
| 桌面旧弹簧参数 | CustomRectFSpringAnim，LauncherAnimHook | 17.3.12 字段结构已变；保留上游的禁用分支，明确不承诺这些控件有效 | 该版本不可用 |
| Zuyqa 原生桌面倾斜 | ZuyqaWindowTilt / ZuyqaAnimationTuning，LauncherTiltHook | 两个入口不在所给桌面 DEX；仅在运行时精确解析成功才注册 | 所给 APK 不支持 |
| 通知倾斜 | ScaleState、RowAnimationStateEx 等 | 类存在；保留独立实验开关 | 静态部分匹配；真机待验 |
| 锁屏大时钟玻璃 | LockScreenClockHook | 上游已有停用记录；源码保留，不擅自恢复宽泛 View 绘制接管 | 暂未启用 |
| 流体云卡片 / 胶囊 | 插件 CardBackgroundView / CapsuleView | 所给 SystemUI DEX 中缺少插件类，未发现内嵌插件 APK；宿主容器 CapsulePluginContainer 存在 | 插件部分待补充材料 |
| 3D、模糊、折射、色散、高光、圆角、色彩与遮罩参数 | Prefs、LiquidGlassShader、SettingsActivity | 保留现有参数能力；新增清透、柔雾、棱光三个只修改材质的预设 | 应用内 API33 GPU 预览通过；系统场景待实机复核 |

## 可选外部材料

P0/P1 当前无需补充整个系统镜像，posteffect 核心实现已在用户 APK 内。Android 的 SurfaceControl、ScreenCapture 等平台内部接口来自设备框架，API 37 上的反射可用性和授权仍需运行日志确认。

若继续适配流体云，需要**实际承载以下类的插件 APK**：

```text
com.oplus.systemui.plugins.seedling.card.ui.view.CardBackgroundView
com.oplus.systemui.plugins.seedling.capsule.ui.view.CapsuleView
```

参考实现将其称为 SystemUIPlugin.apk；候选包名 `com.oplus.systemui.plugins`，候选安装位置 `/system_ext/priv-app/SystemUIPlugin/SystemUIPlugin.apk`，**当前未验证这些包名和路径在设备上真实存在**。应先在目标机查询插件包安装路径再提取，不能按候选路径直接假定或要求整份系统镜像。

## 已知限制

- 最低 Android 13 / API 33；目标 API 36；目标样本是 API 37 系统，系统行为与 GPU 仍待验证。
- 已连接设备并普通 ADB 安装、验证返回路径和图标开关；帧率与长时间资源测试仍待完成。
- 原有后台捕获和 shader 缓存主体继续复用，性能、资源回收与最终像素覆盖尚无设备证据。
- 总开关在加载时生效，完全停用需要手动重启对应进程；单独“暂停玻璃”不会撤销已安装的所有非渲染 Hook。
- 预设只影响材质，不替用户启用总开关或扩展功能；首页样图是应用内合成背景，不是设备截图。
- 诊断记录是当前/最近一次进程启动的阶段快照，不等于实时存活或完整效果验收。
