package com.coloros16.liquidglass.hook

import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.WeakHashMap

/** Cache only accessors, including missing optional members. Component/user values remain
 * live: Launcher can update an existing ItemInfo when an app or shortcut is rebound.
 */
internal object IconGlassIdentityAccess {
    data class Access(val component:Method?,val user:Field?,val shortcut:Method?)
    private val contracts=WeakHashMap<Class<*>,Access>()
    @Synchronized fun forType(type:Class<*>):Access = contracts.getOrPut(type) {
        Access(runCatching {type.getMethod("getTargetComponent")}.getOrNull(),field(type,"user"),
            runCatching {type.getMethod("getDeepShortcutId")}.getOrNull())
    }
    private fun field(type:Class<*>,name:String):Field? {
        var current:Class<*>?=type
        while(current!=null) {
            runCatching {current!!.getDeclaredField(name).apply {isAccessible=true}}.getOrNull()?.let {return it}
            current=current.superclass
        }
        return null
    }
}
