package com.coloros16.liquidglass.glass

import android.content.SharedPreferences
import com.coloros16.liquidglass.config.Prefs
import com.coloros16.liquidglass.liquidglass.LiquidGlassShader

/** Cross-process surfaces use the same material keys, IOS overlay and optical shader as QS.
 * The caller caches read() per process; resolving a button never performs Binder reads.
 */
object SharedGlassParams {
    private val defaults by lazy {
        val d=LiquidGlassShader.Params(1f,1f)
        linkedMapOf(Prefs.KEY_BLUR_RADIUS to Prefs.DEFAULT_BLUR_RADIUS,
            Prefs.KEY_BUTTON_BLUR_RADIUS to Prefs.DEFAULT_BUTTON_BLUR_RADIUS,
            Prefs.KEY_REFRACTION_AMOUNT to d.refractionAmount, Prefs.KEY_DEPTH to d.depth,
            Prefs.KEY_DISPERSION to d.dispersion, Prefs.KEY_HIGHLIGHT to d.highlight,
            Prefs.KEY_HIGHLIGHT_WIDTH to d.highlightWidth, Prefs.KEY_HIGHLIGHT_FALLOFF to Prefs.DEFAULT_HIGHLIGHT_FALLOFF,
            Prefs.KEY_HIGHLIGHT_FLOOR to Prefs.DEFAULT_HIGHLIGHT_FLOOR,
            Prefs.KEY_HIGHLIGHT_INNER_ATT to Prefs.DEFAULT_HIGHLIGHT_INNER_ATT,
            Prefs.KEY_INNER_GLOW to Prefs.DEFAULT_INNER_GLOW, Prefs.KEY_VIBRANCY to Prefs.DEFAULT_VIBRANCY,
            Prefs.KEY_LIGHT_ANGLE to d.lightAngleDegrees, Prefs.KEY_PARALLAX_X to d.parallaxX,
            Prefs.KEY_PARALLAX_Y to d.parallaxY,
            Prefs.KEY_REFRACTION_HEIGHT_RATIO to Prefs.DEFAULT_REFRACTION_HEIGHT_RATIO)
    }
    data class Snapshot(val values:Map<String,Float>,val preset:String)
    fun read(prefs:SharedPreferences):Snapshot {
        val map=defaults.mapValues { (key,value) ->
            val n=try { prefs.getFloat(key,value) } catch(_:ClassCastException) { prefs.getInt(key,value.toInt()).toFloat() }
            if(n.isFinite()) n else value
        }.toMutableMap()
        if(Prefs.readIntCompat(prefs,Prefs.KEY_REFRACTION_HEIGHT_MODE,Prefs.REFRACTION_HEIGHT_MODE_RATIO)==Prefs.REFRACTION_HEIGHT_MODE_ABS)
            map[Prefs.KEY_REFRACTION_HEIGHT_PX]=try { prefs.getFloat(Prefs.KEY_REFRACTION_HEIGHT_PX,Prefs.DEFAULT_REFRACTION_HEIGHT_PX) } catch(_:ClassCastException) { prefs.getInt(Prefs.KEY_REFRACTION_HEIGHT_PX,64).toFloat() }
        return Snapshot(map,prefs.getString(Prefs.KEY_BUILTIN_MATERIAL_PRESET,Prefs.GLASS_PRESET_DEFAULT_ID) ?: Prefs.GLASS_PRESET_DEFAULT_ID)
    }
    fun resolve(values:Map<String,Float>,preset:String?,custom:Map<String,Float>?,
        width:Float,height:Float,corner:Float,button:Boolean):LiquidGlassShader.Params {
        val ov=IosGlassPreset.resolve(preset,true,custom)
        fun pick(key:String)=ov?.get(key)?.takeIf { it.isFinite() } ?: values[key]?.takeIf { it.isFinite() } ?: defaults[key] ?: 0f
        val blur=if(button && pick(Prefs.KEY_BUTTON_BLUR_RADIUS)>=0f)pick(Prefs.KEY_BUTTON_BLUR_RADIUS) else pick(Prefs.KEY_BLUR_RADIUS)
        val depth=ov?.get(Prefs.KEY_REFRACTION_HEIGHT_PX) ?: ov?.get(Prefs.KEY_REFRACTION_HEIGHT_RATIO)?.let { minOf(width,height)*it }
            ?: values[Prefs.KEY_REFRACTION_HEIGHT_PX] ?: minOf(width,height)*pick(Prefs.KEY_REFRACTION_HEIGHT_RATIO)
        return LiquidGlassShader.Params(width,height,cornerRadius=corner,
            blurRadius=blur.coerceIn(0f,128f),refractionHeight=depth.coerceIn(0f,4096f),
            refractionAmount=pick(Prefs.KEY_REFRACTION_AMOUNT),depth=pick(Prefs.KEY_DEPTH),
            dispersion=pick(Prefs.KEY_DISPERSION),highlight=pick(Prefs.KEY_HIGHLIGHT),
            highlightWidth=pick(Prefs.KEY_HIGHLIGHT_WIDTH),highlightFalloff=pick(Prefs.KEY_HIGHLIGHT_FALLOFF),
            highlightFloor=pick(Prefs.KEY_HIGHLIGHT_FLOOR),highlightInnerAtt=pick(Prefs.KEY_HIGHLIGHT_INNER_ATT),
            innerGlow=pick(Prefs.KEY_INNER_GLOW).coerceIn(0f,1f),vibrancy=pick(Prefs.KEY_VIBRANCY).coerceIn(1f,3f),
            lightAngleDegrees=pick(Prefs.KEY_LIGHT_ANGLE),parallaxX=pick(Prefs.KEY_PARALLAX_X),parallaxY=pick(Prefs.KEY_PARALLAX_Y),
            surfacePolish=true,sourcePreblurred=true)
    }
}
