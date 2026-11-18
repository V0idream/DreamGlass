package com.coloros16.liquidglass.config

import android.app.WallpaperManager
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import com.coloros16.liquidglass.BuildConfig
import com.coloros16.liquidglass.log.Lg
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Editor-initiated, one-use transfer of a static wallpaper FD. No preview files are persisted. */
internal object SafeAreaWallpaper {
    private val uri = Uri.parse("content://${BuildConfig.APPLICATION_ID}.wallpaper-preview")
    private fun key(scene: SafeAreas.Scene) = scene.key + "_preview"
    private val requests = mutableMapOf<String, Pending>()
    private val registrations = mutableMapOf<SafeAreas.Scene, Pair<SharedPreferences, SharedPreferences.OnSharedPreferenceChangeListener>>()
    private val worker by lazy { Executors.newSingleThreadExecutor { Thread(it, "DreamGlass-wallpaper-preview").apply { isDaemon = true } } }
    private class Pending(val scene: SafeAreas.Scene) {
        val done = CountDownLatch(1)
        var file: ParcelFileDescriptor? = null
    }

    @android.annotation.SuppressLint("MissingPermission")
    fun read(context: Context, scene: SafeAreas.Scene): ParcelFileDescriptor? {
        val privileged = context.checkSelfPermission("android.permission.READ_WALLPAPER_INTERNAL") == PackageManager.PERMISSION_GRANTED
        if (Build.VERSION.SDK_INT >= 33 && !privileged && !Environment.isExternalStorageManager()) return null
        val wm = WallpaperManager.getInstance(context)
        val lock = WallpaperManager.FLAG_LOCK
        if (scene == SafeAreas.Scene.LOCK && wm.getWallpaperId(lock) >= 0) return wm.getWallpaperFile(lock)
        if (wm.wallpaperInfo != null) return null
        return wm.getWallpaperFile(WallpaperManager.FLAG_SYSTEM)
    }

    fun fromHost(prefs: SharedPreferences, scene: SafeAreas.Scene): ParcelFileDescriptor? {
        val id = "${Process.myUid() / 100_000}:${SystemClock.elapsedRealtime()}:${UUID.randomUUID()}"
        val pending = Pending(scene)
        synchronized(requests) { requests[id] = pending }
        try {
            if (!prefs.edit().putString(key(scene), id).commit()) return null
            pending.done.await(2200, TimeUnit.MILLISECONDS)
            return synchronized(requests) { pending.file.also { pending.file = null } }
        } finally {
            synchronized(requests) {
                requests.remove(id)
                pending.file?.close()
            }
        }
    }

    @Synchronized
    fun install(scene: SafeAreas.Scene, prefs: SharedPreferences) {
        registrations.remove(scene)?.let { (p, l) -> p.unregisterOnSharedPreferenceChangeListener(l) }
        val prefKey = key(scene)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, changed ->
            if (changed != prefKey) return@OnSharedPreferenceChangeListener
            val id = runCatching { prefs.getString(prefKey, null) }.getOrNull() ?: return@OnSharedPreferenceChangeListener
            worker.execute { runCatching { respond(scene, id) } }
        }
        registrations[scene] = prefs to listener
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    private fun respond(scene: SafeAreas.Scene, id: String) {
        val parts = id.split(':', limit = 3)
        if (parts.size != 3 || parts[2].length != 36) return
        val user = parts[0].toIntOrNull() ?: return
        val created = parts[1].toLongOrNull() ?: return
        if (SystemClock.elapsedRealtime() - created !in 0L..5000L) return
        val host = Lg.processContext() ?: return
        if (user != Process.myUid() / 100_000) return
        if (scene == SafeAreas.Scene.LOCK) {
            val active = Class.forName("android.app.ActivityManager").getMethod("getCurrentUser").invoke(null) as Int
            if (active != user) return
        }
        val file = runCatching { read(host, scene) }.getOrNull()
        try {
            host.contentResolver.call(uri, "preview", id, Bundle().also {
                it.putString("scene", scene.name)
                if (file != null) it.putParcelable("file", file)
            })
        } finally { file?.close() }
    }

    fun accept(id: String, scene: SafeAreas.Scene, file: ParcelFileDescriptor?) {
        synchronized(requests) {
            val pending = requests[id]
            if (pending == null || pending.scene != scene || pending.done.count == 0L) {
                file?.close()
                return
            }
            pending.file = file
            pending.done.countDown()
        }
    }
}

class SafeAreaWallpaperProvider : ContentProvider() {
    override fun onCreate() = true
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        require(method == "preview" && arg != null && extras != null)
        val scene = when (extras.getString("scene")) {
            "DESKTOP" -> SafeAreas.Scene.DESKTOP
            "LOCK" -> SafeAreas.Scene.LOCK
            else -> throw SecurityException("Unrecognized wallpaper scene")
        }
        val pkg = if (scene == SafeAreas.Scene.DESKTOP) "com.android.launcher" else "com.android.systemui"
        val caller = Binder.getCallingUid()
        if (caller / 100_000 != Process.myUid() / 100_000 ||
            context?.packageManager?.getPackagesForUid(caller)?.contains(pkg) != true) {
            throw SecurityException("Wallpaper preview caller denied")
        }
        val file = if (Build.VERSION.SDK_INT >= 33) extras.getParcelable("file", ParcelFileDescriptor::class.java)
        else @Suppress("DEPRECATION") extras.getParcelable<ParcelFileDescriptor>("file")
        SafeAreaWallpaper.accept(arg, scene, file)
        return Bundle.EMPTY
    }
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, s: String?, a: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, s: String?, a: Array<out String>?): Int = throw UnsupportedOperationException()
}
