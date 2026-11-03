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

/** Verified AppCompatButton setter delegates to super then reapplies tint. It
 * does not forward the replacement into its old Drawable (unlike COUIButton).
 * This host-only lease suppresses the native LayerBlurDrawable's fill/stroke
 * after our OEM backdrop replacement is ready; never lease the panel itself. */
internal object RecentsClearButtonBackgroundLease {
    fun owns(view:View)=(view.background as? TransparentBackground)?.owner?.get()===view
    fun canMount(view:View)=runCatching {
        view.javaClass.getMethod("setBackground",Drawable::class.java).declaringClass==View::class.java &&
            view.javaClass.getMethod("setBackgroundDrawable",Drawable::class.java).declaringClass.name=="androidx.appcompat.widget.AppCompatButton"
    }.getOrDefault(false)
    fun sync(view:View,active:Boolean):Boolean {
        val current=view.background
        val owned=current as? TransparentBackground
        if(active) {
            if(owned?.owner?.get()===view)return !view.isOpaque
            if(!canMount(view))return false
            view.background=TransparentBackground(view,current)
            return owns(view) && !view.isOpaque
        }
        if(owned?.owner?.get()===view)view.background=owned.original
        return true
    }
    private class TransparentBackground(view:View,val original:Drawable?):Drawable() {
        val owner=WeakReference(view)
        override fun draw(canvas:Canvas) {}
        @Deprecated("Deprecated in Java") override fun getOpacity()=PixelFormat.TRANSLUCENT
        override fun getOutline(outline:Outline) {original?.getOutline(outline) ?: outline.setEmpty()}
        override fun getPadding(padding:Rect)=original?.getPadding(padding) ?: super.getPadding(padding)
        override fun onBoundsChange(bounds:Rect) {original?.bounds=Rect(bounds)}
        override fun getIntrinsicWidth()=original?.intrinsicWidth ?: -1
        override fun getIntrinsicHeight()=original?.intrinsicHeight ?: -1
        override fun getMinimumWidth()=original?.minimumWidth ?: 0
        override fun getMinimumHeight()=original?.minimumHeight ?: 0
        override fun isStateful()=original?.isStateful==true
        override fun onStateChange(state:IntArray)=original?.setState(state)==true
        override fun onLevelChange(level:Int)=original?.setLevel(level)==true
        override fun onLayoutDirectionChanged(direction:Int)=original?.setLayoutDirection(direction)==true
        override fun jumpToCurrentState() {original?.jumpToCurrentState()}
        override fun setAlpha(alpha:Int) {original?.alpha=alpha.coerceIn(0,255);invalidateSelf()}
        override fun getAlpha()=original?.alpha ?: 255
        override fun setColorFilter(filter:ColorFilter?) {original?.colorFilter=filter;invalidateSelf()}
        override fun getColorFilter()=original?.colorFilter
        override fun setTint(color:Int) {original?.setTint(color);invalidateSelf()}
        override fun setTintList(tint:ColorStateList?) {original?.setTintList(tint);invalidateSelf()}
        override fun setTintMode(mode:PorterDuff.Mode?) {original?.setTintMode(mode);invalidateSelf()}
    }
}
