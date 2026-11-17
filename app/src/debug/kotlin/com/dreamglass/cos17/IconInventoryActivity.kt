package com.dreamglass.cos17

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Explicit, local-only debug export. Never installed in release builds. */
class IconInventoryActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val status = TextView(this).apply { text = "正在导出本机应用名称、包名与原始图标…" }
        setContentView(status)
        Thread {
            val result = runCatching { export() }.getOrElse {
                JSONObject().put("passed", false).put("error", it.stackTraceToString())
            }
            File(filesDir, "icon-inventory-result.json").writeText(result.toString(2))
            runOnUiThread { status.text = result.toString(2) }
        }.start()
    }

    private fun render(drawable: Drawable): ByteArray {
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val previous = android.graphics.Rect(drawable.bounds)
        try {
            drawable.setBounds(0, 0, 256, 256)
            drawable.draw(Canvas(bitmap))
            return ByteArrayOutputStream().also {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }.toByteArray()
        } finally {
            drawable.bounds = previous
            bitmap.recycle()
        }
    }

    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    @Suppress("DEPRECATION")
    private fun export(): JSONObject {
        val pm = packageManager
        val activities = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS)
            .distinctBy { "${it.activityInfo.packageName}/${it.activityInfo.name}" }
            .sortedWith(compareBy({ it.activityInfo.packageName }, { it.activityInfo.name }))
        check(activities.isNotEmpty())
        val entries = JSONArray()
        val defaultBytes = render(pm.defaultActivityIcon)
        val defaultHash = digest(defaultBytes)
        val zipFile = File(filesDir, "icon-inventory.zip")
        val temporary = File(filesDir, "icon-inventory.tmp")
        var successes = 0
        ZipOutputStream(temporary.outputStream().buffered()).use { zip ->
            fun write(path: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(path)); zip.write(bytes); zip.closeEntry()
            }
            write("reference/android-default.png", defaultBytes)
            activities.forEachIndexed { index, info ->
                val activity = info.activityInfo
                val app = activity.applicationInfo
                val record = JSONObject().put("package", activity.packageName).put("component", activity.name)
                    .put("label", info.loadLabel(pm).toString()).put("applicationLabel", app.loadLabel(pm).toString())
                    .put("applicationIconResource", app.icon).put("activityIconResource", activity.icon)
                    .put("applicationEnabled", app.enabled).put("activityEnabled", activity.enabled)
                    .put("system", app.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0)
                runCatching {
                    val packageInfo = pm.getPackageInfo(activity.packageName, 0)
                    record.put("versionName", packageInfo.versionName).put("versionCode", packageInfo.longVersionCode)
                    val bytes = render(info.loadIcon(pm))
                    val hash = digest(bytes)
                    val path = "icons/${activity.packageName}/${index.toString().padStart(4, '0')}.png"
                    write(path, bytes)
                    record.put("icon", path).put("sha256", hash)
                        .put("defaultIconMatch", hash == defaultHash)
                        .put("missingIconResource", app.icon == 0 && activity.icon == 0)
                    successes++
                }.onFailure { record.put("error", it.stackTraceToString()) }
                entries.put(record)
            }
            val manifest = JSONObject().put("schema", 1).put("exportedAtEpochMillis", System.currentTimeMillis())
                .put("device", android.os.Build.MODEL).put("androidSdk", android.os.Build.VERSION.SDK_INT)
                .put("scope", "Current user enabled and disabled launchable activities, PackageManager original resources")
                .put("defaultIconSha256", defaultHash).put("activities", entries)
            write("manifest.json", manifest.toString(2).toByteArray(Charsets.UTF_8))
        }
        check(temporary.renameTo(zipFile))
        return JSONObject().put("passed", successes == activities.size).put("activities", activities.size)
            .put("packages", activities.map { it.activityInfo.packageName }.distinct().size)
            .put("iconsExported", successes).put("archiveBytes", zipFile.length())
    }
}
