/* DreamGlass contributors. Native GPU scene blur; no image processing or capture worker. */
package com.coloros16.liquidglass.hook

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import com.coloros16.liquidglass.config.Prefs
import com.coloros16.liquidglass.diagnostics.RuntimeDiagnostics
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.ExceptionMode
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.WeakHashMap

object SceneBackdropBlurHook {
    private data class Config(val at:Long,val enabled:Boolean,val percent:Int)
    @Volatile private var cached=Config(-1000L,false,30)
    private val depthScope=ThreadLocal<Boolean>()
    private val popupScope=ThreadLocal<Int?>()
    private val cacheWriteScope=ThreadLocal<Boolean>()
    private val customCallbacks=Collections.synchronizedMap(WeakHashMap<Any,Boolean>())
    private fun config(api:XposedInterface):Config {
        val now=SystemClock.uptimeMillis();if(now-cached.at<500)return cached
        val p=Prefs.read(api)
        return Config(now,p.getBoolean(Prefs.KEY_MASTER,false) && p.getBoolean(SceneBlurPolicy.KEY_ENABLED,false),
            Prefs.readIntCompat(p,SceneBlurPolicy.KEY_STRENGTH,30).coerceIn(0,100)).also { cached=it }
    }
    private fun mount(name:String,block:()->Unit) {
        runCatching(block).onSuccess { RuntimeDiagnostics.mark("scene-blur-$name","原生 GPU 模糊入口已注册，等待场景") }
            .onFailure { RuntimeDiagnostics.mark("scene-blur-$name","当前组件未匹配，保留原生：${it.javaClass.simpleName}") }
    }
    fun install(api:XposedInterface,loader:ClassLoader,packageName:String) {
        installCoui(api,loader)
        when(packageName) {
            "com.android.systemui"->mount("notification") {
                val type=Class.forName("com.oplusos.systemui.common.blurability.motion.BlurUtils",false,loader)
                val root=Class.forName("android.view.ViewRootImpl",false,loader)
                val getView=root.getDeclaredMethod("getView")
                val method=type.getDeclaredMethod("applyBlur",root,Int::class.javaPrimitiveType,Boolean::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,Boolean::class.javaPrimitiveType,Boolean::class.javaPrimitiveType,Float::class.javaPrimitiveType)
                api.hook(method).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                    val c=config(api);val host=chain.getArg(0)
                    val view=host?.let { getView.invoke(it) as? View }
                    if(!c.enabled || view==null || !SceneBlurPolicy.menuRoot(view.javaClass.name))return@intercept chain.proceed()
                    val args=chain.getArgs().toTypedArray();args[1]=SceneBlurPolicy.radius(chain.getArg(1) as Int,c.percent)
                    RuntimeDiagnostics.mark("scene-blur-notification-event","通知展开背景模糊：${c.percent}%；保留原生淡入淡出")
                    chain.proceed(args)
                }
                // applyBlur$default is a small bridge and can inline the actual setter.
                type.declaredMethods.filter { it.name.startsWith("applyBlur") }.forEach { api.deoptimize(it) }
            }
            "com.android.launcher"->{ installLauncher(api,loader);installPopup(api,loader) }
            "com.coloros.assistantscreen"->mount("assistant") {
                val helper=Class.forName("com.oplus.assistantscreen.common.utils.w2",false,loader)
                val method=helper.getDeclaredMethod("a",Float::class.javaPrimitiveType,View::class.java)
                check(Modifier.isStatic(method.modifiers))
                api.hook(method).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                    val c=config(api);if(!c.enabled)return@intercept chain.proceed()
                    val args=chain.getArgs().toTypedArray();args[0]=SceneBlurPolicy.radius(chain.getArg(0) as Float,c.percent)
                    // Zero uses the native clear method; createBlurEffect(0,0) is invalid.
                    if(args[0]==0f)helper.getDeclaredMethod("b",View::class.java).invoke(null,chain.getArg(1))
                    else chain.proceed(args)
                }
                Class.forName("em0.c",false,loader).declaredMethods.filter { it.name=="setBlurRadius" }
                    .forEach { api.deoptimize(it) }
            }
        }
    }
    private fun installCoui(api:XposedInterface,loader:ClassLoader)=mount("coui") {
        val type=Class.forName("com.coui.appcompat.uiutil.COUIBackgroundBlurBuilder",false,loader)
        val radius=type.getDeclaredMethod("getEffectiveBlurRadius")
        check(radius.returnType==Int::class.javaPrimitiveType && radius.parameterCount==0)
        api.hook(radius).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
            val original=chain.proceed() as Int;val c=config(api)
            if(c.enabled)SceneBlurPolicy.radius(original,c.percent) else original
        }
        // Scale the returned request only; the theme builder's native radius is untouched.
        type.declaredMethods.filter { it.name in setOf("applyBlurBackground","refreshBlurParams","setBlurEnable") }
            .forEach { api.deoptimize(it) }
    }
    private fun installLauncher(api:XposedInterface,loader:ClassLoader)=mount("launcher") {
        val depth=Class.forName("com.android.launcher3.uioverrides.states.OplusDepthController",false,loader)
        val set=depth.getDeclaredMethod("setBlur",Float::class.javaPrimitiveType,Boolean::class.javaPrimitiveType)
        api.hook(set).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
            val before=depthScope.get();depthScope.set(config(api).enabled)
            try { chain.proceed() } finally { if(before==null)depthScope.remove() else depthScope.set(before) }
        }
        val manager=Class.forName("com.oplus.graphics.OplusBlurManager",false,loader)
        val setter=manager.declaredMethods.single { it.name=="setBackgroundBlurRadius" && it.parameterCount==4 &&
            it.parameterTypes[0].name=="android.view.SurfaceControl\$Transaction" && it.parameterTypes[2]==Int::class.javaPrimitiveType }
        api.hook(setter).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
            if(depthScope.get()!=true)return@intercept chain.proceed()
            val args=chain.getArgs().toTypedArray();args[2]=SceneBlurPolicy.radius(chain.getArg(2) as Int,cached.percent)
            RuntimeDiagnostics.mark("scene-blur-launcher-event","桌面／负一屏原生背景模糊：${cached.percent}%；不修改前景与动画进度")
            chain.proceed(args)
        }
        api.deoptimize(set)
    }
    private fun installPopup(api:XposedInterface,loader:ClassLoader)=mount("launcher-popup") {
        val helper=Class.forName("com.android.launcher3.popup.PopupBlurHelper",false,loader)
        val load=helper.declaredMethods.single { it.name=="loadPopupBlurBg" && it.parameterCount==2 }
        val blur=helper.declaredMethods.single { it.name=="blurBitmap\$default" && it.parameterCount==7 && it.parameterTypes[2]==Float::class.javaPrimitiveType }
        val gpu=Class.forName("hl.a",false,loader)
        val defaults=Class.forName("hl.a\$a",false,loader)
        val configure=defaults.getDeclaredMethod("a",gpu,Float::class.javaPrimitiveType,Float::class.javaPrimitiveType,
            Float::class.javaPrimitiveType,Int::class.javaPrimitiveType)
        check(Modifier.isStatic(configure.modifiers) && configure.returnType==Void.TYPE)
        val cache=Class.forName("com.android.launcher.wallpaper.d",false,loader)
        val read=cache.getDeclaredMethod("a");check(read.returnType==Bitmap::class.java)
        val write=cache.getDeclaredMethod("d",Bitmap::class.java,Boolean::class.javaPrimitiveType)
        val callback=Class.forName("${helper.name}\$loadPopupBlurBg\$1",false,loader)
        // A preblurred wallpaper cannot be made sharp again. Bypass only this request's
        // cache read and run the existing OEM GPU effect on its original wallpaper input.
        api.hook(load).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
            val c=config(api);val before=popupScope.get();popupScope.set(if(c.enabled && c.percent<100)c.percent else null)
            try { chain.proceed() } finally { if(before==null)popupScope.remove() else popupScope.set(before) }
        }
        api.hook(read).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
            if(popupScope.get()!=null)null else chain.proceed()
        }
        // Both the wallpaper Bitmap and the icon/drag-layer HardwareBuffer reach this
        // GPU parameter setter. Hooking blurBitmap$default alone misses the latter.
        // Scale exactly once here; never multiply the high-level radius as well.
        api.hook(configure).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
            val percent=popupScope.get() ?: return@intercept chain.proceed()
            val args=chain.getArgs().toTypedArray();args[1]=SceneBlurPolicy.radius(chain.getArg(1) as Float,percent)
            RuntimeDiagnostics.mark("scene-blur-popup-event","桌面弹出层壁纸与图标使用相同原生 GPU 模糊力度：$percent%")
            chain.proceed(args)
        }
        for(ctor in callback.declaredConstructors)api.hook(ctor).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
            val result=chain.proceed();if(popupScope.get()!=null && customCallbacks.size<32)
                chain.getThisObject()?.let { customCallbacks[it]=true };result
        }
        val done=callback.getDeclaredMethod("effectDone",Bitmap::class.java)
        api.hook(done).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
            val before=cacheWriteScope.get();cacheWriteScope.set(customCallbacks.containsKey(chain.getThisObject()))
            try { chain.proceed() } finally { if(before==null)cacheWriteScope.remove() else cacheWriteScope.set(before) }
        }
        // Custom-strength results are private to the popup and must not poison the system's
        // normal wallpaper cache, including asynchronous completion after settings changed.
        api.hook(write).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
            if(cacheWriteScope.get()==true)null else chain.proceed()
        }
        listOf(load,blur,configure,done).forEach { api.deoptimize(it) }
    }
}
