# 1.1.9-debug5 本地验证

265 项 JVM 测试全部通过；Debug／Release 构建通过；Lint 0 Error／Fatal，352 Warning。签名沿用既有升级证书。

隔离 Android 33 模拟器运行 debug-only `LiveContentBackdropProbeActivity`，使用生产 `ClearButtonGlassSurface`、`LiveContentGlassNode` 核心和程序生成的输入，结果见 report.json 与 audit.json：

- 没有原生 shader、原生 shader 全透明时，胶囊内部仍有可见 alpha，圆角外保持透明。
- 直接绘制路径不依赖原生 Paint.alpha，输入颜色变化会更新到画面；原生 Paint 的 shader 和 alpha 都没有修改。
- 原实时节点的内容、源移动、宿主偏移、裁剪与无额外帧请求检查继续通过。
- clear-*.png 均为自有程序测试图，不含用户截图。像素读回仅用于 debug 验证，生产绘制没有背景截图或 CPU 模糊。

DEX 审计确认新直接绘制类存在，透明清理背景助手未回流，计算器键未回流；Release 不包含 GPU 探针。许可附件与源码匹配，没有新增依赖。

未对实体设备进行 ADB、安装、重启或 UI 操作。ColorOS 的输入解析、隔离动画状态、相册弹窗 Activity 关联、跨窗口实际显示与功耗仍需真机确认。对黑色编辑画布准确取样不应产生原本不在该区域的照片纹理。
