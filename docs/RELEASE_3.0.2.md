# DreamGlass 3.0.2 · For ColorOS 17

本版优化桌面图标的重复计算与无效渲染工作，保留实时背景随图标位置和壁纸偏移更新。`versionName=3.0.2`，`versionCode=3000004`，沿用现有签名，可覆盖此前正式版和 3.0.2-debug1，保留配置。

- 零模糊复用已缓存的清晰壁纸，取消栅格化路径中无意义的微小模糊；零折射跳过弯折计算，完全透明的图标跳过绘制准备。
- 复用未变化的着色参数、壁纸映射、相同半径的模糊效果与材质参数，减少逐帧分配和原生调用；预计算着色器中的采样比例。
- 缓存图标类型的反射访问器，保留组件、用户和快捷方式实际值的实时读取；自定义图片不请求其绘制路径用不到的壁纸。
- 保留原生动画、预制／实时两种模式及预制回退，没有增加抓屏、持续刷新定时器或空闲轮询。定制图标仍按需下载，不默认内置。

更新后按自己的方式重新加载相应作用域；软件不会自动申请 Root 或重启桌面／SystemUI。

## 验证范围

正式 Release 构建、413 项 Release JVM 测试、Lint（0 错误）及升级签名校验通过。144 组实际生产着色器的离线前后对比覆盖零／非零模糊、折射、透明度和不同图案类型，最大通道差 2/255。上述验证不代表已测量真机帧率或耗电；本次未安装、未操作手机，未执行 Android GPU 探针。详见 [验证记录](validation/3.0.2/README.md)。

## 源码与许可

基于 [AYwlilwYA / ColorOS16-LiquidGlass](https://github.com/AYwlilwYA/ColorOS16-LiquidGlass)，整体按 GNU GPLv3 发布；部分着色算法来自 [Kyant0 / AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass)，保留 Apache-2.0 声明。下载同时提供 APK、对应完整源码、依赖源码附件、许可及 SHA-256 校验和。定制图标库见 [DreamGlass-Icons](https://github.com/V0idream/DreamGlass-Icons)。
