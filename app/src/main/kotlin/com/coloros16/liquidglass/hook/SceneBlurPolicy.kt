package com.coloros16.liquidglass.hook

/** Scale an original native request, never an already modified stored blur value. */
object SceneBlurPolicy {
    const val KEY_ENABLED="scene_backdrop_blur_custom"
    const val KEY_STRENGTH="scene_backdrop_blur_percent"
    fun radius(original:Int,percent:Int)=if(original<=0)0 else
        (original.toDouble()*percent.coerceIn(0,100)/100.0).toInt().coerceIn(0,original)
    fun radius(original:Float,percent:Int)=if(!original.isFinite() || original<=0f)0f else
        original*percent.coerceIn(0,100)/100f
    fun menuRoot(name:String)=name=="com.oplus.systemui.statusbar.notification.row.menupanel.NotificationMenuPanelView" ||
        name=="com.coui.appcompat.poplist.COUIPopupMenuRootView"
}
