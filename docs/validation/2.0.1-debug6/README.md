# 2.0.1-debug6 — 流体云展开崩溃修复

## 已定位的异常路径

debug5 将实际背景包在 BoundedGlassDrawable 中。流体云初次展开、等待本次抓屏时正常回退到原生 BackgroundBlurDrawable 或含它的 LayerDrawable；回退却无条件执行 `setColorFilter(null)`。Android 的原生 BackgroundBlurDrawable 明确在该方法抛出 IllegalArgumentException，该异常原先位于材质渲染保护边界之外，会从背景绘制传播到宿主。

平台契约来源：[AOSP BackgroundBlurDrawable.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/com/android/internal/graphics/drawable/BackgroundBlurDrawable.java)。本轮没有读取手机崩溃栈，不能将此代码层可复现异常当作已确认的真机唯一故障。

## 修改

- 仅在颜色滤镜实际改变时尝试同步，默认 null 不再触发不支持的原生接口。
- 不支持的可选绘制属性隔离处理，仍然绘制原生背景；原生回退绘制异常也不会逃逸至 SystemUI。
- 保留原生背景 alpha、bounds、状态与 callback；不恢复即时 RenderNode bounds，不重复应用 alpha。
- 保留新画面准备后进入共用 GPU 玻璃的路径，不关闭流体云玻璃或回滚此前通知修复。
- versionCode 2000006，2.0.1-debug6，保留原升级证书；debug5 APK 未覆盖。

## 验证

- 334 项 JVM 测试通过，无失败、错误或跳过。
- Debug 构建和 Release Lint 通过，358 Warning、0 Error／Fatal。
- 本地隔离 Android 33 模拟器 emulator-5580 执行生产 BoundedGlassDrawable 的 17 项 GPU 回退契约。测试替身精确模拟原生颜色滤镜接口即使传入 null 也抛异常的行为，并先断言旧调用确实抛出异常。修复后缺源回退、半透明首帧、非空不支持滤镜、LayerDrawable 回退、异常隔离及获得新帧后的玻璃切换通过。
- 签名与包内版本校验通过，详见 audit.json；像素断言结果见 android-gpu-fallback.json。

测试替身不是 ColorOS 的真实 BackgroundBlurDrawable。未操作、安装或重启手机，未获得厂商真机展开效果与崩溃验收；未上传 GitHub。无新增依赖或打包厂商文件。
