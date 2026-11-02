/* DreamGlass contributors, 2026. GPL-3.0-or-later. */
package com.coloros16.liquidglass.hook

import kotlin.math.*

/** Orthographic projection of a rotated plane, composed with the CURRENT native
 * affine matrix. Deliberately no undocumented OEM 4x4 state: parallel projection
 * gives banking/foreshortening, not a near/far perspective trapezoid. The native
 * scale, centre trajectory, crop, alpha and animation clock remain authoritative.
 */
internal object LauncherTiltAffine {
    /** Icon-directed production path. Orthographic foreshortening follows the
     * real endpoint's bearing; bank encodes its signed side (including opposite
     * endpoints, which a pure orthographic projection alone cannot distinguish).
     */
    fun composeTowardsIcon(native:FloatArray,out:FloatArray,progress:Float,strength:Float,depth:Float,
        cx:Float,cy:Float,anchor:LauncherTiltIconAnchor?):Boolean {
        require(native.size==9 && out.size==9 && native!==out)
        native.copyInto(out)
        if(anchor==null || !native.all {it.isFinite()} || !progress.isFinite() || !strength.isFinite() ||
            !depth.isFinite() || !cx.isFinite() || !cy.isFinite() ||
            native[6]!=0f || native[7]!=0f || native[8]!=1f)return false
        if(!floatArrayOf(anchor.deltaX,anchor.deltaY,anchor.fractionX,anchor.fractionY).all {it.isFinite()})return false
        val distance=anchor.distance
        val p=progress.coerceIn(0f,1f)
        if(p<=0f || p>=1f || strength<=0f || distance<=.06f)return false
        val determinant=native[0].toDouble()*native[4]-native[1].toDouble()*native[3]
        if(kotlin.math.abs(determinant)<1e-12)return false
        // Endpoints are screen-space values; convert their direction through the
        // current native basis so landscape/native rotation does not reverse it.
        val lx=(native[4].toDouble()*anchor.deltaX-native[1].toDouble()*anchor.deltaY)/determinant
        val ly=(-native[3].toDouble()*anchor.deltaX+native[0].toDouble()*anchor.deltaY)/determinant
        val length=hypot(lx,ly)
        if(!length.isFinite() || length<=1e-9)return false
        val nx=lx/length;val ny=ly/length
        val amount=sin(PI*p).toFloat()*strength.coerceIn(0f,2f)*distance
        if(amount<.00001f)return false
        val ct=cos(Math.toRadians((25f*amount).toDouble()))
        val xx=1.0+(ct-1.0)*nx*nx;val xy=(ct-1.0)*nx*ny
        val yy=1.0+(ct-1.0)*ny*ny
        val signedSide=(anchor.fractionX*.85f+anchor.fractionY*.15f).coerceIn(-1f,1f)
        val bank=Math.toRadians((3.5f*amount*depth.coerceIn(0f,1.5f)*signedSide).toDouble())
        val cb=cos(bank);val sb=sin(bank)
        val a=(cb*xx-sb*xy).toFloat();val b=(cb*xy-sb*yy).toFloat()
        val c=(sb*xx+cb*xy).toFloat();val d=(sb*xy+cb*yy).toFloat()
        val tx=cx-a*cx-b*cy;val ty=cy-c*cx-d*cy
        out[0]=native[0]*a+native[1]*c;out[1]=native[0]*b+native[1]*d
        out[2]=native[0]*tx+native[1]*ty+native[2]
        out[3]=native[3]*a+native[4]*c;out[4]=native[3]*b+native[4]*d
        out[5]=native[3]*tx+native[4]*ty+native[5]
        if(!out.all {it.isFinite()}) {native.copyInto(out);return false}
        return true
    }

    /** Historical scalar fixture kept for older debug probes, not used by the
     * production hook. Production requires an actual two-dimensional icon anchor.
     */
    fun compose(native:FloatArray,out:FloatArray,progress:Float,strength:Float,depth:Float,
        cx:Float,cy:Float,direction:Float):Boolean {
        require(native.size==9 && out.size==9)
        // Reject aliased buffers: a caller must never mutate a shared OEM Matrix.
        require(native!==out)
        native.copyInto(out)
        if(!native.all { it.isFinite() } || !progress.isFinite() || !strength.isFinite() ||
            !depth.isFinite() || !cx.isFinite() || !cy.isFinite() || !direction.isFinite() ||
            native[6]!=0f || native[7]!=0f || native[8]!=1f) return false
        val p=progress.coerceIn(0f,1f)
        if(p<=0f || p>=1f || strength<=0f || direction==0f)return false
        val envelope=sin(PI*p).toFloat()
        val amount=envelope*strength.coerceIn(0f,2f)
        if(amount<.00001f)return false
        val sign=direction.coerceIn(-1f,1f)
        val tilt=Math.toRadians((25f*amount*sign).toDouble())
        val axis=Math.toRadians((35f*depth.coerceIn(0f,1.5f)).toDouble())
        val ux=cos(axis);val uy=sin(axis);val ct=cos(tilt)
        val xx=ct+(1-ct)*ux*ux;val xy=(1-ct)*ux*uy
        val yy=ct+(1-ct)*uy*uy
        val bank=Math.toRadians((3.5f*amount*sign*depth.coerceIn(0f,1.5f)).toDouble())
        val cb=cos(bank);val sb=sin(bank)
        val a=(cb*xx-sb*xy).toFloat();val b=(cb*xy-sb*yy).toFloat()
        val c=(sb*xx+cb*xy).toFloat();val d=(sb*xy+cb*yy).toFloat()
        val tx=cx-a*cx-b*cy;val ty=cy-c*cx-d*cy
        // out = native * localTilt. Translation is composed, never replaced by
        // an identity-based projection. The same source pivot maps to native's.
        out[0]=native[0]*a+native[1]*c;out[1]=native[0]*b+native[1]*d
        out[2]=native[0]*tx+native[1]*ty+native[2]
        out[3]=native[3]*a+native[4]*c;out[4]=native[3]*b+native[4]*d
        out[5]=native[3]*tx+native[4]*ty+native[5]
        if(!out.all { it.isFinite() }) {native.copyInto(out);return false}
        return true
    }
}

/** A callback owns only its target's writes on its own thread. No retained leash,
 * transaction map, completion timer or second animation is needed. Native writes
 * after completion/cancellation naturally carry no extra transform.
 */
internal class LauncherTiltAffineScope {
    data class Pose(val progress:Float,val strength:Float,val depth:Float,val cx:Float,val cy:Float,val direction:Float,
        val iconAnchor:LauncherTiltIconAnchor?=null)
    private data class Frame(val owner:Any,val surface:Any,val pose:()->Pose?)
    private val current=ThreadLocal<Frame?>()
    fun pose(owner:Any,surface:Any):Pose? {
        val frame=current.get() ?: return null
        return if(frame.owner===owner && frame.surface===surface)frame.pose() else null
    }
    fun <T> run(owner:Any,surface:Any,pose:()->Pose?,native:()->T):T {
        val previous=current.get();current.set(Frame(owner,surface,pose))
        try {return native()} finally {if(previous==null)current.remove() else current.set(previous)}
    }
}
