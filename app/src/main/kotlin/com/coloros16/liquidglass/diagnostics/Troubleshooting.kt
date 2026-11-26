package com.coloros16.liquidglass.diagnostics

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.PowerManager
import android.os.Process
import com.coloros16.liquidglass.root.RootGrant
import io.github.libxposed.service.XposedService
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

internal object Troubleshooting {
    const val LAUNCHER = "com.android.launcher"
    const val SYSTEM_UI = "com.android.systemui"
    const val GLASS = "com.dreamglass.cos17"
    const val DESK = "com.voidream.dreamdesk"
    private val targets = setOf(LAUNCHER, SYSTEM_UI)
    private val packageName = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")

    enum class Level { OK, CHECK, CONFLICT, INFO }
    data class Row(val title: String, val detail: String, val level: Level)
    data class Checks(val rows: List<Row>, val iconEffectsEnabled: Boolean, val requiredScope: Set<String>)
    data class Section(val title: String, val rows: List<Row>)

    fun enabled(title: String, on: Boolean, location: String, optional: Boolean = false): Row =
        if (on) Row(title, "已开启", Level.OK)
        else Row(title, "未开启。$location", if (optional) Level.INFO else Level.CHECK)

    fun scan(context: Context, service: XposedService?, checks: Checks, allowRoot: Boolean? = null): List<Section> {
        val pm = context.packageManager
        val useRoot = allowRoot ?: RootGrant.canTryAutomatically(context)
        val active = if (useRoot) activeScopes() else null
        val ownScope = runCatching { service?.scope?.toSet() }.getOrNull()
            ?: active?.let { it[context.packageName].orEmpty() }
        val hostRows = targets.map { target ->
            component(pm, target, target in checks.requiredScope, useRoot)
        }
        val lsposed = if (service != null) Row("LSPosed 连接", "已连接。", Level.OK)
        else Row("LSPosed 连接", "应用暂未接到模块服务。请检查当前模块是否已启用。", Level.CHECK)
        val scopes = checks.requiredScope.map { target ->
            val label = targetLabel(target)
            when {
                ownScope == null -> Row(label, "无法核实本模块的实际作用域。", Level.INFO)
                target in ownScope -> Row(label, "系统组件已勾选。", Level.OK)
                else -> Row(label, "请在 LSPosed 中为本模块勾选这个组件。", Level.CHECK)
            }
        }
        val switches = if (context.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true) {
            checks.rows + Row("系统省电模式", "已开启，桌面玻璃图标可能暂停渲染。", Level.CHECK)
        } else checks.rows
        return listOf(
            Section("效果开关", switches),
            Section("组件 SHA-256 核对", hostRows),
            Section("本模块作用域", listOf(lsposed) + scopes),
            Section("其他作用于相同组件的模块", modules(context, active, checks.iconEffectsEnabled)),
        )
    }

    private fun targetLabel(target: String) = when (target) {
        LAUNCHER -> "系统桌面"
        else -> "SystemUI"
    }

