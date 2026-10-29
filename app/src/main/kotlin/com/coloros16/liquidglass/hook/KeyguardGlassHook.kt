/* DreamGlass contributors, 2026-10-29. GPL-3.0. No screen/notification capture. */
package com.coloros16.liquidglass.hook

import android.app.Application
import android.app.WallpaperManager
import android.content.*
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.Drawable
import android.os.*
import android.view.*
import android.widget.TextView
import com.coloros16.liquidglass.config.Prefs
import com.coloros16.liquidglass.diagnostics.RuntimeDiagnostics
import com.coloros16.liquidglass.log.Lg
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.ExceptionMode
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Uses actual attached plugin views, not a clock class resolved from the SystemUI loader.
 * Native glyph rendering, parent occlusion, content and click handlers remain authoritative.
 * No source or unsupported live wallpaper -> native appearance. AOD is always excluded.
 */
object KeyguardGlassHook {
    const val KEY_ENABLE="keyguard_glass_enable"
    const val KEY_TEXT="keyguard_glass_text"
    const val KEY_CARDS="keyguard_glass_cards"
    const val KEY_SHORTCUTS="keyguard_glass_shortcuts"
    const val KEY_STRENGTH="keyguard_glass_strength"
    private val ui=Handler(Looper.getMainLooper())
    private val entries=WeakHashMap<View,Entry>()
    private val nativeEffects=WeakHashMap<View,RenderEffect?>()
    private val pendingLayout=WeakHashMap<View,Boolean>()
    private val worker=RuntimeWorkPolicy.executor("DreamGlass-keyguard-wallpaper")
    private val loading=AtomicBoolean(false)
    private var api:XposedInterface?=null
    private var mounted=false
    private var ownChange=false
    private var scanning=false
    private var enabled=false;private var text=true;private var cards=true;private var shortcuts=true
    private var strength=1f;private var blur=6f;private var readAt=-1000L
    private var powerAt=-1000L;private var powerAllowed=false
    @Volatile private var source:Bitmap?=null
    @Volatile private var epoch=0
    private var retryAt=0L
    private var sourceKey=""
    private var loadedKey=""
    private var application:Context?=null

