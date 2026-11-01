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
 * Each target callback writes the affine matrix with i(Matrix), then the native 4x4
 * bridge supplies perspective. Do not replace the native scale/position,
 * alter unrelated Transactions, or require Zuyqa classes from a modified launcher APK.
 * Unknown contracts remain native. Reflection is resolved once before installing.
 */
object LauncherTiltCompat {
    private const val HELPER = "com.android.quickstep.util.animation.RectTransformHelper"
    private const val PARAMS = "$HELPER\$TransformParams"
    private data class Owned(val surface: WeakReference<SurfaceControl>, val touched: Long)
    private val ui by lazy { Handler(Looper.getMainLooper()) }
    private val owned = java.util.concurrent.ConcurrentHashMap<Int, Owned>()
    private val watching = java.util.concurrent.atomic.AtomicBoolean(false)
    private var readAt = -1L
    @Volatile private var enabled = false
    @Volatile private var strength = 1f
    @Volatile private var perspective = 1f
    @Volatile private var retryAt = 0L

    /** Row-major mathematical projection. OEM JNI's TMat44 payload is column-major;
     * use nativeProjection for submission, rather than passing this array directly. */
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

    /** COS17 JNI copies all 16 floats directly into android::mat4; it does not
     * transpose Java arrays. Convert our row-major math to that column-major ABI.
     * Each frame owns this array, so no second matrix or shared mutable scratch.
     */
    fun nativeProjection(out:FloatArray,progress:Float,strength:Float,perspective:Float,
        cx:Float,cy:Float,height:Float,direction:Float):Boolean {
        val active=projection(out,progress,strength,perspective,cx,cy,height,direction)
        if(active)for(r in 0..2)for(c in r+1..3) {
            val value=out[r*4+c];out[r*4+c]=out[c*4+r];out[c*4+r]=value
        }
        return active
    }

