/* DreamGlass contributors, 2026. GPL-3.0-or-later. */
package com.coloros16.liquidglass.hook

import kotlin.math.abs
import kotlin.math.hypot
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference

/** Native animation endpoint in the SAME screen/window space as screenRectF.
 * Store numbers only, never a launcher View, surface, animator or cached icon.
 */
internal data class LauncherTiltIconAnchor(
    val deltaX:Float,val deltaY:Float,val fractionX:Float,val fractionY:Float,
) {
    val distance:Float get()=hypot(fractionX,fractionY).coerceAtMost(1f)
    companion object {
        fun fromRects(iconLeft:Float,iconTop:Float,iconRight:Float,iconBottom:Float,
            windowLeft:Float,windowTop:Float,windowRight:Float,windowBottom:Float):LauncherTiltIconAnchor? {
            if(!floatArrayOf(iconLeft,iconTop,iconRight,iconBottom,windowLeft,windowTop,windowRight,windowBottom)
                    .all {it.isFinite()})return null
            val iw=iconRight-iconLeft;val ih=iconBottom-iconTop
            val ww=windowRight-windowLeft;val wh=windowBottom-windowTop
            if(iw<=0f || ih<=0f || ww<=0f || wh<=0f || iw.toDouble()*ih>=ww.toDouble()*wh*.5)return null
            val dx=(iconLeft+iw*.5f)-(windowLeft+ww*.5f)
            val dy=(iconTop+ih*.5f)-(windowTop+wh*.5f)
            val fx=dx/(ww*.5f);val fy=dy/(wh*.5f)
            // An off-screen/foreign-coordinate endpoint is not a legitimate
            // excuse to substitute a fixed angle. Keep that native frame intact.
            if(!dx.isFinite() || !dy.isFinite() || abs(fx)>1.25f || abs(fy)>1.25f)return null
            return LauncherTiltIconAnchor(dx,dy,fx.coerceIn(-1f,1f),fy.coerceIn(-1f,1f))
        }
    }
}

/** The actual executing native owner supplies its endpoint for this helper.
 * A delayed native callback enters its own scope as well. Nothing survives a
 * return/cancel/exception, so a reused helper cannot inherit a previous app icon.
 */
internal class LauncherTiltAnchorScope {
    private data class Frame(val helper:Any,val animation:Any,val read:()->LauncherTiltIconAnchor?)
    private val current=ThreadLocal<Frame?>()
    fun anchor(helper:Any):LauncherTiltIconAnchor? {
        val frame=current.get() ?: return null
        return if(frame.helper===helper)frame.read() else null
    }
    fun owner():Any?=current.get()?.animation
    fun capture():LauncherTiltCapturedAnchor? {
        val frame=current.get() ?: return null
        val anchor=frame.read() ?: return null
        return LauncherTiltCapturedAnchor(frame.helper,frame.animation,anchor)
    }
    fun <T> run(helper:Any,animation:Any,read:()->LauncherTiltIconAnchor?,native:()->T):T {
        val previous=current.get();current.set(Frame(helper,animation,read))
        try {return native()} finally {if(previous==null)current.remove() else current.set(previous)}
    }
}

internal data class LauncherTiltCapturedAnchor(val helper:Any,val animation:Any,val anchor:LauncherTiltIconAnchor)

/** A native delayed Function2 can outlive onUpdate. Bind metadata to that exact
 * callback identity without retaining its animation, helper or callback. This
 * also supports callbacks which do not themselves capture an animation owner.
 */
internal class LauncherTiltAnchorRegistry {
    private class Key(value:Any,queue:ReferenceQueue<Any>?=null):WeakReference<Any>(value,queue) {
        private val hash=System.identityHashCode(value)
        override fun hashCode()=hash
        override fun equals(other:Any?):Boolean= this===other ||
            (other is Key && get()!=null && get()===other.get())
    }
    private data class Binding(val helper:WeakReference<Any>,val animation:WeakReference<Any>,val anchor:LauncherTiltIconAnchor)
    private val queue=ReferenceQueue<Any>()
    private val bindings=HashMap<Key,Binding>()
    private fun clean() {
        while(true) {
            val key=queue.poll() as? Key ?: break
            bindings.remove(key)
        }
    }
    @Synchronized fun bind(callback:Any,captured:LauncherTiltCapturedAnchor?) {
        clean();val key=Key(callback)
        if(captured==null)bindings.remove(key)
        else bindings[Key(callback,queue)]=Binding(WeakReference(captured.helper),WeakReference(captured.animation),captured.anchor)
    }
    @Synchronized fun resolve(callback:Any,actualHelper:Any):LauncherTiltCapturedAnchor? {
        clean();val key=Key(callback);val binding=bindings[key] ?: return null
        val helper=binding.helper.get();val animation=binding.animation.get()
        if(helper==null || animation==null) {bindings.remove(key);return null}
        return if(helper===actualHelper)LauncherTiltCapturedAnchor(helper,animation,binding.anchor) else null
    }
}
