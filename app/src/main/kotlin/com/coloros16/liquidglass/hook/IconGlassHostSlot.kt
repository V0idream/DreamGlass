package com.coloros16.liquidglass.hook

import android.graphics.drawable.Drawable
import android.view.View
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.WeakHashMap

/** Rebind the native icon node, including OEM setIcon vetoes and retained compound caches. */
internal object IconGlassHostSlot {
    private data class Contract(val icon:Field,val setter:Method,val compound:Method,val visible:Method?)
    private val contracts=WeakHashMap<Class<*>,Contract?>()
    private val changing=ThreadLocal<Boolean>()
    fun isBinding()=changing.get()==true
    private fun contract(view:View):Contract? {
        if(contracts.containsKey(view.javaClass))return contracts[view.javaClass]
        val value=runCatching {
            var type:Class<*>?=view.javaClass;var icon:Field?=null
            while(type!=null && icon==null) {
                icon=runCatching {type!!.getDeclaredField("mIcon").apply {isAccessible=true}}.getOrNull()
                type=type.superclass
            }
            Contract(checkNotNull(icon),view.javaClass.getMethod("setIcon",Drawable::class.java),
                view.javaClass.getMethod("applyCompoundDrawables",Drawable::class.java),
                runCatching {view.javaClass.getMethod("getIconVisible")}.getOrNull())
        }.getOrNull()
        contracts[view.javaClass]=value
        return value
    }
    fun supported(view:View)=contract(view)!=null
    fun bind(view:View,drawable:Drawable):Boolean {
        val c=contract(view) ?: return false
        val previous=changing.get();changing.set(true)
        try {
            c.setter.invoke(view,drawable)
            // COS17 can veto setIcon, leaving both mIcon and its retained icon node unchanged.
            if(c.icon.get(view)!==drawable)c.icon.set(view,drawable)
            if(c.visible?.invoke(view)!=false)c.compound.invoke(view,drawable)
            view.invalidate()
            check(c.icon.get(view)===drawable)
            return true
        }finally {if(previous==null)changing.remove() else changing.set(previous)}
    }
}
