/* DreamGlass contributors, 2026. GPL-3.0-or-later. */
package com.coloros16.liquidglass.hook

import android.graphics.*
import android.view.View
import com.coloros16.liquidglass.diagnostics.RuntimeDiagnostics
import com.coloros16.liquidglass.liquidglass.LiquidGlassShader
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** COS17's native LayerBlurDrawable owns capture, buffer adoption, position and
 * clipping. Filter its prepared GPU shader only for a verified clear button;
 * never remove that Drawable or substitute a wallpaper/screenshot source. */
internal object NativeLauncherButtonMaterial {
    private class State(view:View,@Volatile var params:LiquidGlassShader.Params) {
        val host=WeakReference(view)
        val shader by lazy {LiquidGlassShader.create()}
        @Volatile var ready=false
    }
    private val entries=WeakHashMap<Any,State>()
    @Synchronized fun register(drawable:Any,view:View,params:LiquidGlassShader.Params):Boolean {
        val state=entries[drawable]
        if(state?.host?.get()===view) {
            if(state.params==params)return false
            state.params=params;state.ready=false
        } else entries[drawable]=State(view,params)
        return true // Native recording must be dirtied only on an actual change.
    }
    @Synchronized fun remove(view:View):List<Any> {
        val removed=entries.filterValues {it.host.get()===view}.keys.toList()
        removed.forEach(entries::remove);return removed
    }
    @Synchronized fun ready(view:View)=entries.values.any {it.host.get()===view && it.ready}
    @Synchronized fun contains(drawable:Any)=entries.containsKey(drawable)
    @Synchronized fun unavailable(drawable:Any) {entries[drawable]?.ready=false}
    fun record(drawable:Any,paint:Paint,path:Path,record:()->Boolean):Boolean {
        val state=synchronized(this) {entries[drawable]} ?: return false
        state.ready=false
        if(state.host.get()==null)return false
        val source=paint.shader ?: return false
        val area=RectF();path.computeBounds(area,true)
        if(area.isEmpty)return false
        val shader=state.shader
        if(source===shader)return false
        val p=state.params.copy(viewportWidth=area.width(),viewportHeight=area.height(),
            cornerRadius=state.params.cornerRadius.coerceAtMost(minOf(area.width(),area.height())*.5f),
            sourcePreblurred=true,parallaxX=0f,parallaxY=0f)
        LiquidGlassShader.setUniforms(shader,p,source,source,sourceRect=area,maskRect=area,
            maskOffsetX=area.left,maskOffsetY=area.top)
        val result=NativePaintShaderLease.withShader(paint,shader,record)
        state.ready=result
        if(result)RuntimeDiagnostics.mark("recents-clear-native-source",
            "原LayerBlurDrawable及实时GPU纹理保留；仅替换本次录制shader，finally还原native Paint")
        return result
    }
}
