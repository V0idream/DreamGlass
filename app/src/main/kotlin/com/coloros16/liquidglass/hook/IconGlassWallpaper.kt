/* DreamGlass contributors, 2026-11-13. GPL-3.0. Static desktop-wallpaper input only. */
package com.coloros16.liquidglass.hook

import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Choreographer
import android.view.WindowManager
import com.coloros16.liquidglass.diagnostics.RuntimeDiagnostics
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

/** One file-backed system wallpaper shared by visible desktop icons. No lock wallpaper,
 * display capture, recurring task or pixel recrop is used. The launcher owns wallpaper
 * offsets/zoom; its existing callbacks forward those values through [onTransform].
 *
 * The owning hook must keep its onReady callback alive and make it invalidate weak hosts.
 * This object keeps only a weak callback reference so it cannot retain a View or Activity.
 */
internal object IconGlassWallpaper {
    data class Frame(val bitmap: Bitmap, val sourceRect: RectF, val sharpBitmap:Bitmap=bitmap)
    private data class Transform(val x:Float=0f,val y:Float=0f,val zoom:Float=1f)
    private val ui=Handler(Looper.getMainLooper())
    private val worker=RuntimeWorkPolicy.executor("DreamGlass-icon-wallpaper")
    private val loading=AtomicBoolean(false)
    private val registrationQueued=AtomicBoolean(false)
    private val notificationQueued=AtomicBoolean(false)
    private val lock=Any()
    private val projections=IconWallpaperGeometry.ProjectionCache()
    @Volatile private var image:Bitmap?=null
    @Volatile private var sharp:Bitmap?=null
    @Volatile private var wallpaperGeneration=0L
    @Volatile private var preparedGeneration=-1L
    @Volatile private var blur=26
    @Volatile private var generation=0L
    @Volatile private var imageSerial=0L
    @Volatile private var attemptedGeneration=-1L
    @Volatile private var active=false
    @Volatile private var transform=Transform()
    @Volatile private var screenWidth=0
    @Volatile private var screenHeight=0
    @Volatile private var app:Context?=null
    @Volatile private var registered=false
    @Volatile private var registrationBlocked=false
    @Volatile private var onReady=WeakReference<()->Unit>(null)

    // Revision changes only after a completed blur is published. Existing icons stay valid
    // while the worker prepares a new setting; dragging/scrolling never decodes wallpaper.
    fun revision():Long=imageSerial
    fun configureBlur(value:Int) {
        val next=value.coerceIn(0,100)
        synchronized(lock) {
            if(next==blur)return
            blur=next;generation++;attemptedGeneration=-1L
        }
        scheduleLoad()
    }
    fun resolve(context:Context,iconScreen:RectF,onReady:()->Unit):Frame? {
        if(Build.VERSION.SDK_INT<33 || !IconWallpaperGeometry.valid(iconScreen.left,iconScreen.top,
                iconScreen.right,iconScreen.bottom))return null
        this.onReady=WeakReference(onReady)
        app=context.applicationContext ?: return null
        active=true
        ensureReceiver()
        if(screenWidth<=0 || screenHeight<=0)updateScreen(context)
        scheduleLoad()
        val bitmap=image?.takeUnless {it.isRecycled}
        if(bitmap==null) {
            scheduleLoad()
            return LauncherHook.publishedIconBackdrop(iconScreen)
        }
        val current=transform
        val projection=projections.get(bitmap.width,bitmap.height,screenWidth,screenHeight,current.x,current.y,current.zoom)
            ?: return null
        return Frame(bitmap,RectF(projection.x(iconScreen.left),projection.y(iconScreen.top),
            projection.x(iconScreen.right),projection.y(iconScreen.bottom)),sharp ?: bitmap)
    }

