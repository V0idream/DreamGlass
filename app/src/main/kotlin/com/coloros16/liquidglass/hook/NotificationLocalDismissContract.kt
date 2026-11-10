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
    /** Real collection acceptance is sufficient. Same-package notifications can
     * be top-level rows or OEM bundles without a notificationParent relation. */
    fun accepted(clearable:Boolean,before:String?,after:String?):Boolean =
        clearable && before=="NOT_DISMISSED" && after in setOf("DISMISSED","PARENT_DISMISSED")

    /** An accepted whole-row image already contains its attached child pixels.
     * An ongoing ancestor is NOT selected and does not exclude ordinary children. */
    fun <T:Any> coveredByAncestor(view:T,parent:(T)->T?,selected:(T)->Boolean):Boolean {
        val seen=java.util.Collections.newSetFromMap(java.util.IdentityHashMap<T,Boolean>())
        var current=parent(view)
        repeat(64) {
            val node=current ?: return false
            if(!seen.add(node) || node===view)return true
            if(selected(node))return true
            current=parent(node)
        }
        return true // Do not animate an unbounded/malformed hierarchy.
    }

    /** Exact stock Lambda5 completion layout. A different ROM keeps its native
     * close order rather than guessing at runnable/consumer callback semantics. */
    fun completionRoute(callback:Class<*>,stack:Class<*>):Method?=runCatching {
        check(java.util.function.Consumer::class.java.isAssignableFrom(callback))
        val fields=callback.declaredFields.filter {!Modifier.isStatic(it.modifiers)}
        check(fields.size==3 && fields.count {it.type==stack}==1 &&
            fields.count {List::class.java.isAssignableFrom(it.type)}==1 &&
            fields.count {it.type==Int::class.javaPrimitiveType}==1)
        callback.getDeclaredMethod("accept",Any::class.java).also {
            check(it.returnType==Void.TYPE && !Modifier.isAbstract(it.modifiers));it.isAccessible=true
        }
    }.getOrNull()

    /** First native completion wins, even on re-entry. Never retries an already
     * invoked native callback after partial side effects or a post-collapse replay. */
    class OnceCompletion {
        private var invoked=false
        fun claim():Boolean {
            if(invoked)return false
            invoked=true
            return true
        }
    }

    data class PrepareRoute(val clear:Method,val includes:Method,val children:Method,val adapter:Method,val entry:Method)
    fun prepareRoute(stack:Class<*>,row:Class<*>):PrepareRoute?=runCatching {
        val clear=stack.getDeclaredMethod("clearNotifications",Int::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,Boolean::class.javaPrimitiveType)
        val includes=stack.getDeclaredMethod("includeChildInClearAll",row,Int::class.javaPrimitiveType)
        val children=row.getMethod("getAttachedChildren")
        val adapter=row.getMethod("getEntryAdapter")
        val entry=adapter.returnType.getMethod("getEntry")
        check(clear.returnType==Void.TYPE && !Modifier.isAbstract(clear.modifiers) &&
            Modifier.isStatic(includes.modifiers) && includes.returnType==Boolean::class.javaPrimitiveType &&
            List::class.java.isAssignableFrom(children.returnType) && entry.returnType!=Void.TYPE)
        clear.isAccessible=true;includes.isAccessible=true
        PrepareRoute(clear,includes,children,adapter,entry)
    }.getOrNull()
}
