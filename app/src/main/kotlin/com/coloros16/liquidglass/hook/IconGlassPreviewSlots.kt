package com.coloros16.liquidglass.hook

import android.graphics.Canvas
import android.graphics.drawable.Drawable
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.ArrayList

internal object IconGlassFolderPreviewContract {
    fun pageEntry(manager: Class<*>): Method {
        val prefix = arrayOf(Canvas::class.java, Integer.TYPE, ArrayList::class.java)
        // ColorOS 17.5.5 removed the explicit scale from the paged draw method.
        // Keep the page/nav branch with the cache check, not the list-only overload.
        return listOf(
            prefix + java.lang.Boolean::class.java,
            prefix + arrayOf(java.lang.Float.TYPE, java.lang.Boolean::class.java),
        ).firstNotNullOfOrNull { signature ->
            runCatching { manager.getDeclaredMethod("drawParams", *signature) }.getOrNull()
                ?.takeIf { it.returnType == Void.TYPE }
        } ?: throw NoSuchMethodException("${manager.name}#drawParams(Canvas, int, ArrayList, …)")
    }
}

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
