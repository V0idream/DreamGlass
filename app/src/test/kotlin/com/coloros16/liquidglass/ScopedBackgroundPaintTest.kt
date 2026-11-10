package com.coloros16.liquidglass

import com.coloros16.liquidglass.hook.ScopedBackgroundPaint
import org.junit.Assert.*
import org.junit.Test

class ScopedBackgroundPaintTest {
    @Test fun nestedNotificationsCannotStealEachOthersBackground() {
        val paint = ScopedBackgroundPaint<Any>(); val first = Any(); val second = Any()
        var nativeCalls = 0
        paint.with(first) {
            assertSame(first, paint.current())
            paint.with(second) { assertSame(second, paint.current()); nativeCalls++ }
            assertSame(first, paint.current()); nativeCalls++
        }
        assertNull(paint.current()); assertEquals(2, nativeCalls)
    }

    @Test fun failedNativeDrawIsNotRetriedAndCannotLeakOverride() {
        val paint = ScopedBackgroundPaint<Any>(); var calls = 0
        try { paint.with(Any()) { calls++; error("native failure") } }
        catch (_: IllegalStateException) { }
        assertEquals(1, calls); assertNull(paint.current())
    }
}
