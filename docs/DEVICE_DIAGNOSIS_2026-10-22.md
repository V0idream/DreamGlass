# Preview2 设备诊断与修复

日期：2026-10-22。目标 PJZ110，ColorOS 17.0.0.103(CN01)，Android API 37。

## 已确认原因

1. 实机 framework.jar 的旧式截屏接口在 `android.window.ScreenCaptureInternal`。旧路径 `android.window.ScreenCapture$DisplayCaptureArgs$Builder` 和 `$CaptureArgs` 查找失败。新增 CaptureApi 解析同一完整类族，API 37 优先 Internal，旧系统回退原类族，避免参数与监听器混用。
2. 宿主 RemotePreferences 为只读，实现会抛出 `UnsupportedOperationException: Read only implementation`。运行快照、日志摘要和已发现类经包名/UID 校验的模块 ContentProvider 存入设备保护存储，设置仍经框架写入，诊断读取不要求 Root。

实际设备描述符：

```text
ScreenCaptureInternal.createSyncCaptureListener() -> ScreenCaptureInternal$SynchronousScreenCaptureListener
ScreenCaptureInternal.captureDisplay(ScreenCaptureInternal$DisplayCaptureArgs) -> ScreenCaptureInternal$ScreenshotHardwareBuffer
IWindowManager.captureDisplay(int, ScreenCaptureInternal$CaptureArgs, ScreenCaptureInternal$ScreenCaptureListener) -> void
```

## 产品修复

- 四个主页面：概览、材质、诊断、设置。高级子菜单关闭时回到 DreamGlass 设置页；动画参数子页先回到动画设置。
- 固定桌面与 SystemUI 两个 Root 重启操作。仅用户在应用内点击才运行 su；先校验目标 UID，再终止目标 PID，并检查 PID 变化。没有 Root Shell 授权、开机自动执行或任意命令输入。
- 独立 LauncherAlias 隐藏/恢复桌面图标。真实 DreamActivity 的 MODULE_SETTINGS 保留，可经 LSPosed 模块设置打开。直接用 am 的隐式启动缺少 DEFAULT 匹配；管理器应查询 MODULE_SETTINGS 并显式启动该 Activity。

## 验证与边界

- 普通 ADB 覆盖安装成功，保留配置。
- ADB 实际验证材质子菜单进入和返回，新主设置页正确恢复。
- 实际关闭图标后 LAUNCHER 查询无结果，MODULE_SETTINGS 查询仍有 DreamActivity；打开图标后 LauncherAlias 恢复。测试结束图标保持显示。
- 用户随后明确反馈“测试通过”。该反馈作为本版设备验收记录；未将其扩展为长期性能、所有子功能或所有 ROM 的验证。
- 本轮自动操作未申请本应用 Root、未重启作用域，未使用 ADB Shell Root。用户可手动使用上述按钮。
- 普通 ADB 取得的私有日志、设备框架、用户配置与截图不随源码公开。
