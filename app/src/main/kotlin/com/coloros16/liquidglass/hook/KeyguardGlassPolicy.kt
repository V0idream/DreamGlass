package com.coloros16.liquidglass.hook

/** Pure scope rules: do not touch PIN/password, fingerprint, notifications or AOD. */
object KeyguardGlassPolicy {
    const val NONE=0;const val TEXT=1;const val CARD=2;const val SHORTCUT=3
    fun role(names:List<String>,id:String,textView:Boolean,background:Boolean,clickable:Boolean,aspect:Float):Int {
        val path=names.joinToString("/").lowercase();val key=id.lowercase()
        if(path.contains(".aod.") || path.contains("security.") || path.contains("bouncer") || path.contains("password") || path.contains("pinentry") || path.contains("notification.") || path.contains("notificationrow"))return NONE
        val shortcut=key in setOf("start_button","end_button","left_button","right_button","keyguard_left_button","keyguard_right_button","flashlight_button","camera_button")
        if(shortcut && path.contains("keyguard"))return SHORTCUT
        val clock=names.any { it=="com.oplus.systemui.keyguard.view.CustomOplusKeyguardStyleClock" || it=="com.oplus.keyguard.ui.OplusKeyguardStyleClock" || it=="com.android.keyguard.AnimatableClockView" } ||
            (path.contains("keyguard") && (key.contains("clock") || key in setOf("date_view","date","lunar_date","weekday")))
        if(textView && clock)return TEXT
        val widgets=path.contains("keyguard") && (path.contains("stylewidget") || path.contains("keyguard.widget.") || path.contains("keyguard.widgets.") || path.contains("widgetplugin") || path.contains("#widget") || path.contains("_widget"))
        if(!textView && widgets && (background || clickable) && aspect in .55f..2.2f)return CARD
        return NONE
    }
    /** Center-cover mapping, shared by wallpaper read and the GPU material. */
    fun mapping(width:Int,height:Int,screenWidth:Int,screenHeight:Int):FloatArray {
        require(width>0 && height>0 && screenWidth>0 && screenHeight>0)
        val scale=maxOf(screenWidth.toFloat()/width,screenHeight.toFloat()/height)
        return floatArrayOf(1f/scale,(width-screenWidth/scale)*.5f,(height-screenHeight/scale)*.5f)
    }
}
