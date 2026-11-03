/* DreamGlass contributors, 2026. GPL-3.0-or-later. */
package com.coloros16.liquidglass.hook

import java.util.IdentityHashMap

/** Prevent a source containing the glass host (or vice versa) from becoming a
 * self-referencing RenderNode graph. Unknown/cyclic owner paths are rejected. */
internal object LiveContentSourceGraph {
    fun <T:Any> independent(source:T,target:T,parent:(T)->T?):Boolean {
        fun excludes(start:T,other:T):Boolean {
            val seen=IdentityHashMap<T,Boolean>();var current:T?=start
            repeat(64) {
                val node=current ?: return true
                if(node===other || seen.put(node,true)!=null)return false
                current=parent(node)
            };return false
        }
        return source!==target && excludes(source,target) && excludes(target,source)
    }
}
