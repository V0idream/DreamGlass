/* DreamGlass contributors, 2026. GPL-3.0-or-later. */
package com.coloros16.liquidglass.hook

internal object LauncherSuggestionSurfacePolicy {
    /** Native desktop suggestion cards are itemType=100, cardType=82/83.
     * Four icons are not sufficient evidence: require the wrapper's exact
     * dedicated background and its position behind every content child.
     */
    fun eligible(wrapperOwnsCard:Boolean,wrapperOwnsBackground:Boolean,firstChild:Boolean,
        itemType:Int,cardType:Int):Boolean = wrapperOwnsCard && wrapperOwnsBackground &&
        firstChild && itemType==100 && (cardType==82 || cardType==83)
}
