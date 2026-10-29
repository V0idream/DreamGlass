/* DreamGlass contributors, 2026. GPL-3.0-or-later. */
package com.coloros16.liquidglass.hook

import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.SurfaceControl
import com.coloros16.liquidglass.config.Prefs
import com.coloros16.liquidglass.diagnostics.RuntimeDiagnostics
import com.coloros16.liquidglass.log.Lg
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.ExceptionMode
import java.lang.ref.WeakReference
import kotlin.math.*

/** Stock 17.3.12's SurfaceTransaction is xi.q, its SurfaceProperties is xi.q$b.
 * RectTransformHelper.a writes the affine matrix with i(Matrix), then the extra 4x4
 * channel j(float[]) supplies perspective. Do not replace the native scale/position,
 * hook all Transactions, or require Zuyqa classes from a modified launcher APK.
 * Unknown contracts remain native. Reflection is resolved once before installing.
 */
object LauncherTiltCompat {
    private const val HELPER = "com.android.quickstep.util.animation.RectTransformHelper"
    private const val PARAMS = "$HELPER\$TransformParams"
    private data class Frame(val targets: Set<SurfaceControl>, val rect: RectF, val progress: Float,
        val direction: Float, val helper: WeakReference<Any>? = null, val capturedAt: Long = SystemClock.uptimeMillis())
    private data class Owned(val surface: WeakReference<SurfaceControl>, var touched: Long)
    private val ui by lazy { Handler(Looper.getMainLooper()) }
    private val frames = java.util.WeakHashMap<Any, Frame>()
    private val owned = LinkedHashMap<Int, Owned>()
    private var watching = false
    private var readAt = -1L
    private var enabled = false
    private var strength = 1f
    private var perspective = 1f
    private var retryAt = 0L
    private val matrix = FloatArray(16)

    /** Row-major, same convention as the upstream extra 4x4 channel. */
    fun projection(out: FloatArray, progress: Float, strength: Float, perspective: Float,
        cx: Float, cy: Float, height: Float, direction: Float): Boolean {
        require(out.size == 16)
        java.util.Arrays.fill(out, 0f);out[0]=1f;out[5]=1f;out[10]=1f;out[15]=1f
        if (!progress.isFinite() || !strength.isFinite() || !perspective.isFinite() ||
            !cx.isFinite() || !cy.isFinite() || !height.isFinite() || height <= 0f || !direction.isFinite()) return false
        val p=progress.coerceIn(0f,1f)
        val angle = (sin(PI*p)*Math.toRadians(18.0)*strength.coerceIn(0f,2f)*direction.coerceIn(-1f,1f)).toFloat()
        if (abs(angle)<0.00001f) return false
        val sn=sin(angle);val cs=cos(angle);val d=perspective.coerceIn(0f,1.5f)*.65f/height
        val a=cx*d*sn;val b=cy*d*sn
        out[1]=a;out[3]=-a*cy;out[2]=cx*d*cs
        out[5]=cs+b;out[15]=1f-b;out[7]=(1f-cs)*cy-b*cy
        out[6]=cy*d*cs-sn;out[9]=sn;out[10]=cs;out[11]=-cy*sn
        out[13]=d*sn;out[14]=d*cs
        return true
    }

