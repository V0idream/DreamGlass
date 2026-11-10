/* DreamGlass contributors, 2026. GPL-3.0-or-later. */
package com.coloros16.liquidglass.hook

import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** A dialog body has two native background hosts, but exactly one GPU paint.
 * References are weak so dismissed windows cannot be retained by the hooks. */
internal class GalleryDialogPaintRoute<T:Any> {
    private val overlays=WeakHashMap<T,WeakReference<T>>()
    private val panels=WeakHashMap<T,WeakReference<T>>()
    fun overlay(panel:T):T?=overlays[panel]?.get()
    fun panel(overlay:T):T?=panels[overlay]?.get()?.takeIf {this.overlay(it)===overlay}
    fun bind(panel:T,overlay:T) {
        if(this.overlay(panel)===overlay && this.panel(overlay)===panel)return
        unbind(panel)
        this.panel(overlay)?.let {overlays.remove(it)}
        overlays[panel]=WeakReference(overlay)
        panels[overlay]=WeakReference(panel)
    }
    fun unbind(panel:T):T?=overlays.remove(panel)?.get()?.also {panels.remove(it)}
    fun clear() {overlays.clear();panels.clear()}
    fun paintPanel(host:T,isPanel:Boolean,visible:(T)->Boolean,leased:(T)->Boolean):T? {
        if(isPanel) {
            val overlay=overlay(host)
            return if(overlay!=null && visible(overlay) && leased(overlay))null else host
        }
        return panel(host)?.takeIf {visible(host) && leased(host)}
    }
}
