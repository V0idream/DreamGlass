/* DreamGlass contributors, 2026. GPL-3.0-or-later. */
package com.coloros16.liquidglass.hook

import android.graphics.Matrix
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import android.view.SurfaceControl
import android.view.View
import com.coloros16.liquidglass.diagnostics.RuntimeDiagnostics
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.ExceptionMode
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Light windows have no RectTransformHelper. Their per-request creator still
 * supplies the genuine source/return icon, before constructing an update listener.
 * Bind that one listener to that one targets object; never use a last-click cache.
 */
internal object LauncherTiltLightAnchorHook {
    private const val LISTENER = "com.android.launcher3.anim.light.AppLaunchAnimUtil\$getLightWindowAnimation\$1"

    internal fun resolveSubmit(loader: ClassLoader): Method {
        val listener = Class.forName(LISTENER, false, loader)
        val bool = java.lang.Boolean.TYPE
        val float = java.lang.Float.TYPE
        return listener.declaredMethods.single { method ->
            val p = method.parameterTypes
            method.name == "b" && Modifier.isStatic(method.modifiers) && method.returnType == Void.TYPE &&
                p.size == 16 && p[2] == bool && p[3] == bool && p[4] == float && p[5] == float &&
                p[6] == bool && p[7] == bool && p[8] == listener && p[9] == Matrix::class.java &&
                p[10] == float && p[11].name == "com.android.launcher3.Launcher" && p[12] == float &&
                p[13] == Point::class.java && p[14].name == "kotlin.jvm.internal.Ref\$BooleanRef" && p[15] == Rect::class.java
        }
    }

