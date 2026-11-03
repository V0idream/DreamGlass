/* DreamGlass contributors, 2026. GPL-3.0-or-later. */
package com.coloros16.liquidglass.hook

/** Cache successful preference reads only. Framework availability can change
 * between preDraw and draw on the first entering frame. A failed attempt must
 * not hide a now-enabled material behind the normal 500ms polling interval. */
internal class ClearButtonReadCadence {
    private var successfulAt:Long?=null
    fun shouldRead(now:Long,entering:Boolean=false):Boolean {
        val last=successfulAt ?: return true
        return entering || now<last || now-last>=500L
    }
    fun succeeded(now:Long) {successfulAt=now}
}
