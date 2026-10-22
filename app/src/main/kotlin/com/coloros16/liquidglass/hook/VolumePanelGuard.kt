package com.coloros16.liquidglass.hook

import android.view.View
import java.util.Collections
import java.util.WeakHashMap

object VolumePanelGuard {
    private val platformHosts = Collections.synchronizedMap(WeakHashMap<Any, Boolean>())

    fun isVolumeWrapper(wrapper: Any): Boolean {
        platformHosts[wrapper]?.let { return it }
        val volume = try {
            // API 37 exposes a Function0 field, not a getHostViewName() method.
            // Invoke reflectively: the host may have its own Kotlin class loader.
            val supplier = wrapper.javaClass.getDeclaredField("getHostViewName").apply { isAccessible = true }.get(wrapper)
            val identity = supplier?.javaClass?.getMethod("invoke")?.apply { isAccessible = true }?.invoke(supplier) as? String
            identity != null && NativeScenePolicy.isVolume(identity)
        } catch (_: Throwable) { false }
        platformHosts[wrapper] = volume
        return volume
    }

    fun isVolumeView(view: View): Boolean {
        var current: View? = view
        repeat(10) {
            val item = current ?: return false
            if (NativeScenePolicy.isVolume(item.javaClass.name)) return true
            current = item.parent as? View
        }
        return false
    }
}
