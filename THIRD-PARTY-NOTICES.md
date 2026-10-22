# 第三方组件与许可声明

本项目包含派生自第三方开源项目的代码。按原项目许可证要求，声明如下。

## AYwlilwYA / ColorOS16-LiquidGlass

- 来源：https://github.com/AYwlilwYA/ColorOS16-LiquidGlass
- 本次基线：`aa96e1aeb98f4e3777f4bced265a1b86bf3e10cd`，上游 0.2.1。
- 许可证：GPL-3.0，完整文本见 `LICENSE`。
- DreamGlassforCOS17 是该代码的派生开发，保留原包命名空间以保持来源可追溯；安装包 ID 独立为 `com.dreamglass.cos17`。
- 本次修改：原创产品首页、材质预设、阶段诊断、桌面反射字段校正、结构能力识别、API 范围与资源生命周期修正、独立应用身份。修改文件可通过 Git 差异与 `docs/DEVELOPMENT.md` 追踪。
- 原项目说明保存于 `docs/UPSTREAM_README.md`。许可证及原作者声明继续有效。

## 视觉和功能参考

ElbowHitColor 仅用于包信息核查及功能范围参考。没有反编译、复制或打包它的代码、资源或素材。
Z841973620/ColorOSLiquidGlass 仅查阅公开项目介绍，本次未复制其代码。

---

## Kyant0 / AndroidLiquidGlass

- **来源**：https://github.com/Kyant0/AndroidLiquidGlass
- **许可证**：Apache License 2.0
- **许可证副本**：[`licenses/Apache-2.0.txt`](licenses/Apache-2.0.txt)

**涉及文件**

| 文件 | 说明 |
|------|------|
| `app/src/main/kotlin/com/coloros16/liquidglass/liquidglass/LiquidGlassShader.kt` | 主模块液态玻璃着色器 |
| `demo/src/main/kotlin/com/coloros16/liquidglass/demo/liquidglass/LiquidGlassShader.kt` | 独立验证 demo（与主模块算法一致） |

**修改说明**

原项目为 Kotlin Multiplatform + Skiko（SkSL）实现。本项目将其液态玻璃着色算法**移植为 Android AGSL**（`RuntimeShader`），并针对 Android 渲染管线与 ColorOS 场景做了以下改动：

1. **着色器语言重写**：SDF 圆角矩形、光谱色散、定向边缘高光、vibrancy 等算法由 SkSL 改写为 AGSL。
2. **采样坐标改为手动换算**：Android 的 `BitmapShader` 其 `localMatrix` 在 `RuntimeShader` 的 uniform shader 中不生效，故改为显式传入 `uSourceRect` / `uSourceLowResRect`，由着色器内 `srcCoord()` 计算采样坐标。
3. **新增低分辨率采样路径**（`uSourceLowRes`），用于降采样模糊以降低开销。
4. **圆角半径与模糊半径分离**（`uCornerRadius` / `uBlurRadius`），适配系统控件的实际圆角。
5. **新增 ColorOS 适配参数**：遮罩色（`uMaskColor` / `uMaskAlpha`）、视差偏移（`uParallax`）、MetaBall 扩张区裁剪（`uMaskRect` / `uMaskCornerRadius`）等 uniform。
6. **CONIC 圆角**（ColorOS 平滑圆角）相关处理为原项目中所无。

按 Apache License 2.0 第 4 条，上述文件保留了原项目的版权与许可声明，并在此标注修改内容。

---

## 其他依赖

项目通过 Gradle 依赖的第三方库（AndroidX、Material Components、Kotlin 标准库、LibXposed API 等）遵循各自许可证，其声明随依赖产物分发。
