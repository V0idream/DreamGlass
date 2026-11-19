/* DreamGlass contributors, 2026. GPL-3.0-or-later. */
package com.coloros16.liquidglass.hook

import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.view.SurfaceControl
import com.coloros16.liquidglass.config.Prefs
import com.coloros16.liquidglass.diagnostics.RuntimeDiagnostics
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.ExceptionMode

/** Only decorates the native matrix setter inside a verified per-target callback.
 * No SurfaceControl.Transaction hooks, 4x4 bridge, synthetic transaction, timers,
 * duration changes or retained surfaces. Every native method proceeds exactly once.
 */
internal object LauncherTiltAffineHook {
    private const val HELPER="com.android.quickstep.util.animation.RectTransformHelper"
    private const val PARAMS="$HELPER\$TransformParams"
    private data class StandardSite(val target: String, val helper: String, val params: String, val callback: List<String>)
    private val standardSites = mapOf(
        "xi.q" to StandardSite("gi.c", HELPER, PARAMS, listOf("ma.r0", "ma.p0")),
        "w99" to StandardSite("w99", "i69", "h69", listOf("f69")),
        "da9" to StandardSite("da9", "l69", "k69", listOf("h69")),
    )
    private data class Config(val enabled:Boolean,val strength:Float,val depth:Float)
    @Volatile private var snapshot=Config(false,1f,1f)
    private var readAt=-1L
    @Volatile private var ready=false

