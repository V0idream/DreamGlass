# DreamGlass 1.0.0 验证记录

日期：2026-10-23。产品名称 DreamGlass；支持声明 For ColorOS 17。versionCode 1000000，包名 com.dreamglass.cos17。

## 本次范围

重做应用界面、参数页组织、品牌和离线许可页，移除旧联系人/收款/升级入口。本版未重新修改 Refine4 提交 3bde6df 的 Hook 与 AGSL 算法，逐文件及真实着色源码比较通过。参数页保留 15 份已有布局的全部绑定 ID；关于页有意替换为新来源入口。

## 已完成的验证

- Release assembleRelease、testReleaseUnitTest、lintRelease 全部通过；26 项 JVM 测试，lint 0 错误、191 项警告。警告清单未声称全部解决。
- 给定 SystemUI 17.99.02、Launcher 17.3.12 与 API37 framework 的 9 项 DEX 合约通过，未将静态匹配称为运行验证。
- 独立 API33 x86_64 模拟器 emulator-5580，1080×2340：12 个页面场景通过，覆盖四页导航、参数子菜单返回、图标隐藏/恢复、关于页、离线 GPL/来源文本及 1.3 倍字体。实际 GPU 预览可见，未发现应用进程崩溃。
- 最终 APK 的产品名、1.0.0 / 1000000、非 debuggable、完整性及签名通过。沿用既有签名以支持覆盖升级；证书名称与构建类型的区别见 docs/BUILDING.md。
- APK 内 GPL/Apache/NOTICE/来源说明与源文件逐字节一致；57 个运行时依赖坐标及 12 项原始许可条目保留。对应源码包另含 58 份依赖/API 源码归档、Gradle wrapper、本地 AAR 和构建说明。
- 无旧收款资源、旧升级类或旧版重复许可资产；没有打入本机配置、签名私钥、系统 APK 或设备日志。

## 实机边界

本次只操作新建的独立模拟器，没有连接、安装或控制实体手机，没有申请 Shell Root、改作用域或重启 SystemUI。Refine3 的玻璃效果已有用户确认；Refine4 的滑杆/内圈/边缘/独立音量窗口修补及本次 1.0.0 尚未获得新的 ColorOS 真机验收。独立音量窗口需检查首次弹出、连按、拖动、展开多流、静音/蓝牙及收起动画。模拟器应用截图不代表系统控制中心效果。

Refine4 的桌面 Skia 与进度轮廓夹具仍见 docs/validation/refine4/；本版真实 AGSL 字符串与该基线完全一致。旧版本的设备/构建验证保留在 docs/PREVIEW2_*.md 中。

## 复现与证据

构建见 docs/BUILDING.md。静态发行核验：`python scripts/check_release_package.py --apk <APK> --aapt2 <aapt2> --output <JSON>`（该脚本需 Git 历史中的 3bde6df 作基线；纯源码 ZIP 的构建不依赖 Git）。模拟器界面脚本见 scripts/qa_release_emulator.py，固定 emulator-5580，禁止默认选择手机。ROM 合约见 scripts/check_rom_contracts.py，ROM 样本由使用者另行提供，未随发行分发。

验证记录和应用 UI 截图：docs/validation/1.0.0/。

APK SHA-256：0ace1ecb79fbbda61ae1cc7b1c835dcdbbf1d7a7af21975ce4e11bf426edcf6e

证书 SHA-256：12db3813c08e0d85d97884f7bf87e337d91c9e376e470a41e086a1f682402a96
