/* DreamGlass contributors, 2026. GPL-3.0-or-later. */
package com.coloros16.liquidglass.hook

/** Latest native requests are value arguments, not a View or a drawing callback.
 * Do not take over a native channel unless both channels can be restored.
 */
internal class GalleryMaterialRequests<R> {
    enum class Channel { BLUR, STROKE }
    private var blur:R?=null
    private var stroke:R?=null
    var active=false
        private set

    fun remember(channel:Channel,request:R):Boolean {
        when(channel) {Channel.BLUR -> blur=request;Channel.STROKE -> stroke=request}
        return active
    }
    fun begin():Boolean {
        if(blur==null || stroke==null)return false
        active=true
        return true
    }
    fun end():List<R> {
        if(!active)return emptyList()
        active=false
        return listOfNotNull(blur,stroke)
    }
}
