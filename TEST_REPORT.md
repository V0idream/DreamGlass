# DreamGlass 2.0 验证记录

正式 Release 构建、265 项 Release JVM 测试、Lint 通过（352 Warning，0 Error／Fatal）。正式 APK 版本 2.0／2000000，不可调试、无新增探针，升级证书及法律附件校验通过。实体设备未安装或验收，已知视觉和时序问题列入发布说明。详见 [发行验证](docs/validation/2.0/README.md)。

---

# DreamGlass 1.1.9-debug5 验证记录

265 项 JVM 测试、Debug／Release 构建和 Lint 通过（352 Warning，0 Error／Fatal）。隔离模拟器执行生产直接按钮绘制及实时内容核心的 11 项 GPU 断言，包括缺源／透明输入下底层可见、动态颜色更新和原生 Paint 不变。实体设备未安装或操作，厂商控件与画面仍待验收，详见 [记录](docs/validation/1.1.9-debug5/README.md)。

---

# DreamGlass 1.1.9-debug4 验证记录

265 项 JVM 测试、Debug／Release 构建和 Lint 通过（352 Warning，0 Error／Fatal）。隔离 Android 模拟器执行生产实时 RenderNode 核心的内容／位置更新、坐标映射、裁剪与原生 Paint 恢复检查；全部通过。实体手机未安装或操作，厂商相册内容源和桌面 shader 未验收，详见 [验证记录](docs/validation/1.1.9-debug4/README.md)。

---

# DreamGlass 1.1.9-debug3 验证记录

262 项 JVM 测试、Debug／Release 构建和 Lint 通过（352 Warning，0 Error／Fatal）。用户反馈清理按钮背景消失、相册底栏错位、保存面板玻璃仍未完成；本版先按要求作为预发布上传，后续修复。自有 RenderNode 的模拟器几何报告不代表 OEM 背景或真机通过。详见 [验证记录](docs/validation/1.1.9-debug3/README.md)。

---

# DreamGlass 1.1.9-debug2 验证记录

248 项 JVM 测试，Debug／Release 构建及 Lint 通过（0 Error／Fatal，350 Warning）。签名与既有升级证书一致。APK 审计确认新适配类存在、计算器相关运行类与旧弹簧 Hook 未回流，许可和赞赏图片与源文件一致。

实际共享 AGSL 的桌面 Skia144.1／F32 栅格对照复现固定宿主蒙版位移切边，锚定后轮廓完整；不等同 Android GPU／OEM 背景滤镜链验证。保存菜单递归背景风险以原生 setter 实现及无环图回归验证；未取得此次设备崩溃栈。

本轮没有 ADB、安装、重启或设备 UI 操作，未验收相册保存、原生控件对齐、小布建议或批量清理的真机结果。详见 [记录](docs/validation/1.1.9-debug2/README.md)。

---

# DreamGlass 1.1.7 验证记录

154 项单元测试通过，构建与 Lint 通过（0 Error、307 Warning）。正式编译类审计确认：安全安装入口不读取开启配置，不注册事务 Hook，不写矩阵，也不安排复位；桌面入口不调用原生或旧版窗口倾斜安装器。正式 APK 的隔离模拟器实操确认开关关闭且禁用，强度／透视不可操作。

未进行实体设备安装与动画恢复验收；纯数学／GPU 夹具不替代此验收。不再声明窗口倾斜可用。升级后需要重新启动桌面以卸载旧代码。详见 [本版记录](docs/validation/1.1.7/README.md)。

---

# DreamGlass 1.1.6 验证记录

154 项单元测试、6 组 GPU／生命周期回归、Debug／Release 构建通过；Lint 0 Error、309 Warning。正式包的首次诚信确认、退出、会员分组、赞赏码保存及导航通过隔离模拟器实操；原始 PNG 保存后字节一致。灰底和统一压暗通过 GPU 对照，灰底开关／滑条默认回显通过。

新旧桌面按实际捕获类型双入口匹配。音乐原生装饰去重、收起流体云黑底及倾斜尚未完成实体设备外观验收；新桌面仅有静态契约证据。本轮没有向实体手机安装，也未测量耗电和长期稳定性。详情见 [本版记录](docs/validation/1.1.6/README.md)。

---

# DreamGlass 1.1.5 验证记录

