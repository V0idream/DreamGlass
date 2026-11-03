# 2.0.1-debug1 验证范围

2026-11-04。275 项 JVM 测试，0 失败／错误。Debug／Release 构建与 Lint 通过（354 Warning，0 Error／Fatal），沿用覆盖升级证书。

- 新增运行时 wrapper 宽类型与精确字段的节点读取、偏好读取首帧／失败时序、通知集合提交及常驻／取消／重复删除的回归。
- 实际 COS17 静态类确认了 locallyDismissNotifications(List,boolean)、NotificationEntry.getRow／getDismissState／isClearable、EntryAdapter 的 clearable／Sbn 与行父子入口。集合先经过原生拦截筛选，再本地改变删除状态；模块不改其列表或状态。
- 隔离 Android 33 的 LiveContentBackdropProbeActivity 直接运行生产实时节点、按钮表面与对话框背景租用。15 项 GPU 断言通过，结果见 report.json。
- 对话框夹具使用真实 View.draw／dispatchDraw、willNotDraw 父容器、独立灰色子覆盖层及原生文字控件。关闭材质后恢复原背景对象。dialog-*.png 是程序输入，不是用户图或 ColorOS 截图，也未运行真正 COUI 组件。
- 清理按钮的缺源、透明输入、动态输入和原生 Paint 不变检查继续通过。读回只存在于 debug 验证；生产模糊依然使用 GPU。
- 审计确认背景刷新相关源码逐字与 v2.0 对齐，撤回的 CaptureAdmissionSignal 不在 Debug／Release DEX 中。计算器键未回流，Release 不含新增探针，许可文件匹配、无新增依赖。

没有进行实体设备 ADB、安装或操作；相册 OEM 源入口、真实常驻组删除事件、按钮入场与主观视觉效果均未验收。audit.json 中相关实体设备／OEM 标记保留 false，不能用测试数量替代用户可见确认。
