/* DreamGlass contributors, 2026. GPL-3.0-or-later. */
package com.coloros16.liquidglass.hook

import android.graphics.*
import com.coloros16.liquidglass.liquidglass.LiquidGlassShader
import kotlin.math.ceil

/** References an existing HWUI content node. No Bitmap, readback, screenshot,
 * CPU blur, View.draw recursion, or frame timer. A changed source display list
 * remains a live node reference, rather than a frozen recorded copy. */
internal class LiveContentGlassNode {
    private val node=RenderNode("DreamGlass live native content")
    private val material=AppGlassEffect()
    private var source:RenderNode?=null
    private var values:List<Float>?=null
    private var area=RectF()
    private var params:LiquidGlassShader.Params?=null
    private var alpha=1f
    private var dim=0f
    var effect:RenderEffect?=null;private set
    fun update(content:RenderNode,parentToHost:Matrix,bounds:RectF,p:LiquidGlassShader.Params,opacity:Float,dimAmount:Float=0f,refreshContent:Boolean=false):Boolean {
        require(!bounds.isEmpty && bounds.width()<=8192 && bounds.height()<=8192 && content!==node)
        val matrix=FloatArray(9).also(parentToHost::getValues).toList()
        require(matrix.all(Float::isFinite))
        val geometry=source!==content || values!=matrix || area!=bounds || dim!=dimAmount
        val optics=params!=p || alpha!=opacity
        if(!geometry && !optics && !refreshContent)return false
        val w=ceil(bounds.width()).toInt();val h=ceil(bounds.height()).toInt()
        if(optics) {
            // Source and mask are canonical node-local coordinates. Native host
            // offsets are applied only by draw(), never inside shader uniforms.
            effect=material.create(p,RectF(0f,0f,bounds.width(),bounds.height()),opacity)
            node.setRenderEffect(effect)
        }
        node.setPosition(0,0,w,h);node.setClipToBounds(true)
        node.setOutline(Outline().apply {setRoundRect(0,0,w,h,p.cornerRadius)})
        node.setClipToOutline(true)
        if(geometry || refreshContent) {
            val c=node.beginRecording(w,h)
            try {
                c.translate(-bounds.left,-bounds.top)
                c.concat(parentToHost)
                // The source node's own left/top/transform are applied by HWUI.
                // parentToHost maps its PARENT, so no source offset is doubled.
                c.drawRenderNode(content)
                if(dimAmount>0f) {
                    c.setMatrix(Matrix())
                    c.drawColor(Color.argb((dimAmount.coerceIn(0f,1f)*255f).toInt(),0,0,0))
                }
            } finally {node.endRecording()}
        }
        source=content;values=matrix;area=RectF(bounds);params=p;alpha=opacity;dim=dimAmount
        // A native source frame may need its GPU input list refreshed without
        // scheduling another UI frame. Otherwise onPreDraw -> invalidate would
        // sustain a loop even after Gallery stops changing.
        return geometry || optics
    }
    fun draw(canvas:Canvas) {
        if(!canvas.isHardwareAccelerated || effect==null || !node.hasDisplayList())return
        val save=canvas.save()
        try {canvas.translate(area.left,area.top);canvas.drawRenderNode(node)} finally {canvas.restoreToCount(save)}
    }
    fun close() {source=null;effect=null;node.setRenderEffect(null);node.discardDisplayList()}
}