148 项 JVM 单元测试通过，包含事务合并／过期／关闭、逐目标矩阵隔离、原生数组布局和媒体滚动坐标。Debug／Release 构建及 Lint 通过。双光学方案与历史版本的 GPU 像素对照、媒体裁剪、通知／窗口投影、F16 模糊、共享材质和 120 帧生命周期回归见 [本版证据](docs/validation/1.1.5/README.md)。

真实设备此前加载 debug5；0% 桌面菜单背景清晰，原生回调与绘制阶段命中。最终正式包已覆盖安装并核对哈希；当前音乐修正、双方案开关和最终事务提交补强的视觉证据来自模拟器，未宣称完成实体手机效果验收。窗口／通知倾斜最终可见效果、主题时钟、耗电和长期稳定性仍待验证。

---

# DreamGlass 1.1.4 验证记录

新增多任务卡片 GPU 粒子，独立开关与速度。124 项单元回归及向上飘散、取消、原生透明度恢复、自然结束、慢速完整播放和并发上限的 GPU／生命周期测试通过；普通通知粒子输出与上一版一致。Lint 0 Error、292 Warning。

已提取真实17.3.12桌面APK核对删除分支；普通ADB覆盖安装后，用户手动重启桌面。实际记录确认原生上划删除决策和GPU硬件纹理播放，DreamGlass自身任务卡片被正常移除，用户确认可见效果。详细边界见 [1.1.4记录](docs/validation/1.1.4/README.md)。下方保留已发布1.1.3的历史记录。

---

# DreamGlass 1.1.3 验证记录

120 项 JVM 与 9 组模拟器 GPU／生命周期验证通过，Android Lint 0 Error、285 Warning。正式包不包含 debug 探针或实际字形导出类。

普通 ADB 确认 debug8 已在实体设备加载；粒子 REQUEST/ACCEPT/COMMIT、硬件纹理和清空通知等待（记录 747ms）实际执行。侧边栏已有用户效果确认。锁屏数字独立 GPU 对照有玻璃折射，但屏上数字仍偏平、相机玻璃未实际绘制；用户决定停止继续调试，本版将其明确列为实验性限制。

详情见 [1.1.3 记录](docs/validation/1.1.3/README.md)。下方保留 1.1.2 的历史验证。

---

# DreamGlass 1.1.2 验证记录

日期：2026-10-30。包名 com.dreamglass.cos17，versionCode 1001002，沿用覆盖升级签名。本地候选，未发布、未连接或操作实体手机。

## 实现

- 原厂 COS17 缺少修改版 Zuyqa 类，因此旧版在设置显示开关但未安装倾斜。新增 LauncherTiltCompat：核对 helper、params、xi.q/xi.q$b、gi.c 与轻动画 lambda 的实际类型；限定目标 leash，在 native i(Matrix) 后调用额外 j(float[])，保留原仿射通道。额外4×4语义与既有16版 SurfaceProperties 用途对应，未在17厂商二进制中实际执行，列为待真机验收。
- 透视 pivot 使用原始 source/clip，而非当前屏幕缩放矩形；进度端点/关闭为单位矩阵，约120–192ms 停更复位、最多16个所有权记录、异常30s退避。未知接口保留原生。
- 通知旧变换函数未被帧泵调用；COS17 改接通知行 add/remove 与 heads-up show/移除 spring，使用原生 RenderNode 的 rotationX/scale/cameraDistance。450ms 后停止，不写 native translationX/Y 或 pivot。宿主覆盖属性后恢复不会擦掉新值；熄屏、不可见、禁动画、省电或开关关闭停止。电源查询最多10Hz，偏好状态缓存500ms，空闲不挂帧回调。
- 粒子原先只选 SwipeHelper 最长 dismissChild，取消方法也假设 View 在第0参数；本次按真实参数位置接入子类 onDownUpdate/dismiss 与 (float,float,View) snap。
- 「清空全部」在 NSSL 直接调用 dismissChild，不逐行执行 performDismiss。本次同时接实际控制器的 onSwipeOutAnimStart / onChildDismissed / handleChildViewDismissed；onChildNotDismissed 为取消。请求前准备快照、系统确认后才播放；普通 snap/reset 不掐断已经确认的删除。不会替换/重复执行原删除回调。
- GPU 录制对 COS17 的子视图滑动补偿，不修改源 View。单文件、一行接入、最多三张；RenderNode/HardwareRenderer 硬件纹理与 AGSL 绘制，未加入 CPU 模糊或像素回读。面板关闭、detach、低功耗与完成会清理。

## 场景补齐