    fun install(api: XposedInterface, loader: ClassLoader): Boolean = try {
        val helper=Class.forName(HELPER,false,loader)
        val params=Class.forName(PARAMS,false,loader)
        val tx=Class.forName("xi.q",false,loader)
        val props=Class.forName("xi.q\$b",false,loader)
        val target=Class.forName("gi.c",false,loader)
        val affine=props.getDeclaredMethod("i",Matrix::class.java)
        val extra=props.getDeclaredMethod("j",FloatArray::class.java)
        val owner=props.getDeclaredField("b").apply { isAccessible=true }
        val surface=props.getDeclaredField("a").apply { isAccessible=true }
        val leash=target.getDeclaredField("d").apply { isAccessible=true }
        val bounds=target.getDeclaredField("k").apply { isAccessible=true }
        val progress=params.getDeclaredField("l").apply { isAccessible=true }
        val sourceBounds=helper.getDeclaredField("a").apply { isAccessible=true }
        val clipBounds=helper.getDeclaredField("c").apply { isAccessible=true }
        val mode=target.getDeclaredField("c").apply { isAccessible=true }
        val windowMode=target.getDeclaredField("s").apply { isAccessible=true }
        val task=params.getDeclaredField("D").apply { isAccessible=true }
        val forSurface=tx.getDeclaredMethod("e",SurfaceControl::class.java)
        val transaction=tx.getDeclaredMethod("g")
        val ctor=tx.getDeclaredConstructor()
        val apply=helper.declaredMethods.single { it.name=="a" && it.parameterCount==9 &&
            it.parameterTypes[0]==params && it.parameterTypes[3]==tx && it.parameterTypes[5]==RectF::class.java }
        check(affine.returnType==props && extra.returnType==Void.TYPE && owner.type==tx &&
            surface.type==SurfaceControl::class.java && forSurface.returnType==props &&
            transaction.returnType==SurfaceControl.Transaction::class.java)
        fun config() {
            val now=SystemClock.uptimeMillis()
            if (readAt>=0 && now-readAt<250) return
            readAt=now
            val p=Prefs.read(api)
            enabled=p.getBoolean(Prefs.KEY_MASTER,false) && p.getBoolean(Prefs.KEY_ANIM_TILT_ENABLED,false)
            strength=Prefs.readIntCompat(p,Prefs.KEY_ANIM_TILT_STRENGTH,100).coerceIn(0,200)/100f
            perspective=Prefs.readIntCompat(p,Prefs.KEY_ANIM_TILT_PERSPECTIVE,100).coerceIn(0,150)/100f
        }
        fun reset(surface: SurfaceControl) {
            if (!surface.isValid) return
            val wrapper=ctor.newInstance()
            val native=transaction.invoke(wrapper) as SurfaceControl.Transaction
            try {
                projection(matrix,0f,0f,0f,0f,0f,1f,1f)
                extra.invoke(forSurface.invoke(wrapper,surface),matrix)
                native.apply()
            } finally { native.close() }
        }
        val watchdog=object:Runnable {
            override fun run() {
                try {
                    try { config() } catch (_:Throwable) { enabled=false }
                    val now=SystemClock.uptimeMillis()
                    val expired=owned.filterValues { !enabled || now-it.touched>=120L }
                    for ((id,item) in expired) {
                        owned.remove(id)
                        runCatching { item.surface.get()?.let(::reset) }
                    }
                } finally {
                    if (owned.isEmpty()) watching=false else ui.postDelayed(this,64L)
                }
            }
        }
        fun remember(surface: SurfaceControl) {
            if (owned.size>=16 && !owned.containsKey(System.identityHashCode(surface))) return
            owned[System.identityHashCode(surface)]=Owned(WeakReference(surface),SystemClock.uptimeMillis())
            if (!watching) { watching=true;ui.postDelayed(watchdog,128L) }
        }
        api.hook(apply).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
            if (Looper.myLooper()==Looper.getMainLooper()) runCatching {
                config()
                val txObject=chain.getArg(3)!!
                frames.remove(txObject)
                val p=chain.getArg(0)!!
                if (enabled && !task.getBoolean(p)) {
                    val targets=(chain.getArg(1) as Array<*>).mapNotNull {
                        it?.let { value -> leash.get(value) as? SurfaceControl }
                    }.toSet()
                    val rect=RectF(chain.getArg(5) as RectF)
                    val direction=if (rect.centerY()>=rect.height()/2f) 1f else -1f
                    val h=chain.getThisObject()!!
                    // Perspective is in leash-local source coordinates, not the moving/scaled
                    // on-screen rectangle. c is refreshed inside native l() before i(Matrix).
                    val source=RectF(sourceBounds.get(h) as Rect)
                    frames[txObject]=Frame(targets,source,progress.getFloat(p),direction,WeakReference(h))
                }
            }
            chain.proceed()
        }
        // Lightweight launch animations bypass RectTransformHelper on COS17.
        val light=runCatching { Class.forName("com.android.launcher3.anim.light.AppLaunchAnimUtil\$getLightWindowAnimation\$1",false,loader) }.getOrNull()
        var lightRoutes=0
        light?.declaredMethods?.filter { it.name=="b" && it.parameterCount==16 &&
            it.parameterTypes[0]==target && it.parameterTypes[1]==tx && it.parameterTypes[12]==Float::class.javaPrimitiveType
        }?.forEach { method ->
            api.hook(method).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                if (Looper.myLooper()==Looper.getMainLooper()) runCatching {
                    config();val t=chain.getArg(1)!!;frames.remove(t)
                    if (enabled) {
                        val source=chain.getArg(0)!!
                        val s=leash.get(source) as? SurfaceControl
                        val r=bounds.get(source) as? Rect
                        val opening=chain.getArg(2) as Boolean
                        val animationTarget=(mode.getInt(source)==2 && windowMode.getInt(source)==1) ||
                            (mode.getInt(source)==0 && opening) || (mode.getInt(source)==1 && !opening)
                        if (animationTarget && s!=null && r!=null)
                            frames[t]=Frame(setOf(s),RectF(r),chain.getArg(12) as Float,1f)
                    }
                }
                chain.proceed()
            }
            lightRoutes++
        }
        api.hook(affine).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
            val result=chain.proceed()
            if (Looper.myLooper()==Looper.getMainLooper()) runCatching {
                config()
                val properties=chain.getThisObject()!!
                val s=surface.get(properties) as SurfaceControl
                val frame=frames[owner.get(properties)]
                val id=System.identityHashCode(s)
                if (frame!=null && SystemClock.uptimeMillis()-frame.capturedAt<=40L &&
                    s in frame.targets && enabled && SystemClock.uptimeMillis()>=retryAt && s.isValid && (owned.size<16 || id in owned)) {
                    val clip=frame.helper?.get()?.let { clipBounds.get(it) as? Rect }
                    val rect=if (clip!=null && !clip.isEmpty) RectF(clip) else frame.rect
                    val active=projection(matrix,frame.progress,strength,perspective,
                        rect.centerX(),rect.centerY(),rect.height(),frame.direction)
                    extra.invoke(properties,matrix)
                    if (active) {
                        remember(s)
                        RuntimeDiagnostics.mark("launcher-tilt-event","COS17 窗口矩阵倾斜已触发")
                    } else owned.remove(id)
                } else if (owned.remove(id)!=null && s.isValid) {
                    projection(matrix,0f,0f,0f,0f,0f,1f,1f);extra.invoke(properties,matrix)
                }
            }.onFailure {
                val now=SystemClock.uptimeMillis()
                if (now>=retryAt) Lg.w("lg-ltilt","COS17 tilt frame skipped; retry in 30s",it)
                retryAt=now+30_000L
            }
            result
        }
        RuntimeDiagnostics.mark("launcher-tilt","COS17 标准矩阵及 $lightRoutes 个轻动画入口已安装；等待动画")
        true
    } catch (error:Throwable) {
        RuntimeDiagnostics.mark("launcher-tilt","未匹配 COS17 矩阵接口，保留系统动画")
        Lg.w("lg-ltilt","COS17 window tilt unavailable",error);false
    }
}
