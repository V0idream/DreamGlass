/* DreamGlass contributors, 2026. GPL-3.0-or-later. */
package com.coloros16.liquidglass.hook

/** OplusClearAllPanelView's actual btn_clear slot, not arbitrary launcher Buttons. */
internal object RecentsClearButtonPolicy {
    fun panelLevel(directPanel:Boolean,relativeParent:Boolean,grandparentPanel:Boolean):Int? =
        if(directPanel)0 else if(relativeParent && grandparentPanel)1 else null
    fun eligible(exactButton:Boolean,exactPanel:Boolean,slotIdentity:Boolean,getterIdentity:Boolean,id:String)=
        exactButton && exactPanel && slotIdentity && getterIdentity && id=="btn_clear"
    // PressFeedbackButton.onLayout uses min(width,height)/2 for both background
    // Paths; its getItemBackgroundRect returns the full local box.
    fun radius(left:Float,top:Float,right:Float,bottom:Float):Float? {
        if(!listOf(left,top,right,bottom).all(Float::isFinite))return null
        val width=right-left;val height=bottom-top
        return (minOf(width,height)*.5f).takeIf {width>0f && height>0f && it.isFinite()}
    }
}
