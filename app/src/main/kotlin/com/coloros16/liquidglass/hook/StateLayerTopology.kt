/* DreamGlass contributors, 2026. GPL-3.0-or-later. */
package com.coloros16.liquidglass.hook

import java.util.IdentityHashMap

/** Metadata delegates may only reference an acyclic native child graph which
 * does not lead back to its owning state container. Unknown graph nodes fail
 * closed, including wrappers left by another module/class loader.
 */
internal object StateLayerTopology {
    fun canWrap(container:Any,original:Any,children:(Any)->List<Any>?,limit:Int=64):Boolean {
        val colors=IdentityHashMap<Any,Int>();var count=0
        fun visit(node:Any):Boolean {
            if(node===container)return false
            when(colors[node]) {1 -> return false;2 -> return true}
            if(++count>limit)return false
            colors[node]=1
            val edges=children(node) ?: return false
            for(edge in edges)if(!visit(edge))return false
            colors[node]=2;return true
        }
        return limit>0 && visit(original)
    }
    fun canRestore(container:Any,expectedContainer:Any?,current:Any,owned:Any)=
        container===expectedContainer && current===owned
}
