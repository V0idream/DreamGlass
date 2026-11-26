package com.coloros16.liquidglass.hook

import android.content.ComponentName
import android.content.Intent
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.WeakHashMap

/** Cache only accessors, including missing optional members. Component/user values remain
 * live: Launcher can update an existing ItemInfo when an app or shortcut is rebound.
 */
internal object IconGlassIdentityAccess {
    data class Access(val component:Method?,val user:Field?,val shortcut:Method?,val packageName:Method?,val intent:Method?)
    private val contracts=WeakHashMap<Class<*>,Access>()
    @Synchronized fun forType(type:Class<*>):Access = contracts.getOrPut(type) {
        fun method(name: String) = runCatching { type.getMethod(name) }.getOrNull()
        Access(method("getTargetComponent"), field(type,"user"), method("getDeepShortcutId"),
            method("getTargetPackage"), method("getIntent"))
    }

    fun component(item: Any): ComponentName? {
        val access = forType(item.javaClass)
        val target = runCatching { access.component?.invoke(item) as? ComponentName }.getOrNull()
        if (target != null && target.packageName.isNotEmpty()) return target
        val intent = runCatching { access.intent?.invoke(item) as? Intent }.getOrNull()
        intent?.component?.takeIf { it.packageName.isNotEmpty() }?.let { return it }
        val pkg = (runCatching { access.packageName?.invoke(item) as? String }.getOrNull()
            ?.takeIf { it.isNotEmpty() } ?: intent?.`package`)?.takeIf { it.isNotEmpty() } ?: return null
        return ComponentName(pkg, "")
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
