/* DreamGlass contributors, 2026-11-13. GPL-3.0. Original GPU icon material. */
package com.coloros16.liquidglass.hook

import com.coloros16.liquidglass.config.SafeAreas

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build

/** Draws only an icon's bitmap slot, or supplies a texture-backed effect for that same slot.
 * The caller controls native bounds, foreground state, text and badges. No input pixels are changed, no capture is requested and no timer is started.
 * Instances are confined to the owning render thread; recorded display lists retain their images.
 */
internal class IconGlassMaterial {
    data class Params(
        val clarity: Float = .76f,
        val refraction: Float = .56f,
        val thickness: Float = .45f,
        val keepColor: Boolean = true,
        val alpha: Int = 255,
        val disabled: Boolean = false,
        val reliefArtwork: Boolean = false,
        val customImage: Boolean = false,
        val detailedArtwork: Boolean = false,
    )

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var material: RuntimeShader? = null
    private var unavailable = false
    private var foregroundImage: Bitmap? = null
    private var wallpaperImage: Bitmap? = null
    private var foregroundGeneration = -1
    private var sharpImage:Bitmap?=null
    private var sharpGeneration=-1
    private var wallpaperGeneration = -1
    private val uniforms=IconGlassUniformCache()
    private var safeAreas = SafeAreas.Binding()

    fun draw(canvas: Canvas, bounds: RectF, foreground: Bitmap, wallpaper: Bitmap,
             wallpaperSource: RectF, params: Params = Params(),sharpWallpaper:Bitmap=wallpaper, safeMapping: SafeAreas.Mapping? = null): Boolean {
        if (Build.VERSION.SDK_INT < 33 || !canvas.isHardwareAccelerated || unavailable ||
            foreground.isRecycled || wallpaper.isRecycled || !valid(bounds) || !valid(wallpaperSource) ||
            foreground.width <= 0 || foreground.height <= 0 || wallpaper.width <= 0 || wallpaper.height <= 0 ||
            !params.clarity.isFinite() || !params.refraction.isFinite() || !params.thickness.isFinite()) return false
        // An invisible native icon needs neither shader creation nor texture/uniform updates.
        if(params.alpha<=0)return true
        try {
            val shader = material ?: try {
                RuntimeShader(SOURCE).also { material = it; paint.shader = it }
            } catch (_: Throwable) {
                unavailable = true
                return false
            }
            configure(shader,bounds,foreground,wallpaper,wallpaperSource,params,sharpWallpaper,safeMapping)
            // Shader premultiplies its output once. Applying the native alpha to Paint too would
            // square the entrance/pressed opacity and cause dim outlines during transitions.
            paint.alpha = 255
            canvas.drawRect(bounds, paint)
            return true
        } catch (_: Throwable) {
            uniforms.clear() // Retry all writes if a native uniform update failed part-way.
            return false // Caller draws the original bitmap in this same slot.
        }
    }

    private fun configure(shader:RuntimeShader,bounds:RectF,foreground:Bitmap,wallpaper:Bitmap,
        wallpaperSource:RectF,params:Params,sharpWallpaper:Bitmap,safeMapping:SafeAreas.Mapping?) {
        safeAreas.set(shader, SafeAreas.Scene.DESKTOP, safeMapping)
        if (foregroundImage !== foreground || foregroundGeneration != foreground.generationId) {
            shader.setInputShader("uForeground", input(foreground))
            foregroundImage = foreground; foregroundGeneration = foreground.generationId
        }
        if (wallpaperImage !== wallpaper || wallpaperGeneration != wallpaper.generationId) {
            shader.setInputShader("uWallpaper", input(wallpaper))
            wallpaperImage = wallpaper; wallpaperGeneration = wallpaper.generationId
        }
        val sharp=sharpWallpaper.takeUnless {it.isRecycled} ?: wallpaper
        if(sharpImage!==sharp || sharpGeneration!=sharp.generationId) {
            shader.setInputShader("uSharpWallpaper",input(sharp))
            sharpImage=sharp;sharpGeneration=sharp.generationId
        }
        float1(shader,"uEdgeClarity",if(sharp!==wallpaper)1f else 0f)
        float2(shader,"uOrigin", bounds.left, bounds.top)
        float2(shader,"uSize", bounds.width(), bounds.height())
        // These ratios depend on the icon/crop, not on fragment position. Share them across
        // every foreground/underlay sample instead of dividing again for each pixel.
        float2(shader,"uForegroundScale", foreground.width/bounds.width(), foreground.height/bounds.height())
        float2(shader,"uWallpaperScale", wallpaperSource.width()/bounds.width(),wallpaperSource.height()/bounds.height())
        float4(shader,"uSource", wallpaperSource.left, wallpaperSource.top,
            wallpaperSource.width(), wallpaperSource.height())
        float1(shader,"uClarity", params.clarity.coerceIn(0f, 1f))
        float1(shader,"uRefraction", params.refraction.coerceIn(0f, 1f))
        float1(shader,"uThickness", params.thickness.coerceIn(0f, 1f))
        float1(shader,"uKeepColor", if (params.keepColor) 1f else 0f)
        float1(shader,"uDisabled", if (params.disabled) 1f else 0f)
        float1(shader,"uReliefArtwork", if (params.reliefArtwork) 1f else 0f)
        float1(shader,"uDetailedArtwork", if (params.detailedArtwork) 1f else 0f)
        float1(shader,"uAlpha", params.alpha.coerceIn(0, 255) / 255f)
    }

