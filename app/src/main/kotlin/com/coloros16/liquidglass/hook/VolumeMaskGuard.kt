package com.coloros16.liquidglass.hook

import android.view.View
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.WeakHashMap

/** Keep the independent volume window's mask and content on their native rendering path. */
object VolumeMaskGuard {
    private val windows=Collections.synchronizedMap(WeakHashMap<View,Boolean>())
    fun noteWindow(host:View):Boolean {
        if(!isVolumeClass(host.javaClass.name))return false
        return windows.put(host.rootView,true)==null
    }
    fun windowRoots():List<View> = synchronized(windows) { windows.keys.toList() }
    private val decisions = Collections.synchronizedMap(WeakHashMap<Any, Boolean>())
    private val nativeContents = Collections.synchronizedMap(WeakHashMap<Any, Boolean>())

    /** Keep both wrapper mask and inner content native, including asynchronous content recording. */
    fun protectNativeContent(wrapper: Any?, target: Any) {
        if (wrapper != null) decisions[wrapper] = true
        nativeContents[target] = true
    }

    fun preservesContent(target: Any): Boolean = nativeContents.containsKey(target)

    fun isVolumeClass(name: String): Boolean = name.startsWith("com.oplus.systemui.volume.") ||
        name.startsWith("com.android.systemui.volume.")

    fun isVolumeHost(host: View?): Boolean {
        if(host!=null && windows.containsKey(host.rootView))return true
        var cursor = host
        repeat(10) {
            val view = cursor ?: return false
            if (isVolumeClass(view.javaClass.name)) return true
            cursor = view.parent as? View
        }
        return false
    }

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
            isVolumeHost(host)
        } catch (_: Throwable) { false }
        // A generic host may be queried before it is attached to the volume hierarchy.
        // Cache only positive ownership; an early negative result must be reconsidered later.
        if (result) decisions[wrapper] = true
        return result
    }
}
