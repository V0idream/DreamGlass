/* DreamGlass contributors, 2026. GPL-3.0-or-later. */
package com.coloros16.liquidglass.hook

/** COUIAlertDialogMaxLinearLayout.setOutLineProviderInternal's actual bounds.
 * The dialog-overlay feature replaces the InsetDrawable's inner surface with a
 * transparent ColorDrawable. Drawable.getOutline can therefore be empty even
 * though the panel's own OutlineProvider has valid visible geometry. */
internal object GalleryDialogGeometry {
    data class Bounds(val left:Float,val top:Float,val right:Float,val bottom:Float)
    fun bounds(width:Int,height:Int,horizontalMargin:Int,verticalMarginTotal:Int,
        effectiveBottomMargin:Int,radius:Int,tiny:Boolean,loading:Boolean):Bounds? {
        if(width<=0 || height<=0 || horizontalMargin<0 || radius<0)return null
        var nativeWidth=width.toLong();var nativeHeight=height.toLong();var left=0L
        if(loading || tiny) {
            if(tiny)nativeHeight+=radius else nativeHeight=maxOf(0L,nativeHeight-verticalMarginTotal)
        } else {
            left=horizontalMargin.toLong();nativeWidth-=2L*horizontalMargin;nativeHeight-=verticalMarginTotal
        }
        if(nativeWidth<=0L || nativeHeight<=0L)return null
        val top=verticalMarginTotal.toLong()-effectiveBottomMargin
        // Int-backed native geometry should never overflow during a transition.
        if(listOf(left,top,left+nativeWidth,top+nativeHeight).any {it !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()})return null
        return Bounds(left.toFloat(),top.toFloat(),(left+nativeWidth).toFloat(),(top+nativeHeight).toFloat())
    }
}
