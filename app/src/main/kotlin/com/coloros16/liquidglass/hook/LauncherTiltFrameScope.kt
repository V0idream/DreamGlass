/* DreamGlass contributors. GPL-3.0-or-later. */
package com.coloros16.liquidglass.hook

/** A target's native callback owns its scope on the actual animation thread. Submit
 * once after its final affine write; sibling targets and nested callbacks cannot
 * borrow a transaction-wide pose. No elapsed-time guess or global frame cache.
 */
internal class LauncherTiltFrameScope {
    private class Frame(val transaction:Any,val surface:Any,var written:Boolean=false)
    private val current=ThreadLocal<Frame?>()
    fun matrixWritten(transaction:Any,surface:Any):Boolean {
        val frame=current.get() ?: return false
        if(frame.transaction!==transaction || frame.surface!==surface)return false
        frame.written=true;return true
    }
    fun <T> run(transaction:Any,surface:Any,native:()->T,submit:()->Unit):T {
        val previous=current.get();val frame=Frame(transaction,surface);current.set(frame)
        try {
            val result=native()
            if(frame.written)submit()
            return result
        } finally { if(previous==null)current.remove() else current.set(previous) }
    }
}
