/* DreamGlass contributors, 2026. GPL-3.0-or-later. */
package com.coloros16.liquidglass.hook

import io.github.libxposed.api.XposedInterface
import kotlin.math.*

/** Historical perspective helpers remain debug math only. Production uses the
 * ordinary affine channel and composes each current native animation matrix.
 */
object LauncherTiltCompat {
    fun projection(out: FloatArray, progress: Float, strength: Float, perspective: Float,
        cx: Float, cy: Float, height: Float, direction: Float): Boolean {
        require(out.size == 16)
        java.util.Arrays.fill(out, 0f);out[0]=1f;out[5]=1f;out[10]=1f;out[15]=1f
        if (!progress.isFinite() || !strength.isFinite() || !perspective.isFinite() ||
            !cx.isFinite() || !cy.isFinite() || !height.isFinite() || height <= 0f || !direction.isFinite()) return false
        val p=progress.coerceIn(0f,1f)
        val angle = (sin(PI*p)*Math.toRadians(18.0)*strength.coerceIn(0f,2f)*direction.coerceIn(-1f,1f)).toFloat()
        if (abs(angle)<0.00001f) return false
        val sn=sin(angle);val cs=cos(angle);val d=perspective.coerceIn(0f,1.5f)*.65f/height
        val a=cx*d*sn;val b=cy*d*sn
        out[1]=a;out[3]=-a*cy;out[2]=cx*d*cs
        out[5]=cs+b;out[15]=1f-b;out[7]=(1f-cs)*cy-b*cy
        out[6]=cy*d*cs-sn;out[9]=sn;out[10]=cs;out[11]=-cy*sn
        out[13]=d*sn;out[14]=d*cs
        return true
    }

    /** COS17 JNI copies all 16 floats directly into android::mat4; it does not
     * transpose Java arrays. Convert our row-major math to that column-major ABI.
     * Each frame owns this array, so no second matrix or shared mutable scratch.
     */
    fun nativeProjection(out:FloatArray,progress:Float,strength:Float,perspective:Float,
        cx:Float,cy:Float,height:Float,direction:Float):Boolean {
        val active=projection(out,progress,strength,perspective,cx,cy,height,direction)
        if(active)for(r in 0..2)for(c in r+1..3) {
            val value=out[r*4+c];out[r*4+c]=out[c*4+r];out[c*4+r]=value
        }
        return active
    }

    fun install(api:XposedInterface,loader:ClassLoader):Boolean = LauncherTiltAffineHook.install(api,loader)
}
