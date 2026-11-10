/* DreamGlass contributors, 2026. GPL-3.0-or-later. */
package com.coloros16.liquidglass.hook

/** Native notification draw may re-enter another notification. An override only
 * belongs to its draw stack, including when the native draw throws. */
internal class ScopedBackgroundPaint<T : Any> {
    private val active = ThreadLocal<T>()
    fun current(): T? = active.get()
    fun <R> with(value: T, draw: () -> R): R {
        val previous = active.get()
        active.set(value)
        return try { draw() } finally {
            if (previous == null) active.remove() else active.set(previous)
        }
    }
}
