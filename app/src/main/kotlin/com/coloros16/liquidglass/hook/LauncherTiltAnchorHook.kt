/* DreamGlass contributors, 2026. GPL-3.0-or-later. */
package com.coloros16.liquidglass.hook

import android.graphics.RectF
import com.coloros16.liquidglass.diagnostics.RuntimeDiagnostics
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.ExceptionMode
import java.lang.reflect.Field

/** Read the source/target icon of the EXECUTING native animation. Legacy COS16
 * callback numbering changed from 7/8 to 9/10 on both supplied COS17 APKs.
 * Endpoint capture never changes animation arguments, surfaces or timestamps.
 */
internal object LauncherTiltAnchorHook {
    private data class Site(val name:String,val icon:String,val helper:String="\$rectTransformHelper",
        val screen:String="\$screenRectF")
    private data class Reader(val helper:Field,val icon:Field,val screen:Field) {
        fun read(owner:Any):LauncherTiltIconAnchor? {
            val i=icon.get(owner) as? RectF ?: return null
            val s=screen.get(owner) as? RectF ?: return null
            return LauncherTiltIconAnchor.fromRects(i.left,i.top,i.right,i.bottom,s.left,s.top,s.right,s.bottom)
        }
    }
    private val sites=listOf(
        // These are stable icon endpoints in window/screen coordinates. The
        // separately captured iconRect is a mutable drawing scratch rectangle.
        Site("com.android.launcher3.anim.OplusLauncherAppTransitionHelper\$startAppLaunchWindowAnim\$9","\$startRect"),
        Site("com.android.launcher3.anim.OplusLauncherAppTransitionHelper\$startAppCloseWindowAnim\$10","\$targetRect"),
        Site("com.android.launcher3.anim.engine.creator.SwipeToHomeAnimCreator\$Companion\$buildSpringAnimation\$6","\$targetRect"),
        // Retain the earlier contracts when a ROM genuinely provides them.
        Site("com.android.launcher3.anim.OplusLauncherAppTransitionHelper\$startAppLaunchWindowAnim\$7","\$startRect"),
        Site("com.android.launcher3.anim.OplusLauncherAppTransitionHelper\$startAppCloseWindowAnim\$8","\$targetRect"),
        Site("com.oplus.quickstep.gesture.OplusBaseSwipeUpHandler\$createAppToHomeAnimation\$5","\$targetRect"),
    )

    fun install(api:XposedInterface,loader:ClassLoader,helperType:Class<*>,scope:LauncherTiltAnchorScope):Int {
        var mounted=0
        val readers=HashMap<Class<*>,Reader>()
        for(site in sites)runCatching {
            val type=Class.forName(site.name,false,loader)
            val helper=type.getDeclaredField(site.helper).apply {isAccessible=true}
            val icon=type.getDeclaredField(site.icon).apply {isAccessible=true}
            val screen=type.getDeclaredField(site.screen).apply {isAccessible=true}
            check(helper.type==helperType && icon.type==RectF::class.java && screen.type==RectF::class.java)
            val method=type.getDeclaredMethod("onUpdate",RectF::class.java,
                Float::class.javaPrimitiveType,Float::class.javaPrimitiveType,Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,Float::class.javaPrimitiveType)
            check(method.returnType==Void.TYPE)
            val reader=Reader(helper,icon,screen)
            api.hook(method).setExceptionMode(ExceptionMode.PROTECTIVE).intercept {chain ->
                val owner=chain.getThisObject()!!
                val h=runCatching {reader.helper.get(owner)}.getOrNull()
                if(h==null)chain.proceed() else scope.run(h,owner,{reader.read(owner)}) {chain.proceed()}
            }
            api.deoptimize(method)
            readers[type]=reader;mounted++
        }

        // Launch/close/swipe can submit a native frame AFTER onUpdate returns.
        // Their Function2 carriers do not all retain the owner. Bind the active
        // verified owner to the exact callback when native k.b schedules it.
        // No arguments or native delay/cancellation behavior are changed.
        val registry=LauncherTiltAnchorRegistry()
        val carrierHelpers=HashMap<Class<*>,Field>()
        val function=Class.forName("kotlin.jvm.functions.Function2",false,loader)
        for(name in listOf("o6.v","o6.x","o6.z","o6.b0","o6.d0","q6.m1","q6.n1"))runCatching {
            val carrier=Class.forName(name,false,loader)
            check(function.isAssignableFrom(carrier))
            val helper=carrier.declaredFields.single {it.type==helperType}.apply {isAccessible=true}
            check(carrier.declaredFields.count {it.type.name=="xi.q"}==1)
            val invoke=carrier.getDeclaredMethod("invoke",Any::class.java,Any::class.java)
            check(invoke.returnType==Any::class.java)
            api.hook(invoke).setExceptionMode(ExceptionMode.PROTECTIVE).intercept {chain ->
                val native=chain.getThisObject()!!
                val binding=runCatching {registry.resolve(native,helper.get(native)!!)}.getOrNull()
                if(binding==null)chain.proceed() else scope.run(binding.helper,binding.animation,{binding.anchor}) {chain.proceed()}
            }
            api.deoptimize(invoke);carrierHelpers[carrier]=helper;mounted++
        }
        if(readers.isNotEmpty())runCatching {
            val delayer=Class.forName("com.android.quickstep.util.animation.k",false,loader)
            val methods=delayer.declaredMethods.filter {method ->
                val p=method.parameterTypes
                method.name=="b" && method.returnType==Void.TYPE && p.size==6 &&
                    p[1]==Float::class.javaPrimitiveType && p[2].name=="${helperType.name}\$TransformParams" &&
                    p[3]==RectF::class.java && p[4]==Runnable::class.java && p[5]==function
            }
            for(method in methods) {
                api.hook(method).setExceptionMode(ExceptionMode.PROTECTIVE).intercept {chain ->
                    runCatching {
                        val callback=chain.getArg(5) ?: return@runCatching
                        val helperField=carrierHelpers[callback.javaClass] ?: return@runCatching
                        val captured=scope.capture()
                        registry.bind(callback,captured?.takeIf {helperField.get(callback)===it.helper})
                    }
                    chain.proceed()
                }
                api.deoptimize(method)
            }
        }
        RuntimeDiagnostics.mark("launcher-tilt-icon-contract",
            "已核对 ${readers.size} 个真实图标端点入口、${mounted-readers.size} 个延迟帧入口；无端点时保持原生")
        return mounted
    }
}
