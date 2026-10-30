package com.coloros16.liquidglass.log

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.ParcelFileDescriptor
import com.coloros16.liquidglass.diagnostics.RuntimeStore
import com.coloros16.liquidglass.diagnostics.RuntimeDiagnostics
import com.coloros16.liquidglass.config.Prefs
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException

/**
 * 日志文件读写通道：物理文件 = 模块 app `filesDir/logs/lg.log`。
 *
 * hook 进程（SystemUI / 桌面）经 content uri 把日志写进模块自身数据目录，
 * 设置页分享入口读同一份文件。日志含界面类名，故 exported 但逐入口校验调用方。
 */
class LogProvider : ContentProvider() {

    companion object {
        private const val LOG_DIR = "logs"
        private const val LOG_FILE = "lg.log"
        /** 单个日志文件上限，超过即轮转（主文件 + .1 共约 4MB） */
        private const val ROTATE_BYTES = 2L * 1024 * 1024

        /** 允许访问的调用方（日志含界面类名，不放开给任意 app） */
        private val ALLOWED_PACKAGES = RuntimeStore.HOSTS + "com.dreamglass.cos17"
    }

    override fun onCreate(): Boolean = true

    /** 只放行白名单包名（日志含界面类名，不放开给任意 app）。 */
    private fun checkCaller() {
        // 本进程调用直接放行，避免读到上一次远程调用留下的包名
        if (Binder.getCallingUid() == android.os.Process.myUid()) return
        callingPackage?.let { if (it in ALLOWED_PACKAGES) return }
        val uid = Binder.getCallingUid()
        val pkgs = try {
            context?.packageManager?.getPackagesForUid(uid)
        } catch (t: Throwable) {
            null
        }
        if (pkgs != null && pkgs.any { it in ALLOWED_PACKAGES }) return
        throw SecurityException("lg log access denied: uid=$uid pkg=$callingPackage")
    }

    private fun logFile(): File {
        val root = context?.filesDir ?: throw IllegalStateException("no context")
        return File(File(root, LOG_DIR), LOG_FILE)
    }

    /** 写入打开前轮转：主文件超上限就改名成 .1（旧 .1 直接丢弃）。 */
    private fun rotateIfNeeded(file: File) {
        if (file.length() <= ROTATE_BYTES) return
        val old = File(file.parentFile, "$LOG_FILE.1")
        try {
            if (old.exists()) old.delete()
            file.renameTo(old)
        } catch (t: Throwable) {
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        checkCaller()
        if(com.coloros16.liquidglass.BuildConfig.DEBUG && mode in setOf("w","wt") &&
            uri.lastPathSegment in setOf("raw.png","native.png","glass.png","combined.png") &&
            uri.path=="/glyph-debug/${uri.lastPathSegment}") {
            val directory=File(context!!.filesDir,"glyph-audit").apply { mkdirs() }
            return ParcelFileDescriptor.open(File(directory,uri.lastPathSegment!!),
                ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE)
        }
        if (uri.path != "/log") throw FileNotFoundException("unknown path: $uri")
        val file = logFile()
        file.parentFile?.mkdirs()
        if (mode.contains('w')) rotateIfNeeded(file)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        checkCaller()
        return null
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        checkCaller()
        val ctx = context ?: return null
        val callingUid = Binder.getCallingUid()
        fun requireHost(pkg: String) {
            val packages = ctx.packageManager.getPackagesForUid(callingUid).orEmpty()
            if (callingUid != android.os.Process.myUid() && pkg !in packages) {
                throw SecurityException("Host identity mismatch")
            }
        }
        val prefs = RuntimeStore.local(ctx)
        val accepted = when (method) {
            RuntimeStore.STATE -> {
                require(arg in RuntimeStore.HOSTS)
                requireHost(arg!!)
                val raw = extras?.getString("snapshot") ?: return null
                require(raw.length <= 8192)
                val report = JSONObject(raw)
                require(report.getString("host") == arg)
                report.put("pid", Binder.getCallingPid())
                report.put("receivedAt", System.currentTimeMillis())
                val process=report.optString("process",arg)
                require(process.length<=200 && (process==arg || process.startsWith("$arg:")))
                val perProcess=RuntimeDiagnostics.PREFIX+arg+"@"+process
                val editor=prefs.edit().putString(RuntimeDiagnostics.PREFIX + arg, report.toString())
                    .putString(perProcess,report.toString())
                // Each allowed package has a small fixed process set. Bound retained history.
                prefs.all.keys.filter { it.startsWith(RuntimeDiagnostics.PREFIX+arg+"@") && it!=perProcess }
                    .sorted().drop(7).forEach { editor.remove(it) }
                editor.commit()
            }
            RuntimeStore.CLASSES -> {
                require(arg in RuntimeStore.HOSTS)
                requireHost(arg!!)
                val raw = extras?.getString("classes") ?: return null
                require(raw.length <= 65_536)
                val classes = com.coloros16.liquidglass.glass.GlassRegistry.parseClasses(raw).take(1024)
                prefs.edit().putString("observed_" + arg,
                    com.coloros16.liquidglass.glass.GlassRegistry.encodeClasses(classes)).commit()
            }
            RuntimeStore.LOG -> {
                val pkg = when (arg) {
                    "systemui" -> "com.android.systemui"
                    "launcher" -> "com.android.launcher"
                    "app" -> "com.dreamglass.cos17"
                    else -> throw IllegalArgumentException("Unknown host")
                }
                requireHost(pkg)
                prefs.edit().putString(Prefs.KEY_LG_HB_PREFIX + arg, extras?.getString("heartbeat").orEmpty().take(512))
                    .putString(Prefs.KEY_LG_CHUNK_PREFIX + arg, extras?.getString("tail").orEmpty().takeLast(4000)).commit()
            }
            else -> return null
        }
        return Bundle().apply { putBoolean("accepted", accepted) }
    }

    override fun getType(uri: Uri): String = "text/plain"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
