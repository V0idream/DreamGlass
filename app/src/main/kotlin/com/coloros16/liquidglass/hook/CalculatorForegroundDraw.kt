/* DreamGlass contributors, 2026. GPL-3.0-or-later. */
package com.coloros16.liquidglass.hook

/** Keep operator backgrounds and TextView.onDraw outside suppression. The only
 * writable native state is the verified fill flag, scoped to this onDraw call.
 */
internal class CalculatorForegroundDraw {
    private val drawing=ScopedDrawOwner<Any>()
    fun suppressCoat(owner:Any):Boolean=drawing.current()===owner
    fun <R> draw(owner:Any,active:Boolean,readFill:()->Boolean,writeFill:(Boolean)->Unit,native:()->R):R =
        drawing.run(if(active)owner else null) {
            if(!active)return@run native()
            val previous=readFill();writeFill(false)
            try {native()} finally {writeFill(previous)}
        }
}
