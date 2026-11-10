/* DreamGlass contributors, 2026. GPL-3.0-or-later. */
package com.coloros16.liquidglass.hook

internal enum class NativeClearSourceKind { M,L,BITMAP,NONE }

/** Mirrors the actual native alternatives: M is prepared at record time, L can
 * be ready earlier, and disabled AGSL still has a legitimate bitmap draw path. */
internal object NativeClearSourcePolicy {
    fun choose(agslEnabled:Boolean,mReady:Boolean,lReady:Boolean,bitmapReady:Boolean):NativeClearSourceKind = when {
        agslEnabled && mReady -> NativeClearSourceKind.M
        agslEnabled && lReady -> NativeClearSourceKind.L
        bitmapReady -> NativeClearSourceKind.BITMAP
        else -> NativeClearSourceKind.NONE
    }
}
