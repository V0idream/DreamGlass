package com.coloros16.liquidglass.hook

/** Native shader availability does not imply that the native image/blur branch will draw it. */
object ContentDrawBridge {
    inline fun dispatch(ownedDraw: () -> Boolean, original: () -> Any?, onOwnedFailure: (Throwable) -> Unit): Any? {
        val handled = try {
            ownedDraw()
        } catch (failure: Throwable) {
            try { onOwnedFailure(failure) } catch (_: Throwable) { }
            false
        }
        // Keep native invocation OUTSIDE the catch: preserve its original return and exception.
        return if (handled) null else original()
    }
}
