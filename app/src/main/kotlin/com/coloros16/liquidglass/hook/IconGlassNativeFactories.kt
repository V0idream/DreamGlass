/* DreamGlass contributors, 2026. GPL-3.0-or-later. */
package com.coloros16.liquidglass.hook

import java.lang.reflect.Constructor
import java.lang.reflect.Method

internal object IconGlassNativeFactories {
    private val integer = Int::class.javaPrimitiveType!!

    fun drawableConstructor(drawable: Class<*>, bitmap: Class<*>): Constructor<*> =
        runCatching { drawable.getConstructor(bitmap) }
            .getOrElse { drawable.getConstructor(bitmap, integer) }

    fun bitmapInfoFactories(info: Class<*>, context: Class<*>, drawable: Class<*>): List<Method> {
        val contextOnly = arrayOf(context)
        val contextFirst = arrayOf(context, integer)
        val flagsFirst = arrayOf(integer, context)
        return info.declaredMethods.filter { method ->
            method.name == "newIcon" && drawable.isAssignableFrom(method.returnType) &&
                (method.parameterTypes.contentEquals(contextOnly) || method.parameterTypes.contentEquals(contextFirst) ||
                    method.parameterTypes.contentEquals(flagsFirst))
        }
    }
}
