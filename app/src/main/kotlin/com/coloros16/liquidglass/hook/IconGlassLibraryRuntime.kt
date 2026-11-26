package com.coloros16.liquidglass.hook

import android.content.Context
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.UserManager
import com.coloros16.liquidglass.icons.IconLibrary
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Local provider is authoritative. A framework preference change only asks for another read.
 * Never read on the draw thread, and never publish a failed provider call as an empty catalog.
 */
internal object IconGlassLibraryRuntime {
    @Volatile var revision = 0L
        private set
    private val lock = Any()
    private val recovery = IconLibraryRecovery<Map<String, IconLibrary.Entry>>()
    private val images = LinkedHashMap<String, Bitmap>(32, .75f, true)
    private val missing = LinkedHashMap<String, String>()
    private val worker = ScheduledThreadPoolExecutor(1) {
        Thread(it, "DreamGlass-icon-library").apply { isDaemon = true }
    }.apply {
        removeOnCancelPolicy = true
        setKeepAliveTime(15, TimeUnit.SECONDS)
        allowCoreThreadTimeOut(true)
    }
    private var app: Context? = null
    private var changed: (() -> Unit)? = null
    private var active = false
    private var needsRecovery = false
    private var observerRegistered = false // worker-owned
    private var serial = 0L
    private var scheduled: ScheduledFuture<*>? = null
    private val observer = object : ContentObserver(null) {
        override fun onChange(selfChange: Boolean) = requestRefresh()
    }

    fun observe(context: Context, onChanged: () -> Unit) {
        synchronized(lock) {
            if (app != null) return
            app = context.applicationContext ?: context
            changed = onChanged
            scheduleLocked(0, replace = false)
        }
    }

    fun setEnabled(enabled: Boolean) = synchronized(lock) {
        if (active == enabled) return@synchronized
        active = enabled
        if (enabled) {
            recovery.resetBudget()
            scheduleLocked(0, replace = true)
        } else {
            serial++
            scheduled?.cancel(false)
            scheduled = null
            missing.clear()
        }
    }

    fun requestRefresh() = synchronized(lock) {
        if (!active) return@synchronized
        recovery.resetBudget()
        scheduleLocked(0, replace = true)
    }

    fun resume() = synchronized(lock) {
        if (active && (needsRecovery || recovery.snapshot == null || missing.isNotEmpty())) {
            recovery.resetBudget()
            scheduleLocked(0, replace = true)
        }
    }

    private fun scheduleLocked(delay: Long, replace: Boolean) {
        val context = app ?: return
        if (!active || (scheduled != null && !replace)) return
        scheduled?.cancel(false)
        val token = ++serial
        scheduled = worker.schedule({ refresh(context, token) }, delay, TimeUnit.MILLISECONDS)
    }

    private fun refresh(context: Context, token: Long) {
        synchronized(lock) {
            if (!active || serial != token) return
            scheduled = null
        }
        if (context.getSystemService(UserManager::class.java)?.isUserUnlocked == false) {
            // Credential-encrypted imports become available after first unlock.
            synchronized(lock) { if (serial == token) needsRecovery = true }
            return
        }
        // Subscribe only after unlock; some firmware rejects this registration during Direct Boot.
        // Subscribe before the read so a concurrent import cannot get lost between them.
        if (!observerRegistered) observerRegistered = runCatching {
            context.contentResolver.registerContentObserver(IconLibrary.URI, false, observer)
        }.isSuccess
        val updated = runCatching {
            val response = requireNotNull(context.contentResolver.call(IconLibrary.URI, "catalog", null, null))
            IconLibrary.parseRuntimeCatalog(response).associateBy { it.pkg }
        }.getOrNull()
        var redraw = false
        val recoverEntries = synchronized(lock) {
            if (!active || serial != token) return
            needsRecovery = updated == null || !observerRegistered
            val prior = recovery.snapshot
            val accepted = recovery.accept(updated)
            if (accepted && updated != null) {
                redraw = prior.orEmpty() != updated
                missing.entries.removeAll { (pkg, sha) -> updated[pkg]?.sha256 != sha }
            }
            val snapshot = recovery.snapshot
            if (updated == null) emptyList() else missing.keys.mapNotNull { snapshot?.get(it) }
        }
        for (entry in recoverEntries) {
            if (synchronized(lock) { !active || serial != token }) break
            val bitmap = readImage(context, entry) ?: continue
            synchronized(lock) {
                if (active && recovery.snapshot?.get(entry.pkg)?.sha256 == entry.sha256 &&
                    missing[entry.pkg] == entry.sha256) {
                    putImage(entry.sha256, bitmap)
                    missing.remove(entry.pkg)
                    redraw = true
                }
            }
        }
        val callback = synchronized(lock) {
            if (active && serial == token) {
                recovery.retryDelay(needsRecovery || missing.isNotEmpty())?.let { scheduleLocked(it, replace = false) }
            }
            if (active && redraw) { revision++; changed } else null
        }
        callback?.invoke()
    }

    fun create(context: Context, pkg: String?, size: Int): IconGlassMasks.Prepared? {
        val entry = recovery.snapshot?.get(pkg ?: return null) ?: return null
        val cached = synchronized(lock) {
            if (!active || missing[pkg] == entry.sha256) return null
            images[entry.sha256]
        }
        val image = cached ?: readImage(context, entry)
        if (image == null) {
            synchronized(lock) {
                if (active && recovery.snapshot?.get(pkg)?.sha256 == entry.sha256 && pkg !in missing) {
                    if (missing.isEmpty() && !needsRecovery) recovery.resetBudget()
                    if (missing.size >= 96) missing.remove(missing.keys.first())
                    missing[pkg] = entry.sha256
                    if (scheduled == null) recovery.retryDelay(true)?.let { scheduleLocked(it, replace = false) }
                }
            }
            return null
        }
        synchronized(lock) {
            if (!active || recovery.snapshot?.get(pkg)?.sha256 != entry.sha256) return null
            if (cached == null) putImage(entry.sha256, image)
        }
        val target = if (entry.source == "upload") image.width else if (entry.render == "engraved") 192 else size
        val scaled = Bitmap.createScaledBitmap(image, target, target, true)
        val copy = scaled.copy(Bitmap.Config.ARGB_8888, true) ?: return null
        if (scaled !== image) scaled.recycle()
        if (entry.source != "upload") IconGlassDynamicIcons.paint(copy, pkg, entry.dynamic)
        return IconGlassMasks.Prepared(copy, if (entry.source == "upload") "user-image" else "downloaded",
            relief = entry.source != "upload", passthrough = entry.source == "upload", detailed = entry.source != "upload" && entry.render == "engraved")
    }

    private fun putImage(hash: String, bitmap: Bitmap) {
        if (images.size >= 64 && hash !in images) images.remove(images.keys.first())
        images[hash] = bitmap
    }

    private fun readImage(context: Context, entry: IconLibrary.Entry): Bitmap? = runCatching {
        val uri = IconLibrary.URI.buildUpon().appendPath(entry.pkg).build()
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readNBytes(262145) }
            ?: return@runCatching null
        require(bytes.size <= 262144 && IconLibrary.digest(bytes) == entry.sha256)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth in 32..512 && bounds.outHeight in 32..512 && bounds.outMimeType == "image/png")
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()
}
