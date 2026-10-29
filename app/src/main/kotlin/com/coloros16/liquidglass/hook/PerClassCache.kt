package com.coloros16.liquidglass.hook

import java.util.WeakHashMap

/** ClassValue is only available from Android 14. These immutable class decisions also support API33. */
abstract class PerClassCache<T:Any> {
    private val values=WeakHashMap<Class<*>,T>()
    protected abstract fun computeValue(type:Class<*>):T
    @Synchronized fun get(type:Class<*>):T = values[type] ?: computeValue(type).also { values[type]=it }
}
