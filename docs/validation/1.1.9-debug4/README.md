# 1.1.9-debug4 验证范围

2026-11-03。本地候选，未向实体设备安装或进行实体手机 ADB 操作。

## 已执行

- 265 项 JVM 测试，0 失败／错误；新增内容图独立性检查，拒绝自身、祖先、后代与异常循环。
- Debug、Release 构建成功；Lint 0 Error／Fatal、352 Warning。
- 隔离 Android 33 模拟器直接执行生产 `LiveContentGlassNode` 与 `NativePaintShaderLease`：实际 HardwareRenderer 复用同一渲染器，验证内容颜色更新、原生源位置更新、非零宿主坐标和源坐标分别处理、圆角外透明、异常后原生 shader 恢复。纯内容引用刷新不请求下一帧，避免自循环重绘。
- 探针结果为 [report.json](report.json)，程序生成的测试图为 initial.png、native-content-changed.png、native-content-moved.png、host-origin-moved.png；不含用户图片。读回仅用于 debug 探针，生产渲染不读回背景。
- DEX 审计确认已删除透明清理背景助手，包含新实时节点／相册源／原生按钮材质类；计算器配置未回流。Release 不含新增探针。沿用覆盖升级签名，没有新增第三方依赖。

## 未执行

ColorOS 的相册 ViewWrapper 源发现、独立对话框 Activity 关联、桌面原生 LayerBlurDrawable 着色输入与清理按钮外观，均不能在普通 Android 模拟器验收。实际厂商界面位置、相册保存操作、后台清理、耗电与长期稳定性仍需真机确认。

`audit.json` 中各实体设备与 OEM 验收标记均为 false。测试通过仅说明明确列出的本地边界，不能替代用户对可见效果的确认。
