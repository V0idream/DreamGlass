/* DreamGlass contributors, 2026. GPL-3.0-or-later. */
package com.coloros16.liquidglass.hook

import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.DrawableWrapper
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.NinePatchDrawable
import android.graphics.drawable.StateListDrawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.VectorDrawable
import android.view.View
import java.lang.ref.WeakReference
import java.lang.reflect.Field

/** COUIButton.setBackground redirects its argument INTO its current state
 * LayerDrawable. Wrapping the whole background there creates S -> W -> S.
 * Replace only native layer index 0 with W(originalChild), without invoking any
 * View background setter. Preserve every other layer, callback and native input.
 */
internal object GalleryStateLayerBackgroundLease {
    private var stateType:Class<*>?=null
    private var buttonType:Class<*>?=null
    private var buttonState:Field?=null
    fun install(loader:ClassLoader):Boolean=runCatching {
        stateType=null;buttonType=null;buttonState=null
        val state=Class.forName("com.coui.appcompat.state.COUIStateEffectDrawable",false,loader)
        val button=Class.forName("com.coui.appcompat.button.COUIButton",false,loader)
        val field=button.getDeclaredField("mStateEffectBackground").apply {
            check(type==state);isAccessible=true
        }
        check(LayerDrawable::class.java.isAssignableFrom(state))
        stateType=state;buttonType=button;buttonState=field;true
    }.getOrDefault(false)
    fun isStateBackground(host:View)=stateType?.isInstance(host.background)==true
    private fun container(host:View):LayerDrawable? {
        val current=host.background as? LayerDrawable ?: return null
        if(stateType?.isInstance(current)!=true || current.numberOfLayers<=0)return null
        if(buttonType?.isInstance(host)==true) {
            if(buttonState?.get(host)!==current)return null
        } else {
            // n0q's verified plain FrameLayout hosts have ordinary View setters.
            // Unknown custom setters are not made safe merely by sharing a name.
            if(host.javaClass.getMethod("setBackground",Drawable::class.java).declaringClass!=View::class.java)return null
        }
        return current
    }
    private fun children(value:Any):List<Any>?=when(value) {
        is TransparentLayer -> listOf(value.original)
        is LayerDrawable -> if(value.numberOfLayers<=32)(0 until value.numberOfLayers).map {value.getDrawable(it)} else null
        is StateListDrawable -> if(value.stateCount<=32)(0 until value.stateCount).mapNotNull {value.getStateDrawable(it)} else null
        is DrawableWrapper -> value.drawable?.let {listOf(it)} ?: emptyList()
        is ColorDrawable,is GradientDrawable,is ShapeDrawable,is BitmapDrawable,is NinePatchDrawable,is VectorDrawable -> emptyList()
        else -> null
    }
    fun canMount(host:View):Boolean=runCatching {
        // LayerDrawable opacity alone is insufficient: this COUI implementation
        // returns UNKNOWN. Respect View's actual cached HWUI opaque metadata.
        if(host.isOpaque)return@runCatching false
        val state=container(host) ?: return@runCatching false
        val layer=state.getDrawable(0) ?: return@runCatching false
        if(layer is TransparentLayer)return@runCatching layer.belongsTo(host,state)
        StateLayerTopology.canWrap(state,layer,::children)
    }.getOrDefault(false)
    fun sync(host:View,active:Boolean=true):Boolean {
        if(!active)return restore(host)
        if(!canMount(host))return false
        return runCatching {
            val state=container(host) ?: return@runCatching false
            val current=state.getDrawable(0) ?: return@runCatching false
            if(current is TransparentLayer)return@runCatching current.belongsTo(host,state)
            val replacement=TransparentLayer(host,state,current)
            state.setDrawable(0,replacement) // INDEX 0, never a guessed resource layer ID.
            if(container(host)===state && state.getDrawable(0)===replacement && !host.isOpaque) {
                host.invalidate();true
            } else {
                if(state.getDrawable(0)===replacement)state.setDrawable(0,current)
                false
            }
        }.getOrDefault(false)
    }
    fun restore(host:View):Boolean=runCatching {
        val state=container(host) ?: return@runCatching false
        val current=state.getDrawable(0) as? TransparentLayer ?: return@runCatching false
        if(!current.belongsTo(host,state) || !StateLayerTopology.canRestore(state,current.container.get(),state.getDrawable(0),current))
            return@runCatching false
        state.setDrawable(0,current.original);host.invalidate();true
    }.getOrDefault(false)

    private class TransparentLayer(host:View,state:LayerDrawable,val original:Drawable):Drawable() {
        private val owner=WeakReference(host)
        val container=WeakReference(state)
        fun belongsTo(host:View,state:LayerDrawable)=owner.get()===host && container.get()===state
        override fun draw(canvas:Canvas) {}
        @Deprecated("Deprecated in Java") override fun getOpacity()=PixelFormat.TRANSLUCENT
        override fun getPadding(padding:Rect)=original.getPadding(padding)
        override fun getOutline(outline:Outline)=original.getOutline(outline)
        override fun onBoundsChange(bounds:Rect) {original.bounds=Rect(bounds)}
        override fun getIntrinsicWidth()=original.intrinsicWidth
        override fun getIntrinsicHeight()=original.intrinsicHeight
        override fun getMinimumWidth()=original.minimumWidth
        override fun getMinimumHeight()=original.minimumHeight
        override fun isStateful()=original.isStateful
        override fun onStateChange(state:IntArray)=original.setState(state)
        override fun onLevelChange(level:Int)=original.setLevel(level)
        override fun onLayoutDirectionChanged(layoutDirection:Int)=original.setLayoutDirection(layoutDirection)
        override fun jumpToCurrentState()=original.jumpToCurrentState()
        override fun setAlpha(alpha:Int) {original.alpha=alpha.coerceIn(0,255);invalidateSelf()}
        override fun getAlpha()=original.alpha
        override fun setColorFilter(colorFilter:ColorFilter?) {original.colorFilter=colorFilter;invalidateSelf()}
        override fun getColorFilter()=original.colorFilter
        override fun setTint(tintColor:Int) {original.setTint(tintColor);invalidateSelf()}
        override fun setTintList(tint:ColorStateList?) {original.setTintList(tint);invalidateSelf()}
        override fun setTintMode(tintMode:PorterDuff.Mode?) {original.setTintMode(tintMode);invalidateSelf()}
    }
}
