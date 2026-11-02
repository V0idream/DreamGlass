/* DreamGlass contributors, 2026. GPL-3.0-or-later. */
package com.coloros16.liquidglass.hook

/** Only numerical bounds from this native request's icon survive creation.
 * Never retain a View, Launcher, remote target or SurfaceControl here.
 */
internal data class LauncherTiltIconBounds(val left:Float,val top:Float,val right:Float,val bottom:Float)

internal class LauncherTiltLightAnchorScope {
    private data class Frame(val request:Any,val targets:Any,val bounds:LauncherTiltIconBounds)
    private val current=ThreadLocal<Frame?>()
    fun anchor(targets:Any,screenWidth:Float,screenHeight:Float):LauncherTiltIconAnchor? {
        val frame=current.get() ?: return null
        if(frame.targets!==targets)return null
        val b=frame.bounds
        return LauncherTiltIconAnchor.fromRects(b.left,b.top,b.right,b.bottom,0f,0f,screenWidth,screenHeight)
    }
    fun request():Any?=current.get()?.request
    fun <T> run(request:Any,targets:Any,bounds:LauncherTiltIconBounds,native:()->T):T {
        val previous=current.get();current.set(Frame(request,targets,bounds))
        try {return native()} finally {if(previous==null)current.remove() else current.set(previous)}
    }
}