    fun install(api: XposedInterface, loader: ClassLoader): Boolean = try {
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
        val progress=params.getDeclaredField("l").apply { isAccessible=true }
        val sourceBounds=helper.getDeclaredField("a").apply { isAccessible=true }
        val clipBounds=helper.getDeclaredField("c").apply { isAccessible=true }
        val mode=target.getDeclaredField("c").apply { isAccessible=true }
        val windowMode=target.getDeclaredField("s").apply { isAccessible=true }
        val task=params.getDeclaredField("D").apply { isAccessible=true }
        val transaction=tx.getDeclaredMethod("g")
        val mark=tx.getDeclaredMethod("c",tx,SurfaceControl::class.java)
        val ctor=tx.getDeclaredConstructor()
        // xi.q$b.j catches and logs framework exceptions, then returns void. Call the
        // verified public OEM bridge directly so failures reach our bounded fallback.
        val bridge=Class.forName("com.oplus.wrapper.view.SurfaceControl\$Transaction",false,loader)
            .getDeclaredMethod("setTransformationMatrix4x4",SurfaceControl.Transaction::class.java,
                SurfaceControl::class.java,FloatArray::class.java)
        check(affine.returnType==props && owner.type==tx && surface.type==SurfaceControl::class.java &&
            transaction.returnType==SurfaceControl.Transaction::class.java && bridge.returnType==SurfaceControl.Transaction::class.java &&
            java.lang.reflect.Modifier.isStatic(bridge.modifiers) && java.lang.reflect.Modifier.isStatic(mark.modifiers))
        val scopes=LauncherTiltFrameScope()
        val pending=LauncherTiltSubmission<SurfaceControl.Transaction,SurfaceControl>()
        val ownershipLock=Any()
        val configLock=Any()
        fun config() = synchronized(configLock) {
            val now=SystemClock.uptimeMillis()
            if (readAt>=0 && now-readAt<250) return@synchronized
            readAt=now
            val p=Prefs.read(api)
            enabled=p.getBoolean(Prefs.KEY_MASTER,false) && p.getBoolean(Prefs.KEY_ANIM_TILT_ENABLED,false)
            strength=Prefs.readIntCompat(p,Prefs.KEY_ANIM_TILT_STRENGTH,100).coerceIn(0,200)/100f
            perspective=Prefs.readIntCompat(p,Prefs.KEY_ANIM_TILT_PERSPECTIVE,100).coerceIn(0,150)/100f
        }
        fun write(wrapper:Any,s:SurfaceControl,matrix:FloatArray) {
            check(s.isValid)
            val native=transaction.invoke(wrapper) as SurfaceControl.Transaction
            check(bridge.invoke(null,native,s,matrix)===native) { "OEM matrix bridge returned another transaction" }
            mark.invoke(null,wrapper,s)
        }
        fun identity()=FloatArray(16).also { it[0]=1f;it[5]=1f;it[10]=1f;it[15]=1f }
        fun failure(error:Throwable) {
            val cause=(error as? java.lang.reflect.InvocationTargetException)?.targetException ?: error
            val now=SystemClock.uptimeMillis()
            if(now>=retryAt) {
                Lg.w("lg-ltilt","COS17 matrix bridge failed; retain native affine animation",cause)
                RuntimeDiagnostics.mark("launcher-tilt-error","${cause.javaClass.simpleName}: ${cause.message}")
            }
            retryAt=now+30_000L
        }
        // Match the final framework apply, rather than one of its delegating overloads.
        // Every unrelated transaction takes only a weak-map lookup and remains untouched.
        val apply=SurfaceControl.Transaction::class.java.getDeclaredMethod("apply",
            Boolean::class.javaPrimitiveType,Boolean::class.javaPrimitiveType).apply { isAccessible=true }
        api.hook(apply).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
            val native=chain.getThisObject() as SurfaceControl.Transaction
            val poses=pending.take(native,SystemClock.uptimeMillis())
            if(poses.isNotEmpty())synchronized(ownershipLock) { runCatching {
                config()
                for(pose in poses) {
                    val s=pose.surface.get() ?: continue
                    val current=owned[System.identityHashCode(s)]
                    if(enabled && s.isValid && current?.surface?.get()===s && current.touched==pose.created) {
                        bridge.invoke(null,native,s,pose.matrix)
                        RuntimeDiagnostics.mark("launcher-tilt-final-apply","已在实际 apply 前合成目标透视矩阵；可见效果待设备核验")
                    }
                }
            }.onFailure(::failure) }
            chain.proceed()
        }
        val merge=SurfaceControl.Transaction::class.java.getDeclaredMethod("merge",SurfaceControl.Transaction::class.java)
        api.hook(merge).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
            val result=chain.proceed()
            runCatching { pending.merge(chain.getThisObject() as SurfaceControl.Transaction,
                chain.getArg(0) as SurfaceControl.Transaction,SystemClock.uptimeMillis()) }.onFailure(::failure)
            result
        }
        val close=SurfaceControl.Transaction::class.java.getDeclaredMethod("close")
        api.hook(close).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
            pending.discard(chain.getThisObject() as SurfaceControl.Transaction);chain.proceed()
        }
        api.deoptimize(apply);api.deoptimize(merge)
        fun reset(s:SurfaceControl) {
            if(!s.isValid)return
            val wrapper=ctor.newInstance()
            val native=transaction.invoke(wrapper) as SurfaceControl.Transaction
            try { write(wrapper,s,identity());native.apply() } finally { native.close() }
        }
        val watchdog=object:Runnable {
            override fun run() {
                try {
                    try { config() } catch(_:Throwable) { enabled=false }
                    val now=SystemClock.uptimeMillis()
                    for((id,item) in owned)synchronized(ownershipLock) { if(!enabled || now-item.touched>=240L) {
                        // New frames replace Owned instead of mutating a record removed by cleanup.
                        if(owned.remove(id,item))runCatching { item.surface.get()?.let(::reset) }.onFailure(::failure)
                    } }
                } finally {
                    if(owned.isEmpty()) {
                        watching.set(false)
                        if(owned.isNotEmpty() && watching.compareAndSet(false,true))ui.postDelayed(this,64L)
                    } else ui.postDelayed(this,64L)
                }
            }
        }
        fun remember(s:SurfaceControl) {
            val id=System.identityHashCode(s)
            if(owned.size>=16 && !owned.containsKey(id))return
            // Touch at submission time on launcher.anim, not in a delayed main-thread task.
            owned[id]=Owned(WeakReference(s),SystemClock.uptimeMillis())
            if(watching.compareAndSet(false,true))ui.postDelayed(watchdog,256L)
        }
        val committed=java.util.concurrent.atomic.AtomicBoolean(false)
        val directExecutor=java.util.concurrent.Executor { it.run() }
        fun submit(wrapper:Any,s:SurfaceControl,rect:RectF,p:Float,route:String) {
            synchronized(ownershipLock) { runCatching {
                config();val id=System.identityHashCode(s)
                if(!s.isValid)return@runCatching
                val matrix=FloatArray(16)
                val active=enabled && SystemClock.uptimeMillis()>=retryAt &&
                    nativeProjection(matrix,p,strength,perspective,rect.centerX(),rect.centerY(),rect.height(),1f)
                if(active && (owned.size<16 || owned.containsKey(id))) {
                    write(wrapper,s,matrix);remember(s)
                    val native=transaction.invoke(wrapper) as SurfaceControl.Transaction
                    pending.put(native,s,matrix,owned[id]!!.touched)
                    RuntimeDiagnostics.mark("launcher-tilt-event","$route：原生 4×4 已写入；p=${(p*100).toInt()}%；线程=${Thread.currentThread().name}")
                    if(p in .2f.. .8f && committed.compareAndSet(false,true)) {
                        val native=transaction.invoke(wrapper) as SurfaceControl.Transaction
                        runCatching { native.addTransactionCommittedListener(directExecutor) {
                            RuntimeDiagnostics.mark("launcher-tilt-committed","中段倾斜事务已提交；最终可见效果仍需截图核验")
                        } }.onFailure { committed.set(false) }
                    }
                } else if(owned.remove(id)!=null)write(wrapper,s,identity())
            }.onFailure(::failure) }
        }
        // Observe native affine writes but don't inject midway through its target callback.
        api.hook(affine).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
            val result=chain.proceed()
            runCatching {
                val properties=chain.getThisObject()!!
                val wrapper=owner.get(properties)!!;val s=surface.get(properties) as? SurfaceControl
                if(s!=null && !scopes.matrixWritten(wrapper,s))synchronized(ownershipLock) {
                    val native=transaction.invoke(wrapper) as SurfaceControl.Transaction
                    if(!pending.contains(native,s) && owned.remove(System.identityHashCode(s))!=null && s.isValid)
                        write(wrapper,s,identity())
                }
            }.onFailure(::failure)
            result
        }
        var routes=0
        // This is the actual per-target callback inside RectTransformHelper.a, under
        // gi.c.g's surface lock. Read its live params and refreshed clip, not a cached
        // transaction-wide Frame with a 40ms expiry.
        runCatching {
            val callback=LauncherTiltContract.callback(listOf("ma.r0","ma.p0").mapNotNull { name ->
                runCatching { Class.forName(name,false,loader) }.getOrNull()
            },target,helper,tx,params) ?: error("No verified standard target callback")
            val capturedTarget=callback.getDeclaredField("a").apply { isAccessible=true }
            val capturedHelper=callback.getDeclaredField("b").apply { isAccessible=true }
            val capturedTx=callback.getDeclaredField("c").apply { isAccessible=true }
            val capturedParams=callback.getDeclaredField("d").apply { isAccessible=true }
            check(capturedTarget.type==target && capturedHelper.type==helper && capturedTx.type==tx && capturedParams.type==params)
            val run=callback.getDeclaredMethod("run")
            api.hook(run).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                val callbackObject=chain.getThisObject()!!
                val frame=runCatching {
                    config()
                    val p=capturedParams.get(callbackObject)!!
                    val s=leash.get(capturedTarget.get(callbackObject)) as? SurfaceControl
                    if(!enabled || task.getBoolean(p) || s==null || !s.isValid)null
                    else Triple(capturedTx.get(callbackObject)!!,s,p)
                }.getOrNull()
                if(frame==null)return@intercept chain.proceed()
                scopes.run(frame.first,frame.second,{ chain.proceed() }) {
                    runCatching {
                    val h=capturedHelper.get(callbackObject)
                    val clip=clipBounds.get(h) as Rect
                    val rect=RectF(if(!clip.isEmpty)clip else sourceBounds.get(h) as Rect)
                    submit(frame.first,frame.second,rect,progress.getFloat(frame.third),"标准窗口")
                    }.onFailure(::failure)
                }
            }
            api.deoptimize(run);routes++
            // The target's surface lock wrapper invokes Runnable.run. ART may have
            // inlined this callback into that wrapper before our hook was installed.
            api.deoptimize(target.getDeclaredMethod("g",Runnable::class.java))
            helper.declaredMethods.filter { it.name=="a" && it.parameterCount==9 && it.parameterTypes[0]==params }
                .forEach { api.deoptimize(it) }
        }.onFailure { Lg.w("lg-ltilt","Standard target callback unavailable",it) }
        runCatching {
            val light=Class.forName("com.android.launcher3.anim.light.AppLaunchAnimUtil\$getLightWindowAnimation\$1",false,loader)
            val method=light.declaredMethods.single { it.name=="b" && it.parameterCount==16 &&
                it.parameterTypes[0]==target && it.parameterTypes[1]==tx && it.parameterTypes[12]==Float::class.javaPrimitiveType }
            api.hook(method).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                val frame=runCatching {
                    config();val t=chain.getArg(0)!!;val s=leash.get(t) as? SurfaceControl
                    val opening=chain.getArg(2) as Boolean
                    val eligible=(mode.getInt(t)==2 && windowMode.getInt(t)==1) ||
                        (mode.getInt(t)==0 && opening) || (mode.getInt(t)==1 && !opening)
                    if(enabled && eligible && s!=null && s.isValid)Pair(t,s) else null
                }.getOrNull()
                if(frame==null)return@intercept chain.proceed()
                val wrapper=chain.getArg(1)!!
                scopes.run(wrapper,frame.second,{chain.proceed()}) {
                    runCatching {
                    val boundsValue=bounds.get(frame.first) as? Rect
                    if(boundsValue!=null) {
                        // Native b() offsets the crop to (0,0). Screen-space left/top
                        // are not the perspective pivot in that cropped leash.
                        val local=RectF(0f,0f,boundsValue.width().toFloat(),boundsValue.height().toFloat())
                        submit(wrapper,frame.second,local,chain.getArg(12) as Float,"轻动画窗口")
                    }
                    }.onFailure(::failure)
                }
            }
            api.deoptimize(method)
            // onUpdate calls a7.a.run -> b(); deoptimizing onUpdate alone misses b
            // inlined into the runnable which actually writes each target's matrix.
            val caller=Class.forName("a7.a",false,loader).getDeclaredMethod("run")
            api.deoptimize(caller);routes++
            light.declaredMethods.filter { it.name=="onUpdate" }.forEach { api.deoptimize(it) }
        }.onFailure { Lg.w("lg-ltilt","Light target callback unavailable",it) }
        api.deoptimize(affine)
        check(routes>0) { "No verified target animation callback" }
        RuntimeDiagnostics.mark("launcher-tilt","COS17 $routes 个逐目标入口已安装；末次矩阵合成与底层事务确认已启用")
        true
    } catch(error:Throwable) {
        RuntimeDiagnostics.mark("launcher-tilt","未匹配 COS17 窗口接口，保留系统动画")
        Lg.w("lg-ltilt","COS17 window tilt unavailable",error);false
    }
}
