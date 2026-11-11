# 2.0.1-debug8 — 按需更新响应验证

对应安装包 SHA-256：`96493ea01295d237e0fae69f2faec5983ea3f5be9ecb78580ad747112ed74b96`。

- `audit.json`：343 项单元测试、版本与升级签名、Release lint、产物摘要。记录为发布前本地验证快照，其中 uploaded=false 指采集当时状态。
- `android-gpu-latency.json`：实际 GPU 工作线程处理两种初始材质、最新待处理背景、切换场景清除旧图、已发布位图所有权及隐藏后停止计算。
- `android-gpu-fallback.json`：27 项玻璃背景与通知原生回调保护契约。
- `glass.png`：隔离模拟器执行生产 AGSL 后的人工色块输出，仅验证材质几何与绘制。

发布沿用通过验证的原 APK。用户随后反馈试用效果良好；本地模拟器耗时不能当作 ColorOS 真机延迟或功耗测量，不宣称消除全部延迟。本轮未操作手机。
