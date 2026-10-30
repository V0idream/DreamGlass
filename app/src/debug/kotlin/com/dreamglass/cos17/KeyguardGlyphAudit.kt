package com.dreamglass.cos17

import android.graphics.*
import android.view.View
import android.widget.TextView
import com.coloros16.liquidglass.diagnostics.RuntimeDiagnostics
import com.coloros16.liquidglass.diagnostics.RuntimeStore
import com.coloros16.liquidglass.hook.HardwareBackdropBlur
import com.coloros16.liquidglass.log.Lg
import java.util.concurrent.atomic.AtomicBoolean

/** Debug-only, one real glyph per SystemUI process. GPU-render all branches, then read back
 * for comparison/export. No CPU blur and no change to the real View or its native effect.
 */
object KeyguardGlyphAudit {
    private val done=AtomicBoolean()
    @JvmStatic fun capture(view:View,native:RenderEffect?,glass:RenderEffect?) {
        if(view.width<100 || view.height<400 || !view.isShown || glass==null || !done.compareAndSet(false,true))return
        val context=Lg.processContext() ?: return
        val w=view.width;val h=view.height
        val node=RenderNode("DreamGlass real glyph audit");node.setPosition(0,0,w,h)
        val canvas=node.beginRecording(w,h)
        try { view.draw(canvas) } finally { node.endRecording() }
        val metadata="${view.javaClass.name}; nativeEffect=${native!=null}; background=${view.background?.javaClass?.simpleName}; paintShader=${(view as? TextView)?.paint?.shader?.javaClass?.simpleName}"
        RuntimeDiagnostics.mark("keyguard_glyph_audit",metadata)
        Thread({
            try {
                val outW=minOf(w,224);val outH=minOf(h,448)
                HardwareBackdropBlur().use { renderer ->
                    for((name,effect) in listOf("raw" to null,"native" to native,"glass" to glass,
                        "combined" to if(native!=null)RenderEffect.createChainEffect(glass,native) else glass)) {
                        node.setRenderEffect(effect)
                        val gpu=renderer.render(node,w,h,outW,outH,.01f) { false }
                        val pixels=gpu.copy(Bitmap.Config.ARGB_8888,false) ?: error("GPU readback unavailable")
                        var covered=0;var opaque=0
                        for(y in 0 until outH)for(x in 0 until outW) {
                            val alpha=Color.alpha(pixels.getPixel(x,y));if(alpha>16)covered++;if(alpha>240)opaque++
                        }
                        RuntimeDiagnostics.mark("keyguard_glyph_$name","covered=$covered/${outW*outH}; opaque=$opaque; ${outW}x$outH")
                        val uri=RuntimeStore.URI.buildUpon().appendPath("glyph-debug").appendPath("$name.png").build()
                        context.contentResolver.openOutputStream(uri,"w")?.use { pixels.compress(Bitmap.CompressFormat.PNG,100,it) }
                        pixels.recycle()
                    }
                }
            } catch(error:Throwable) { RuntimeDiagnostics.mark("keyguard_glyph_audit_error","${error.javaClass.simpleName}: ${error.message}") }
            finally { node.discardDisplayList() }
        },"DreamGlass-glyph-audit").apply { isDaemon=true }.start()
    }
}
