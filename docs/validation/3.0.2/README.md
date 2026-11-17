# DreamGlass 3.0.2 验证记录

- Release APK：3.0.2，versionCode 3000004；不可调试，沿用既有签名。
- 413 项 Release JVM 测试通过，0 失败；覆盖缓存失效、移动坐标更新、投影等价性及重新绑定后的实际值读取。
- Release Lint 0 错误；警告数量记录在 audit.json。许可资产、调试入口排除、依赖来源及对应源码归档均已检查。
- 144 组离线生产着色器前后对比通过，最大像素通道差 2/255，详见 shader-validation.json。
- 未安装到手机、未执行 Android GPU 探针、未测量本版真机功耗或帧率。构建与离线测试不能替代厂商 Hook 的实体设备验证。
- 私人截图、设备清单、厂商 APK／反编译样本及签名密钥均不随发布公开。

机器可读检查记录见 [audit.json](audit.json)。
