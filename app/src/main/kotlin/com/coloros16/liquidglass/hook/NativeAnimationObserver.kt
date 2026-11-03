/* DreamGlass contributors, 2026. GPL-3.0-or-later. */
package com.coloros16.liquidglass.hook

/** OEM playback dispatch iterates Animator.getListeners() directly. Observers
 * must never mutate that list during dispatch or propagate visual-effect errors
 * into native deletion/end callbacks. Cleanup runs on a later UI queue turn. */
internal class NativeAnimationObserver(
    private val defer:((()->Unit)->Unit),private val failure:(Throwable)->Unit) {
    fun invoke(effect:()->Unit) {
        try {effect()} catch(error:Throwable) {runCatching {failure(error)}}
    }
    fun finish(effect:()->Unit,detach:()->Unit) {
        invoke(effect)
        // If queuing itself fails, leave the listener for Animator collection.
        // Never fall back to synchronous listener-list mutation.
        invoke {defer {invoke(detach)}}
    }
}
