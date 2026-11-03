/* DreamGlass contributors, 2026. GPL-3.0-or-later. */
package com.coloros16.liquidglass.hook

import android.view.View
import com.coloros16.liquidglass.liquidglass.LiquidGlassShader
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Marks native clear-button inputs so the general launcher recorder leaves
 * them original. Actual glass is drawn in the button's onDraw, before text. */
internal object NativeLauncherButtonMaterial {
    private class State(view:View,var params:LiquidGlassShader.Params) {val host=WeakReference(view)}
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
}