    fun install(api:XposedInterface,loader:ClassLoader,submit:Method,scopes:LauncherTiltAffineScope,
        configuration:()->Pair<Float,Float>?):Int = runCatching {
        val creator=Class.forName("com.android.launcher3.anim.engine.creator.LightAnimatorCreator",false,loader)
        val targetType=submit.parameterTypes[0]
        val txType=submit.parameterTypes[1]
        val listener=submit.declaringClass
        val targets=listener.getDeclaredField("\$targets").apply {isAccessible=true}
        val targetsType=targets.type
        val create=creator.declaredMethods.single {
            it.name=="create" && !it.isBridge && it.parameterCount==2 && it.parameterTypes[1]==View::class.java &&
                runCatching { it.parameterTypes[0].getDeclaredMethod("getTargets").returnType==targetsType }.getOrDefault(false)
        }
        val requestType=create.parameterTypes[0]
        val getTargets=requestType.getDeclaredMethod("getTargets")
        val getIcon=requestType.getDeclaredMethod("getIconView").apply {check(returnType==View::class.java)}
        val centerX=listener.getDeclaredField("\$centerX").apply {isAccessible=true}
        val centerY=listener.getDeclaredField("\$centerY").apply {isAccessible=true}
        val leash=targetType.getDeclaredField("d").apply {isAccessible=true}
        val bounds=targetType.getDeclaredField("k").apply {isAccessible=true}
        val splitBounds=targetType.getDeclaredField("l").apply {isAccessible=true}
        val mode=targetType.getDeclaredField("c").apply {isAccessible=true}
        val windowType=targetType.getDeclaredField("s").apply {isAccessible=true}
        check(centerX.type==Float::class.javaPrimitiveType &&
            centerY.type==Float::class.javaPrimitiveType && leash.type==SurfaceControl::class.java &&
            bounds.type==Rect::class.java && splitBounds.type==Rect::class.java &&
            mode.type==Int::class.javaPrimitiveType && windowType.type==Int::class.javaPrimitiveType)
        val constructors=listener.declaredConstructors.filter {
            it.parameterCount==17 && it.parameterTypes[3]==targetsType &&
                it.parameterTypes[7]==Float::class.javaPrimitiveType && it.parameterTypes[8]==Float::class.javaPrimitiveType
        }
        check(constructors.size==1)
        val schedule = targetType.getDeclaredMethod(if (txType.name == "xi.q") "g" else "e", Runnable::class.java)
            .apply { check(returnType == Void.TYPE) }
        val requests=LauncherTiltLightAnchorScope()
        val bindings=LauncherTiltAnchorRegistry()
        api.hook(create).setExceptionMode(ExceptionMode.PROTECTIVE).intercept {chain ->
            val frame=runCatching {
                val request=chain.getArg(0) ?: return@runCatching null
                val nativeTargets=getTargets.invoke(request) ?: return@runCatching null
                // The second argument is the engine-resolved icon used by the
                // same AnimationRecord. It is a per-call fallback, never global.
                val view=(getIcon.invoke(request) as? View) ?: (chain.getArg(1) as? View) ?: return@runCatching null
                if(!view.isAttachedToWindow || view.width<=0 || view.height<=0)return@runCatching null
                val iconRect=RectF(0f,0f,view.width.toFloat(),view.height.toFloat())
                val matrix=Matrix();view.transformMatrixToGlobal(matrix);matrix.mapRect(iconRect)
                Triple(request,nativeTargets,LauncherTiltIconBounds(iconRect.left,iconRect.top,iconRect.right,iconRect.bottom))
            }.getOrNull()
            if(frame==null)chain.proceed() else requests.run(frame.first,frame.second,frame.third) {chain.proceed()}
        }
        api.hook(constructors.single()).setExceptionMode(ExceptionMode.PROTECTIVE).intercept {chain ->
            val result=chain.proceed()
            runCatching {
                val self=chain.getThisObject() ?: return@runCatching
                val nativeTargets=targets.get(self) ?: return@runCatching
                val anchor=requests.anchor(nativeTargets,centerX.getFloat(self)*2f,centerY.getFloat(self)*2f)
                bindings.bind(self,anchor?.let {LauncherTiltCapturedAnchor(nativeTargets,self,it)})
            }
            result
        }
        api.hook(submit).setExceptionMode(ExceptionMode.PROTECTIVE).intercept {chain ->
            val frame=runCatching {
                val config=configuration() ?: return@runCatching null
                val self=chain.getArg(8) ?: return@runCatching null
                val nativeTargets=targets.get(self) ?: return@runCatching null
                val binding=bindings.resolve(self,nativeTargets) ?: return@runCatching null
                val target=chain.getArg(0) ?: return@runCatching null
                val isOpen=chain.getArg(2) as Boolean;val m=mode.getInt(target)
                // Match native app-target selection. Never tilt wallpaper,
                // closing sibling surfaces, or the alpha-only privacy route.
                if((chain.getArg(6) as Boolean) || !((m==2 && windowType.getInt(target)==1) ||
                    (m==0 && isOpen) || (m==1 && !isOpen)))return@runCatching null
                val surface=leash.get(target) as? SurfaceControl ?: return@runCatching null
                if(!surface.isValid)return@runCatching null
                val nativeBounds=bounds.get(target) as? Rect ?: return@runCatching null
                if(nativeBounds.isEmpty)return@runCatching null
                val split=chain.getArg(3) as Boolean
                val offset=splitBounds.get(target) as? Rect
                val cx=if(split)(chain.getArg(4) as Float)-(offset?.left ?: 0) else nativeBounds.exactCenterX()
                val cy=if(split)(chain.getArg(5) as Float)-(offset?.top ?: 0) else nativeBounds.exactCenterY()
                val pose=LauncherTiltAffineScope.Pose(chain.getArg(12) as Float,config.first,config.second,
                    cx,cy,binding.anchor.fractionX,binding.anchor)
                Triple(chain.getArg(1)!!,surface,pose)
            }.getOrNull()
            if(frame==null)chain.proceed() else scopes.run(frame.first,frame.second,{frame.third}) {chain.proceed()}
        }
        api.deoptimize(create);api.deoptimize(submit)
        val util=Class.forName("com.android.launcher3.anim.light.AppLaunchAnimUtil",false,loader)
        util.declaredMethods.filter {it.name=="getLightWindowAnimation" && it.parameterCount==6 && it.parameterTypes[0]==targetsType}
            .forEach {api.deoptimize(it)}
        listener.declaredMethods.filter {it.name=="onUpdate" && it.parameterCount==1 && it.parameterTypes[0]==Float::class.javaPrimitiveType}
            .forEach {api.deoptimize(it)}
        api.deoptimize(schedule)
        runCatching {
            val runnable=Class.forName("a7.a",false,loader)
            check(runnable.getDeclaredField("a").type==targetType && runnable.getDeclaredField("b").type==txType &&
                runnable.getDeclaredField("r").type==listener)
            api.deoptimize(runnable.getDeclaredMethod("run"))
        }
        RuntimeDiagnostics.mark("launcher-tilt-light","轻动画已接入本次请求的真实图标端点，按 listener／targets 身份匹配；方向效果待设备验证")
        1
    }.getOrElse {
        RuntimeDiagnostics.mark("launcher-tilt-light","未识别轻动画图标关联：${it.javaClass.simpleName}；该路径保持原生")
        0
    }
}
