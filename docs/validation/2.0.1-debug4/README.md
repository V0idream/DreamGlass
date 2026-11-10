# 2.0.1-debug4 验证边界

2026-11-10。本轮修正新通知的材质入口；其余修改延续此前未上传的 debug2／debug3 工作。

- JVM 单元测试：329 项，0 失败、0 错误、0 跳过。
- Debug／Release 构建和 Release Lint 通过；APK 签名和既有证书校验见 audit.json。
- 新通知 ROM 契约：10 项通过，核对本地 COS17 SystemUI 的主题 Non 分支、Motion 分支、挂载后惰性创建及局部排除放行。原生 exclusion 调用链另由本地 DEX 核对。契约结果见 notification-native-contract.json。
- 先前 debug3 隔离 Android GPU 探针结果随附：壁纸来源及缓存 8 项、实时内容／面板 15 项通过，清理生命周期 PASS。本轮没有重新执行；不代表当前 APK 的厂商接入验收。
- 未操作实体设备，未用 ADB 安装、重启、截图或读取用户手机，未申请 Shell Root。
- 未完成物理画面验收、OEM 壁纸实际获取验收或功耗测量；不能由本地通过推断这些问题已在实体设备消失。

生产代码与测试均保留 GPU 模糊；发布包没有加入系统 APK、厂商反编译源码、私人日志或设备截图。
