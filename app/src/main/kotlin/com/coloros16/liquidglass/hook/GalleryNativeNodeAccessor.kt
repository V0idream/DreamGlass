/* DreamGlass contributors, 2026. GPL-3.0-or-later. */
package com.coloros16.liquidglass.hook

import java.lang.reflect.Method

/** Resolve the native node by its RUNTIME value, not an OEM stub's declared
 * return type. Some View wrappers expose getRenderNode only on their actual
 * implementation. The only fallback is View's exact, correctly typed field;
 * no arbitrary field scan or View.draw recursion is allowed. Android-free so
 * the same reflective contract is exercised by local regression tests. */
internal class GalleryNativeNodeAccessor(private val viewType:Class<*>,private val nodeType:Class<*>) {
    data class Result(val node:Any,val route:String)
    private val wrapperGetter=runCatching {
        viewType.getMethod("getViewWrapper").apply {runCatching {isAccessible=true}}
    }.getOrNull()
    private val nativeField=runCatching {
        viewType.getDeclaredField("mRenderNode").apply {
            check(type==nodeType);isAccessible=true
        }
    }.getOrNull()
    private val methods=HashMap<Class<*>,Method?>()
    val available get()=wrapperGetter!=null || nativeField!=null
    fun read(view:Any):Result? {
        if(!viewType.isInstance(view))return null
        val wrapper=runCatching {wrapperGetter?.invoke(view)}.getOrNull()
        if(wrapper!=null) {
            val type=wrapper.javaClass
            val method=if(methods.containsKey(type))methods[type] else runCatching {
                type.getMethod("getRenderNode").apply {runCatching {isAccessible=true}}
            }.getOrNull().also {methods[type]=it}
            val node=runCatching {method?.invoke(wrapper)}.getOrNull()
            if(node!=null && nodeType.isInstance(node))return Result(node,"runtime-wrapper")
        }
        val node=runCatching {nativeField?.get(view)}.getOrNull()
        return if(node!=null && nodeType.isInstance(node))Result(node,"typed-View.mRenderNode") else null
    }
}
