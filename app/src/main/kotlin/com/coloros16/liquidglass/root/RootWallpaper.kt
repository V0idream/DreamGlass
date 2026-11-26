package com.coloros16.liquidglass.root

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Process
import com.coloros16.liquidglass.config.SafeAreas
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Timer
import java.util.TimerTask

internal object RootWallpaper {
    data class Result(val granted: Boolean, val image: Bitmap?)

    fun read(context: Context, scene: SafeAreas.Scene, requestRoot: Boolean): Result {
        val wm = WallpaperManager.getInstance(context)
        val dir = "/data/system/users/${Process.myUid() / 100_000}"
        val lockId = runCatching { wm.getWallpaperId(WallpaperManager.FLAG_LOCK) }.getOrNull()
        val mayShare = (scene == SafeAreas.Scene.DESKTOP || lockId != null && lockId < 0) &&
            runCatching { wm.wallpaperInfo == null }.getOrDefault(false)
        val read = when {
            scene == SafeAreas.Scene.LOCK -> {
                val fallback = when {
                    lockId != null && lockId >= 0 -> "cat $dir/wallpaper_lock_orig"
                    mayShare -> "cat $dir/wallpaper"
                    else -> ":"
                }
                "if [ -s $dir/wallpaper_lock ]; then cat $dir/wallpaper_lock; else $fallback; fi"
            }
            mayShare -> "cat $dir/wallpaper"
            else -> ":"
        }
        // Derive all filenames from the caller's own Android user and fixed system names.
        val command = "[ \"\$(id -u)\" = 0 ] || exit 13; echo DGROOT; { $read; } 2>/dev/null"
        val process = ProcessBuilder("su", "-c", command).start()
        var granted = false
        val watchdog = Timer(true)
        try {
            watchdog.schedule(object : TimerTask() {
                override fun run() { process.destroyForcibly() }
            }, if (requestRoot) 40_000L else 2_500L)
            val bytes = process.inputStream.use { input ->
                val marker = "DGROOT\n".toByteArray(Charsets.US_ASCII)
                if (!input.readNBytes(marker.size).contentEquals(marker)) return Result(false, null)
                granted = true
                val output = ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                while (true) {
                    val count = input.read(chunk)
                    if (count < 0) break
                    if (output.size() + count > 24 * 1024 * 1024) return Result(true, null)
                    output.write(chunk, 0, count)
                }
                output.toByteArray()
            }
            return Result(true, if (process.waitFor() == 0) decode(bytes) else null)
        } catch (_: IOException) {
            return Result(granted, null)
        } finally {
            watchdog.cancel()
            if (process.isAlive) process.destroyForcibly()
        }
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        if (bytes.isEmpty()) return null
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        val longest = maxOf(options.outWidth, options.outHeight)
        if (longest <= 0) return null
        options.inJustDecodeBounds = false
        var sample = 1
        while (longest / sample > 1800) sample *= 2
        options.inSampleSize = sample
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }
}
