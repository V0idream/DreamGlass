package com.coloros16.liquidglass.hook
import android.graphics.Bitmap
import android.graphics.RenderEffect
import android.graphics.drawable.Drawable
import android.view.View
import java.lang.ref.WeakReference

/** Reversible production attachment, also exercised directly by the Android rendering probe. */
internal class KeyguardGlassAttachment(view:View,val role:Int,native:RenderEffect?=null,
    private val change:((()->Unit)->Unit)={it()}) {
    private val view=WeakReference(view)
    var originalEffect=native
    var originalBackground:Drawable?=view.background
    val background=if(role==KeyguardGlassPolicy.TEXT)null else KeyguardGlassMaterial.Background()
    private val material by lazy { background?.material ?: KeyguardGlassMaterial(true) }
    var applied=false;private set
    fun compose(native:RenderEffect?):RenderEffect?=material.effect?.let { if(native!=null)RenderEffect.createChainEffect(it,native) else it }
    fun apply(image:Bitmap,matrix:FloatArray,screenW:Int,screenH:Int,strength:Float,corner:Float) {
        val v=view.get() ?: return
        material.update(image,matrix,v.width.toFloat(),v.height.toFloat(),screenW,screenH,strength,corner)
        background?.radius=corner
        change { if(role==KeyguardGlassPolicy.TEXT)v.setRenderEffect(compose(originalEffect)) else v.background=background }
        applied=true
    }
    fun restore() {
        if(!applied)return
        val v=view.get() ?: return
        change { if(role==KeyguardGlassPolicy.TEXT)v.setRenderEffect(originalEffect)
            else if(v.background===background)v.background=originalBackground }
        applied=false;material.clearImage()
    }
}
