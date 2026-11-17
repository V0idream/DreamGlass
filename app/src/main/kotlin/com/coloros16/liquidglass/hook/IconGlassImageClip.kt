package com.coloros16.liquidglass.hook

import android.graphics.Outline
import android.graphics.RectF
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView
import java.util.WeakHashMap
import kotlin.math.roundToInt

/** Clip only a qualified recommendation's image slot, never its card or text container.
 * The compositor background and the native bitmap must use the same rounded boundary. */
internal object IconGlassImageClip {
    private class Lease(view:View,bounds:RectF):ViewOutlineProvider() {
        val previous=view.outlineProvider
        val previousClip=view.clipToOutline
        val area=RectF(bounds)
        override fun getOutline(view:View,outline:Outline) {
            outline.setRoundRect(area.left.roundToInt(),area.top.roundToInt(),
                area.right.roundToInt(),area.bottom.roundToInt(),minOf(area.width(),area.height())*.235f)
            outline.alpha=1f
        }
    }
    private val leases=WeakHashMap<ImageView,Lease>()
    fun apply(view:ImageView,bounds:RectF) {
        if(bounds.isEmpty)return
        val lease=leases.getOrPut(view) {Lease(view,bounds)}
        val changed=lease.area!=bounds || view.outlineProvider!==lease || !view.clipToOutline
        if(changed) {
            lease.area.set(bounds);view.outlineProvider=lease;view.clipToOutline=true;view.invalidateOutline()
        }
    }
    fun restore(view:ImageView) {
        val lease=leases.remove(view) ?: return
        if(view.outlineProvider===lease) {
            view.outlineProvider=lease.previous;view.clipToOutline=lease.previousClip;view.invalidateOutline()
        }
    }
    fun clear() {leases.keys.toList().forEach(::restore)}
}
