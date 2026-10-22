package com.coloros16.liquidglass.hook

import android.view.View
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.WeakHashMap

/** Preserve the volume window's mask without changing Preview2's glass rendering path. */
object VolumeMaskGuard {
    private val decisions = Collections.synchronizedMap(WeakHashMap<Any, Boolean>())

    fun isVolumeClass(name: String): Boolean = name.startsWith("com.oplus.systemui.volume.") ||
        name.startsWith("com.android.systemui.volume.")

    fun preserveMask(wrapper: Any): Boolean {
        decisions[wrapper]?.let { return it }
        val result = try {
            val supplier = wrapper.javaClass.getDeclaredField("getHostViewName")
                .apply { isAccessible = true }.get(wrapper)
            // COS17's callback captures PlatformBlurHelper, which owns the actual host View.
            // Resource IDs and shared SurfaceControl layer descriptions are not scene evidence.
            val host = supplier?.javaClass?.declaredFields?.asSequence()
                ?.filter { !Modifier.isStatic(it.modifiers) }
                ?.mapNotNull { field ->
                    try {
                        val owner = field.apply { isAccessible = true }.get(supplier)
                        if (owner is View) owner else owner?.javaClass?.getDeclaredField("hostView")
                            ?.apply { isAccessible = true }?.get(owner) as? View
                    } catch (_: Throwable) { null }
                }?.firstOrNull()
            var cursor = host
            var volume = false
            for (i in 0 until 10) {
                val view = cursor ?: break
                if (isVolumeClass(view.javaClass.name)) { volume = true; break }
                cursor = view.parent as? View
            }
            volume
        } catch (_: Throwable) { false }
        decisions[wrapper] = result
        return result
    }
}
