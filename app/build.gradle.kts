import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// 签名配置从 local.properties 读取（不入库）；缺失时 release 构建无签名，不阻塞编译
val signingProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.coloros16.liquidglass"
    buildFeatures { buildConfig = true }
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.dreamglass.cos17"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        // Published versions use SemVer; increment this code for every distributed update so
        // Android and the module framework can recognize the new APK. Do not publish timestamp suffixes.
        versionCode = 1_001_003
        versionName = "1.1.3"

    }

    signingConfigs {
        create("release") {
            val sf = signingProps.getProperty("signing.storeFile")
            if (sf != null) {
                storeFile = rootProject.file(sf)
                storePassword = signingProps.getProperty("signing.storePassword")
                keyAlias = signingProps.getProperty("signing.keyAlias")
                keyPassword = signingProps.getProperty("signing.keyPassword")
            }
        }
    }

    buildTypes {
        debug { versionNameSuffix = "-debug8" }
        release {
            // 框架阶段先不开启 minify，避免 R8 误删 META-INF/xposed 清单文件；
            // 开启 minify 时需配合 proguard-rules.pro（见 LibXposed README 要求）。
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
                .takeIf { cfg -> cfg.storeFile?.exists() == true }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // LibXposed API：仅编译期可见（compileOnly），打包时排除，避免与框架注入冲突。
    compileOnly(libs.libxposed.api)

    // LibXposed Service：模块 UI 进程连接框架、读写框架配置存储。
    // 官方 aar(io.github.libxposed:service/interface:102.0.0) 声明 minCompileSdk=37，本工程 compileSdk=36
    // （AGP 8.13.2 上限）直接引 Maven aar 会校验失败；故用官方 aar 解压后把 aar-metadata 的
    // minCompileSdk 改为 36 重打包为本地 aar（app/libs/），按文件依赖引用。
    // 两 aar 均走 mergeExtDexDebug（带 global-synthetics consumer），可正常 desugar 其中 Java 17 record 类
    // （HotReloadResult）；XposedProvider 的 manifest 声明由 service aar 自动合并。
    implementation(files("libs/libxposed-interface-102.0.0.aar"))
    implementation(files("libs/libxposed-service-102.0.0.aar"))

    // 模块自身 UI：DreamGlass 配色与 Material 参数控件。
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
}

// Release compliance inventory: exact resolved runtime artifacts, including transitive dependencies.
tasks.register("writeReleaseComponentInventory") {
    doLast {
        val rows = configurations.getByName("releaseRuntimeClasspath").resolvedConfiguration.resolvedArtifacts
            .map { artifact -> "${artifact.moduleVersion.id.group}\t${artifact.name}\t${artifact.moduleVersion.id.version}\t${artifact.file.absolutePath}" }
            .sorted()
        val output = layout.buildDirectory.file("reports/release-components.tsv").get().asFile
        output.parentFile.mkdirs()
        output.writeText(rows.joinToString("\n", postfix = "\n"))
        println("Release component inventory: ${rows.size} resolved artifacts")
    }
}
