package com.coloros16.liquidglass.hook
import android.graphics.Bitmap
import android.graphics.RenderEffect
import android.graphics.Matrix
import android.graphics.drawable.Drawable
import android.view.View
import com.coloros16.liquidglass.config.SafeAreas
import android.widget.ImageView
import java.lang.ref.WeakReference

/** Reversible production attachment, also exercised directly by the Android rendering probe. */
internal class KeyguardGlassAttachment(view:View,val role:Int,native:RenderEffect?=null,
    private val change:((()->Unit)->Unit)={it()},
    private val setEffect:(View,RenderEffect?)->Unit={v,e->v.setRenderEffect(e)},
    private val outerGlyph:Boolean=false) {
    private val view=WeakReference(view)
    var originalEffect=native
    var originalBackground:Drawable?=view.background
    val imageSlot=role==KeyguardGlassPolicy.SHORTCUT && view is ImageView &&
        runCatching { view.resources.getResourceEntryName(view.id)=="affordance_background_circle" }.getOrDefault(false)
    var originalImage:Drawable?=(view as? ImageView)?.drawable
    val background=if(role==KeyguardGlassPolicy.TEXT)null else KeyguardGlassMaterial.Background()
    private val material by lazy { background?.material ?: KeyguardGlassMaterial(true) }
    private var ownNativeOnGlyph = false
    var applied=false;private set
    fun compose(native:RenderEffect?):RenderEffect? {
        val v = view.get() ?: return native
        if (!outerGlyph && KeyguardGlyphDraw.owns(v)) {
            val takeNative = material.protectsGlyph && native != null
            if (takeNative || ownNativeOnGlyph) {
                val effect = material.glyphEffect(if (takeNative) native else null, nativeOnTop = true)
                if (effect != null && KeyguardGlyphDraw.bind(v, effect)) {
                    ownNativeOnGlyph = takeNative
                    return if (takeNative) null else native
                }
            } else return native
        }
        return material.glyphEffect(native) ?: native
    }
    fun apply(image:Bitmap,matrix:FloatArray,screenW:Int,screenH:Int,strength:Float,corner:Float,safeMapping:SafeAreas.Mapping?=null) {
        val v=view.get() ?: return
        material.update(image,matrix,v.width.toFloat(),v.height.toFloat(),screenW,screenH,strength,corner,safeMapping)
        background?.radius=corner
        applied=true
        try {
            change { if(role==KeyguardGlassPolicy.TEXT) {
                    val native = originalEffect
                    val takeNative = material.protectsGlyph && native != null
                    val effect = material.glyphEffect(if (takeNative) native else null, nativeOnTop = true)
                    if (!outerGlyph && effect != null && KeyguardGlyphDraw.bind(v, effect)) {
                        ownNativeOnGlyph = takeNative
                        setEffect(v, if (takeNative) null else native)
                    } else {
                        ownNativeOnGlyph = false
                        setEffect(v, material.glyphEffect(native))
                    }
                }
                else if(role==KeyguardGlassPolicy.SHORTCUT && KeyguardLayeredShortcut.supported(v)) {
                    val transform=Matrix();val coords=FloatArray(9)
                    val weakButton=WeakReference(v as ImageView)
                    val shortcutMapper=SafeAreas.ViewMapper()
                    val localOrigin=IntArray(2)
                    check(KeyguardLayeredShortcut.bind(v as ImageView,background!!) { bounds ->
                        val button=weakButton.get() ?: return@bind
                        transform.reset();button.transformMatrixToGlobal(transform)
                        button.getLocationOnScreen(localOrigin)
                        transform.getValues(coords)
                        coords[2]=localOrigin[0].toFloat();coords[5]=localOrigin[1].toFloat()
                        transform.setValues(coords)
                        transform.preTranslate(button.paddingLeft.toFloat(),button.paddingTop.toFloat())
                        transform.preConcat(button.imageMatrix)
                        transform.preTranslate(bounds.left.toFloat(),bounds.top.toFloat())
                        transform.getValues(coords)
                        material.update(image,coords,bounds.width().toFloat(),bounds.height().toFloat(),screenW,screenH,
                            strength,minOf(bounds.width(),bounds.height())*.5f,
                            shortcutMapper.lock(button,coords,bounds.width().toFloat(),bounds.height().toFloat()))
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
        try {
            change { if(role==KeyguardGlassPolicy.TEXT)setEffect(v,originalEffect)
                else if(imageSlot) { if((v as ImageView).drawable===background)v.setImageDrawable(originalImage) }
                else if(v.background===background)v.background=originalBackground }
        } finally {
            KeyguardGlyphDraw.unbind(v);KeyguardLayeredShortcut.unbind(v)
            ownNativeOnGlyph=false;applied=false;material.clearImage()
        }
    }
}
