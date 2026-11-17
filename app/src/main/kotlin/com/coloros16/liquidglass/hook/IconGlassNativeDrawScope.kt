package com.coloros16.liquidglass.hook

/** Suppress only the legacy icon paint inside its engine call. The engine's native
 * animator must still run and return its own visibility/interception result. */
internal object IconGlassNativeDrawScope {
    private val owner=ScopedDrawOwner<Any>()
    fun suppresses(manager:Any?):Boolean=manager!=null && owner.current()===manager
    fun <T> run(manager:Any?,nativeAnimation:()->T):T=owner.run(manager,nativeAnimation)

    /** A native animator may already have drawn the content (including two clipped passes).
     * Otherwise live mode must explicitly use that content entry before returning true, so
     * TextView cannot replay the prefab over the GPU draw. Prefab mode keeps native routing.
     */
    fun finish(intercepted:Boolean,live:Boolean,content:()->Unit):Boolean {
        if(intercepted)return true
        if(!live)return false
        content()
        return true
    }
}
