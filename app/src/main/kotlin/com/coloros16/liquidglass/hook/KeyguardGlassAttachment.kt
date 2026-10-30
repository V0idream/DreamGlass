package com.coloros16.liquidglass.hook
import android.graphics.Bitmap
import android.graphics.RenderEffect
import android.graphics.Matrix
import android.graphics.drawable.Drawable
import android.view.View
import android.widget.ImageView
import java.lang.ref.WeakReference

/** Reversible production attachment, also exercised directly by the Android rendering probe. */
internal class KeyguardGlassAttachment(view:View,val role:Int,native:RenderEffect?=null,
    private val change:((()->Unit)->Unit)={it()}) {
    private val view=WeakReference(view)
    var originalEffect=native
    var originalBackground:Drawable?=view.background
    val imageSlot=role==KeyguardGlassPolicy.SHORTCUT && view is ImageView &&
        runCatching { view.resources.getResourceEntryName(view.id)=="affordance_background_circle" }.getOrDefault(false)
    var originalImage:Drawable?=(view as? ImageView)?.drawable
    val background=if(role==KeyguardGlassPolicy.TEXT)null else KeyguardGlassMaterial.Background()
    private val material by lazy { background?.material ?: KeyguardGlassMaterial(true) }
    var applied=false;private set
    fun compose(native:RenderEffect?):RenderEffect?=material.effect?.let { if(native!=null)RenderEffect.createChainEffect(it,native) else it }
    fun apply(image:Bitmap,matrix:FloatArray,screenW:Int,screenH:Int,strength:Float,corner:Float) {
        val v=view.get() ?: return
        material.update(image,matrix,v.width.toFloat(),v.height.toFloat(),screenW,screenH,strength,corner)
        background?.radius=corner
        applied=true
        try {
            change { if(role==KeyguardGlassPolicy.TEXT)v.setRenderEffect(compose(originalEffect))
                else if(role==KeyguardGlassPolicy.SHORTCUT && KeyguardLayeredShortcut.supported(v)) {
                    val transform=Matrix();val coords=FloatArray(9)
                    val weakButton=WeakReference(v as ImageView)
                    check(KeyguardLayeredShortcut.bind(v as ImageView,background!!) { bounds ->
                        val button=weakButton.get() ?: return@bind
                        transform.reset();button.transformMatrixToGlobal(transform)
                        transform.preTranslate(button.paddingLeft.toFloat(),button.paddingTop.toFloat())
                        transform.preConcat(button.imageMatrix)
                        transform.preTranslate(bounds.left.toFloat(),bounds.top.toFloat())
                        transform.getValues(coords)
                        material.update(image,coords,bounds.width().toFloat(),bounds.height().toFloat(),screenW,screenH,
                            strength,minOf(bounds.width(),bounds.height())*.5f)
                    }) { "Native shortcut layer hook unavailable" }
                } else if(imageSlot)(v as ImageView).setImageDrawable(background) else v.background=background }
        } catch(error:Throwable) {
            // A vendor setter can throw after applying its value. Undo our partial assignment.
            try { restore() } catch(_:Throwable) { }
            throw error
        }
    }
    fun restore() {
        if(!applied)return
        val v=view.get() ?: return
        change { if(role==KeyguardGlassPolicy.TEXT)v.setRenderEffect(originalEffect)
            else if(imageSlot) { if((v as ImageView).drawable===background)v.setImageDrawable(originalImage) }
            else if(v.background===background)v.background=originalBackground }
        KeyguardLayeredShortcut.unbind(v)
        applied=false;material.clearImage()
    }
}
