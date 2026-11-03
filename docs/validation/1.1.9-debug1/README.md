# 1.1.9-debug1 验证

本轮按用户要求不连接 ADB。228 项 JVM 测试、Debug／Release 构建通过；Lint 无 Error／Fatal，仍保留非阻断 Warning，数量及 APK 哈希见 audit.json。

会员门控测试覆盖旧配置 true、默认 true、旧确认、免费功能保留、版本名／编号分别变化、token 导入与导出、延迟 editor、类型混入、撤销、listener，以及跨本地／框架存储同步、失败提交和失败读取。UI 与运行端均使用同一版本策略；确认是自报，不验证真实付款。

APK 签名与既有升级证书相同；检查 DEX 不含计算器 Adapter／ForegroundDraw／专用租约、旧 LauncherAnimHook 或 calculator_glass_enable，默认作用域也不含计算器。布局 XML 均可解析。

桌面大文件夹、负一屏 header／shortcut_card，以及相册导航／编辑／保存菜单按本地实际 APK 的类、资源、字段和方法核对。匹配不代表实际 OEM 背景输入或画面验收通过。

本轮未运行 GPU 探针或真实 UI 测试、未安装、未重启，也未测功耗或长期稳定性。五张私人截图、厂商 APK 及反编译结果不纳入分发。新包需要手动安装并重新加载作用域；会员确认后仍需自行开启所需开关。