    fun install(api:XposedInterface,loader:ClassLoader) {
        if(mounted || Application.getProcessName()!="com.android.systemui")return
        this.api=api
        try {
            // Default-off must not add global view work. First enabling requires scope reload.
            if(!Prefs.read(api).getBoolean(KEY_ENABLE,false))return
            api.hook(View::class.java.getDeclaredMethod("onAttachedToWindow")).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                val result=chain.proceed()
                guarded { (chain.getThisObject() as? View)?.let(::consider) };result
            }
            api.hook(View::class.java.getDeclaredMethod("setRenderEffect",RenderEffect::class.java)).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                if(ownChange || Looper.myLooper()!=Looper.getMainLooper())return@intercept chain.proceed()
                val view=chain.getThisObject() as? View
                val native=chain.getArg(0) as? RenderEffect
                var replacement:RenderEffect?=native
                guarded {
                    if(view is TextView) {
                        if(nativeEffects.size>=128)nativeEffects.clear()
                        nativeEffects[view]=native
                        entries[view]?.let { entry -> entry.originalEffect=native
                            if(entry.applied)replacement=entry.compose(native)
                        }
                    }
                }
                val args=chain.getArgs().toTypedArray();args[0]=replacement;chain.proceed(args)
            }
            api.hook(View::class.java.getDeclaredMethod("setBackgroundDrawable",Drawable::class.java)).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                if(ownChange || Looper.myLooper()!=Looper.getMainLooper())return@intercept chain.proceed()
                val view=chain.getThisObject() as? View
                val entry=entries[view]
                val background=chain.getArg(0) as? Drawable
                if(entry!=null && entry.role!=KeyguardGlassPolicy.TEXT) {
                    entry.originalBackground=background;entry.dirty()
                    if(entry.applied) {
                        val args=chain.getArgs().toTypedArray();args[0]=entry.background;return@intercept chain.proceed(args)
                    }
                }
                val result=chain.proceed()
                guarded { if(view!=null && entry==null && view.isAttachedToWindow)consider(view) };result
            }
            mounted=true
            RuntimeDiagnostics.mark("keyguard_hook","锁屏视图发现入口已安装；实际命中与效果待核验")
        } catch(error:Throwable) { failure(error) }
    }
    private fun guarded(block:()->Unit) { RuntimeWorkPolicy.guarded(::failure,block) }
    private fun failure(error:Throwable) {
        retryAt=SystemClock.uptimeMillis()+RuntimeWorkPolicy.failureDelayMs(error)
        try { Lg.w("DreamGlass","keyguard glass fallback",error) } catch(_:Throwable) { }
    }
    private fun resourceId(view:View)=try { view.resources.getResourceEntryName(view.id) } catch(_:Throwable) { "" }
    private fun names(view:View):List<String> {
        val out=ArrayList<String>();var node:View?=view;var depth=0
        while(node!=null && depth++<20) { out.add(node.javaClass.name+"#"+resourceId(node));out.add(node.javaClass.name);node=node.parent as? View }
        return out
    }
    private fun consider(view:View) {
        if(Looper.myLooper()!=Looper.getMainLooper() || entries.containsKey(view) || entries.size>=64 || !view.isAttachedToWindow)return
        val path=names(view)
        if(path.none { it.contains("keyguard",true) })return
        // Decide card geometry after layout: an unmeasured tall widget column must not look square.
        if(view.width<=0 || view.height<=0) {
            if(pendingLayout.size<128 && pendingLayout.put(view,true)==null) {
                view.addOnLayoutChangeListener(object:View.OnLayoutChangeListener {
                    override fun onLayoutChange(v:View,l:Int,t:Int,r:Int,b:Int,ol:Int,ot:Int,or:Int,ob:Int) {
                        v.removeOnLayoutChangeListener(this);pendingLayout.remove(v);guarded { consider(v) }
                    }
                })
            }
            return
        }
        if(!scanning && view is ViewGroup && (view.javaClass.name.contains("KeyguardStyleClock") ||
            view.javaClass.name.contains("KeyguardStyleWidget") || view.javaClass.name.endsWith("KeyguardStatusView") ||
            view.javaClass.name.endsWith("KeyguardBottomAreaView") || resourceId(view) in setOf("keyguard_widget_container","keyguard_widget_area"))) {
            scanning=true
            try {
                var count=0
                fun scan(node:View,depth:Int) {
                    if(count++>=192 || depth>12)return
                    if(node!==view)consider(node)
                    if(node is ViewGroup)for(i in 0 until node.childCount)scan(node.getChildAt(i),depth+1)
                }
                scan(view,0)
            } finally { scanning=false }
        }
        val role=KeyguardGlassPolicy.role(path,resourceId(view),view is TextView,view.background!=null,view.isClickable,view.width.toFloat()/view.height)
        if(role==KeyguardGlassPolicy.NONE || view is android.widget.EditText)return
        ensureApplication(Lg.processContext()?.applicationContext ?: view.context.applicationContext)
        Entry(view,role).also { entry -> entries[view]=entry;view.viewTreeObserver.addOnPreDrawListener(entry);view.addOnAttachStateChangeListener(entry);entry.onPreDraw() }
        RuntimeDiagnostics.mark("keyguard_detected","锁屏角色=$role，${view.javaClass.simpleName}；保留字形、子控件与点击行为")
    }
    private fun ensureApplication(context:Context) {
        if(application!=null || context.packageName!="com.android.systemui")return
        application=context
        val filter=IntentFilter().apply { addAction(Intent.ACTION_WALLPAPER_CHANGED);addAction(Intent.ACTION_SCREEN_OFF);addAction(Intent.ACTION_SCREEN_ON);addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED);addAction("android.intent.action.USER_SWITCHED") }
        context.registerReceiver(object:BroadcastReceiver() {
            override fun onReceive(c:Context?,intent:Intent?) { guarded {
                if(intent?.action==Intent.ACTION_WALLPAPER_CHANGED || intent?.action=="android.intent.action.USER_SWITCHED") {
                    epoch++;source=null;sourceKey="";loadedKey=""
                } else if(intent?.action==Intent.ACTION_SCREEN_OFF)epoch++
                readAt=-1000L;powerAt=-1000L
                entries.values.toList().forEach { it.onPreDraw();it.view.get()?.invalidate() }
            } }
        },filter,Context.RECEIVER_NOT_EXPORTED)
    }
    private fun preferences() {
        val now=SystemClock.uptimeMillis();if(now-readAt<700)return
        readAt=now
        val prefs=api?.let(Prefs::read) ?: return
        enabled=prefs.getBoolean(Prefs.KEY_MASTER,false) && prefs.getBoolean(Prefs.KEY_GLASS_ENABLE,true) && prefs.getBoolean(KEY_ENABLE,false)
        text=prefs.getBoolean(KEY_TEXT,true);cards=prefs.getBoolean(KEY_CARDS,true);shortcuts=prefs.getBoolean(KEY_SHORTCUTS,true)
        strength=(Prefs.readIntCompat(prefs,KEY_STRENGTH,100)/100f).coerceIn(0f,2f)
        blur=(try { prefs.getFloat(Prefs.KEY_LOCKSCREEN_CLOCK_BLUR,6f) } catch(_:ClassCastException) { prefs.getInt(Prefs.KEY_LOCKSCREEN_CLOCK_BLUR,6).toFloat() }).coerceIn(0f,24f)
    }
    private fun active(view:View,role:Int):Boolean {
        preferences()
        if(!enabled)return false
        val now=SystemClock.uptimeMillis()
        if(now-powerAt>=400L) {
            powerAt=now
            val power=view.context.getSystemService(PowerManager::class.java)
            powerAllowed=power?.isInteractive==true && !power.isPowerSaveMode && view.display?.state==Display.STATE_ON
        }
        return powerAllowed && when(role) { KeyguardGlassPolicy.TEXT->text;KeyguardGlassPolicy.CARD->cards;else->shortcuts } &&
            view.isShown && view.windowVisibility==View.VISIBLE
    }
    /** One owned decode + hardware blur per wallpaper/config generation, never on the main Looper. */
    private fun request(view:View) {
        val context=application ?: return
        val dm=view.resources.displayMetrics
        val key="${dm.widthPixels}x${dm.heightPixels}/$blur"
        if(source!=null && loadedKey==key || SystemClock.uptimeMillis()<retryAt)return
        if(!loading.compareAndSet(false,true))return
        val version=epoch;sourceKey=key;val radius=blur
        try { worker.execute {
            var result:Bitmap?=null
            try {
                val image=readWallpaper(context) ?: return@execute
                if(version!=epoch)return@execute
                val scale=maxOf(dm.widthPixels.toFloat()/image.width,dm.heightPixels.toFloat()/image.height)
                result=if(radius>0f)HardwareBackdropBlur().use { it.render(image,image.width,image.height,radius/scale) { version!=epoch } } else image
            } catch(error:Throwable) { ui.post { failure(error) } }
            finally {
                val published=result
                ui.post { guarded {
                    loading.set(false)
                    if(version==epoch && sourceKey==key) {
                        source=published;loadedKey=key
                        if(published==null)retryAt=SystemClock.uptimeMillis()+30_000L
                        RuntimeDiagnostics.mark("keyguard_source",if(published!=null)"锁屏静态壁纸GPU材质已就绪" else "未取得可用静态锁屏壁纸，保留原生外观")
                    }
                    entries.values.toList().forEach { it.onPreDraw();it.view.get()?.invalidate() }
                } }
            }
        } } catch(error:Throwable) { loading.set(false);failure(error) }
    }
    // The host must already have this privileged permission. The module app requests none.
    @android.annotation.SuppressLint("MissingPermission")
    private fun readWallpaper(context:Context):Bitmap? {
        if(context.checkSelfPermission("android.permission.READ_WALLPAPER_INTERNAL")!=PackageManager.PERMISSION_GRANTED)return null
        // SystemUI may run as user 0 while another user owns the visible keyguard.
        // If the active-user context cannot be established, do not show a different user's image.
        val user=try { Class.forName("android.app.ActivityManager").getMethod("getCurrentUser").invoke(null) as Int } catch(_:Throwable) { return null }
        val userContext=if(user==android.os.Process.myUid()/100000)context else try {
            Context::class.java.getMethod("createContextAsUser",UserHandle::class.java,Integer.TYPE)
                .invoke(context,UserHandle.getUserHandleForUid(user*100000),0) as Context
        } catch(_:Throwable) { return null }
        val wm=WallpaperManager.getInstance(userContext)
        val lock=wm.getWallpaperFile(WallpaperManager.FLAG_LOCK)
        val descriptor=lock ?: if(wm.wallpaperInfo==null)wm.getWallpaperFile(WallpaperManager.FLAG_SYSTEM) else null
        descriptor ?: return null
        descriptor.use { fd ->
            val info=BitmapFactory.Options().apply { inJustDecodeBounds=true }
            fd.dup().use { BitmapFactory.decodeFileDescriptor(it.fileDescriptor,null,info) }
            if(info.outWidth<=0 || info.outHeight<=0)return null
            var sample=1
            while(maxOf(info.outWidth,info.outHeight)/sample>1600)sample*=2
            return fd.dup().use { BitmapFactory.decodeFileDescriptor(it.fileDescriptor,null,BitmapFactory.Options().apply {
                inSampleSize=sample;inPreferredConfig=Bitmap.Config.ARGB_8888
            }) }
        }
    }
    private class Entry(view:View,val role:Int):ViewTreeObserver.OnPreDrawListener,View.OnAttachStateChangeListener {
        val view=WeakReference(view)
        private val attachment=KeyguardGlassAttachment(view,role,nativeEffects[view],::change)
        var originalEffect:RenderEffect?
            get()=attachment.originalEffect
            set(value) { attachment.originalEffect=value }
        var originalBackground:Drawable?
            get()=attachment.originalBackground
            set(value) { attachment.originalBackground=value }
        val background get()=attachment.background
        private val matrix=Matrix();private val values=FloatArray(9);private val origin=IntArray(2)
        private var previous=0
        val applied get()=attachment.applied
        fun dirty() { previous=0 }
        fun compose(native:RenderEffect?):RenderEffect?=attachment.compose(native)
        private var blockedUntil=0L
        override fun onPreDraw():Boolean {
            if(SystemClock.uptimeMillis()<blockedUntil)return true
            try { refresh() } catch(error:Throwable) {
                blockedUntil=SystemClock.uptimeMillis()+RuntimeWorkPolicy.failureDelayMs(error)
                guarded { restore() };failure(error)
            }
            return true
        }
        fun refresh() {
            val v=view.get() ?: return
            if(!active(v,role) || !v.isHardwareAccelerated || v.width<=0 || v.height<=0) { restore();return }
            request(v)
            val image=source ?: run { restore();return }
            matrix.reset();v.transformMatrixToGlobal(matrix);matrix.getValues(values)
            v.getLocationOnScreen(origin);values[2]=origin[0].toFloat();values[5]=origin[1].toFloat()
            var hash=System.identityHashCode(image)
            for(value in values)hash=hash*31+value.toRawBits()
            hash=hash*31+v.width;hash=hash*31+v.height;hash=hash*31+strength.toRawBits()
            if(hash!=previous || !applied) {
                val first=!applied
                val outline=Outline();originalBackground?.getOutline(outline)
                if(outline.isEmpty)v.outlineProvider?.getOutline(v,outline)
                val radius=if(role==KeyguardGlassPolicy.SHORTCUT)minOf(v.width,v.height)*.5f else
                    if(outline.radius>0f)outline.radius else minOf(v.width,v.height)*.24f
                val dm=v.resources.displayMetrics
                attachment.apply(image,values,dm.widthPixels,dm.heightPixels,strength,radius)
                previous=hash;v.invalidate()
                if(first)RuntimeDiagnostics.mark("keyguard_material","锁屏角色=${role}已挂载GPU材质；真机外观待确认")
            }
        }
        private fun change(block:()->Unit) { ownChange=true;try { block() } finally { ownChange=false } }
        fun restore() {
            val v=view.get() ?: return
            if(!applied)return
            previous=0;attachment.restore()
        }
        override fun onViewAttachedToWindow(v:View) { }
        override fun onViewDetachedFromWindow(v:View) { guarded {
            restore();v.viewTreeObserver.removeOnPreDrawListener(this);v.removeOnAttachStateChangeListener(this)
            entries.remove(v);nativeEffects.remove(v)
        } }
    }
}
