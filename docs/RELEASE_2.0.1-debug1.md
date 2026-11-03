# DreamGlass 2.0.1-debug1 · 发布后修复候选

2.0 已正式发布且保持不变。本候选仅处理用户继续要求的三项问题；第 3 点背景变化时的延迟已按最新要求停止处理，BackgroundCadence 与 BlurDrawHook 的源码均与 v2.0 一致，撤回的等待调度类也不在 APK 中。

## 调整

- 相册保存大面板：原生节点按 wrapper 的实际对象类型读取，兼容声明返回类型过宽的 OEM stub，并提供精确 View.mRenderNode 字段通道。面板在 dispatchDraw 的子控件绘制之前提交实时玻璃；背景与灰色 overlay 使用仅限普通 View setter 的独立租用，避免状态背景被误拒绝或背景绘制被跳过。保留文字、按钮及实际保存流程。
- 清理按钮：原生入场隔离期间继续准备材质和几何，onDraw 同帧补齐，避免解除禁止绘制后再等下一次准备。进入多任务立即刷新偏好；去掉有实时来源时的固定白色外圈，采用共同预设的边缘。缺源仍保留轻量底色，尊重原生隐藏／动画状态，不新增持续刷新定时器。
- 混合常驻／普通通知：按已分析的 COS17 NotifCollection.locallyDismissNotifications 提交入口接入，覆盖保留组容器时绕过 SwipeHelper 的可见普通子通知。只处理原生已经筛选的集合，并确认实际删除状态才播放粒子；常驻通知、父容器、原生参数与回调不改，保留并发上限及已有去重。

## 验证

275 项 JVM 回归、Debug／Release 构建通过；Lint 0 Error／Fatal、354 Warning。隔离 Android GPU 探针的 15 项断言通过，包含真实 View.draw／dispatchDraw 遍历、灰色覆盖层移除、实时内容更新、原生背景恢复、缺源按钮可见及实时输入变化。

以上不代表 ColorOS 真机验收。本轮未安装、重启或操作实体设备；厂商的实际绘制入口、混合通知事件命中、入场时序和外观仍待用户确认。静态契约来自本地系统 APK，APK／反编译代码和用户图片均未进入分发包。详见 [验证记录](validation/2.0.1-debug1/README.md)。

覆盖安装保留配置，需重新加载桌面、相册和 SystemUI 才能运行新代码。无 Shell Root 请求、无新增第三方依赖，会员确认行为本次未调整。许可与对应完整源码随包提供，继续遵循 GPLv3，并保留 ColorOS16-LiquidGlass 与 AndroidLiquidGlass／Apache-2.0 等来源声明。
