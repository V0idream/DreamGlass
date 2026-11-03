/* DreamGlass contributors, 2026. GPL-3.0-or-later. */
package com.coloros16.liquidglass.hook

import android.graphics.Outline
import android.graphics.RectF
import android.view.View
import android.view.ViewOutlineProvider
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.ceil
import kotlin.math.floor

/** Clip the native RenderNode itself, including its background image-filter
 * outsets. Clipping inside onDraw does not constrain an OEM backdrop effect.
 * Only exact Gallery material surfaces opt in. No foreground is transformed. */
internal object NativeSurfaceClipLease {
    private class Owned(val previous:ViewOutlineProvider?,val previousClip:Boolean,
        view:View,var area:RectF,var radius:Float):ViewOutlineProvider() {
        val owner=WeakReference(view)
        override fun getOutline(view:View,outline:Outline) {
            if(owner.get()!==view || area.isEmpty) {outline.setEmpty();return}
            outline.setRoundRect(floor(area.left).toInt(),floor(area.top).toInt(),
                ceil(area.right).toInt(),ceil(area.bottom).toInt(),radius)
            outline.alpha=1f
        }
    }
    // Weak values matter: a vendor's previous provider can itself capture View.
    private val states=WeakHashMap<View,WeakReference<Owned>>()
    fun sync(view:View,area:RectF,radius:Float) {
        if(area.isEmpty || !radius.isFinite())return
        val old=states[view]?.get()
        if(old!=null && view.outlineProvider===old) {
            val changed=old.area!=area || old.radius!=radius
            if(changed) {old.area=RectF(area);old.radius=radius}
            if(!view.clipToOutline)view.clipToOutline=true
            if(changed)view.invalidateOutline()
            return
        }
        val owned=Owned(view.outlineProvider,view.clipToOutline,view,RectF(area),radius)
        states[view]=WeakReference(owned)
        view.outlineProvider=owned;view.clipToOutline=true;view.invalidateOutline()
    }
    fun restore(view:View) {
        val owned=states.remove(view)?.get() ?: return
        if(view.outlineProvider!==owned)return // A later theme/native update owns it.
        view.outlineProvider=owned.previous;view.clipToOutline=owned.previousClip
        view.invalidateOutline()
    }
}
