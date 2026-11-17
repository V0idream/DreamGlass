package com.coloros16.liquidglass.hook

import android.graphics.drawable.Drawable
import java.lang.reflect.Field
import java.lang.reflect.Method

/** A stacked tile is a layout of independent icon slots, never one app icon. Reflection is
 * resolved once against the launcher ABI; traversal does not inspect arbitrary view trees. */
internal class IconGlassPreviewSlots(private val drawable:Field,private val item:Field,
    private val x:Field,private val y:Field,private val scale:Field,
    private val stacked:Class<*>,private val children:Method) {
    data class Slot(val owner:Any,val field:Field,val drawable:Drawable,val item:Any?,
        val x:Float,val y:Float,val scale:Float)
    fun visit(roots:Iterable<*>,accept:(Slot)->Unit) {
        var remaining=64
        fun visitOne(node:Any,px:Float,py:Float,ps:Float,depth:Int) {
            if(remaining--<=0 || depth>3)return
            val nx=px+ps*x.getFloat(node);val ny=py+ps*y.getFloat(node);val ns=ps*scale.getFloat(node)
            if(!nx.isFinite() || !ny.isFinite() || !ns.isFinite() || ns<=0f)return
            if(stacked.isInstance(node)) {
                (children.invoke(node) as? Iterable<*>)?.forEach {if(it!=null)visitOne(it,nx,ny,ns,depth+1)}
            } else {
                val image=drawable.get(node) as? Drawable ?: return
                accept(Slot(node,drawable,image,item.get(node),nx,ny,ns))
            }
        }
        roots.forEach {if(it!=null)visitOne(it,0f,0f,1f,0)}
    }
}
