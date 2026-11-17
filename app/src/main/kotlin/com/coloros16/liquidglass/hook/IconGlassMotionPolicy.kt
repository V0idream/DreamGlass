package com.coloros16.liquidglass.hook

/** Ignore subpixel noise while tracking the full ancestor-transformed icon rectangle. */
internal object IconGlassMotionPolicy {
    fun changed(l:Float,t:Float,r:Float,b:Float,nl:Float,nt:Float,nr:Float,nb:Float):Boolean =
        nl.isFinite() && nt.isFinite() && nr.isFinite() && nb.isFinite() && nr>nl && nb>nt &&
            (!l.isFinite() || kotlin.math.abs(l-nl)>=.5f || kotlin.math.abs(t-nt)>=.5f ||
                kotlin.math.abs(r-nr)>=.5f || kotlin.math.abs(b-nb)>=.5f)
}
