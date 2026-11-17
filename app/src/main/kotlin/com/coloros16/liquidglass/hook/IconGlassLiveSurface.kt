package com.coloros16.liquidglass.hook

import android.graphics.*
import android.os.Looper
import android.view.View
import android.widget.ImageView
import com.coloros16.liquidglass.diagnostics.RuntimeDiagnostics
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.ExceptionMode
import java.lang.reflect.Method
import java.util.WeakHashMap

/** A view-local GPU icon with explicit shared wallpaper input. No screenshot, recrop or timer. */
internal object IconGlassLiveSurface {
    private class Lease {
        val material=IconGlassMaterial()
        var effect:RenderEffect?=null
        var wallpaper:Bitmap?=null
        var wallpaperGeneration=-1
        var sharpWallpaper:Bitmap?=null
        var sharpGeneration=-1
        val crop=RectF()
        var image:Bitmap?=null
        var generation=-1
        var params:IconGlassMaterial.Params?=null
        val bounds=RectF()
        var active=false
        var imageAlpha:Int?=null
    }
    private val leases=WeakHashMap<View,Lease>()
    private val native=WeakHashMap<View,RenderEffect?>()
    private var bridge:Method?=null
    private val changing=ThreadLocal<Boolean>()
    fun install(api:XposedInterface,loader:ClassLoader) {
        runCatching {
            val method=Class.forName("com.oplus.view.OplusViewBackgroundRenderEffect",false,loader)
                .getMethod("setBackgroundRenderEffect",RenderEffect::class.java,View::class.java)
            bridge=method
            api.hook(method).setExceptionMode(ExceptionMode.PROTECTIVE).intercept {chain ->
                if(Looper.myLooper()==Looper.getMainLooper() && changing.get()!=true) {
                    val view=chain.getArg(1) as? View
                    if(view!=null) {native[view]=chain.getArg(0) as? RenderEffect;leases[view]?.active=false}
                }
                chain.proceed()
            }
            RuntimeDiagnostics.mark("icon_live_surface_contract","图标原生实时背景接口已安装")
        }.onFailure {bridge=null;RuntimeDiagnostics.update("icon_live_surface_contract","实时接口不可用：${it.javaClass.simpleName}")}
    }
    private fun assign(view:View,effect:RenderEffect?) {
        val previous=changing.get();changing.set(true)
        try {checkNotNull(bridge).invoke(null,effect,view)}finally {
            if(previous==null)changing.remove() else changing.set(previous)
        }
    }
    fun sync(view:View,foreground:Bitmap,bounds:RectF,params:IconGlassMaterial.Params,frame:IconGlassWallpaper.Frame?):Boolean {
        if(bridge==null || !view.isHardwareAccelerated || bounds.isEmpty || frame==null || frame.bitmap.isRecycled) {
            restore(view)
            if(frame==null)RuntimeDiagnostics.mark("icon_live_waiting_wallpaper","实时背景未就绪，保留预制图标；不挂载空背景效果")
            return false
        }
        val lease=leases.getOrPut(view) {Lease()}
        return try {
            var changed=false
            if(lease.effect==null || lease.image!==foreground || lease.generation!=foreground.generationId ||
                lease.bounds!=bounds || lease.params!=params || lease.wallpaper!==frame.bitmap ||
                lease.wallpaperGeneration!=frame.bitmap.generationId || lease.crop!=frame.sourceRect ||
                lease.sharpWallpaper!==frame.sharpBitmap || lease.sharpGeneration!=frame.sharpBitmap.generationId) {
                lease.effect=lease.material.createWallpaperEffect(bounds,foreground,frame,params)
                lease.wallpaper=frame.bitmap;lease.wallpaperGeneration=frame.bitmap.generationId;lease.crop.set(frame.sourceRect)
                lease.sharpWallpaper=frame.sharpBitmap;lease.sharpGeneration=frame.sharpBitmap.generationId
                lease.image=foreground;lease.generation=foreground.generationId;lease.bounds.set(bounds);lease.params=params
                changed=true
            }
            if(!lease.active || changed) {assign(view,lease.effect);lease.active=true}
            // ImageView's base onDraw may be inlined in a card engine. Hide its owned native
            // image using its public API; keep the compositor background and view alpha intact.
            if(view is ImageView) {
                if(lease.imageAlpha==null)lease.imageAlpha=view.imageAlpha
                if(view.imageAlpha!=0)view.imageAlpha=0
            }
            RuntimeDiagnostics.mark("icon_live_surface_active","图标使用显式壁纸纹理及当前坐标实时 GPU 着色；不依赖空合成器输入，不抓屏")
            true
        } catch(t:Throwable) {
            restore(view)
            RuntimeDiagnostics.update("icon_live_surface_error","${t.javaClass.simpleName}: ${t.message?.take(100)}")
            false
        }
    }
    fun imageAlpha(view:ImageView)=leases[view]?.imageAlpha ?: view.imageAlpha
    fun owns(view:View)=leases[view]?.active==true
    fun restore(view:View) {
        val lease=leases.remove(view) ?: return
        if(lease.active)runCatching {assign(view,native[view])}
        if(view is ImageView)lease.imageAlpha?.let {view.imageAlpha=it}
        lease.material.clear()
    }
    fun clear() {leases.keys.toList().forEach(::restore)}
}
