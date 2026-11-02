/* DreamGlass contributors, 2026. GPL-3.0-or-later. */
package com.coloros16.liquidglass.hook

import android.graphics.*
import android.graphics.drawable.Drawable
import android.view.View
import java.lang.ref.WeakReference

/** Background replacement must obey Drawable geometry/state and fall back if the
 * GPU material has no source. Clone ordinary shared backgrounds when necessary;
 * an uncloneable native backdrop keeps the current bounds, since rolling back its
 * live RenderNode before playback would move the already-recorded background.
 */
internal open class BoundedGlassDrawable(
    host:View,
    var orig:Drawable?,
    private val enabled:()->Boolean,
    private val render:(Canvas,View,Rect)->Boolean,
    private val rendered:()->Unit={},
    private val failure:(Throwable)->Unit={},
):Drawable() {
    private val target=WeakReference(host)
    private var opacity=255
    private var filter:ColorFilter?=null
    private val layerPaint=Paint()
    override fun draw(canvas:Canvas) {
        val area=Rect(bounds)
        if(area.isEmpty || opacity==0)return
        val save=if(opacity<255 || filter!=null) {
            layerPaint.alpha=opacity;layerPaint.colorFilter=filter
            canvas.saveLayer(RectF(area),layerPaint)
        } else canvas.save()
        try {
            canvas.clipRect(area)
            val host=target.get()
            val success=try {
                if(host!=null && canvas.isHardwareAccelerated && enabled())render(canvas,host,area) else false
            } catch(error:Throwable) {runCatching {failure(error)};false}
            if(success)runCatching {rendered()} else orig?.let {native ->
                val fallback=if(native.bounds==area)native else
                    runCatching {native.constantState?.newDrawable()?.mutate()}.getOrNull() ?: native
                if(fallback!==native) {
                    fallback.state=native.state;fallback.level=native.level
                    fallback.alpha=native.alpha;fallback.colorFilter=native.colorFilter
                }
                fallback.bounds=area;fallback.draw(canvas)
            }
        } finally {canvas.restoreToCount(save)}
    }
    override fun setAlpha(alpha:Int) {opacity=alpha.coerceIn(0,255);invalidateSelf()}
    override fun getAlpha():Int=opacity
    override fun setColorFilter(colorFilter:ColorFilter?) {filter=colorFilter;invalidateSelf()}
    override fun getColorFilter():ColorFilter?=filter
    override fun getPadding(padding:Rect):Boolean=orig?.getPadding(padding) ?: super.getPadding(padding)
    override fun getIntrinsicWidth():Int=orig?.intrinsicWidth ?: -1
    override fun getIntrinsicHeight():Int=orig?.intrinsicHeight ?: -1
    override fun isStateful():Boolean=orig?.isStateful==true
    override fun onStateChange(state:IntArray):Boolean=orig?.setState(state)==true
    override fun onLevelChange(level:Int):Boolean=orig?.setLevel(level)==true
    override fun getOutline(outline:Outline) {orig?.getOutline(outline) ?: outline.setRect(bounds)}
    @Deprecated("Deprecated in Java") override fun getOpacity():Int=PixelFormat.TRANSLUCENT
}
