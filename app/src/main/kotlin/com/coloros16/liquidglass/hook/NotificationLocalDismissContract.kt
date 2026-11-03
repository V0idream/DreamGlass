/* DreamGlass contributors, 2026. GPL-3.0-or-later. */
package com.coloros16.liquidglass.hook

import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** COS17 collection commit, after dismissal interceptors have filtered entries.
 * A mixed group can retain its summary and bypass SwipeHelper for its children. */
internal object NotificationLocalDismissContract {
    data class Route(val commit:Method,val row:Method,val state:Method,val clearable:Method)
    fun resolve(collection:Class<*>,entry:Class<*>,row:Class<*>):Route?=runCatching {
        val commit=collection.getDeclaredMethod("locallyDismissNotifications",List::class.java,Boolean::class.javaPrimitiveType)
        val rowGetter=entry.getMethod("getRow")
        val state=entry.getMethod("getDismissState")
        val clearable=entry.getMethod("isClearable")
        check(commit.returnType==Void.TYPE && !Modifier.isAbstract(commit.modifiers) &&
            rowGetter.returnType==row && state.returnType.isEnum && clearable.returnType==Boolean::class.javaPrimitiveType)
        check(state.returnType.enumConstants.map {it.toString()}.containsAll(listOf("NOT_DISMISSED","DISMISSED","PARENT_DISMISSED")))
        commit.isAccessible=true
        Route(commit,rowGetter,state,clearable)
    }.getOrNull()
    fun accepted(clearable:Boolean,retainedParent:Boolean,ongoingSibling:Boolean,before:String?,after:String?):Boolean =
        clearable && retainedParent && ongoingSibling && before=="NOT_DISMISSED" &&
            after in setOf("DISMISSED","PARENT_DISMISSED")
}