    private fun float1(shader:RuntimeShader,name:String,x:Float) {
        if(uniforms.one(name,x))shader.setFloatUniform(name,x)
    }
    private fun float2(shader:RuntimeShader,name:String,x:Float,y:Float) {
        if(uniforms.two(name,x,y))shader.setFloatUniform(name,x,y)
    }
    private fun float4(shader:RuntimeShader,name:String,x:Float,y:Float,z:Float,w:Float) {
        if(uniforms.four(name,x,y,z,w))shader.setFloatUniform(name,x,y,z,w)
    }

    /** Wallpaper is a separate Surface on COS17: bind its texture explicitly instead of
     * treating a successfully assigned background effect as proof of a valid backdrop. */
    fun createWallpaperEffect(bounds:RectF,foreground:Bitmap,frame:IconGlassWallpaper.Frame,params:Params):android.graphics.RenderEffect {
        require(valid(bounds) && valid(frame.sourceRect) && !foreground.isRecycled && !frame.bitmap.isRecycled)
        val shader=material ?: RuntimeShader(SOURCE).also {material=it}
        configure(shader,bounds,foreground,frame.bitmap,frame.sourceRect,params,frame.sharpBitmap,frame.safeMapping)
        return android.graphics.RenderEffect.createShaderEffect(shader)
    }

    /** Releases Java references only; never recycles images owned by the launcher or display list. */
    fun clear() {
        material = null; paint.shader = null; foregroundImage = null; wallpaperImage = null
        foregroundGeneration = -1; wallpaperGeneration = -1; sharpImage=null;sharpGeneration=-1
        uniforms.clear(); safeAreas = SafeAreas.Binding()
    }

    private fun input(bitmap: Bitmap) = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        .apply { setFilterMode(BitmapShader.FILTER_MODE_LINEAR) }

    private fun valid(rect: RectF) = rect.left.isFinite() && rect.top.isFinite() &&
        rect.right.isFinite() && rect.bottom.isFinite() && rect.width() > 0f && rect.height() > 0f

