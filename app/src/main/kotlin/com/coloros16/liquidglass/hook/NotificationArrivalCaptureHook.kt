/* DreamGlass contributors, 2026. GPL-3.0-or-later. */
package com.coloros16.liquidglass.hook

import android.os.Handler
import android.os.Looper
import android.view.View
import com.coloros16.liquidglass.log.Lg
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.ExceptionMode
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

/** Observe native notification arrivals, including reuse of the same background/geometry.
 * No polling, notification mutation, screenshots or visibility decisions live here. The owner
 * admits a fresh capture only if this background is actually visible and the device is unlocked. */
internal object NotificationArrivalCaptureHook {
    private const val TAG = "NotificationArrival"
    private const val ROW = "com.android.systemui.statusbar.notification.row.ExpandableNotificationRow"
    private val installed = Collections.synchronizedSet(HashSet<Method>())
    private val pending = Collections.synchronizedMap(WeakHashMap<View, Boolean>())
    private data class BackgroundField(val field: Field?)
    private val fields = ConcurrentHashMap<Class<*>, BackgroundField>()
    private val main by lazy { Handler(Looper.getMainLooper()) }
    @Volatile private var callback: ((View) -> Unit)? = null

    fun install(api: XposedInterface, loader: ClassLoader, onVisibleArrival: (View) -> Unit) {
        callback = onVisibleArrival
        val row = try { Class.forName(ROW, false, loader) } catch (error: Throwable) {
            Lg.w(TAG, "notification row unavailable; retain native rendering", error)
            return
        }
        for (method in supportedMethods(row)) {
            if (!installed.add(method)) continue
            try {
                method.isAccessible = true
                api.hook(method).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                    // Never retry a native call if the observer fails after native side effects.
                    val result = chain.proceed()
                    try {
                        val enabled = method.name != "setHeadsUp" || chain.getArg(0) == true
                        val host = chain.getThisObject() as? View
                        if (enabled && host != null) postArrival(host)
                    } catch (error: Throwable) {
                        Lg.w(TAG, "arrival observer failed after ${method.name}", error)
                    }
                    result
                }
            } catch (error: Throwable) {
                installed.remove(method)
                Lg.w(TAG, "arrival hook unavailable: ${method.name}", error)
            }
        }
    }

    /** Resolve only concrete native event methods present on this ROM. */
    internal fun supportedMethods(row: Class<*>): List<Method> = row.declaredMethods.filter { method ->
        !Modifier.isStatic(method.modifiers) && !Modifier.isAbstract(method.modifiers) &&
            !method.isSynthetic && method.returnType == Void.TYPE && when (method.name) {
                "setHeadsUp" -> method.parameterTypes.contentEquals(arrayOf(Boolean::class.javaPrimitiveType))
                "setEntry", "setEntryAdapter" -> method.parameterTypes.size == 1 &&
                    !method.parameterTypes[0].isPrimitive
                "onNotificationUpdated" -> method.parameterTypes.isEmpty()
                else -> false
            }
    }

    private fun postArrival(row: View) {
        // Multiple native binding events in one UI turn share one capture request. Handler.post
        // runs after the native mutation/layout turn and does not retain a detached row's queue.
        if (pending.put(row, true) != null) return
        val ref = WeakReference(row)
        val accepted = main.post {
            val current = ref.get() ?: return@post
            pending.remove(current)
            try {
                val background = backgroundField(current.javaClass)?.get(current) as? View ?: return@post
                callback?.invoke(background)
            } catch (error: Throwable) {
                Lg.w(TAG, "visible-arrival callback failed", error)
            }
        }
        if (!accepted) pending.remove(row)
    }

    private fun backgroundField(type: Class<*>): Field? = fields.getOrPut(type) {
        var cursor: Class<*>? = type
        var result: Field? = null
        while (cursor != null && result == null) {
            try {
                val candidate = cursor.getDeclaredField("mBackgroundNormal")
                if (!Modifier.isStatic(candidate.modifiers) && View::class.java.isAssignableFrom(candidate.type)) {
                    candidate.isAccessible = true
                    result = candidate
                }
            } catch (_: NoSuchFieldException) {
                // Some ROMs move the field into the row base class.
            } catch (_: Throwable) { break }
            cursor = cursor.superclass
        }
        BackgroundField(result)
    }.field
}
