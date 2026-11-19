package com.coloros16.liquidglass.hook

/** Same version number, different R8 names. Validate the complete captured frame
 * instead of trusting that ma.r0 still represents a window animation. */
object LauncherTiltContract {
    fun callback(candidates:List<Class<*>>,target:Class<*>,helper:Class<*>,transaction:Class<*>,params:Class<*>,
        fields:List<String> = listOf("a", "b", "c", "d")):Class<*>? =
        candidates.firstOrNull { c -> runCatching {
            c.getDeclaredField(fields[0]).type==target && c.getDeclaredField(fields[1]).type==helper &&
                c.getDeclaredField(fields[2]).type==transaction && c.getDeclaredField(fields[3]).type==params &&
                c.getDeclaredMethod("run").returnType==Void.TYPE
        }.getOrDefault(false) }
}
