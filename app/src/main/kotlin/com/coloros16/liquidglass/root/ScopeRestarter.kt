package com.coloros16.liquidglass.root

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Only explicit in-app actions reach su. No receiver, intent or user text can supply commands. */
object ScopeRestarter {
    enum class Scope(val pkg: String, val label: String) {
        LAUNCHER("com.android.launcher", "系统桌面"), SYSTEM_UI("com.android.systemui", "SystemUI")
    }
    data class Result(val ok: Boolean, val message: String)
    private val busy = AtomicBoolean()

    fun requestAccess(): Result = execute("id -u", false)

    fun restart(context: android.content.Context, scope: Scope): Result {
        // pkg comes exclusively from the enum above, never from an Intent or text field.
        val pkg = scope.pkg
        val expectedUid = try { context.packageManager.getApplicationInfo(pkg, 0).uid }
        catch (_: Exception) { return Result(false, "无法确认目标包身份，未执行重启") }
        val reopen = if (scope == Scope.LAUNCHER)
            "am start -a android.intent.action.MAIN -c android.intent.category.HOME -p com.android.launcher >/dev/null 2>&1" else ":"
        val command = """
            [ "${'$'}(id -u)" = "0" ] || { echo ROOT_DENIED; exit 13; }
            before="${'$'}(pidof $pkg)"
            [ -n "${'$'}before" ] || { echo PROCESS_NOT_RUNNING; exit 14; }
            for target in ${'$'}before; do
                actual="${'$'}(stat -c %u /proc/${'$'}target)"
                [ "${'$'}actual" = "$expectedUid" ] || { echo PROCESS_UID_MISMATCH; exit 17; }
            done
            echo "BEFORE=${'$'}before"
            kill -TERM ${'$'}before || exit 15
            $reopen
            for attempt in 1 2 3 4 5 6 7 8 9 10; do
                sleep 1
                after="${'$'}(pidof $pkg)"
                if [ -n "${'$'}after" ] && [ "${'$'}after" != "${'$'}before" ]; then
                    echo "AFTER=${'$'}after"
                    exit 0
                fi
            done
            echo RESTART_NOT_CONFIRMED
            exit 16
        """.trimIndent()
        return execute(command, true)
    }

    private fun execute(command: String, restarting: Boolean): Result {
        if (!busy.compareAndSet(false, true)) return Result(false, "已有 Root 操作进行中，请等待")
        var process: Process? = null
        return try {
            process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
            if (!process.waitFor(45, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                Result(false, "授权或操作超时；未确认重启，请检查目标进程")
            } else {
                val output = process.inputStream.bufferedReader().use { it.readText().take(4096) }.trim()
                when {
                    process.exitValue() != 0 -> Result(false, "Root 被拒绝或执行失败（${process.exitValue()}）：$output")
                    !restarting && output == "0" -> Result(true, "本次 Root 授权成功；仅在你点击重启时执行固定命令")
                    restarting && output.contains("AFTER=") -> Result(true, "已核对重启前后 PID：\n$output")
                    else -> Result(false, "未确认操作结果：$output")
                }
            }
        } catch (t: Exception) {
            Result(false, "Root 不可用：${t.message ?: t.javaClass.simpleName}")
        } finally {
            process?.destroy(); busy.set(false)
        }
    }
}
