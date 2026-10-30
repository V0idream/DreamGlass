/* DreamGlass contributors, 2026-10-30. GPL-3.0. Native GPU texture adapter. */
package com.coloros16.liquidglass.hook

import android.graphics.*
import android.graphics.drawable.Drawable
import android.os.Looper
import android.os.SystemClock
import android.view.View
import com.coloros16.liquidglass.diagnostics.RuntimeDiagnostics
import com.coloros16.liquidglass.liquidglass.LiquidGlassShader
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.ExceptionMode
import java.util.WeakHashMap

/** COS17 SidebarPlatformBlurHelper uses an obfuscated ContinuousBlurDrawable subclass.
 * Its texture already comes from the system GPU pipeline, independently of the View's
 * background RenderEffect. Replace only the native shader draw; keep capture, clip, alpha,
 * animation, lifetime and foreground under native ownership. No CPU blur or extra capture.
 */
internal object NativeAppBackdropHook {
    private val states=java.util.Collections.synchronizedMap(WeakHashMap<Drawable,State>())
    private var baseType:Class<*>?=null
    fun owns(drawable:Drawable?)=drawable!=null && baseType?.isInstance(drawable)==true
    @Volatile private var retryAt=0L
    fun install(api:XposedInterface,loader:ClassLoader) {
        try {
            val base=Class.forName("com.oplus.posteffect.drawable.BaseDrawable",false,loader)
            val shaderType=Class.forName("com.oplus.posteffect.agsl.DrawableShader",false,loader)
            // Contracts from the device's SmartSideBar 17.9.8, not COS16 method names.
            val draw=base.getDeclaredMethod("j",Canvas::class.java)
            val paint=base.getDeclaredMethod("x")
            val shader=base.getDeclaredMethod("w")
            val owner=base.getDeclaredMethod("L")
            val input=shaderType.declaredFields.single { it.type==BitmapShader::class.java }.apply { isAccessible=true }
            val matrix=shaderType.declaredFields.single { it.type==Matrix::class.java }.apply { isAccessible=true }
            val scale=shaderType.declaredFields.single { it.type==PointF::class.java }.apply { isAccessible=true }
            val blend=Class.forName("com.oplus.posteffect.drawable.b",false,loader)
            val record=blend.getDeclaredMethod("S",Canvas::class.java)
            val scratch=base.getDeclaredMethod("F")
            baseType=base
            api.hook(base.getDeclaredMethod("draw",Canvas::class.java)).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                if(Looper.myLooper()==Looper.getMainLooper())runCatching {
                    val drawable=chain.getThisObject() as Drawable
                    val view=(drawable.callback as? View) ?: (owner.invoke(drawable) as? View)
                    if(view!=null) {
                        val state=states[drawable] ?: if(states.size<96)State().also { states[drawable]=it } else null
                        state?.params(view,drawable)
                        RuntimeDiagnostics.mark("app-glass-native-owner","${view.javaClass.simpleName} ${view.width}x${view.height}")
                    }
                }.onFailure { RuntimeDiagnostics.mark("app-glass-native-owner-error","${it.javaClass.simpleName}: ${it.message}") }
                chain.proceed()
            }
            runCatching {
                val helper=Class.forName("com.oplus.smartsidebar.utils.SidebarPlatformBlurHelper",false,loader)
                val host=helper.declaredFields.single { it.type==View::class.java }.apply { isAccessible=true }
                val paramsType=Class.forName("com.oplus.posteffect.BlurParam",false,loader)
                val build=helper.declaredMethods.single { it.name=="t" && it.returnType==paramsType && it.parameterTypes.size==2 }
                api.hook(build).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                    val view=host.get(chain.getThisObject()) as? View
                    var radius:Int?=null
                    if(view!=null && Looper.myLooper()==Looper.getMainLooper())runCatching {
                        AppGlassHook.nativeParams(view,view.width.toFloat(),view.height.toFloat())?.let { p ->
                            // A minimum capture radius keeps the OEM GPU texture channel alive;
                            // the shared shader never applies a second blur to that texture.
                            radius=p.blurRadius.toInt().coerceIn(1,128)
                        }
                    }
                    if(radius!=null) {
                        val args=chain.getArgs().toTypedArray();args[1]=radius
                        RuntimeDiagnostics.mark("app-glass-native-blur","原生 GPU 模糊跟随材质预设：${radius}px；不重复滤波")
                        chain.proceed(args)
                    } else chain.proceed()
                }
                // q() calls the tiny t() builder and can otherwise retain its inlined radius.
                helper.declaredMethods.filter { it.name=="q" }.forEach { runCatching { api.deoptimize(it) } }
            }.onFailure { RuntimeDiagnostics.mark("app-glass-native-blur-error","${it.javaClass.simpleName}: ${it.message}") }
            api.hook(record).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                val drawable=chain.getThisObject() as? Drawable ?: return@intercept chain.proceed()
                val canvas=chain.getArg(0) as? Canvas ?: return@intercept chain.proceed()
                RuntimeDiagnostics.mark("app-glass-native-draw","${drawable.javaClass.simpleName}; main=${Looper.myLooper()==Looper.getMainLooper()}")
                if(!canvas.isHardwareAccelerated || SystemClock.uptimeMillis()<retryAt)
                    return@intercept chain.proceed()
                var target:Paint?=null;var original:Shader?=null
                try {
                    if(drawable.bounds.width()>0 && drawable.bounds.height()>0) {
                        val state=states[drawable]
                        if(state!=null) {
                            val params=state.cached
                            val native=shader.invoke(drawable)
                            val source=input.get(native) as? BitmapShader
                            val transform=matrix.get(native) as? Matrix
                            val factor=scale.get(native) as? PointF
                            if(params!=null && source!=null && factor!=null) {
                                target=paint.invoke(drawable) as Paint;original=target.shader
                                target.shader=state.bind(params,source,transform,factor)
                                RuntimeDiagnostics.mark("app-glass-native-material","原生 GPU 背景纹理已接入统一预设；保留系统裁剪与前景")
                            }
                        }
                    }
                } catch(error:Throwable) {
                    target?.shader=original;target=null
                    retryAt=SystemClock.uptimeMillis()+30_000L
                    RuntimeDiagnostics.mark("app-glass-native-error","${error.javaClass.simpleName}: ${error.message}")
                }
                if(target!=null) {
                    try { draw.invoke(drawable,canvas) }
                    finally { target.shader=original;(scratch.invoke(drawable) as Paint).reset() }
                    null
                } else chain.proceed()
            }
            // positionChanged() runs on RenderThread and records k()->S() there. View and
            // preference state is cached on UI; native recording consumes only that snapshot.
            for(type in listOf(base,blend,Class.forName("${base.name}\$a",false,loader)))
                for(method in type.declaredMethods.filter { it.name in setOf("k","S","positionChanged","positionLost") })runCatching { api.deoptimize(method) }
            RuntimeDiagnostics.mark("app-glass-native-hook","已识别原生 GPU 纹理与录制路径，等待实际绘制")
        } catch(error:Throwable) {
            RuntimeDiagnostics.mark("app-glass-native-hook","当前应用不含此纹理接口，使用其他背景路径：${error.javaClass.simpleName}")
        }
    }
    private class State {
        val optics=LiquidGlassShader.create()
        val mapping=RuntimeShader("""
            uniform shader uNative;
            uniform float3x3 uNativeMatrix;
            uniform float2 uNativeScale;
            uniform float2 uNativeTrans;
            half4 main(float2 p) {
                float2 s=max(float2(0.0000001),uNativeScale);
                float2 q=(uNativeMatrix*float3((p-uNativeTrans)/s/s,0.0)).xy;
                return uNative.eval(q);
            }
        """)
        val values=FloatArray(9)
        val identity=Matrix()
        var checkedAt=-1000L;var width=0;var height=0
        @Volatile var cached:LiquidGlassShader.Params?=null
        fun params(view:View,d:Drawable):LiquidGlassShader.Params? {
            val now=SystemClock.uptimeMillis();val b=d.bounds
            if(now-checkedAt>=250 || width!=b.width() || height!=b.height()) {
                checkedAt=now;width=b.width();height=b.height()
                cached=AppGlassHook.nativeParams(view,width.toFloat(),height.toFloat())
            }
            return cached
        }
        fun bind(params:LiquidGlassShader.Params,source:BitmapShader,transform:Matrix?,scale:PointF):RuntimeShader {
            (transform ?: identity).getValues(values)
            mapping.setInputShader("uNative",source)
            mapping.setFloatUniform("uNativeMatrix",values)
            mapping.setFloatUniform("uNativeScale",scale.x,scale.y)
            mapping.setFloatUniform("uNativeTrans",values[2],values[5])
            LiquidGlassShader.setUniforms(optics,params.copy(sourcePreblurred=true),mapping,mapping,
                sourceRect=RectF(0f,0f,params.viewportWidth,params.viewportHeight))
            return optics
        }
    }
}
