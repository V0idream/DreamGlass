/* DreamGlass contributors, 2026. GPL-3.0-or-later. */
package com.coloros16.liquidglass.hook

internal object LauncherFolderSurfacePolicy {
    /** Desktop large-folder preview only; never an arbitrary widget, opened
     * folder root, nested application icon or a guessed rectangle by dimensions.
     */
    fun eligible(verifiedOwner:Boolean,sameBackground:Boolean,spanX:Int,spanY:Int):Boolean =
        verifiedOwner && sameBackground && spanX in 1..8 && spanY in 1..8 && (spanX>1 || spanY>1)
}