    /** Pure coordinate update: no wallpaper manager call, decode, copy or work submission. */
    fun onTransform(x:Float,y:Float,zoom:Float) {
        if(!x.isFinite() || !y.isFinite() || !zoom.isFinite() || zoom<=0f)return
        val next=Transform(x.coerceIn(0f,1f),y.coerceIn(0f,1f),zoom.coerceIn(.1f,4f))
        if(next==transform)return
        transform=next
        if(active && image!=null)notifyReady()
    }

    /** Cancel publication and release references, never recycle a published/native-shader input. */
    fun clear() {
        synchronized(lock) {
            generation++;wallpaperGeneration++;imageSerial++;image=null;sharp=null;preparedGeneration=-1L;attemptedGeneration=-1L;active=false
            registrationBlocked=false
            onReady=WeakReference(null)
        }
    }

    /** Screen-off cancels new work while retaining already prepared static wallpaper pixels. */
    fun pause() {
        synchronized(lock) {
            // Cancel in-flight work, but keep a successfully prepared generation reusable.
            val ready=preparedGeneration==generation
            generation++;if(ready)preparedGeneration=generation
            attemptedGeneration=-1L;active=false
        }
    }

    private fun ensureReceiver() {
        if(registered || registrationBlocked || !registrationQueued.compareAndSet(false,true))return
        ui.post {
            try {
                val owner=app ?: return@post
                val filter=IntentFilter().apply {
                    addAction(Intent.ACTION_WALLPAPER_CHANGED)
                    addAction(Intent.ACTION_CONFIGURATION_CHANGED)
                }
                owner.registerReceiver(object:BroadcastReceiver() {
                    override fun onReceive(context:Context,intent:Intent) {
                        if(intent.action==Intent.ACTION_CONFIGURATION_CHANGED) {
                            updateScreen(context)
                            if(active)notifyReady()
                        } else if(intent.action==Intent.ACTION_WALLPAPER_CHANGED) {
                            synchronized(lock) {generation++;wallpaperGeneration++;imageSerial++;image=null;sharp=null;preparedGeneration=-1L;attemptedGeneration=-1L}
                            // No background activity while disabled/hidden. A visible owner first
                            // repaints its native fallback, then gets a second frame on load.
                            if(active) {notifyReady();scheduleLoad()}
                        }
                    }
                },filter,null,ui,Context.RECEIVER_NOT_EXPORTED)
                registered=true
                if(active)scheduleLoad()
            } catch(error:Throwable) {
                diagnostic("壁纸变化监听不可用；图标保留原生外观: "+error.javaClass.simpleName)
                // If freshness cannot be maintained, do not publish a potentially stale image.
                synchronized(lock) {generation++;image=null;attemptedGeneration=generation}
                registrationBlocked=true
            } finally {registrationQueued.set(false)}
        }.also {accepted -> if(!accepted)registrationQueued.set(false)}
    }

