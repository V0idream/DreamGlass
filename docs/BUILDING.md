# 构建 DreamGlass 1.0.0

源码包包含完整应用源码、Gradle wrapper、本地 LibXposed AAR 和许可文档，不需要私有仓库凭据或原作者的签名密钥。

1. 安装 JDK 17、Android SDK Platform 36、Build Tools 35 或兼容的已声明版本。
2. 设置 ANDROID_HOME / JAVA_HOME，或在不提交的 local.properties 中指定 sdk.dir。
3. 在项目根目录运行 `./gradlew :app:assembleRelease :app:testReleaseUnitTest :app:lintRelease`（Windows 使用 gradlew.bat）。依赖首次解析需要联网；已缓存时可使用 --offline。
4. 官方 Maven 仓库不可用时可增加 `-PuseMavenMirror=true`，该选项只切换公开依赖镜像。
5. 未提供签名配置时 release APK 为未签名文件。接收者可用自己的密钥签名运行；覆盖已安装的官方包需要相同签名，本项目不分发私钥。
6. 运行 `:app:writeReleaseComponentInventory` 后，用 scripts/generate_legal_bundle.py 生成许可资产；源码包已包含此版本生成结果，不要求构建时访问审计者的缓存。

发行 APK 的 applicationId 保留 com.dreamglass.cos17，显示名称为 DreamGlass；版本来自 Gradle BuildConfig。仅在兼容的 LSPosed / API102 框架和指定 ROM 上启用模块能力。普通 Android 模拟器只用于应用界面验收，不证明 ColorOS Hook 效果。

源代码包包含修改后的本地 AAR，修改与重建说明见 app/libs/README.md。其他依赖及来源见 licenses/dependency-inventory.json。

third_party_sources/ 包含实际 57 个运行时坐标及仅编译 API 的 58 份对应版本源码归档。manifest.json 记录原始发布地址与 SHA-256；这些归档不打入 APK。可运行 scripts/fetch_dependency_sources.py --output third_party_sources 重新收集。

本次 release 构建的 APK 不启用 android:debuggable。为保持早期安装版本的覆盖升级，发行包沿用原签名证书，其证书 DN 为 Android Debug；该名称不代表使用 debug 构建。签名私钥始终不属于源码包。证书 SHA-256：12db3813c08e0d85d97884f7bf87e337d91c9e376e470a41e086a1f682402a96。
