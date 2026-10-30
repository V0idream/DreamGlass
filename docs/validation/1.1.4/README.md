# 1.1.4 多任务粒子候选

发布版本1.1.4；实机测试包1.1.4-debug1。新增「空间效果 → 删除多任务卡片时粒子飘散」，默认关闭，速度独立调整为0.35×–3×。

普通ADB只读提取桌面17.3.12（versionCode170030012），SHA-256 `af89139f18857f5c9e130cd492f08dc1a3e26b131aec74490432b2c590ac972e`。核对实际入口：ja.g.onDragEnd → no.g.onDragEnd → mCurrentAnimation.startWithVelocity；只接同一playback、向上且goingToEnd=true的删除分支，排除pull-down reset与取消回弹。ja.g.contineDragEnd可改为startWithVelocity(false)，该分支不触发。

单卡程序关闭限定removeTask=true，排除拆分选择；清空接已筛选卡片的PendingAnimation开始/取消，不自行删除或强停任务。原生方法继续执行，未知契约保留原生。

复用通知粒子的RenderNode/HardwareRenderer快照与AGSL消散器，添加向上方向；没有CPU模糊、像素回读或持续抓屏。最多两张，单张硬件纹理限制640×960；源未及时就绪则跳过，避免卡片删除后重新出现。只在overlay就绪后隐藏源，保留原生后续alpha，取消/结束恢复所持状态。每张效果固定采用确认时的速度，准备超时与完整消散时长分开。

124项JVM回归通过。API33/SwiftShader模拟器多任务GPU探针验证确认前不播放、取消保留源、GPU接管、保留后续原生alpha、点击、自然完成、并发上限、源删除后继续、停止清理、向上飘散及终点全透明。旧通知方向探针覆盖与上一版一致。仅使用合成卡片，不关闭真实任务。

经用户授权普通ADB覆盖安装，用户手动重启桌面。核对新增选项原先关闭后按授权开启，仅删除DreamGlass自身任务卡片。实际状态出现原生确认上划删除和GPU向上飘散，纹理165×960（侧边部分可见卡片），自身卡片正常移除；随后用户确认看到效果并认可。没有点击清空或删除其他应用。清空整组卡片仍缺少本轮实机体验验收，未测量功耗或长期稳定性。此目录不含私有任务缩略图、手机截图、OEM APK或原始厂商源码。