- 智能侧边栏、相册与负一屏：原先只有独立描边/内发光，且App进程未attach组件预设读取。现删除装饰Drawable，安装OEM背景RenderEffect适配，复用LiquidGlassShader全部光学、IOS风和逐键组件覆盖；GPU RenderEffect滤波，不使用CPU模糊或第三方进程抓屏。
- 背景接口作用于背景，不对整个按钮/工具栏前景使用View.setRenderEffect。按控件/面板分类，宽工具栏、缩略条与整屏排除；单按钮不因位于工具栏而被排除。默认关闭，潜在背景的轻量观察保证之后开启能绑定；不可用则原生，不画替代白框。OEM实现不在本机SystemUI DEX内，尚未在真机执行该通道。
- 锁屏：AbstractSubPlugin.getView的实际ClockPlugin/WidgetPlugin角色优先于类名猜测，支持FrameLayout内Canvas时钟；Widget上下文先排除前景文字，父子绑定去重。屏幕开启补扫已附着窗口，仍排除认证、AOD和SurfaceView。
- 壁纸：ClockPlugin原生Bundle/Binder与OplusLockScreenWallpaperController输入，当前激活目标需要时补取控制器缓存；已有原生壁纸源不再受文件权限门槛限制。UI录制RenderNode，后台生成限1600px的独立硬件源，再GPU滤波；原宿主Bitmap回收不影响录制。源代次、失败退避、屏幕/用户切换保护保留，不落盘壁纸、不新增Root权限。
- 状态分别显示插件返回值、发现角色、原生壁纸源和GPU材质；来源从等待变成就绪时允许更新。

## 本地验证

- 110 项 JVM 回归：含 COS17/旧版参数顺序、请求/确认/取消分类、投影端点与中心不变、完整参数范围有限性、通知包络收敛。
- Android Lint：0 Error；警告数及 APK 身份见 summary.json。包含厂商私有接口反射的兼容性警告，不据此保证其他 ROM 支持。
- 静态：12个类接口，头部动画 control/holdY 从保留解编译源另行核对；证据包含原样本 SHA-256，未重新取得 APK。
- 普通 API33 / SwiftShader 模拟器七组：GPU 后台、F16/AGSL 画质、异常及取消恢复、粒子、锁屏、3D。该模拟器没有 ColorOS、实际主题插件与 LibXposed 宿主。
- 粒子：硬件快照、缩放坐标、GPU编译、4阶段覆盖/飘出、尝试不播放、取消准备、确认后复位不停、取消删除停止、detach与自然完成清理。
- 3D：实际 RenderNode 可见姿态、原生位移保留、恢复原角度/缩放/相机、保留宿主新变换、detach停止、自然回正；另绘制窗口投影数学结果。并非实际 xi.q$b/SurfaceControl 厂商实现测试。
- 最终 release 排除全部 debug probe，离线许可与源码/依赖清单一并交付。

新增场景探针：相同IOS参数由主着色器消费，动态输入改变超过10万像素且无需重建效果；按钮前景点击3次保留；原壁纸Bitmap立即recycle后，GPU输出中心颜色/alpha及硬件配置正确。时钟探针改成FrameLayout内自绘Canvas，检查大数字可见像素和整组字形alpha，避免只验证日期/组件而误报整个时钟通过。

自绘时钟字形覆盖96123像素。玻璃与同样经过RenderEffect的恒等基准alpha最大差0；相对不经Effect的原生边缘最多4/255，恒等与玻璃一致，属离屏采样差异。普通TextView测试alpha差0，原样恢复差0。

## 真机待验收

相册/负一屏/侧边栏在「其他应用与组件」启用相应作用域与开关；锁屏玻璃在对应页启用。更新APK后按自己的方式重新加载相关宿主，旧包的装饰对象可能仍在旧进程中；不自动重启或申请Root。

设置 → 空间效果，分别开启「动态倾斜」「通知卡动效」「删除通知时粒子飘散」，原配置默认值不会自动改动。首次加载新包后，按用户自己的方式重新加载桌面与 SystemUI。
需实际核对标准/轻动画、打开/退出/打断、普通通知与横幅、手动滑出/取消/清空全部、只读状态中的 launcher-tilt-event/notification-tilt-event，以及系统动画关闭、省电和混用插件情况。
这次没有连接实体设备，没有测量 ColorOS 功耗、帧率或长时间 SystemUI 稳定性。模拟器探针不证明厂商 Hook 已命中。此前1.1.1的验证保留在对应目录，不混作本次证明。
