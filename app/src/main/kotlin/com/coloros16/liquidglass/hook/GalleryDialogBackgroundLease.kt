/* DreamGlass contributors, 2026. GPL-3.0-or-later. */
package com.coloros16.liquidglass.hook

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.View
import java.lang.ref.WeakReference

/** Only used for the verified dialog parentPanel / non-content dialogOverlay.
 * COUIStateEffectDrawable is safe as a CHILD of an ordinary View background:
 * unlike COUIButton, these hosts do not forward setBackground into that child.
 * Preserve native geometry while clearing View's opaque-background metadata.
 * Draw in the actual native background channel before foreground children.
 * This does not depend on ART dispatching a hooked ViewGroup super method.
 */
internal object GalleryDialogBackgroundLease {
    fun owns(view:View)=(view.background as? Leased)?.owner?.get()===view
    fun canMount(view:View)=runCatching {
        view.javaClass.getMethod("setBackground",Drawable::class.java).declaringClass==View::class.java &&
            view.javaClass.getMethod("setBackgroundDrawable",Drawable::class.java).declaringClass==View::class.java
    }.getOrDefault(false)
    fun sync(view:View,active:Boolean):Boolean {
        val current=view.background
        val leased=current as? Leased
        if(!active) {
            if(leased?.owner?.get()===view)view.background=leased.original
            return true
        }
        if(leased?.owner?.get()!==view) {
            if(!canMount(view))return false
            view.background=Leased(view,current)
        }
        return owns(view) && !view.isOpaque
    }
    private class Leased(view:View,val original:Drawable?):Drawable() {
        val owner=WeakReference(view)
        override fun draw(canvas:Canvas) {
            val host=owner.get() ?: return
            val panel=GalleryNativeSurfaceContract.dialogPaintPanel(host) ?: return
            if(GalleryLiveBackdrop.owns(panel))GalleryLiveBackdrop.drawDialogBackground(panel,host,canvas)
            else original?.draw(canvas)
        }
        @Deprecated("Deprecated in Java") override fun getOpacity()=PixelFormat.TRANSLUCENT
        override fun getPadding(out:Rect)=original?.getPadding(out) ?: super.getPadding(out)
        override fun getOutline(out:Outline) {original?.getOutline(out) ?: out.setEmpty()}
        override fun onBoundsChange(bounds:Rect) {original?.bounds=Rect(bounds)}
        override fun getMinimumWidth()=original?.minimumWidth ?: 0
        override fun getMinimumHeight()=original?.minimumHeight ?: 0
        override fun getIntrinsicWidth()=original?.intrinsicWidth ?: -1
        override fun getIntrinsicHeight()=original?.intrinsicHeight ?: -1
        override fun isStateful()=original?.isStateful==true
        override fun onStateChange(state:IntArray)=original?.setState(state)==true
        override fun onLevelChange(level:Int)=original?.setLevel(level)==true
        override fun setAlpha(alpha:Int) {original?.alpha=alpha.coerceIn(0,255);invalidateSelf()}
        override fun getAlpha()=original?.alpha ?: 255
        override fun setColorFilter(filter:ColorFilter?) {original?.colorFilter=filter;invalidateSelf()}
    }
}
