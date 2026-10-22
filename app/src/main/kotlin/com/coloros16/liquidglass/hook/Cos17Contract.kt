package com.coloros16.liquidglass.hook

import android.graphics.Paint
import android.graphics.Path
import android.graphics.RenderNode
import android.graphics.drawable.Drawable
import java.lang.reflect.Modifier

/** Exact structural evidence, usable before Application/PackageManager is ready. */
object Cos17Contract {
    fun launcherInline(cl: ClassLoader?): Boolean = try {
        if (cl == null) false else {
            val base = Class.forName("com.oplus.posteffect.drawable.b", false, cl)
            Drawable::class.java.isAssignableFrom(base) &&
                base.getDeclaredField("M").type == Paint::class.java &&
                base.getDeclaredField("h").type == RenderNode::class.java &&
                base.getDeclaredField("i").type == Path::class.java &&
                base.getDeclaredField("a").type == Boolean::class.javaPrimitiveType &&
                base.declaredMethods.any {
                    it.name == "a" && Modifier.isStatic(it.modifiers) && it.returnType == Void.TYPE &&
                        it.parameterTypes.size == 1 && base.isAssignableFrom(it.parameterTypes[0])
                }
        }
    } catch (_: Throwable) { false }

    fun nativeTilt(cl: ClassLoader): Boolean = try {
        Class.forName("com.android.quickstep.util.animation.ZuyqaWindowTilt", false, cl).getMethod("isEnabled")
        Class.forName("com.android.common.util.ZuyqaAnimationTuning", false, cl).getMethod(
            "getInt", android.content.Context::class.java, String::class.java, Int::class.javaPrimitiveType)
        true
    } catch (_: Throwable) { false }
}
