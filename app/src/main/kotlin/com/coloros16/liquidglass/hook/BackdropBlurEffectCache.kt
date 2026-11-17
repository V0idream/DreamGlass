package com.coloros16.liquidglass.hook

/** At zero radius no blur effect exists. Retain one positive-radius effect per renderer,
 * and explicitly clear it on zero so a reused RenderNode cannot keep an earlier blur.
 */
internal class BackdropBlurEffectCache<T:Any>(private val create:(Float)->T) {
    private var radius=Float.NaN
    private var value:T?=null
    fun resolve(requested:Float):T? {
        require(requested.isFinite()) {"Non-finite backdrop blur"}
        val next=requested.coerceAtLeast(0f)
        if(next==radius)return value
        val result=if(next>0f)create(next) else null
        radius=next;value=result
        return result
    }
    fun clear() {radius=Float.NaN;value=null}
}
