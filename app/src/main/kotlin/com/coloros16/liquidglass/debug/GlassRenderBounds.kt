/* DreamGlass contributors, 2026. GPL-3.0-or-later.
 * Region-debugging workflow/color convention adapted from AYwlilwYA's
 * ColorOS16-LiquidGlass DebugOverlay (468b40c), implemented in the common COS17
 * GPU material instead of installing privileged/global overlay windows.
 */
package com.coloros16.liquidglass.debug

import android.content.SharedPreferences
import android.os.SystemClock
import com.coloros16.liquidglass.config.Prefs

/** No timers, view traversal, capture, permissions or retained windows. Only an
 * evaluated material shows a frame; candidates never masquerade as rendered glass.
 */
internal object GlassRenderBounds {
    @Volatile private var provider:(()->SharedPreferences?)?=null
    @Volatile private var checkedAt=-1000L
    @Volatile private var value=false
    fun attach(read:()->SharedPreferences?) {provider=read;checkedAt=-1000L;value=false}
    fun enabled():Boolean {
        val read=provider ?: return false
        val now=SystemClock.uptimeMillis()
        if(now-checkedAt>=500) synchronized(this) {
            if(now-checkedAt>=500) {
                value=runCatching {read()?.getBoolean(Prefs.KEY_DEBUG_GLASS_BOUNDS,false)==true}.getOrDefault(false)
                checkedAt=now
            }
        }
        return value
    }
}
