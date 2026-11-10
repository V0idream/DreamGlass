/* DreamGlass contributors, 2026. GPL-3.0-or-later. */
package com.coloros16.liquidglass.hook

import android.graphics.*
import java.lang.reflect.Field

/** COS17 BaseDrawable b's audited input graph (b.a(d), 17.3.12). Native C.a.a
 * is the already blurred image, l is its bitmap-to-local matrix, r its lock.
 * Bitmap inputs are submitted while that lock is held; no bitmap/source is
 * retained in a module cache, copied, read back, or blurred on the CPU. */
internal class NativeClearSourceExtractor private constructor(
    private val base:Class<*>,private val lock:Field,private val enabled:Field,
    private val paint:Field,private val drawableShader:Field,private val ready:Field,
    private val runtime:Field,private val capture:Field,private val image:Field,
    private val bitmap:Field,private val recycled:Field,private val mapping:Field,
) {
    data class Info(val kind:NativeClearSourceKind,val reason:String)
    data class Stamp(val capture:Int,val bitmap:Int,val shader:Int,val ready:Boolean,
        val enabled:Boolean,val matrix:Int,val recycled:Boolean)
    private val bitmapMapping=NativeClearBitmapShader()

    fun stamp(drawable:Any):Stamp? {
        if(!base.isInstance(drawable))return null
        return synchronized(lock.get(drawable)) {
            val c=capture.get(drawable);val i=if(c==null)null else image.get(c)
            val b=if(i==null)null else bitmap.get(i) as? Bitmap
            val d=drawableShader.get(drawable)
            val m=FloatArray(9).also {(mapping.get(drawable) as Matrix).getValues(it)}
            Stamp(System.identityHashCode(c),System.identityHashCode(b),
                System.identityHashCode((paint.get(drawable) as? Paint)?.shader ?: d?.let(runtime::get)),
                ready.getBoolean(d),enabled.getBoolean(drawable),m.contentHashCode(),
                i!=null && recycled.getBoolean(i) || b?.isRecycled==true)
        }
    }

    fun draw(drawable:Any,accept:(Shader)->Boolean,submit:(Shader?,Info)->Boolean):Boolean {
        if(!base.isInstance(drawable))return submit(null,Info(NativeClearSourceKind.NONE,"wrong-native-type"))
        return synchronized(lock.get(drawable)) {
            val agsl=enabled.getBoolean(drawable)
            val m=(paint.get(drawable) as? Paint)?.shader?.takeIf(accept)
            val d=drawableShader.get(drawable)
            val l=(if(ready.getBoolean(d))runtime.get(d) as? RuntimeShader else null)?.takeIf(accept)
            val c=capture.get(drawable);val holder=if(c==null)null else image.get(c)
            val b=if(holder==null || recycled.getBoolean(holder))null else (bitmap.get(holder) as? Bitmap)
                ?.takeUnless {it.isRecycled}
            val kind=NativeClearSourcePolicy.choose(agsl,m!=null,l!=null,b!=null)
            when(kind) {
                NativeClearSourceKind.M -> submit(m,Info(kind,"native-recorded-shader"))
                NativeClearSourceKind.L -> submit(l,Info(kind,"native-shader-ready-before-paint-record"))
                NativeClearSourceKind.NONE -> submit(null,Info(kind,
                    if(c==null)"capture-pending" else "native-input-unavailable-or-recycled"))
                NativeClearSourceKind.BITMAP -> {
                    val result=bitmapMapping.draw(b!!,Matrix(mapping.get(drawable) as Matrix)) {shader ->
                        submit(shader,Info(kind,"native-preblurred-bitmap;matrix=l;config=${b.config}"))
                    }
                    result ?: submit(null,Info(NativeClearSourceKind.NONE,"native-bitmap-or-matrix-invalid"))
                }
            }
        }
    }

    companion object {
        fun resolve(base:Class<*>):NativeClearSourceExtractor {
            val named=base.name=="com.oplus.posteffect.drawable.BaseDrawable"
            fun field(type:Class<*>,expected:Class<*>,old:String,new:String,unique:Boolean=false)=
                NativeClearFieldResolver.resolve(type,expected,if(named)new else old,uniqueType=unique)
            fun declared(type:Class<*>,old:String,new:String)=type.getDeclaredField(if(named)new else old).apply {isAccessible=true}
            val c=declared(base,"C","drawingImageInfo");check(c.type.enclosingClass==base)
            val image=declared(c.type,"a","image")
            check(image.type.name==if(named)"com.oplus.posteffect.image.ContentImage" else "yn.b")
            val drawable=declared(base,"L","drawableShader")
            check(drawable.type.name=="com.oplus.posteffect.agsl.DrawableShader")
            return NativeClearSourceExtractor(base,field(base,Any::class.java,"r","dataLock"),
                field(base,Boolean::class.javaPrimitiveType!!,"a","enableShader"),field(base,Paint::class.java,"M","drawableShaderPaint"),
                drawable,field(drawable.type,Boolean::class.javaPrimitiveType!!,"b","isValid"),
                field(drawable.type,RuntimeShader::class.java,"v","runtimeShader",true),c,image,
                field(image.type,Bitmap::class.java,"a","bitmap",true),
                field(image.type,Boolean::class.javaPrimitiveType!!,"b","isRecycled"),
                field(base,Matrix::class.java,"l","bitmapMatrix"))
        }
    }
}
