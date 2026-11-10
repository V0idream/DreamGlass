package com.coloros16.liquidglass.hook

import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/** A lighter native GPU blur for the independent volume window, retaining its native masks. */
object VolumeNativeBlurPolicy {
    // COS17 BlurParam uses physical pixels. Do not revive the old 800px backdrop replacement.
    const val MAX_RADIUS_PX = 16

    /** A ceiling, never a multiplier: repeated native updates cannot progressively erase blur. */
    fun limitRadius(nativeRadius: Int): Int =
        if (nativeRadius > MAX_RADIUS_PX) MAX_RADIUS_PX else nativeRadius

    private data class WrapperAccess(val inner: Method)
    private data class DrawableAccess(val params: Method, val commit: Method)
    private data class ParamAccess(val radius: Method, val setRadius: Method)
    private val wrappers = ConcurrentHashMap<Class<*>, WrapperAccess>()
    private val drawables = ConcurrentHashMap<Class<*>, DrawableAccess>()
    private val params = ConcurrentHashMap<Class<*>, ParamAccess>()

    /**
     * Call after PlatformBlurDrawable.applyBlurConfig, not on every frame. COS17's
     * ContinuousBlurDrawable.getBlurParams returns a detached copy, so only its radius changes;
     * mix colors, capture region, native shader, expansion clipping and mask remain untouched.
     * Unsupported ROM ABIs leave the native drawable intact rather than substituting a bitmap.
     */
    fun reduceNativeRadius(wrapper: Any): Boolean {
        if (!VolumeMaskGuard.preserveMask(wrapper)) return false
        return try {
            val wrapperAccess = wrappers.getOrPut(wrapper.javaClass) {
                WrapperAccess(wrapper.javaClass.getMethod("getBlurDrawable").apply { isAccessible = true })
            }
            val drawable = wrapperAccess.inner.invoke(wrapper) ?: return false
            val drawableAccess = drawables.getOrPut(drawable.javaClass) {
                val getParams = drawable.javaClass.getMethod("getBlurParams").apply { isAccessible = true }
                val commit = drawable.javaClass.getMethod("setBlurParamsSync", getParams.returnType)
                    .apply { isAccessible = true }
                DrawableAccess(getParams, commit)
            }
            val detached = drawableAccess.params.invoke(drawable) ?: return false
            val paramAccess = params.getOrPut(detached.javaClass) {
                ParamAccess(
                    detached.javaClass.getMethod("getBlurRadius").apply { isAccessible = true },
                    detached.javaClass.getMethod("setBlurRadius", Int::class.javaPrimitiveType)
                        .apply { isAccessible = true }
                )
            }
            val oldRadius = (paramAccess.radius.invoke(detached) as? Number)?.toInt() ?: return false
            val radius = limitRadius(oldRadius)
            if (radius == oldRadius) return false
            paramAccess.setRadius.invoke(detached, radius)
            drawableAccess.commit.invoke(drawable, detached)
            true
        } catch (_: Throwable) {
            false
        }
    }
}
