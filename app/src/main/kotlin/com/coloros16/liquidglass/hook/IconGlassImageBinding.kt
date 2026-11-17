package com.coloros16.liquidglass.hook

import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Matrix
import android.widget.ImageView

/** Qualified recommendation ImageViews bind before drawing; no Xposed draw interception required. */
internal object IconGlassImageBinding {
    fun prepare(view:ImageView,source:Bitmap,component:ComponentName?,user:Int,
        params:IconGlassMaterial.Params,ready:()->Unit,frame:IconGlassWallpaper.Frame?=null):Bitmap? {
        val bounds=IconGlassImageGeometry.bounds(view) ?: return null
        IconGlassImageClip.apply(view,bounds)
        val generated=LauncherIconGlassAssets.prefabFor(view.tag) ?: LauncherIconGlassAssets.generated(source) ?: run {
            val matrix=Matrix();view.transformMatrixToGlobal(matrix);matrix.mapRect(bounds)
            LauncherIconGlassBinding.prepareBitmap(view.context,view.tag,source,component,user,
                bounds,params,ready,frame)
        } ?: return null
        val drawable=view.drawable ?: return null
        if((drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap!==generated)
            LauncherIconGlassAssets.apply(view,drawable,view.tag,source,generated)
        return generated
    }
    fun drawFallback(view:ImageView,canvas:android.graphics.Canvas):Boolean {
        val area=IconGlassImageGeometry.bounds(view) ?: return false
        val bitmap=(view.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap ?: return false
        val generated=if(LauncherIconGlassAssets.isGenerated(bitmap))bitmap else LauncherIconGlassAssets.generated(bitmap) ?: return false
        val save=canvas.save()
        canvas.clipPath(android.graphics.Path().apply {addRoundRect(area,
            minOf(area.width(),area.height())*.235f,minOf(area.width(),area.height())*.235f,android.graphics.Path.Direction.CW)})
        canvas.drawBitmap(generated,null,area,android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG).apply {
            alpha=IconGlassLiveSurface.imageAlpha(view)
        })
        canvas.restoreToCount(save)
        return true
    }

}