    @Synchronized private fun config(api:XposedInterface):Config {
        if(!Prefs.memberAllowed(api))return Config(false,1f,1f)
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
        val lightSubmit = runCatching { LauncherTiltLightAnchorHook.resolveSubmit(loader) }.getOrNull()
        val tx = lightSubmit?.parameterTypes?.get(1) ?: Class.forName("xi.q",false,loader)
        val prepare = tx.getDeclaredMethod(if (tx.name == "xi.q") "e" else "b", SurfaceControl::class.java)
        val props = prepare.returnType
        val affine=props.getDeclaredMethod("i",Matrix::class.java)
        val owner=props.getDeclaredField("b").apply { isAccessible=true }
        val surface=props.getDeclaredField("a").apply { isAccessible=true }
        check(affine.returnType==props && owner.type==tx && surface.type==SurfaceControl::class.java)
        val scopes=LauncherTiltAffineScope()
        api.hook(affine).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
            // Catch only preparation failures. Do not retry chain.proceed if the
            // host throws; replaying native methods can double-advance animation.
            val replacement=runCatching {
                if(!ready || !Prefs.memberAllowed(api))return@runCatching null
                val self=chain.getThisObject() ?: return@runCatching null
                val pose=scopes.pose(owner.get(self),surface.get(self)) ?: return@runCatching null
                val input=chain.getArg(0) as? Matrix ?: return@runCatching null
                val values=FloatArray(9);val out=FloatArray(9);input.getValues(values)
                if(!LauncherTiltAffine.composeTowardsIcon(values,out,pose.progress,pose.strength,pose.depth,
                        pose.cx,pose.cy,pose.iconAnchor))return@runCatching null
                Matrix().apply {setValues(out)}
            }.getOrElse {
                RuntimeDiagnostics.mark("launcher-tilt-error","兼容倾斜准备失败，当前帧保持原生：${it.javaClass.simpleName}");null
            }
            if(replacement==null)chain.proceed() else {
                RuntimeDiagnostics.mark("launcher-tilt-affine-write","已按本动画的真实图标端点叠加倾转；原缩放／中心路径保留，方向效果待设备验证")
                val args=chain.getArgs().toTypedArray();args[0]=replacement;chain.proceed(args)
            }
        }
        val standardReady=runCatching {
            val standardKey = if (tx.name == "xi.q") tx.name else checkNotNull(lightSubmit).parameterTypes[0].name
            val site = standardSites.getValue(standardKey)
            val helper=Class.forName(site.helper,false,loader)
            val params=Class.forName(site.params,false,loader)
            val target=Class.forName(site.target,false,loader)
            val relocated = site.helper != HELPER
            val update=helper.declaredMethods.single {
                val p = it.parameterTypes
                it.name=="a" && it.returnType==Void.TYPE && p.size==9 && p[0]==params &&
                    p[1].isArray && p[1].componentType==target && p[2]==Integer.TYPE && p[3]==tx &&
                    p[5]==RectF::class.java && p[6]==java.lang.Boolean.TYPE &&
                    p[7]==RectF::class.java && p[8]==target
            }
            val leash=target.getDeclaredField("d").apply { isAccessible=true }
            val progress=params.getDeclaredField("l").apply { isAccessible=true }
            val task=params.getDeclaredField("D").apply { isAccessible=true }
            val source=helper.getDeclaredField("a").apply { isAccessible=true }
            val crop=helper.getDeclaredField("c").apply { isAccessible=true }
            check(leash.type==SurfaceControl::class.java &&
                progress.type==Float::class.javaPrimitiveType && task.type==Boolean::class.javaPrimitiveType &&
                source.type==Rect::class.java && crop.type==Rect::class.java)
            val captureFields = if (relocated) listOf("c", "q", "r", "s") else listOf("a", "b", "c", "d")
            val callback=LauncherTiltContract.callback(site.callback.mapNotNull {
                runCatching { Class.forName(it,false,loader) }.getOrNull()
            },target,helper,tx,params,captureFields) ?: error("No verified standard target callback")
            val (capturedTarget,capturedHelper,capturedTx,capturedParams) = captureFields.map {
                callback.getDeclaredField(it).apply { isAccessible = true }
            }
            val anchors=LauncherTiltAnchorScope()
            val anchorRoutes=LauncherTiltAnchorHook.install(api,loader,helper,params,tx,anchors)
            check(anchorRoutes > 0) { "No verified standard icon anchor" }
            val run=callback.getDeclaredMethod("run")
            api.hook(run).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                val frame=runCatching {
                    val cfg=config(api);val self=chain.getThisObject()!!
                    val p=capturedParams.get(self);val t=capturedTarget.get(self)
                    val s=leash.get(t) as? SurfaceControl
                    if(!ready || !cfg.enabled || task.getBoolean(p) || s==null || !s.isValid)null
                    else Triple(capturedTx.get(self),s,cfg)
                }.getOrNull()
                if(frame==null)chain.proceed() else scopes.run(frame.first,frame.second,pose@{
                    // The helper refreshes its crop inside run(). Read it at each
                    // matrix write, not before native code has populated it.
                    val self=chain.getThisObject()!!;val h=capturedHelper.get(self)
                    val anchor=anchors.anchor(h) ?: run {
                        RuntimeDiagnostics.mark("launcher-tilt-anchor-missing","当前标准帧没有匹配的图标端点，保持原生，不使用固定方向")
                        return@pose null
                    }
                    val clip=crop.get(h) as Rect
                    val r=if(clip.isEmpty)source.get(h) as Rect else clip
                    if(r.isEmpty)null else LauncherTiltAffineScope.Pose(
                        progress.getFloat(capturedParams.get(self)),frame.third.strength,frame.third.depth,
                        r.exactCenterX(),r.exactCenterY(),anchor.fractionX,anchor)
                },{chain.proceed()})
            }
            api.deoptimize(run)
            api.deoptimize(target.getDeclaredMethod(if (relocated) "e" else "g",Runnable::class.java))
            api.deoptimize(update)
            RuntimeDiagnostics.mark("launcher-tilt-standard","已匹配标准窗口 ${callback.name}，仅修改该目标的原生仿射参数")
            true
        }.getOrElse {
            RuntimeDiagnostics.mark("launcher-tilt-standard-missing",it.javaClass.simpleName)
            false
        }
        val lightReady = lightSubmit != null &&
            LauncherTiltLightAnchorHook.install(api,loader,lightSubmit,scopes) {
                val cfg=config(api)
                if(cfg.enabled)cfg.strength to cfg.depth else null
            } > 0
        ready=standardReady || lightReady
        RuntimeDiagnostics.mark("launcher-tilt",if(ready)"已接入图标端点驱动的兼容倾斜（平行投影，非完整透视）；方向效果待设备验证"
            else "未识别窗口动画入口，保留系统动画")
        ready
    }.getOrElse {
        ready=false
        RuntimeDiagnostics.mark("launcher-tilt","当前桌面不匹配兼容倾斜：${it.javaClass.simpleName}；保留原生动画")
        false
    }
}
