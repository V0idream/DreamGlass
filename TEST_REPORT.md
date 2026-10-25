# DreamGlass 1.0.4 验证记录

日期：2026-10-25。包名 com.dreamglass.cos17；versionCode 1000004；沿用原签名。

## 原因与修复

- 原始 BitmapShader 未设置 filterMode。作为 RuntimeShader 的输入时，DEFAULT 实际使用 NEAREST，即使绘制 Paint 开启过滤也不会自动变为线性。低分辨率背景经放大和折射后，像素边界形成方块。此前桌面验证直接指定线性采样，未覆盖这个 Android 默认行为，属于验证缺口。
- 原图与低分辨率两条工厂统一显式使用 FILTER_MODE_LINEAR。AGSL 形状、折射、高光、文字/图标及 alpha 不改。
- 旧工作图按最长边 384 的条件连续减半，720×1584 输入可能最终降到 90×198。新策略按屏幕像素密度与模糊半径选择精确目标；同例 64px 模糊保留 360×792。较弱模糊保留更多细节，最多 524288 像素、最长边 1536，不放大输入。
- 六次模糊中间计算使用预乘浮点通道，仅最后输出 8bit 像素，避免每次整数截断积累暗化和色阶。以外圈加权匹配连续方差，避免小半径直接跳成整像素滤镜。
- 保留一条后台线程、一帧等待队列与失败回退。工作图和浮点缓冲比 1.0.3 更大；未将桌面耗时当成手机性能证明。

API 依据：[Android BitmapShader 官方文档](https://developer.android.com/reference/android/graphics/BitmapShader#FILTER_MODE_DEFAULT)。没有从文档复制实现代码。

## 验证

- Release/Debug 构建成功；48 项 JVM 测试通过。新增六项覆盖正常手机图像密度、内存边界、亚像素半径、跨半径边界连续性、平滑渐变亮度及半透明平色不暗化。
- Lint 0 错误、196 警告；同签名、非 debuggable、包名/版本、15 个参数布局 ID、57 项依赖及 12 项保留声明校验通过。
- 独立 API33 模拟器在真实 Android RuntimeShader GPU 路径运行测试，使用实际生产工厂创建输入。最近邻参照：32 档灰阶、相邻最大跳变 8；生产原图与低分辨率路径均为 249 档、最大跳变 1。截图为测试灰阶，不是 ColorOS 系统界面。
- SamplingProbeActivity 及其 exported 入口只在 src/debug，发行 APK 的 DEX 已校验不含此类。探针 APK 不加入分发包。
- 同一模拟器覆盖正式 APK 后检查 0/32/64 px 滑杆、实时预览与返回路径，未检测到应用崩溃。模拟器无 LibXposed，配置持久化和 SystemUI 运行效果未由这些测试证明。
- 实际 Kotlin 滤镜生成的离线对照图检查通过。原始 ROM APK 未在原提供路径，9 项 DEX 合约本版未重跑；历史 1.0.2 证据不计作本版结果。

## 边界

未操作实体手机，未申请 Root 或重启作用域。已复现并修复最近邻放大的确定缺陷，尚未对用户设备进行完整画质、动态背景延迟、功耗和稳定性验收。共享按钮模糊仍是同系统参数的高斯近似，不保证厂商滤镜逐像素一致；独立材质的大半径稀疏采样算法不属于本次替换范围。

证据：docs/validation/1.0.4/。GPU 回归脚本：scripts/qa_sampling_gpu_emulator.py。APK SHA-256：90aae26895464e465e0af243b065e20db10bdeacf6d896f4ef6178791338b611
