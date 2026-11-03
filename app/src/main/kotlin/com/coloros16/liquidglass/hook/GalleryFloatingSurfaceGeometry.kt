/* DreamGlass contributors, 2026. GPL-3.0-or-later. */
package com.coloros16.liquidglass.hook

/** Literal Gallery 17.10.41 native background formulas. The menu's drawing rect
 * is in its parent's coordinates, whereas the OEM background filter is attached
 * to the child View. Native setX/scale/translation already position that child.
 * Neither parent origin, View scroll nor press scale is an extra shader offset.
 */
internal object GalleryFloatingSurfaceGeometry {
    data class Bounds(val left:Float,val top:Float,val right:Float,val bottom:Float)

    fun menu(viewWidth:Float,viewHeight:Float,drawingLeft:Float,drawingTop:Float,
        drawingRight:Float,drawingBottom:Float,rtl:Boolean):Bounds? {
        if(!listOf(viewWidth,viewHeight,drawingLeft,drawingTop,drawingRight,drawingBottom).all {it.isFinite()})return null
        val width=drawingRight-drawingLeft;val height=drawingBottom-drawingTop
        if(viewWidth<=0f || viewHeight<=0f || !width.isFinite() || !height.isFinite() || width<=0f || height<=0f)return null
        // BaseBottomFloatingMenuView.updateBackgroundBounds. Do not clamp spring
        // overshoot: native RTL can legitimately have a negative local left.
        return if(rtl)Bounds(viewWidth-width,viewHeight-height,viewWidth,viewHeight)
            else Bounds(0f,0f,width,height)
    }

    fun accessory(viewWidth:Float,viewHeight:Float):Bounds? {
        if(!viewWidth.isFinite() || !viewHeight.isFinite() || viewWidth<=0f || viewHeight<=0f)return null
        // Accessory updateBlurEffect assigns a full-view OvalShape background.
        // Its padding changes mRadius, not this Drawable's background bounds.
        return Bounds(0f,0f,viewWidth,viewHeight)
    }

    fun radius(nativeHeight:Float,paddingTop:Int,paddingBottom:Int):Float? {
        if(!nativeHeight.isFinite() || nativeHeight<=0f)return null
        return ((nativeHeight-paddingTop-paddingBottom)/2f).takeIf {it.isFinite() && it>=0f}
    }
}
