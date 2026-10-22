package com.coloros16.liquidglass.diagnostics

import android.os.Process
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Host RemotePreferences are read-only. Publish bounded snapshots through the module provider. */
object RuntimeDiagnostics {
    const val PREFIX = "dreamglass_status_"
    private val stages = ConcurrentHashMap<String, String>()
    private val revision = AtomicInteger()
    private val pending = AtomicBoolean()
    @Volatile private var published = -1
    @Volatile private var retryAfter = 0L
    @Volatile private var host = ""
    @Volatile private var classesSnapshot: String? = null
    private val writer = ScheduledThreadPoolExecutor(1) { r ->
        Thread(r, "DreamGlass-status").apply { isDaemon = true }
    }.apply { setKeepAliveTime(2L, TimeUnit.SECONDS); allowCoreThreadTimeOut(true) }

    fun begin(pkg: String) {
        host = pkg
        stages.clear(); revision.incrementAndGet(); published = -1
        mark("loaded", "入口已执行；PID=${Process.myPid()}")
    }

    fun mark(stage: String, detail: String = "已到达") {
        if (host.isEmpty()) return
        if (stages.putIfAbsent(stage, detail.take(500)) == null) revision.incrementAndGet()
        if (published == revision.get() || SystemClock.uptimeMillis() < retryAfter) return
        if (pending.compareAndSet(false, true)) writer.execute { publish(0) }
    }

    fun discovered(classes: String) {
        if (classes.length > 65_536 || classes == classesSnapshot) return
        classesSnapshot = classes
        revision.incrementAndGet()
        if (pending.compareAndSet(false, true)) writer.execute { publish(0) }
    }

    private fun publish(attempt: Int) {
        val version = revision.get()
        val snapshot = JSONObject().apply {
            put("host", host); put("pid", Process.myPid()); put("at", System.currentTimeMillis())
            put("version", "0.1.1-COS17-Refine1"); put("stages", JSONObject(stages.toMap()))
        }.toString()
        val classes = classesSnapshot
        val accepted = try {
            RuntimeStore.sendState(host, snapshot) && (classes == null || RuntimeStore.sendClasses(host, classes))
        } catch (t: Throwable) {
            if (attempt == 0) Log.w("DreamGlass-Diag", "Provider publication unavailable: $t")
            false
        }
        if (accepted) {
            published = version; retryAfter = 0L; pending.set(false)
            if (published != revision.get() && pending.compareAndSet(false, true)) writer.execute { publish(0) }
        } else if (attempt < 3) {
            writer.schedule({ publish(attempt + 1) }, 1L shl attempt, TimeUnit.SECONDS)
        } else {
            retryAfter = SystemClock.uptimeMillis() + 10_000L
            pending.set(false) // A later host event may retry; no permanent polling.
        }
    }
}
