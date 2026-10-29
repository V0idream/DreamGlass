# DreamGlass 1.1.2 本地验证证据

- summary.json：最终 APK 身份、许可清单、测试统计和验证边界。
- scene-contracts.json：锁屏插件与原生壁纸六个类的静态接口；App的OEM背景桥只能在宿主运行时核对。
- motion-contracts.json：原厂 COS17 的12个类接口；来自保留 DEX 清单和头部动画解编译源。原 APK 当前缺失，未重新解包，不能代替执行。
- android/：七组普通 API33 / SwiftShader 探针的日志、画面与合成动画阶段；没有 ColorOS 框架。
- ui/：正式候选的空间效果设置页和返回导航；只读检查，保留原配置。
- build-summary.json：构建任务、测试结果与 lint 摘要。
- signature.txt：升级签名核对，只有公开证书摘要，无私钥。

没有连接实体手机；本轮没有发布到 GitHub。桌面额外4×4厂商接口、实际 Hook 命中、功耗与长期稳定性仍需真机测试。详细行为和手动验收步骤见根目录 TEST_REPORT.md。
