/* DreamGlass contributors, 2026. GPL-3.0-or-later. */
package com.coloros16.liquidglass.hook

import android.view.View
import com.coloros16.liquidglass.liquidglass.LiquidGlassShader
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Marks native clear-button inputs so the general launcher recorder leaves
 * them original. Actual glass is drawn in the button's onDraw, before text. */
internal object NativeLauncherButtonMaterial {
    private class State(view:View,var params:LiquidGlassShader.Params) {
        val host=WeakReference(view)
        var sourceStamp:NativeClearSourceExtractor.Stamp?=null
    }
    private val entries=WeakHashMap<Any,State>()
    @Synchronized fun register(drawable:Any,view:View,params:LiquidGlassShader.Params):Boolean {
        val state=entries[drawable]
        if(state?.host?.get()===view) {
            if(state.params==params)return false
            state.params=params
        } else entries[drawable]=State(view,params)
        return true
    }
    @Synchronized fun remove(view:View):List<Any> {
        val removed=entries.filterValues {it.host.get()===view}.keys.toList()
        removed.forEach(entries::remove);return removed
    }
    @Synchronized fun contains(drawable:Any)=entries.containsKey(drawable)
    fun sourceChanged(drawable:Any,stamp:NativeClearSourceExtractor.Stamp,nativeContentChanged:Boolean=false) {
        val host=synchronized(this) {
            val state=entries[drawable] ?: return
            // OEM can update a RuntimeShader's uniforms or a hardware image
            // in place. An actual native content record also changes pixels,
            // even when all Java object identities remain identical.
            if(state.sourceStamp==stamp && !nativeContentChanged)return
            state.sourceStamp=stamp
            state.host.get()
        }
        // Native background RenderNode updates do not necessarily rerecord the
        // button's own onDraw display list. Only an actual input/geometry change
        // invalidates it; the next native record with the same stamp is idle.
        host?.takeIf {it.isAttachedToWindow && it.windowVisibility==View.VISIBLE && it.isShown}?.postInvalidateOnAnimation()
    }
}
