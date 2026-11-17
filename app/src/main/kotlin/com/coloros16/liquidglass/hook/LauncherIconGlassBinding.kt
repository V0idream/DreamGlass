/* DreamGlass contributors, 2026. GPL-3.0-or-later. */
package com.coloros16.liquidglass.hook

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View
import com.coloros16.liquidglass.diagnostics.RuntimeDiagnostics

/** Prepare from the View entry, before OEM/native consumers read their bitmap. Both modes
 * need this prefab: live shading may be bypassed by an OEM draw engine or an animation. */
internal object LauncherIconGlassBinding {
    fun prepare(view:View,drawable:Drawable,item:Any?,raw:Bitmap,component:ComponentName?,userId:Int,
        screen:RectF,params:IconGlassMaterial.Params,ready:()->Unit,
        frame:IconGlassWallpaper.Frame?=null):Boolean {
        val source=LauncherIconGlassAssets.original(raw)
        IconGlassPipelineTrace.record(component,"source",IconGlassPipelineTrace.source(source))
        val generated=prepareBitmap(view.context,item,source,component,userId,screen,params,ready,frame) ?: return false
        LauncherIconGlassAssets.apply(view,drawable,item,source,generated)
        IconGlassPipelineTrace.record(component,"bind","applied ${drawable.javaClass.simpleName}")
        RuntimeDiagnostics.mark("icon_glass_bound","玻璃位图已在原生 View 绘制之前绑定")
        return true
    }
    fun prepareBitmap(context:Context,item:Any?,raw:Bitmap,component:ComponentName?,userId:Int,
        screen:RectF,params:IconGlassMaterial.Params,ready:()->Unit,
        frame:IconGlassWallpaper.Frame?=null,wallpaperReady:()->Unit=ready):Bitmap? {
        val source=LauncherIconGlassAssets.original(raw)
        if(source.isRecycled || source.width<16 || source.height<16)return null
        val cached=LauncherIconGlassAssets.prefabFor(item) ?: LauncherIconGlassAssets.generated(source)
        if(cached!=null)return cached
        val mask=IconGlassMasks.resolve(context,component,userId,source,ready) ?: return null
        // Imported images are already the final artwork: their CPU crop path never reads
        // wallpaper pixels. Avoid decoding/blurring wallpaper just to pass an unused input.
        val background=if(mask.passthrough)null else frame ?: IconGlassWallpaper.resolve(context,screen,wallpaperReady)
        return LauncherIconGlassAssets.request(source,mask.foreground,
            background?.bitmap ?: IconGlassFallback.backdrop,
            background?.sourceRect ?: RectF(0f,0f,8f,8f),params.copy(reliefArtwork=mask.relief,detailedArtwork=mask.detailed,customImage=mask.passthrough),
            ready,LauncherIconGlassAssets.identity(item),background?.sharpBitmap ?: IconGlassFallback.backdrop)
    }
}
