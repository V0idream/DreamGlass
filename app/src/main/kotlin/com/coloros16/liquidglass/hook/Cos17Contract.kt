package com.coloros16.liquidglass.hook

import android.graphics.Paint
import android.graphics.Path
import android.graphics.RenderNode
import android.graphics.drawable.Drawable
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Exact structural evidence, usable before Application/PackageManager is ready. */
object Cos17Contract {
    internal const val RELOCATED_DRAWABLE = "com.oplus.posteffect.drawable.a"

    internal fun relocatedRecord(base: Class<*>): Method? = runCatching {
        check(base.name == RELOCATED_DRAWABLE && Drawable::class.java.isAssignableFrom(base))
        check(base.getDeclaredField("K").type.name == "com.oplus.posteffect.agsl.b")
        check(base.getDeclaredField("L").type == Paint::class.java)
        check(base.getDeclaredField("h").type == RenderNode::class.java)
        check(base.getDeclaredField("i").type == Path::class.java)
        check(base.getDeclaredField("E").type == Int::class.javaPrimitiveType)
        check(base.getDeclaredField("v").type == Boolean::class.javaPrimitiveType)
        check(base.getDeclaredField("a").type == Boolean::class.javaPrimitiveType)
        base.getDeclaredMethod("a").apply {
            check(!Modifier.isStatic(modifiers) && returnType == Void.TYPE)
            isAccessible = true
        }
    }.getOrNull()

    fun launcherInline(cl: ClassLoader?): Boolean {
        if (cl == null) return false
        return try {
            val relocated = runCatching { Class.forName(RELOCATED_DRAWABLE, false, cl) }.getOrNull()
            if (relocated != null && relocatedRecord(relocated) != null) return true
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
        } catch (_: Throwable) { false }
    }

    fun nativeTilt(cl: ClassLoader): Boolean = try {
        Class.forName("com.android.quickstep.util.animation.ZuyqaWindowTilt", false, cl).getMethod("isEnabled")
        Class.forName("com.android.common.util.ZuyqaAnimationTuning", false, cl).getMethod(
            "getInt", android.content.Context::class.java, String::class.java, Int::class.javaPrimitiveType)
        true
    } catch (_: Throwable) { false }
}
