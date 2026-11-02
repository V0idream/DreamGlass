package com.coloros16.liquidglass.hook

/** Scope must mask an active ancestor even for an inactive nested draw. Never
 * retain a View outside native drawing; restore correctly on exceptions/reentry.
 */
internal class ScopedDrawOwner<T:Any> {
    private val owner=ThreadLocal<T?>()
    fun current():T?=owner.get()
    fun <R> run(value:T?,draw:()->R):R {
        val previous=owner.get()
        if(value==null)owner.remove() else owner.set(value)
        try {return draw()} finally {if(previous==null)owner.remove() else owner.set(previous)}
    }
}
