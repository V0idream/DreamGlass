package com.coloros16.liquidglass.island

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import java.lang.reflect.Proxy

/** Read only the foreground package. Subscribe to SystemUI's existing task-stack channel. */
internal class IslandForeground(
    context: Context,
    private val loader: ClassLoader,
    private val main: Handler,
    private val changed: () -> Unit,
) {
    private val tasks = context.getSystemService(ActivityManager::class.java)
    var packageName: String? = null
        private set
    private var needed = false
    private var triedListener = false
    private var unsubscribe: (() -> Unit)? = null
    private val refresh = object : Runnable {
        override fun run() {
            if (!needed) return
            val value = read()
            if (packageName != value) { packageName = value; changed() }
            if (unsubscribe == null) main.postDelayed(this, 750L)
        }
    }

    fun watch(value: Boolean) {
        if (value == needed) return
        needed = value
        main.removeCallbacks(refresh)
        if (!value) {
            packageName = null
            return
        }
        if (!triedListener) {
            triedListener = true
            unsubscribe = runCatching { register() }.getOrNull()
        }
        packageName = read()
        if (unsubscribe == null) main.postDelayed(refresh, 750L)
    }

    fun close() {
        watch(false)
        runCatching { unsubscribe?.invoke() }
        unsubscribe = null
    }

    @Suppress("DEPRECATION")
    private fun read(): String? = runCatching {
        tasks.getRunningTasks(1).firstOrNull()?.topActivity?.packageName
    }.getOrNull()

    private fun register(): () -> Unit {
        val channelType = Class.forName("com.android.systemui.shared.system.TaskStackChangeListeners", false, loader)
        val listenerType = Class.forName("com.android.systemui.shared.system.TaskStackChangeListener", false, loader)
        val channel = channelType.getMethod("getInstance").invoke(null)
        val detach = channelType.getMethod("unregisterTaskStackListener", listenerType)
        val listener = Proxy.newProxyInstance(listenerType.classLoader, arrayOf(listenerType)) { proxy, method, args ->
            when (method.name) {
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> "DreamGlass foreground observer"
                "onTaskStackChanged", "onTaskMovedToFront", "onActivityRestartAttempt" -> {
                    main.removeCallbacks(refresh)
                    main.postDelayed(refresh, 35L)
                    null
                }
                else -> null
            }
        }
        channelType.getMethod("registerTaskStackListener", listenerType).invoke(channel, listener)
        return { detach.invoke(channel, listener) }
    }
}
