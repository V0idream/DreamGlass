package com.coloros16.liquidglass.hook

import android.graphics.Rect
import android.view.View
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap

/** Factory signatures and the wrapper -> actual rendering-object relationship are separate contracts. */
class QsSeekbarCompat {
    private val activeWrappers = Collections.synchronizedMap(WeakHashMap<Any, Boolean>())
    private val activeTargets = Collections.synchronizedMap(WeakHashMap<Any, Boolean>())

    fun resolveActiveFactory(type: Class<*>): Method {
        val name = "getSeekBarActiveDrawable"
        return try {
            // SystemUI 17.99.02. Do not select unrelated overloads by name/argument count.
            type.getDeclaredMethod(name, View::class.java, Rect::class.java,
                Float::class.javaPrimitiveType!!, Float::class.javaPrimitiveType!!,
                Boolean::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!,
                Float::class.javaPrimitiveType!!)
        } catch (_: NoSuchMethodException) {
            type.getDeclaredMethod(name, View::class.java)
        }
    }

    fun registerFactoryResult(wrapper: Any?) {
        if (wrapper != null) activeWrappers[wrapper] = true
    }

    fun isActiveWrapper(wrapper: Any): Boolean = activeWrappers.containsKey(wrapper)

    /** Called during AutoBlurDrawable mapping, after PlatformBlurDrawable has a real inner target. */
    fun bind(wrapper: Any, target: Any): Boolean {
        if (!activeWrappers.containsKey(wrapper)) return false
        activeTargets[target] = true
        return true
    }

    fun isActiveTarget(target: Any): Boolean = activeTargets.containsKey(target)
}