    @Suppress("DEPRECATION")
    private fun component(pm: PackageManager, target: String, required: Boolean, useRoot: Boolean): Row {
        val label = targetLabel(target)
        val pkg = runCatching { pm.getPackageInfo(target, 0) }.getOrNull()
            ?: return Row(label, "无法读取 $target。" + if (required) "请确认已安装原厂组件。" else "", if (required) Level.CHECK else Level.INFO)
        val version = "${pkg.versionName ?: "版本未知"}（${pkg.longVersionCode}）"
        val info = pkg.applicationInfo
        val digest = ComponentApkCatalog.read(info?.sourceDir, useRoot)
        val splits = info?.splitSourceDirs?.size ?: 0
        val match = ComponentApkCatalog.match(target, pkg.versionName, pkg.longVersionCode, digest?.value, splits)
        val outcome = when (match) {
            ComponentApkCatalog.Match.EXACT -> "完整 APK 的 SHA-256 与已适配样本一致。"
            ComponentApkCatalog.Match.SPLITS_UNVERIFIED -> "主 APK 与已适配样本一致，另有 $splits 个拆分 APK 尚未核对。"
            ComponentApkCatalog.Match.SAME_VERSION_DIFFERENT_APK -> "版本号已收录，但 APK 的 SHA-256 不同。当前构建兼容性待确认。"
            ComponentApkCatalog.Match.UNLISTED -> "APK 的 SHA-256 未收录，兼容性待确认。"
            ComponentApkCatalog.Match.UNREADABLE -> if (useRoot) "无法读取 APK 或计算 SHA-256。"
                else "普通权限无法读取 APK。可授予 Root 后重查。"
        }
        val detail = buildList {
            add(version)
            add(outcome)
            if (digest != null) {
                add("主 APK SHA-256${if (digest.fromRoot) "（Root）" else ""}")
                add(digest.value)
            }
            if (!required) add("本模块未作用于该组件，此项仅供参考。")
        }.joinToString("\n")
        val level = if (!required) Level.INFO else if (match == ComponentApkCatalog.Match.EXACT) Level.OK else Level.CHECK
        return Row(label, detail, level)
    }

    @Suppress("DEPRECATION")
    private fun modules(context: Context, active: Map<String, Set<String>>?, iconEffectsEnabled: Boolean): List<Row> {
        val pm = context.packageManager
        val installed = runCatching { pm.getInstalledApplications(PackageManager.GET_META_DATA) }.getOrNull()
        val candidates = installed.orEmpty().associateBy { it.packageName }
        val declaredScopes = installed.orEmpty().asSequence()
            .filter { it.packageName != context.packageName }
            .mapNotNull { info ->
                val declared = declaredScope(pm, info)
                if (declared.isEmpty()) null else info.packageName to declared
            }.toMap()
        val rows = (declaredScopes.keys + active.orEmpty().keys - context.packageName).map { id ->
            val configured = active?.get(id).orEmpty()
            val declared = declaredScopes[id].orEmpty() - configured
            val overlaps = duplicate(context.packageName, id, configured, declared, active != null)
            val info = candidates[id]
            val label = info?.let { runCatching { it.loadLabel(pm).toString().lineSequence().firstOrNull()?.take(80) }.getOrNull() }
                ?.takeIf { it.isNotBlank() } ?: id
            val detail = buildList {
                if (label != id) add(id)
                if (configured.isNotEmpty()) add("LSPosed 中已启用：" + configured.joinToString("、", transform = ::targetLabel))
                if (declared.isNotEmpty()) add(
                    (if (active == null) "APK 声明：" else "APK 声明但当前未勾选：") +
                        declared.joinToString("、", transform = ::targetLabel)
                )
                if (overlaps) {
                    add(if (iconEffectsEnabled) "可能冲突：两个模块都能改绘桌面图标，建议只开启其中一个的图标效果。"
                    else "两者都提供桌面图标效果，同时开启时可能冲突。")
                }
            }
            val level = if (overlaps) Level.CONFLICT
                else if (configured.isNotEmpty()) Level.CHECK else Level.INFO
            Row(label, detail.joinToString("\n"), level)
        }.sortedWith(compareBy<Row> { if (it.level == Level.CONFLICT) 0 else if (it.level == Level.CHECK) 1 else 2 }.thenBy { it.title })
        val source = if (active != null) "已核对 LSPosed 中启用的作用域。作用域仍不能证明每个 Hook 已执行。"
            else "当前只能扫描已安装 APK 声明的作用域。无法读取 LSPosed 实际启用清单时，手动勾选且未声明的模块可能遗漏。"
        return listOf(Row("检测范围", source, Level.INFO)) + if (rows.isNotEmpty()) rows else
            listOf(Row("扫描结果", if (installed == null) "无法读取已安装应用。" else "在当前检测范围内未发现其他相关模块。", Level.INFO))
    }

