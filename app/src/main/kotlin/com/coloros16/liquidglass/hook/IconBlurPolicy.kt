package com.coloros16.liquidglass.hook

/** Radius at the shared 768px wallpaper texture, independent of icon/frame count. */
internal object IconBlurPolicy {
    fun radius(percent:Int):Float=percent.coerceIn(0,100)/10f
}
