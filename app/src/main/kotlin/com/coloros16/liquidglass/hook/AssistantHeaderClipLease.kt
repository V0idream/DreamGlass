/* DreamGlass contributors, 2026. GPL-3.0-or-later. */
package com.coloros16.liquidglass.hook

import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Path
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** ImageView.onDraw clips its icon, but that clip cannot constrain the OEM
 * background RenderEffect drawn by the View's RenderNode. Shelf also has a
 * distinct *_blur sibling behind each control. Lease BOTH nodes' outlines using
 * the proven native fill Path; do not dim, hide or replace foreground drawables.
 */
internal object AssistantHeaderClipLease {
    private class State {
        val peers=ArrayList<WeakReference<View>>(1)
        var geometry=0
    }
    private val states=WeakHashMap<View,State>()

    fun sync(control:View,active:Boolean,nativePath:(View)->Path?):Boolean {
        if(!active) {
            val state=states.remove(control)
            var changed=restore(control,control)
            state?.peers?.forEach {it.get()?.let {peer -> changed=restore(peer,control) || changed}}
            return changed
        }
        val path=nativePath(control)
        if(path==null || path.isEmpty)return false
        val state=states[control] ?: State().also {states[control]=it}
        var changed=install(control,control,nativePath)
        val stamp=if(Build.VERSION.SDK_INT>=34)path.generationId else 0
        val geometry=31*(31*stamp+control.width)+control.height
        val updated=Build.VERSION.SDK_INT<34 || state.geometry!=geometry
        state.geometry=geometry
        if(updated)control.invalidateOutline()
        val id=runCatching {control.resources.getResourceEntryName(control.id)}.getOrDefault("")
        val peerId=AssistantSpecificSurfacePolicy.blurPeer(id) ?: return changed
        val parent=control.parent as? ViewGroup ?: return changed
        // The audited layout places both nodes as direct siblings, constrained
        // to the same bounds. Never search the whole window for a matching name.
        for(index in 0 until parent.childCount) {
            val peer=parent.getChildAt(index)
            if(peer.javaClass!=View::class.java ||
                runCatching {peer.resources.getResourceEntryName(peer.id)}.getOrDefault("")!=peerId)continue
            if(state.peers.none {it.get()===peer})state.peers+=WeakReference(peer)
            changed=install(peer,control,nativePath) || changed
            if(updated)peer.invalidateOutline()
        }
        return changed
    }

    private fun install(view:View,control:View,path:(View)->Path?):Boolean {
        val existing=view.outlineProvider as? NativeOutline
        if(existing?.belongsTo(view,control)==true) {
            if(!view.clipToOutline) {view.clipToOutline=true;view.invalidateOutline();return true}
            return false
        }
        view.outlineProvider=NativeOutline(view,control,view.outlineProvider,view.clipToOutline,path)
        view.clipToOutline=true
        view.invalidateOutline()
        return true
    }
    private fun restore(view:View,control:View):Boolean {
        val current=view.outlineProvider as? NativeOutline ?: return false
        if(!current.belongsTo(view,control))return false
        // If the vendor replaced the provider, it owns the new state. Only undo
        // a lease still installed by this control; never overwrite that update.
        view.outlineProvider=current.original
        view.clipToOutline=current.originalClip
        view.invalidateOutline()
        return true
    }

    private class NativeOutline(view:View,control:View,val original:ViewOutlineProvider?,
        val originalClip:Boolean,private val path:(View)->Path?):ViewOutlineProvider() {
        private val owner=WeakReference(view)
        private val source=WeakReference(control)
        fun belongsTo(view:View,control:View)=owner.get()===view && source.get()===control
        override fun getOutline(view:View,outline:Outline) {
            val control=source.get()
            val native=if(control!=null)runCatching {path(control)}.getOrNull() else null
            if(control==null || native==null || native.isEmpty || control.width<=0 || control.height<=0) {
                original?.getOutline(view,outline) ?: outline.setEmpty()
                return
            }
            val mask=Path(native)
            if(view!==control && (view.width!=control.width || view.height!=control.height)) {
                if(view.width<=0 || view.height<=0) {outline.setEmpty();return}
                mask.transform(Matrix().apply {setScale(view.width.toFloat()/control.width,view.height.toFloat()/control.height)})
            }
            outline.setPath(mask)
            outline.alpha=1f
        }
    }
}