    private fun scheduleLoad() {
        if(!active || preparedGeneration==generation || !registered)return
        val version=generation
        val wallpaperVersion=wallpaperGeneration
        val blurValue=blur
        // A zero-blur change can immediately share the already-sized sharp texture. Do not
        // queue a worker, copy pixels or allocate a GPU blur pass merely to return this image.
        val cachedSharp=sharp?.takeUnless {it.isRecycled}
        if(blurValue<=0 && cachedSharp!=null) {
            synchronized(lock) {
                if(!active || version!=generation)return
                image=cachedSharp;preparedGeneration=version;attemptedGeneration=version;imageSerial++
            }
            notifyReady()
            return
        }
        if(attemptedGeneration==version || !loading.compareAndSet(false,true))return
        attemptedGeneration=version
        val owner=app ?: run {loading.set(false);return}
        try {
            worker.execute {
                var decoded:Bitmap?=null
                try {
                    if(active && version==generation) {
                        val source=sharp ?: readStaticSystemWallpaper(owner)?.let {fresh ->
                            synchronized(lock) {
                                if(wallpaperGeneration==wallpaperVersion)sharp=fresh
                            }
                            fresh
                        }
                        if(source!=null && active && version==generation)
                            decoded=IconGlassBackdrop.prepare(source,blurValue) { !active || version!=generation }
                    }
                } catch(error:Throwable) {
                    diagnostic("桌面壁纸读取不可用，图标使用原生外观: "+error.javaClass.simpleName)
                } finally {
                    val result=decoded
                    val accepted=ui.post {
                        loading.set(false)
                        if(active && version==generation && registered) {
                            preparedGeneration=version
                            image=result ?: image
                            if(result!=null) {
                                imageSerial++
                                diagnostic("3.0.0：共享壁纸 ${result.width}x${result.height}；blur=$blurValue；曲边清晰源=${sharp!=null}；未抓屏")
                                notifyReady()
                            }
                        } // Cached sharp pixels can be shared: never recycle published inputs.
                        if(active && version!=generation)scheduleLoad()
                    }
                    if(!accepted)loading.set(false)
                }
            }
        } catch(error:Throwable) {
            loading.set(false)
            diagnostic("壁纸准备不可用: "+error.javaClass.simpleName)
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun readStaticSystemWallpaper(context:Context):Bitmap? {
        val manager=WallpaperManager.getInstance(context)
        if(manager.wallpaperInfo!=null) {
            diagnostic("动态壁纸暂不接入图标玻璃；使用原生图标，不复用旧壁纸")
            return null
        }
        val id=manager.getWallpaperId(WallpaperManager.FLAG_SYSTEM)
        val file=manager.getWallpaperFile(WallpaperManager.FLAG_SYSTEM) ?: return null
        val bitmap=file.use {fd ->
            val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true}
            fd.dup().use {BitmapFactory.decodeFileDescriptor(it.fileDescriptor,null,bounds)}
            if(bounds.outWidth<=0 || bounds.outHeight<=0)return null
            var sample=1
            while((maxOf(bounds.outWidth,bounds.outHeight).toLong()+sample-1L)/sample>2048L)sample*=2
            fd.dup().use {
                BitmapFactory.decodeFileDescriptor(it.fileDescriptor,null,BitmapFactory.Options().apply {
                    inSampleSize=sample;inPreferredConfig=Bitmap.Config.ARGB_8888
                })
            }
        } ?: return null
        if(manager.wallpaperInfo!=null || id!=manager.getWallpaperId(WallpaperManager.FLAG_SYSTEM)) {
            bitmap.recycle();return null
        }
        // Retain a small sharp source once; slider changes reuse it without file IO.
        val ratio=minOf(1f,768f/maxOf(bitmap.width,bitmap.height))
        val cached=Bitmap.createScaledBitmap(bitmap,(bitmap.width*ratio).toInt().coerceAtLeast(1),
            (bitmap.height*ratio).toInt().coerceAtLeast(1),true)
        if(cached!==bitmap)bitmap.recycle()
        return cached
    }

    @Suppress("DEPRECATION")
    private fun updateScreen(context:Context) {
        try {
            val metrics=DisplayMetrics()
            (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.defaultDisplay?.getRealMetrics(metrics)
            if(metrics.widthPixels<=0 || metrics.heightPixels<=0)metrics.setTo(context.resources.displayMetrics)
            screenWidth=metrics.widthPixels;screenHeight=metrics.heightPixels
        } catch(_:Throwable) {
            screenWidth=context.resources.displayMetrics.widthPixels
            screenHeight=context.resources.displayMetrics.heightPixels
        }
    }

    private fun notifyReady() {
        if(!active || !notificationQueued.compareAndSet(false,true))return
        val posted=ui.post {
            try {
                Choreographer.getInstance().postFrameCallback {
                    notificationQueued.set(false)
                    if(active)RuntimeWorkPolicy.guarded({}) {onReady.get()?.invoke()}
                }
            } catch(_:Throwable) {notificationQueued.set(false)}
        }
        if(!posted)notificationQueued.set(false)
    }

    private fun diagnostic(message:String) = RuntimeWorkPolicy.guarded({}) {
        RuntimeDiagnostics.update("icon_wallpaper",message)
    }
}

/** Owned, unpublished output only; callers retain published hardware images without recycling. */
internal object IconGlassBackdrop {
    fun prepare(source:Bitmap,blur:Int=26,cancelled:()->Boolean={false}):Bitmap {
        val ratio=minOf(1f,768f/maxOf(source.width,source.height))
        val width=(source.width*ratio).toInt().coerceAtLeast(1)
        val height=(source.height*ratio).toInt().coerceAtLeast(1)
        if(blur<=0)return Bitmap.createScaledBitmap(source,width,height,true)
        return HardwareBackdropBlur().use { it.render(source,width,height,IconBlurPolicy.radius(blur),cancelled) }
    }
}

/** Screen-to-wallpaper sampling. Offset zero deliberately matches LauncherHook's left/top
 * origin; the launcher forwards its actual startup offsets. Zoom is inverted about screen center.
 */
internal object IconWallpaperGeometry {
    fun valid(left:Float,top:Float,right:Float,bottom:Float) = left.isFinite() && top.isFinite() &&
        right.isFinite() && bottom.isFinite() && right>left && bottom>top

    fun map(width:Int,height:Int,screenWidth:Int,screenHeight:Int,
            left:Float,top:Float,right:Float,bottom:Float,x:Float,y:Float,zoom:Float):FloatArray? {
        if(!valid(left,top,right,bottom))return null
        val projection=create(width,height,screenWidth,screenHeight,x,y,zoom) ?: return null
        return floatArrayOf(projection.x(left),projection.y(top),projection.x(right),projection.y(bottom))
    }
    class Projection internal constructor(val width:Int,val height:Int,val screenWidth:Int,val screenHeight:Int,
        val offsetX:Float,val offsetY:Float,val zoom:Float,private val factor:Float,
        private val originX:Float,private val originY:Float) {
        fun x(screen:Float)=screen*factor+originX
        fun y(screen:Float)=screen*factor+originY
    }
    /** Immutable cover/offset/zoom projection shared by every icon in the same scene.
     * A moving icon only needs four multiply/add pairs; no per-icon projection key/array.
     */
    class ProjectionCache {
        private var last:Projection?=null
        fun get(width:Int,height:Int,screenWidth:Int,screenHeight:Int,x:Float,y:Float,zoom:Float):Projection? {
            last?.let {if(it.width==width && it.height==height && it.screenWidth==screenWidth &&
                it.screenHeight==screenHeight && it.offsetX==x && it.offsetY==y && it.zoom==zoom)return it}
            return create(width,height,screenWidth,screenHeight,x,y,zoom)?.also {last=it}
        }
    }
    private fun create(width:Int,height:Int,screenWidth:Int,screenHeight:Int,x:Float,y:Float,zoom:Float):Projection? {
        if(width<=0 || height<=0 || screenWidth<=0 || screenHeight<=0 || !x.isFinite() || !y.isFinite() ||
            !zoom.isFinite() || zoom<=0f)return null
        val scale=maxOf(screenWidth.toFloat()/width,screenHeight.toFloat()/height)
        val cropX=(width*scale-screenWidth).coerceAtLeast(0f)*x.coerceIn(0f,1f)
        val cropY=(height*scale-screenHeight).coerceAtLeast(0f)*y.coerceIn(0f,1f)
        val cx=screenWidth*.5f;val cy=screenHeight*.5f
        val z=zoom.coerceIn(.1f,4f)
        return Projection(width,height,screenWidth,screenHeight,x,y,zoom,1f/(z*scale),
            (cx-cx/z+cropX)/scale,(cy-cy/z+cropY)/scale)
    }
}
