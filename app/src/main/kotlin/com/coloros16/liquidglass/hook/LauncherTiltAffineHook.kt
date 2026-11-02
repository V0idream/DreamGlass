/* DreamGlass contributors, 2026. GPL-3.0-or-later. */
package com.coloros16.liquidglass.hook

import android.graphics.Matrix
import android.graphics.Rect
import android.os.SystemClock
import android.view.SurfaceControl
import com.coloros16.liquidglass.config.Prefs
import com.coloros16.liquidglass.diagnostics.RuntimeDiagnostics
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.ExceptionMode

/** Only decorates xi.q$b.i(Matrix) INSIDE a verified per-target native callback.
 * No SurfaceControl.Transaction hooks, 4x4 bridge, synthetic transaction, timers,
 * duration changes or retained surfaces. Every native method proceeds exactly once.
 */
internal object LauncherTiltAffineHook {
    private const val HELPER="com.android.quickstep.util.animation.RectTransformHelper"
    private const val PARAMS="$HELPER\$TransformParams"
    private data class Config(val enabled:Boolean,val strength:Float,val depth:Float)
    @Volatile private var snapshot=Config(false,1f,1f)
    private var readAt=-1L
    @Volatile private var ready=false

    @Synchronized private fun config(api:XposedInterface):Config {
        val now=SystemClock.uptimeMillis()
        if(readAt<0 || now-readAt>=500) {
            // A failed refresh never suppresses native work or retains the last
            // enabled value indefinitely. Reads only occur during native callbacks.
            snapshot=runCatching {
                val p=Prefs.read(api)
                Config(p.getBoolean(Prefs.KEY_MASTER,false) && p.getBoolean(Prefs.KEY_ANIM_TILT_ENABLED,Prefs.DEFAULT_ANIM_TILT_ENABLED),
                    Prefs.readIntCompat(p,Prefs.KEY_ANIM_TILT_STRENGTH,100).coerceIn(0,200)/100f,
                    Prefs.readIntCompat(p,Prefs.KEY_ANIM_TILT_PERSPECTIVE,100).coerceIn(0,150)/100f)
            }.getOrElse { Config(false,1f,1f) }
            readAt=now
        }
        return snapshot
    }

