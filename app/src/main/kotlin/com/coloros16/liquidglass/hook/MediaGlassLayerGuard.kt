package com.coloros16.liquidglass.hook

import android.graphics.Canvas
import com.coloros16.liquidglass.diagnostics.RuntimeDiagnostics
import com.coloros16.liquidglass.log.Lg
import io.github.libxposed.api.XposedInterface

/** Native media adds a second shadow/outline after its background material.
 * Suppress that decoration only after our glass was actually drawn in this call.
 * No target field/alpha changes, and fallback media keeps its native decoration.
 */
object MediaGlassLayerGuard {
    private class Frame(var glass:Boolean=false)
    private val current=ThreadLocal<Frame?>()
    private val active=java.util.Collections.synchronizedMap(java.util.WeakHashMap<Any,Boolean>())
    private var readAt = -1000L
    private var enabled=false
    fun material(drawable:Any,drawn:Boolean) {
        if(drawn) { active[drawable]=true;current.get()?.glass=true } else active.remove(drawable)
    }
    fun install(api:XposedInterface,loader:ClassLoader) {
        try {
            val owner=Class.forName("com.oplus.systemui.qs.media.multilight.OplusQsMediaBackgroundDrawable",false,loader)
            val base=Class.forName("com.oplus.posteffect.drawable.BaseDrawable",false,loader)
            api.hook(base.getDeclaredMethod("draw",Canvas::class.java))
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                    if(current.get()!=null && active[chain.getThisObject()]==true)current.get()?.glass=true
                    chain.proceed()
                }
            val light=Class.forName("com.oplus.systemui.qs.media.multilight.MultiLightDrawable",false,loader)
            api.hook(owner.getDeclaredMethod("draw",Canvas::class.java))
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                    val now=android.os.SystemClock.uptimeMillis()
                    if(now-readAt>=500L) { readAt=now;enabled=runCatching {
                        val p=com.coloros16.liquidglass.config.Prefs.read(api)
                        p.getBoolean(com.coloros16.liquidglass.config.Prefs.KEY_MASTER,false) &&
                            p.getBoolean(com.coloros16.liquidglass.config.Prefs.KEY_GLASS_ENABLE,true)
                    }.getOrDefault(false) }
                    if(!enabled)return@intercept chain.proceed()
                    val previous=current.get();current.set(Frame())
                    try { chain.proceed() } finally { if(previous==null)current.remove() else current.set(previous) }
                }
            api.hook(light.getDeclaredMethod("draw",Canvas::class.java))
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                    if(current.get()?.glass==true) {
                        RuntimeDiagnostics.mark("media-rim","已跳过玻璃之后的原生媒体阴影／轮廓层")
                        null
                    } else chain.proceed()
                }
        } catch(error:Throwable) { Lg.w("DG-Media","Native media decoration contract unavailable",error) }
    }
}
