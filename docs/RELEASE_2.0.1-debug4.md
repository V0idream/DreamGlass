# DreamGlass 2.0.1-debug4 · For ColorOS 17

本次为调试预发布，保留 2.0 正式版，供验证通知材质、锁屏背景来源及前几轮修正。versionCode 为 2000004，沿用现有升级签名，覆盖安装保留配置。

## 本次修正

- 新通知的原生材质选择存在两条分支：Motion 和主题／低 Gaussian 设置下的 Non。补齐后一条限定于 heads-up 的转换，并覆盖先选择材质、后挂载窗口才创建 Drawable 的时序；创建失败保留原生背景，不会只移除底色。
- 收到可见通知、打开通知／控制中心、展开流体云时请求新背景，场景退出或熄屏后取消。普通页面切换且无玻璃场景时不启动抓屏；生产模糊继续由 GPU 处理。
- 锁屏和全天候显示改用独立锁屏壁纸提供器，接入原生壁纸回调并准备 GPU 输入，避免把锁屏前应用内容留作背景。
- 相册保存大面板接入实际原生背景 Drawable 绘制和灰色 overlay 租用；多任务清理按钮补原生入场屏蔽期的绘制；混合常驻／普通通知按实际删除提交播放粒子，保留常驻通知及原生回调。
- 独立音量面板的原生模糊半径限制为 16 px，保留原生遮罩、填充及布局。
- 会员确认跨版本保留，仅在启用会员功能时提示，不在普通启动时提示。

## 检查与使用

329 项 JVM 单元测试、Debug／Release 构建、Release Lint 和 10 项本地 ROM 源码契约检查通过。沿用 debug3 已执行的隔离 GPU 壁纸、实时内容和清理生命周期结果；本轮未重复这些 GPU 探针。

本次没有操作或安装到实体手机。上述本地检查不能确认厂商 Hook 的实际命中、画面效果、耗电或长期稳定性；这些仍需实体 ColorOS 17 测试。通知保留原生的禁用模糊／省电安全状态，不强行启用所有原生能力。

安装后需自行重新加载相应作用域。软件不自动申请 Shell Root 或重启系统进程。具体检查见 [验证记录](validation/2.0.1-debug4/README.md)。

## 来源与许可

DreamGlass 基于 [AYwlilwYA / ColorOS16-LiquidGlass](https://github.com/AYwlilwYA/ColorOS16-LiquidGlass)，整体按 GNU GPLv3 发布；部分着色算法来自 [Kyant0 / AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass)，保留 Apache-2.0 声明。没有新增第三方依赖。

附件提供对应完整源码、58 份依赖源码和校验和，许可及修改声明见 LICENSE、NOTICE、THIRD-PARTY-NOTICES.md、MODIFICATIONS.md。会员及赞赏安排不改变开源许可。