    fun install(api:XposedInterface,loader:ClassLoader):Boolean = runCatching {
        val helper=Class.forName(HELPER,false,loader)
        val params=Class.forName(PARAMS,false,loader)
        val tx=Class.forName("xi.q",false,loader)
        val props=Class.forName("xi.q\$b",false,loader)
        val target=Class.forName("gi.c",false,loader)
        val affine=props.getDeclaredMethod("i",Matrix::class.java)
        val owner=props.getDeclaredField("b").apply { isAccessible=true }
        val surface=props.getDeclaredField("a").apply { isAccessible=true }
        val leash=target.getDeclaredField("d").apply { isAccessible=true }
        val bounds=target.getDeclaredField("k").apply { isAccessible=true }
        val mode=target.getDeclaredField("c").apply { isAccessible=true }
        val windowMode=target.getDeclaredField("s").apply { isAccessible=true }
        val progress=params.getDeclaredField("l").apply { isAccessible=true }
        val task=params.getDeclaredField("D").apply { isAccessible=true }
        val source=helper.getDeclaredField("a").apply { isAccessible=true }
        val crop=helper.getDeclaredField("c").apply { isAccessible=true }
        check(affine.returnType==props && owner.type==tx && surface.type==SurfaceControl::class.java &&
            leash.type==SurfaceControl::class.java && bounds.type==Rect::class.java &&
            progress.type==Float::class.javaPrimitiveType && task.type==Boolean::class.javaPrimitiveType &&
            source.type==Rect::class.java && crop.type==Rect::class.java)
        val scopes=LauncherTiltAffineScope()
        api.hook(affine).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
            // Catch only preparation failures. Do not retry chain.proceed if the
            // host throws; replaying native methods can double-advance animation.
            val replacement=runCatching {
                if(!ready)return@runCatching null
                val self=chain.getThisObject() ?: return@runCatching null
                val pose=scopes.pose(owner.get(self),surface.get(self)) ?: return@runCatching null
                val input=chain.getArg(0) as? Matrix ?: return@runCatching null
                val values=FloatArray(9);val out=FloatArray(9);input.getValues(values)
                if(!LauncherTiltAffine.compose(values,out,pose.progress,pose.strength,pose.depth,
                        pose.cx,pose.cy,pose.direction))return@runCatching null
                Matrix().apply {setValues(out)}
            }.getOrElse {
                RuntimeDiagnostics.mark("launcher-tilt-error","兼容倾斜准备失败，当前帧保持原生：${it.javaClass.simpleName}");null
            }
            if(replacement==null)chain.proceed() else {
                RuntimeDiagnostics.mark("launcher-tilt-affine-write","原生矩阵已叠加平行投影倾斜；原缩放／中心路径保留，可见效果待设备验证")
                val args=chain.getArgs().toTypedArray();args[0]=replacement;chain.proceed(args)
            }
        }
        var routes=0
        runCatching {
            val callback=LauncherTiltContract.callback(listOf("ma.r0","ma.p0").mapNotNull {
                runCatching { Class.forName(it,false,loader) }.getOrNull()
            },target,helper,tx,params) ?: error("No verified standard target callback")
            val capturedTarget=callback.getDeclaredField("a").apply {isAccessible=true}
            val capturedHelper=callback.getDeclaredField("b").apply {isAccessible=true}
            val capturedTx=callback.getDeclaredField("c").apply {isAccessible=true}
            val capturedParams=callback.getDeclaredField("d").apply {isAccessible=true}
            val run=callback.getDeclaredMethod("run")
            api.hook(run).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                val frame=runCatching {
                    val cfg=config(api);val self=chain.getThisObject()!!
                    val p=capturedParams.get(self);val t=capturedTarget.get(self)
                    val s=leash.get(t) as? SurfaceControl
                    if(!ready || !cfg.enabled || task.getBoolean(p) || s==null || !s.isValid)null
                    else Triple(capturedTx.get(self),s,cfg)
                }.getOrNull()
                if(frame==null)chain.proceed() else scopes.run(frame.first,frame.second,{
                    // The helper refreshes its crop inside run(). Read it at each
                    // matrix write, not before native code has populated it.
                    val self=chain.getThisObject()!!;val h=capturedHelper.get(self)
                    val clip=crop.get(h) as Rect
                    val r=if(clip.isEmpty)source.get(h) as Rect else clip
                    if(r.isEmpty)null else LauncherTiltAffineScope.Pose(
                        progress.getFloat(capturedParams.get(self)),frame.third.strength,frame.third.depth,
                        r.exactCenterX(),r.exactCenterY(),1f)
                },{chain.proceed()})
            }
            api.deoptimize(run)
            api.deoptimize(target.getDeclaredMethod("g",Runnable::class.java))
            helper.declaredMethods.filter {it.name=="a" && it.parameterCount==9 && it.parameterTypes[0]==params}
                .forEach {api.deoptimize(it)}
            routes++
            RuntimeDiagnostics.mark("launcher-tilt-standard","已匹配标准窗口 ${callback.name}，仅修改该目标的原生仿射参数")
        }.onFailure {RuntimeDiagnostics.mark("launcher-tilt-standard-missing",it.javaClass.simpleName)}
        runCatching {
            val light=Class.forName("com.android.launcher3.anim.light.AppLaunchAnimUtil\$getLightWindowAnimation\$1",false,loader)
            val method=light.declaredMethods.single {it.name=="b" && it.parameterCount==16 &&
                it.parameterTypes[0]==target && it.parameterTypes[1]==tx && it.parameterTypes[2]==Boolean::class.javaPrimitiveType &&
                it.parameterTypes[12]==Float::class.javaPrimitiveType && it.returnType==Void.TYPE}
            api.hook(method).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                val frame=runCatching {
                    val cfg=config(api);val t=chain.getArg(0)!!;val s=leash.get(t) as? SurfaceControl
                    val opening=chain.getArg(2) as Boolean
                    val eligible=(mode.getInt(t)==2 && windowMode.getInt(t)==1) ||
                        (mode.getInt(t)==0 && opening) || (mode.getInt(t)==1 && !opening)
                    val r=bounds.get(t) as? Rect
                    if(!ready || !cfg.enabled || !eligible || chain.getArg(6)==true || s==null || !s.isValid || r==null || r.isEmpty)null
                    else Triple(s,r,cfg)
                }.getOrNull()
                if(frame==null)chain.proceed() else scopes.run(chain.getArg(1)!!,frame.first,{
                    LauncherTiltAffineScope.Pose(chain.getArg(12) as Float,frame.third.strength,frame.third.depth,
                        frame.second.width()/2f,frame.second.height()/2f,if(chain.getArg(2)==true)1f else -1f)
                },{chain.proceed()})
            }
            api.deoptimize(method)
            light.declaredMethods.filter {it.name=="onUpdate" && it.parameterTypes.contentEquals(arrayOf(Float::class.javaPrimitiveType))}
                .forEach {api.deoptimize(it)}
            // R8 can inline b() into this per-target Runnable on either ROM sample.
            runCatching {api.deoptimize(Class.forName("a7.a",false,loader).getDeclaredMethod("run"))}
            routes++
            RuntimeDiagnostics.mark("launcher-tilt-light","已匹配轻动画窗口，原生时长与透明度不变")
        }.onFailure {RuntimeDiagnostics.mark("launcher-tilt-light-missing",it.javaClass.simpleName)}
        ready=routes>0
        RuntimeDiagnostics.mark("launcher-tilt",if(ready)"已接入 $routes 条兼容倾斜路径（平行投影，非完整透视）；未验证设备可见效果"
            else "未识别窗口动画入口，保留系统动画")
        ready
    }.getOrElse {
        ready=false
        RuntimeDiagnostics.mark("launcher-tilt","当前桌面不匹配兼容倾斜：${it.javaClass.simpleName}；保留原生动画")
        false
    }
}