    fun duplicate(self: String, other: String, configured: Set<String>, declared: Set<String>, activeKnown: Boolean): Boolean =
        ((self == GLASS && other == DESK) || (self == DESK && other == GLASS)) &&
            LAUNCHER in (if (activeKnown) configured else configured + declared)

    @Suppress("DEPRECATION")
    private fun declaredScope(pm: PackageManager, info: ApplicationInfo): Set<String> {
        val meta = info.metaData?.get("xposedscope")
        val legacy = when (meta) {
            is String -> declaredTargets(meta)
            is Int -> runCatching {
                val res = pm.getResourcesForApplication(info)
                runCatching { res.getStringArray(meta).flatMap { declaredTargets(it) }.toSet() }
                    .getOrElse { declaredTargets(res.getString(meta)) }
            }.getOrDefault(emptySet())
            else -> emptySet()
        }
        val known = if (info.packageName == GLASS) targets else if (info.packageName == DESK) setOf(LAUNCHER) else emptySet()
        if ((legacy + known).size == targets.size || info.packageName == DESK) return legacy + known
        val modern = (listOf(info.sourceDir) + info.splitSourceDirs.orEmpty()).filterNotNull()
            .flatMap { path ->
                runCatching {
                    ZipFile(path).use { apk ->
                        val entry = apk.getEntry("META-INF/xposed/scope.list") ?: return@use emptySet()
                        if (entry.size !in 1..65_536) return@use emptySet()
                        apk.getInputStream(entry).use { stream ->
                            val bytes = stream.readNBytes(65_537)
                            if (bytes.size > 65_536) emptySet() else declaredTargets(bytes.toString(Charsets.UTF_8))
                        }
                    }
                }.getOrDefault(emptySet())
            }
        return legacy + modern + known
    }

    fun declaredTargets(contents: String): Set<String> =
        contents.lineSequence().flatMap { it.substringBefore('#').split(Regex("[;,\\s]+")).asSequence() }
            .filter { it in targets }.toSet()

    private fun activeScopes(): Map<String, Set<String>>? {
        // Android uses 100,000 UIDs per user. The system UI normally runs as user 0.
        val user = Process.myUid() / 100_000
        val query = "SELECT DISTINCT modules.module_pkg_name, scope.app_pkg_name " +
            "FROM modules JOIN scope ON scope.mid = modules.mid WHERE modules.enabled = 1 AND " +
            "((scope.app_pkg_name = 'com.android.launcher' AND scope.user_id = $user) OR " +
            "(scope.app_pkg_name = 'com.android.systemui' AND scope.user_id IN (0,$user))) LIMIT 201;"
        val command = "command -v sqlite3 >/dev/null && sqlite3 -batch -readonly -init /dev/null " +
            "/data/adb/lspd/config/modules_config.db \"$query\""
        val process = runCatching {
            ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        }.getOrNull() ?: return null
        val read = FutureTask { process.inputStream.use { it.readNBytes(65_537) } }
        Thread(read, "scope-diagnostics-read").start()
        return try {
            if (!process.waitFor(2_000, TimeUnit.MILLISECONDS) || process.exitValue() != 0) return null
            val output = read.get(500, TimeUnit.MILLISECONDS)
            if (output.size > 65_536) return null
            val rows = output.toString(Charsets.UTF_8).lineSequence().filter { it.isNotBlank() }.map { it.split('|') }.toList()
            if (rows.size > 200 || rows.any { it.size != 2 || !packageName.matches(it[0]) || it[1] !in targets }) return null
            rows.groupBy({ it[0] }, { it[1] }).mapValues { (_, values) -> values.toSet() }
        } catch (_: Exception) {
            null
        } finally {
            if (process.isAlive) process.destroyForcibly()
            read.cancel(true)
        }
    }
}
