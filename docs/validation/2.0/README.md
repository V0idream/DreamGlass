# DreamGlass 2.0 发行验证

2026-11-03。基于 1.1.9-debug5 当前实现，仅调整正式版本与发布材料。

- Release 构建与 265 项 Release JVM 测试通过，0 失败／错误；Lint 0 Error／Fatal，352 Warning。
- 发行 APK 的 versionName 为 2.0，versionCode 为 2000000；不是 debuggable，DEX 不含实时内容 debug 探针，计算器键未回流。
- 对齐、签名及签名证书校验通过，与既有覆盖升级证书一致。证书与 APK SHA-256 见 audit.json。
- GPL／Apache／来源声明随 APK 提供且与源码相符，无新增依赖；完整源码包附带 58 份依赖源码，打包时另校验归档内容。
- 本轮没有向实体设备安装、重启或操作 ADB；未重复运行 GPU 探针。相同生产实现的 debug5 已执行 11 项隔离 GPU 核心断言，其记录保留在 ../1.1.9-debug5/。

用户反馈的保存面板效果、清理按钮观感与进入延迟、背景刷新延迟及混合常驻通知清除粒子尚未解决；正式发布不表示这些问题已通过真机验收。
