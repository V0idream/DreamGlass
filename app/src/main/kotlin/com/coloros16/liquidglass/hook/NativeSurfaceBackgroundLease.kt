/* DreamGlass contributors, 2026. GPL-3.0-or-later. */
package com.coloros16.liquidglass.hook

import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.View
import java.lang.ref.WeakReference

/** A parent-only lease, installed with the ordinary View background setter so
 * HWUI clears its opaque-background optimization. Merely skipping drawBackground
 * can leave opaque rendering metadata and a black rectangular backing.
 *
 * There is no registry retaining View/Drawable cycles. The View owns its wrapper;
 * the wrapper's explicit owner reference is weak. Native replacements are adopted
 * on the next active sync, and cleanup never overwrites a newer native background.
 */
internal object NativeSurfaceBackgroundLease {
    fun sync(host:View,active:Boolean):Boolean {
        val current=host.background
        val owned=current as? TransparentParentBackground
        if(active) {
            if(owned?.belongsTo(host)==true)return false
            host.background=TransparentParentBackground(host,current)
            return true
        }
        if(owned?.belongsTo(host)==true) {
            host.background=owned.original
            return true
        }
        return false
    }

    private class TransparentParentBackground(host:View,val original:Drawable?):Drawable() {
        private val owner=WeakReference(host)
        fun belongsTo(host:View)=owner.get()===host
        override fun draw(canvas:Canvas) {} // The RenderNode backdrop supplies the parent surface.
        @Deprecated("Deprecated in Java") override fun getOpacity()=PixelFormat.TRANSLUCENT
        override fun getPadding(padding:Rect):Boolean=original?.getPadding(padding) ?: super.getPadding(padding)
        override fun getOutline(outline:Outline) {original?.getOutline(outline) ?: outline.setEmpty()}
        override fun onBoundsChange(bounds:Rect) {original?.bounds=Rect(bounds)}
        override fun getIntrinsicWidth()=original?.intrinsicWidth ?: -1
        override fun getIntrinsicHeight()=original?.intrinsicHeight ?: -1
        override fun getMinimumWidth()=original?.minimumWidth ?: 0
        override fun getMinimumHeight()=original?.minimumHeight ?: 0
        override fun isStateful()=original?.isStateful==true
        override fun onStateChange(state:IntArray):Boolean=original?.setState(state)==true
        override fun onLevelChange(level:Int):Boolean=original?.setLevel(level)==true
        override fun onLayoutDirectionChanged(layoutDirection:Int):Boolean=original?.setLayoutDirection(layoutDirection)==true
        override fun jumpToCurrentState() {original?.jumpToCurrentState()}
        override fun setAlpha(alpha:Int) {original?.alpha=alpha.coerceIn(0,255);invalidateSelf()}
        override fun getAlpha()=original?.alpha ?: 255
        override fun setColorFilter(colorFilter:ColorFilter?) {original?.colorFilter=colorFilter;invalidateSelf()}
        override fun getColorFilter():ColorFilter?=original?.colorFilter
        override fun setTint(tintColor:Int) {original?.setTint(tintColor);invalidateSelf()}
        override fun setTintList(tint:ColorStateList?) {original?.setTintList(tint);invalidateSelf()}
        override fun setTintMode(tintMode:PorterDuff.Mode?) {original?.setTintMode(tintMode);invalidateSelf()}
    }
}
