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
    private val pluginRoots=WeakHashMap<View,Int>()
    private var nativeWallpaper:WeakReference<Bitmap>?=null
    private var nativeWallpaperAt=-1000L
    @Volatile private var nativeController:WeakReference<Any>?=null
    private var nativeCopy:java.lang.reflect.Method?=null
    private class WallpaperCapture(val node:RenderNode,val width:Int,val height:Int) { var inUse=false }
    private var pendingWallpaper:WallpaperCapture?=null
    @Volatile private var ownedWallpaper:Bitmap?=null
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
            // Install discovery even when disabled: the next screen-on can discover an already
            // attached plugin after enabling. Disabled consider() returns before any tree scan.
            installPluginRoutes(api,loader)
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
                    // Plugin roots can be plain FrameLayout/ImageView and set their original
                    // effect before getView() returns. Retain that effect for exact restoration.
                    if(view!=null) {
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
            Lg.processContext()?.applicationContext?.let(::ensureApplication)
            ui.post { guarded { discoverWindows() } }
            RuntimeDiagnostics.mark("keyguard_hook","锁屏视图发现入口已安装；实际命中与效果待核验")
        } catch(error:Throwable) { failure(error) }
    }
    private fun installPluginRoutes(api:XposedInterface,loader:ClassLoader) {
        try {
            val base=Class.forName("com.oplus.keyguard.plugin.AbstractSubPlugin",false,loader)
            val clock=Class.forName("com.oplus.keyguard.plugin.ClockPlugin",false,loader)
            val widget=Class.forName("com.oplus.keyguard.plugin.WidgetPlugin",false,loader)
            val method=base.getDeclaredMethod("getView",Integer.TYPE)
            api.hook(method).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                val result=chain.proceed()
                val target=result as? View
                val kind=when { clock.isInstance(chain.getThisObject())->KeyguardGlassPolicy.TEXT
                    widget.isInstance(chain.getThisObject())->KeyguardGlassPolicy.CARD;else->KeyguardGlassPolicy.NONE }
                if(target!=null && kind!=KeyguardGlassPolicy.NONE)ui.post { guarded {
                    pluginRoots[target]=kind
                    RuntimeDiagnostics.mark("keyguard_plugin_view_$kind","插件已返回实际视图：${target.javaClass.simpleName}")
                    // Replace earlier descendant attachments, avoiding two filters on one glyph.
                    entries.keys.toList().filter { child ->
                        var p:View?=child.parent as? View;var inside=false
                        while(p!=null) { if(p===target) { inside=true;break };p=p.parent as? View };inside
                    }.forEach { child -> entries.remove(child)?.let { old ->
                        old.restore();child.viewTreeObserver.removeOnPreDrawListener(old);child.removeOnAttachStateChangeListener(old)
                    } }
                    scanTree(target)
                } }
                result
            }
            val peer=clock.getDeclaredMethod("setPeerWallpaperBitmap",Bundle::class.java)
            val transfer=Class.forName("com.oplus.keyguard.binder.WallpaperPeerTransfer",false,loader)
            val resolve=transfer.getDeclaredMethod("resolveBitmapFromBinder",IBinder::class.java).apply { isAccessible=true }
            val instance=if(java.lang.reflect.Modifier.isStatic(resolve.modifiers))null else transfer.getField("INSTANCE").get(null)
            api.hook(peer).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                val result=chain.proceed()
                guarded {
                    preferences()
                    val bundle=if(enabled)chain.getArg(0) as? Bundle else null
                    if(bundle!=null)for(key in bundle.keySet()) {
                        val value=runCatching { bundle.get(key) }.getOrNull()
                        val image=when(value) { is Bitmap->value;is IBinder->runCatching { resolve.invoke(instance,value) as? Bitmap }.getOrNull();else->null }
                        if(image!=null) { acceptWallpaper(image);break }
                    }
                }
                result
            }
            RuntimeDiagnostics.mark("keyguard_plugin","已接入原厂时钟/组件 getView 和壁纸传递接口")
        } catch(error:Throwable) { Lg.w("DreamGlass","keyguard plugin route unavailable",error) }
        try {
            val type=Class.forName("com.oplus.systemui.keyguard.wallpaper.OplusLockScreenWallpaperController",false,loader)
            val method=type.getDeclaredMethod("copyWallpaperBitmap").apply { isAccessible=true }
            nativeCopy=method
            for(ctor in type.declaredConstructors)api.hook(ctor).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                val result=chain.proceed();chain.getThisObject()?.let { nativeController=WeakReference(it) };result
            }
            api.hook(method).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                val result=chain.proceed()
                chain.getThisObject()?.let { nativeController=WeakReference(it) }
                guarded { (result as? Bitmap)?.let(::acceptWallpaper) };result
            }
        } catch(error:Throwable) { Lg.w("DreamGlass","native wallpaper copy route unavailable",error) }
    }
    private fun acceptWallpaper(image:Bitmap) {
        if(Looper.myLooper()!=Looper.getMainLooper()) { ui.post { guarded { acceptWallpaper(image) } };return }
        preferences();if(!enabled || image.isRecycled || image.width<=0 || image.height<=0)return
        val now=SystemClock.uptimeMillis()
        if(nativeWallpaper?.get()===image && source!=null || now-nativeWallpaperAt<1000L)return
        val node=RenderNode("DreamGlass native wallpaper input")
        node.setPosition(0,0,image.width,image.height)
        val canvas=node.beginRecording(image.width,image.height)
        try { canvas.drawBitmap(image,0f,0f,Paint(Paint.FILTER_BITMAP_FLAG)) } finally { node.endRecording() }
        pendingWallpaper?.takeIf { !it.inUse }?.node?.discardDisplayList()
        pendingWallpaper=WallpaperCapture(node,image.width,image.height)
        nativeWallpaperAt=now;nativeWallpaper=WeakReference(image)
        epoch++;source=null;ownedWallpaper=null;sourceKey="";loadedKey="";retryAt=0
        RuntimeDiagnostics.mark("keyguard_native_source","已取得 SystemUI 当前锁屏壁纸输入，不依赖文件读取权限")
        entries.values.toList().forEach { it.dirty();it.onPreDraw();it.view.get()?.invalidate() }
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
    private fun discoverWindows() {
        preferences();if(!enabled)return
        for(root in android.view.inspector.WindowInspector.getGlobalWindowViews())scanTree(root)
        // A disabled-at-start module may have missed the wallpaper event. Read only the native
        // controller's current wallpaper once, when an active lockscreen target actually needs it.
        if(source==null && pendingWallpaper==null && entries.values.any { e -> e.view.get()?.let { active(it,e.role) }==true }) {
            nativeController?.get()?.let { controller -> runCatching {
                (nativeCopy?.invoke(controller) as? Bitmap)?.let(::acceptWallpaper)
            } }
        }
    }
    private fun scanTree(root:View) {
        preferences();if(!enabled)return
        var count=0
        fun scan(v:View,depth:Int) {
            if(count++>=1600 || depth>24)return
            consider(v)
            if(v is ViewGroup)for(i in 0 until v.childCount)scan(v.getChildAt(i),depth+1)
        }
        val before=scanning;scanning=true
        try { scan(root,0) } finally { scanning=before }
    }
    private fun trusted(view:View):Int {
        var node:View?=view;var count=0
        while(node!=null && count++<24) {
            pluginRoots[node]?.let { return it }
            node=node.parent as? View
        }
        return KeyguardGlassPolicy.NONE
    }
    private fun consider(view:View) {
        if(Looper.myLooper()!=Looper.getMainLooper() || entries.containsKey(view) || entries.size>=64 || !view.isAttachedToWindow)return
        preferences();if(!enabled)return
        val path=names(view)
        val trust=trusted(view)
        if(trust==KeyguardGlassPolicy.NONE && path.none { it.contains("keyguard",true) })return
        var parent=view.parent as? View
        while(parent!=null) {
            val ancestor=entries[parent]
            // A clock plugin is one glyph RenderNode. A widget card owns only its own subtree.
            if(ancestor?.role==KeyguardGlassPolicy.TEXT || ancestor?.role==KeyguardGlassPolicy.CARD)return
            parent=parent.parent as? View
        }
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
        if(view is SurfaceView)return
        val glyphRoot=pluginRoots[view]==KeyguardGlassPolicy.TEXT && view.background?.opacity!=PixelFormat.OPAQUE
        val role=KeyguardGlassPolicy.role(path,resourceId(view),view is TextView,view.background!=null,
            view.isClickable,view.width.toFloat()/view.height,
            if(trust==KeyguardGlassPolicy.TEXT && !glyphRoot)KeyguardGlassPolicy.NONE else trust,
            view !is ViewGroup && view !is SurfaceView && view !is TextureView)
        if(role==KeyguardGlassPolicy.NONE || view is android.widget.EditText)return
        if(role==KeyguardGlassPolicy.CARD || role==KeyguardGlassPolicy.TEXT) {
            entries.keys.toList().filter { child ->
                var p:View?=child.parent as? View;var inside=false
                while(p!=null) { if(p===view) { inside=true;break };p=p.parent as? View };inside
            }.forEach { child -> entries.remove(child)?.let { old ->
                old.restore();child.viewTreeObserver.removeOnPreDrawListener(old);child.removeOnAttachStateChangeListener(old)
            } }
        }
        ensureApplication(Lg.processContext()?.applicationContext ?: view.context.applicationContext)
        Entry(view,role).also { entry -> entries[view]=entry;view.viewTreeObserver.addOnPreDrawListener(entry);view.addOnAttachStateChangeListener(entry);entry.onPreDraw() }
        RuntimeDiagnostics.mark("keyguard_detected_$role","锁屏角色=$role，${view.javaClass.simpleName}；保留字形、子控件与点击行为")
    }
    private fun ensureApplication(context:Context) {
        if(application!=null || context.packageName!="com.android.systemui")return
        application=context
        val filter=IntentFilter().apply { addAction(Intent.ACTION_WALLPAPER_CHANGED);addAction(Intent.ACTION_SCREEN_OFF);addAction(Intent.ACTION_SCREEN_ON);addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED);addAction("android.intent.action.USER_SWITCHED") }
        context.registerReceiver(object:BroadcastReceiver() {
            override fun onReceive(c:Context?,intent:Intent?) { guarded {
                if(intent?.action==Intent.ACTION_WALLPAPER_CHANGED || intent?.action=="android.intent.action.USER_SWITCHED") {
                    epoch++;source=null;ownedWallpaper=null;sourceKey="";loadedKey="";nativeWallpaper=null
                    pendingWallpaper?.takeIf { !it.inUse }?.node?.discardDisplayList();pendingWallpaper=null
                } else if(intent?.action==Intent.ACTION_SCREEN_OFF)epoch++
                readAt=-1000L;powerAt=-1000L
                entries.values.toList().forEach { it.onPreDraw();it.view.get()?.invalidate() }
                if(intent?.action==Intent.ACTION_SCREEN_ON) {
                    ui.post { guarded { discoverWindows() } }
                    ui.postDelayed({ guarded { powerAt=-1000L;discoverWindows();entries.values.toList().forEach { it.view.get()?.invalidate() } } },450L)
                }
            } }
        },filter,Context.RECEIVER_NOT_EXPORTED)
    }
    private fun preferences() {
        val now=SystemClock.uptimeMillis();if(now-readAt<700)return
        readAt=now
        val prefs=api?.let(Prefs::read) ?: return
        val before=enabled
        enabled=prefs.getBoolean(Prefs.KEY_MASTER,false) && prefs.getBoolean(Prefs.KEY_GLASS_ENABLE,true) && prefs.getBoolean(KEY_ENABLE,false)
        if(enabled && !before)ui.post { guarded { discoverWindows() } }
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
        val captured=pendingWallpaper?.also { it.inUse=true }
        val cached=ownedWallpaper
        try { worker.execute {
            var result:Bitmap?=null
            var raw:Bitmap?=null
            try {
                val image=if(captured!=null) {
                    val scale=minOf(1f,1600f/captured.width,1600f/captured.height)
                    HardwareBackdropBlur().use { it.render(captured.node,captured.width,captured.height,
                        (captured.width*scale).toInt().coerceAtLeast(1),(captured.height*scale).toInt().coerceAtLeast(1),.01f) { version!=epoch } }
                } else cached ?: readWallpaper(context) ?: return@execute
                raw=image
                if(version!=epoch)return@execute
                val scale=maxOf(dm.widthPixels.toFloat()/image.width,dm.heightPixels.toFloat()/image.height)
                result=if(radius>0f)HardwareBackdropBlur().use { it.render(image,image.width,image.height,radius/scale) { version!=epoch } } else image
            } catch(error:Throwable) {
                if(error !is java.util.concurrent.CancellationException)ui.post { failure(error) }
            }
            finally {
                captured?.node?.discardDisplayList()
                val published=result;val publishedRaw=raw
                ui.post { guarded {
                    if(pendingWallpaper===captured)pendingWallpaper=null
                    loading.set(false)
                    if(version==epoch && sourceKey==key) {
                        source=published;ownedWallpaper=publishedRaw;loadedKey=key
                        if(published==null)retryAt=SystemClock.uptimeMillis()+30_000L
                        RuntimeDiagnostics.update("keyguard_source",if(published!=null)"锁屏壁纸 GPU 材质已就绪" else "尚未取得锁屏壁纸；等待原生壁纸回调，保留原生外观")
                    }
                    entries.values.toList().forEach { it.onPreDraw();it.view.get()?.invalidate() }
                } }
            }
        } } catch(error:Throwable) {
            captured?.inUse=false
            loading.set(false);failure(error)
        }
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
