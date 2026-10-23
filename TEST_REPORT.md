# DreamGlass 1.0.1 验证记录

日期：2026-10-23。包名 com.dreamglass.cos17；versionCode 1000001；保持原签名。

## 本次修改

1. 参数页将重复的大标题合并进工具栏，只保留一行提示。预览移到预设条上方，高度由 156dp 降为 104dp，横屏使用 64dp；参数区继续独立滚动。
2. 「材质参数」首项增加全局「按钮整体透明度」0–100%。默认 0% 不改变既有材质；100% 仅隐去按钮玻璃层。RGB 与 alpha 同步缩放，文字和图标不设 View alpha。按按钮宿主/祖先判定，排除滑杆、媒体和独立音量窗口；通知和 Launcher 沿用默认不透明度。
3. 自动文字反色先找到文字所在的最小有效玻璃区域，采样其中部背景。捕获 worker 用已有像素缓冲构建最大 64×192 格的不可变线性 sRGB 亮度积分图，主线程只查询区域均值并按帧/区域缓存；不在 TextView 绘制时调用 Bitmap.getPixels。缓存换帧清除，避免持有多份旧图。
4. 按黑/白与该区域背景的对比度选择颜色，保留原 alpha、彩色文字和状态栏原色。自动模式优先于旧版默认强制白字；自动模式关闭后仍可使用强制白字/横幅跟随。缺少有效背景时保持原色，不改用全屏统一色。内部压暗只作近似补偿，未声称对任意动态/复杂背景保证 WCAG 达标。

## 验证

- Release 构建成功；32 项 JVM 测试通过，包含相邻深浅按钮不同文字颜色、纵向区域、部分格查询、透明/无效背景、sRGB/alpha 和透明度场景排除；lint 0 错误、192 警告。
- 给定 ROM 的 9 项 DEX 合约通过，原独立音量保护、截图入口和滑杆轮廓测试继续通过。
- 真实 AGSL 的 FULL/BASE 版本在桌面 Skia 上编译/渲染。0/25/50/75/100% 材质不透明度共 10 项检查通过，颜色和 alpha 按相同比例缩放，未产生透明边缘 RGB 残留；默认不透明度下与 1.0.0 的折射等光学代码比较一致。
- 独立 API33 模拟器六张实际应用截图，验证竖屏、透明度 0/100/中间档预览、返回、1.3 倍字体和横屏。竖屏参数滚动区域占屏高 67.0%。界面树每次重新导出，旋转时不复用旧树。
- APK 产品名、1.0.1 / 1000001、非 debuggable、完整性、签名、57 项依赖及离线许可内容校验通过。

## 边界

本次未连接或安装实体手机，未申请 Root 或重启作用域。模拟器无 LibXposed 框架，因此只证明应用交互/Android GPU 预览，不证明框架配置持久化和 ColorOS SystemUI Hook 运行效果。局部取色和按钮透明度仍需更新后由用户手动重新加载 SystemUI 检查；独立音量面板不属于此透明度调节范围。

对比度计算依据：https://www.w3.org/WAI/WCAG21/Understanding/contrast-minimum 。原开源来源及许可见 THIRD-PARTY-NOTICES.md。

证据：docs/validation/1.0.1/。复现脚本：scripts/check_release_package.py、scripts/check_rom_contracts.py、scripts/validate_surface_shader.py、scripts/qa_compact_material_emulator.py。

APK SHA-256：9765fb0c10ddc0747bab6f522b6c32805328a149ec272e2fc03ca4a523187ef6
