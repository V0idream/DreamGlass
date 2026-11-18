package com.coloros16.liquidglass.root

import android.content.Context
import android.os.Process
import com.coloros16.liquidglass.diagnostics.RuntimeStore
import java.util.concurrent.TimeUnit

/** Remember only verified UID 0, never infer permission from the presence of a su binary. */
internal object RootGrant {
    private const val KEY = "root_access_verified"
    fun previouslyVerified(context: Context): Boolean {
        val prefs = RuntimeStore.local(context)
        if (prefs.contains(KEY)) return prefs.getBoolean(KEY, false)
        // Existing installations recorded successful grants/restarts in the settings status.
        val old = prefs.getString("root_last_status", "").orEmpty()
        return old.startsWith("上次操作：本次 Root 授权成功；") || old.startsWith("上次操作：已核对重启前后 PID：")
    }

    fun remember(context: Context, granted: Boolean) {
        RuntimeStore.local(context).edit().putBoolean(KEY, granted).apply()
    }

    // Stock Magisk 31.1+ does not open an interactive prompt on an ordinary su request.
    // A user may have preauthorized DreamGlass in its manager before ever opening this app.
    @Volatile private var knownVersion: String? = null
    private fun managerVersion() = knownVersion ?: suVersion().also { if (it.isNotEmpty()) knownVersion = it }
    private fun suVersion(): String {
        val process = runCatching {
            ProcessBuilder("su", "-v").redirectErrorStream(true).start()
        }.getOrNull() ?: return ""
        return try {
            if (!process.waitFor(400, TimeUnit.MILLISECONDS) || process.exitValue() != 0) ""
            else process.inputStream.use { String(it.readNBytes(128), Charsets.US_ASCII).trim() }
        } catch (_: Exception) { "" }
        finally { if (process.isAlive) process.destroyForcibly() }
    }
    private val magiskVersion = Regex("""^(\d+)\.(\d+)(?:\.\d+)?(?::MAGISK(?:SU)?)(?::[A-Za-z0-9]+)*$""")
    internal fun preGrantOnlyVersion(version: String): Boolean {
        val match = magiskVersion.matchEntire(version) ?: return false
        val major = match.groupValues[1].toIntOrNull() ?: return false
        val minor = match.groupValues[2].toIntOrNull() ?: return false
        return major > 31 || (major == 31 && minor >= 1)
    }
    // KernelSU only routes su to its implementation for granted UIDs. Its version
    // command reaching ksud is a positive signal; authorization is still checked in the read.
    internal fun reportsGrantedKernelSu(version: String) =
        version.endsWith(":KernelSU") && version.length > ":KernelSU".length

    fun canTryAutomatically(context: Context): Boolean {
        if (Process.myUid() == 0 || previouslyVerified(context)) return true
        val version = managerVersion()
        return preGrantOnlyVersion(version) || reportsGrantedKernelSu(version)
    }
    fun managerNeedsPregrant() = preGrantOnlyVersion(managerVersion())
}
