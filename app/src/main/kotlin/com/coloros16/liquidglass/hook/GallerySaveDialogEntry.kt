/* DreamGlass contributors, 2026. GPL-3.0-or-later. */
package com.coloros16.liquidglass.hook

import java.lang.reflect.Method

/** Exact entry used by nae.B0 -> ap5.k in Gallery 17.10.41. Kept separate
 * from toolbar material discovery: saving must not depend on a16/COUI setters. */
internal object GallerySaveDialogEntry {
    data class Contract(val show:Method,val context:Method)
    fun resolve(builder:Class<*>,view:Class<*>,dialog:Class<*>,alertDialog:Class<*>,context:Class<*>):Contract {
        require(dialog.isAssignableFrom(alertDialog))
        val show=builder.getDeclaredMethod("show",view)
        require(alertDialog.isAssignableFrom(show.returnType))
        // getContext belongs to AlertDialog.Builder and may be inherited.
        val getter=builder.getMethod("getContext")
        require(context.isAssignableFrom(getter.returnType))
        return Contract(show,getter)
    }
}