    companion object {
        // The shared wallpaper is already blurred once. One refracted substrate lookup keeps
        // the plate translucent without doing a second blur for every icon and animation frame.
        internal const val SOURCE = SafeAreas.AGSL + """
uniform shader uForeground;
uniform shader uWallpaper;
uniform shader uSharpWallpaper;
uniform float uEdgeClarity;
uniform float2 uOrigin;
uniform float2 uSize;
uniform float2 uForegroundScale;
uniform float2 uWallpaperScale;
uniform float4 uSource;
uniform float uClarity;
uniform float uRefraction;
uniform float uThickness;
uniform float uKeepColor;
uniform float uReliefArtwork;
uniform float uDetailedArtwork;
uniform float uDisabled;
uniform float uAlpha;

float boxDistance(float2 p) {
    float radius=min(uSize.x,uSize.y)*0.235;
    float2 q=abs(p-uSize*.5)-(uSize*.5-radius);
    return length(max(q,float2(0.0)))+min(max(q.x,q.y),0.0)-radius;
}
half4 foreground(float2 p) {
    if(p.x<0.0 || p.y<0.0 || p.x>uSize.x || p.y>uSize.y)return half4(0.0);
    half4 sampled=uForeground.eval(p*uForegroundScale);
    return sampled;
}
half3 background(float2 p) {
    return uWallpaper.eval(uSource.xy+p*uWallpaperScale).rgb;
}
half4 main(float2 position) {
    float2 p=position-uOrigin;
    float d=boxDistance(p);
    float cover=1.0-smoothstep(-.7,.7,d);
    if(cover<.001 || uAlpha<.001)return half4(0.0);
    float side=min(uSize.x,uSize.y);
    // Analytic rounded-rectangle normal: four finite-distance evaluations removed.
    // Interior normals never displace pixels, avoiding the old diagonal seam.
    float2 centered=p-uSize*.5;
    float2 q=abs(centered)-(uSize*.5-side*.235);
    float2 corner=max(q,float2(0.0));
    float cornerLength=length(corner);
    float2 normal=cornerLength>.001 ? sign(centered)*corner/max(cornerLength,.001) :
        (q.x>q.y ? float2(sign(centered.x),0.0) : float2(0.0,sign(centered.y)));
    // A broad curved bevel, followed by refraction through a dielectric surface.
    // The slope rises continuously toward the perimeter instead of a narrow UV nudge.
    float bevelWidth=side*(.14+.08*uThickness);
    float bevel=1.0-smoothstep(0.0,bevelWidth,max(-d,0.0));
    float surfaceXY=.94*bevel;
    float surfaceZ=sqrt(max(1.0-surfaceXY*surfaceXY,.001));
    float2 samplePoint=p;
    float distortion = safeDistortion(p/uSize);
    if(uRefraction*distortion>0.0) {
        float eta=.69;
        float transmittedZ=sqrt(max(1.0-eta*eta*(1.0-surfaceZ*surfaceZ),.001));
        float travel=(transmittedZ-eta*surfaceZ)*surfaceXY/
            max(eta+(transmittedZ-eta*surfaceZ)*surfaceZ,.1);
        float opticalDepth=side*(.16+.22*uThickness);
        float bend=opticalDepth*travel*uRefraction;
        samplePoint=p-(normal*bend+centered*(.12*uRefraction))*distortion;
    }
    float edge=bevel;
    half3 glass=background(samplePoint);
    // A blurred 768px wallpaper can erase a displacement of only a few texels.
    // Use the already cached sharp texture through the curved edge; keep the
    // broad center frosted. This adds no decode, capture, or per-frame blur.
    if(uEdgeClarity>.5 && bevel>.04) {
        half3 refracted=uSharpWallpaper.eval(uSource.xy+samplePoint*uWallpaperScale).rgb;
        glass=mix(glass,refracted,half(smoothstep(.04,.70,bevel)*.94));
    }
    // Frosted substrate compresses busy wallpaper contrast while retaining its colour.
    half3 neutral=half3(.86,.90,.94);
    glass=mix(glass,neutral,half(.045+.045*(1.0-uClarity)));
    float veil=pow(clamp(1.0-p.y/uSize.y,0.0,1.0),2.0)*.045;
    glass=mix(glass,half3(1.0),half(veil));
    // Preserve wallpaper colour and light through the plate; contrast lives in the glyph.
    float topLight=max(dot(normal,normalize(float2(-.62,-.79))),0.0);
    float bottomLight=max(dot(normal,normalize(float2(.72,.69))),0.0);
    float rim=exp(-pow((d+.60)/.85,2.0));
    float innerRim=exp(-pow((d+side*.018)/max(.6,side*.004),2.0));
    // Angle-dependent reflection follows the same curved surface as the refraction.
    float fresnel=.035+.965*pow(1.0-surfaceZ,5.0);
    glass=mix(glass,half3(.94,.97,1.0),half(fresnel*edge*(.24+.26*topLight)));
    glass+=half3(.68,.84,.94)*half(edge*topLight*(.10+.15*uThickness)+
        rim*(.035+.22*topLight+.10*bottomLight));
    glass+=half3(.80,.90,1.0)*half(edge*bottomLight*.07+innerRim*topLight*.055);
    glass-=half3(.07,.09,.11)*half(edge*(1.0-topLight)*.12);

    half4 mark=foreground(p);
    float glyphAlpha=float(mark.a);
    float stepSize=max(.65,side/128.0);
    half4 right=foreground(p+float2(stepSize,0.0));half4 left=foreground(p-float2(stepSize,0.0));
    half4 down=foreground(p+float2(0.0,stepSize));half4 up=foreground(p-float2(0.0,stepSize));
    float2 glyphGradient=float2(float(right.a-left.a),float(down.a-up.a));
    half3 glyphGlass=half3(0.0);
    half3 original=mark.rgb/max(mark.a,half(.001));
    // Relief/detailed artwork replaces this value completely. Do not sample a second
    // wallpaper texture or mix generic ink only to discard that work immediately.
    if(uReliefArtwork<=.5 && uDetailedArtwork<=.5) {
        half3 ink=mix(half3(.76,.84,.92),half3(.99,1.0,1.0),half(clamp(1.0-p.y/uSize.y,0.0,1.0)));
        glyphGlass=background(p+float2(glyphGradient.x,-glyphGradient.y)*side*.022*distortion);
        glyphGlass=mix(glyphGlass,ink,half(.73+.20*uClarity));
        glyphGlass=mix(glyphGlass,original,half(uKeepColor*.045));
    }
    // A close cast shadow separates the subject from the translucent plate.
    // Contact coverage reuses the four existing foreground reads.
    float shadow=float(foreground(p-float2(side*.010,side*.016)).a)*(1.0-glyphAlpha);
    float contact=max(max(float(left.a),float(up.a))-glyphAlpha,0.0);
    glass*=half(1.0-clamp(shadow*.28+contact*.10,0.0,.34));
    if (uReliefArtwork>.5 && uDetailedArtwork<=.5) {
        // Original colours become height/tone levels. No coloured photo fallback.
        float level=float(dot(original,half3(.2126,.7152,.0722)));
        float hx=float(right.r/max(right.a,half(.001))-left.r/max(left.a,half(.001)));
        float hy=float(down.r/max(down.a,half(.001))-up.r/max(up.a,half(.001)));
        float relief=clamp((-hx-hy)*.26,-.17,.17);
        half3 tone=mix(half3(.54,.63,.73),half3(.99,1.0,1.0),half(smoothstep(.04,.96,level)));
        float satin=clamp((1.0-p.y/uSize.y)*.08,0.0,.08);
        glyphGlass=mix(glass,tone,half(.79+.12*uClarity))+half3(relief+satin);
    }
    if (uDetailedArtwork>.5) {
        // The downloaded engraving already carries its own relief and fine contours.
        // Preserve those tones instead of flattening them into the generic height map.
        float level=float(dot(original,half3(.2126,.7152,.0722)));
        // Re-light the engraving as pale layered glass, not metallic source pixels.
        // Fine local recesses preserve eyes, hair and lettering without a dark metal body.
        float lr=float(dot(left.rgb/max(left.a,half(.001)),half3(.2126,.7152,.0722)));
        float rr=float(dot(right.rgb/max(right.a,half(.001)),half3(.2126,.7152,.0722)));
        float ur=float(dot(up.rgb/max(up.a,half(.001)),half3(.2126,.7152,.0722)));
        float dr=float(dot(down.rgb/max(down.a,half(.001)),half3(.2126,.7152,.0722)));
        float surround=(lr+rr+ur+dr)*.25;
        float contour=clamp((surround-level)*2.8,0.0,.35);
        float recess=(1.0-level)*(1.0-level)*.09;
        half3 pearl=mix(half3(.50,.65,.78),half3(1.0),half(.20+.80*sqrt(max(level,0.0))));
        pearl-=half3(.33,.38,.42)*half(contour+recess);
        pearl-=half3(.92,.96,1.0)*half(max(.60-level,0.0)*.38);
        float fineRelief=clamp((lr-rr+ur-dr)*.11,-.04,.04);
        pearl+=half3(fineRelief);
        float transmission=.22+.20*(1.0-uClarity);
        glyphGlass=mix(pearl,glass,half(transmission));
    }
    // Apply after every artwork branch so downloaded contours receive the same
    // upper-left highlight and lower-right shade as procedural glyphs.
    float gradientLength=length(glyphGradient);
    float2 glyphNormal=-glyphGradient/max(gradientLength,.001);
    float glyphEdge=clamp(gradientLength*1.5,0.0,1.0);
    float keyLight=max(dot(glyphNormal,normalize(float2(-.62,-.79))),0.0);
    float shade=max(dot(glyphNormal,normalize(float2(.62,.79))),0.0);
    glyphGlass=mix(glyphGlass,half3(1.0),half(glyphEdge*keyLight*(.52+.16*uThickness)));
    glyphGlass-=half3(.20,.25,.30)*half(glyphEdge*shade*(.65+.20*uThickness));
    glass=mix(glass,glyphGlass,half(glyphAlpha));
    half grey=dot(glass,half3(.2126,.7152,.0722));
    glass=mix(glass,half3(grey),half(uDisabled*.82));
    float alpha=cover*uAlpha;
    return half4(clamp(glass,half3(0.0),half3(1.0))*half(alpha),half(alpha));
}
"""
    }
}
