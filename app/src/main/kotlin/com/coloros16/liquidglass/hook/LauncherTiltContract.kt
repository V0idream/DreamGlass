package com.coloros16.liquidglass.hook

/** Same version number, different R8 names. Validate the complete captured frame
 * instead of trusting that ma.r0 still represents a window animation. */
object LauncherTiltContract {
    fun callback(candidates:List<Class<*>>,target:Class<*>,helper:Class<*>,transaction:Class<*>,params:Class<*>):Class<*>? =
        candidates.firstOrNull { c -> runCatching {
            c.getDeclaredField("a").type==target && c.getDeclaredField("b").type==helper &&
                c.getDeclaredField("c").type==transaction && c.getDeclaredField("d").type==params &&
                c.getDeclaredMethod("run").returnType==Void.TYPE
        }.getOrDefault(false) }
}
