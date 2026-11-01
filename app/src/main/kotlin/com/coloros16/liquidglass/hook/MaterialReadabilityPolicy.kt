package com.coloros16.liquidglass.hook

/** Explicit component overrides win; an explicit global dim must not be silently
 * replaced by the low dim value inside an optical preset. */
object MaterialReadabilityPolicy {
    fun tintedLuminance(raw:Double,gray:Float,dim:Float):Double {
        val c=(gray.coerceIn(0f,1f)*255).toInt()
        val grayLuma=LocalTextContrast.luminance(-0x1000000 or (c shl 16) or (c shl 8) or c)
        val base=if(gray.isFinite() && gray>=0f)raw*.28+grayLuma*.72 else raw
        val brightness=1.0-dim.coerceIn(0f,1f)
        return base*brightness*brightness
    }
    fun dim(global:Float, configured:Boolean, preset:Float?, component:Float?):Float {
        val value=component?.takeIf { it.isFinite() }
            ?: global.takeIf { configured && it.isFinite() }
            ?: preset?.takeIf { it.isFinite() } ?: global
        return if(value.isFinite())value.coerceIn(0f,1f) else 0f
    }
}
