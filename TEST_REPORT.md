# DreamGlass 1.0.2 验证记录

日期：2026-10-24。包名 com.dreamglass.cos17；versionCode 1000002；保持原签名。

## 本次修改

- 将 1.0.1 误做成玻璃层淡出的「按钮整体透明度」替换为「按钮背景模糊」0–64 px。滑杆直接覆盖 QS 按钮材质的 blurRadius，不调整 View alpha、图标、文字或材质输出 alpha。
- 移除 uMaterialOpacity 及其 RGB/alpha 乘法，恢复 1.0.0 的 AGSL 光学源码；未改折射、高光及轮廓算法。
- 新键为 button_blur_radius。旧 button_transparency 完全不再读取，也不将旧数值解释为模糊；新键缺失、负数或非有限值时继承既有按类/全局模糊。上限 64；非按钮保留原值。排除滑杆、媒体、通知、Launcher 和独立音量窗口。
- 保留紧凑参数页、按局部背景决定黑白字、原滑杆轮廓与独立音量保护。现有通用模糊滑杆继续控制其他材质；新增按钮滑杆是 QS 按钮的全局覆盖。

## 验证

- Release 构建成功；36 项 JVM 测试通过，含四项新增解析/继承/范围检查；lint 0 错误、192 警告。
- 给定 ROM 的 9 项 DEX 合约通过。
- FULL/BASE 真实 AGSL 在桌面 Skia 编译和渲染，0/16/32/64 px 共 8 项检查通过：背景 RGB 采样改变，最大 alpha 差小于 0.00001，输出有限且保持预乘约束。既有形状、细边缘与进度裁剪检查继续通过。桌面使用程序生成背景，图中较大半径的稀疏采样纹理不代表真实系统画面。
- 独立 API33 模拟器验证新控件与 0/32/64 px 标签、实际 Android GPU 预览、返回路径；旧透明度控件消失。人工检查两端截图，64 px 时材质仍可见。参数滚动区占屏高 67.0%。
- APK 名称、版本、非 debuggable、ZIP 完整性、同签名、57 项依赖与 12 项保留声明逐字节校验通过。

## 边界

未连接、安装或操作实体手机，未申请 Root 或重启作用域。模拟器无 LibXposed，故不证明框架配置持久化或 ColorOS SystemUI 的实时加载、视觉质量、性能与稳定性。需要用户更新后按自己的方式重新加载 SystemUI 进行实机验收。0 px 表示不增加此材质模糊，背景捕获原有降采样仍可能影响清晰度。大半径继承既有采样算法，尚未进行实机画质或功耗测试。

证据：docs/validation/1.0.2/。复现脚本：scripts/check_release_package.py、scripts/check_rom_contracts.py、scripts/validate_surface_shader.py、scripts/qa_button_blur_emulator.py。

APK SHA-256：9a8ca298fbb667098398ae3c3b52f66929371877a1e4eee591d3f91b3fb73919
