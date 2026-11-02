package com.coloros16.liquidglass

import com.coloros16.liquidglass.hook.ScopedDrawOwner
import org.junit.Assert.*
import org.junit.Test

class ScopedDrawOwnerTest {
    @Test fun inactiveChildMustMaskTheActiveParentSoItsFillIsNotSuppressed() {
        val scope=ScopedDrawOwner<Any>();val parent=Any()
        scope.run(parent) {
            assertSame(parent,scope.current())
            scope.run(null) {assertNull(scope.current())}
            assertSame(parent,scope.current())
        }
        assertNull(scope.current())
    }
    @Test fun differentMountedChildrenOwnOnlyTheirOwnDecoration() {
        val scope=ScopedDrawOwner<Any>();val parent=Any();val child=Any()
        scope.run(parent) {
            scope.run(child) {assertSame(child,scope.current());assertNotSame(parent,scope.current())}
            assertSame(parent,scope.current())
        }
        assertNull(scope.current())
    }
    @Test fun hostFailureIsNotReplayedAndCannotLeakSuppressionToTheNextDraw() {
        val scope=ScopedDrawOwner<Any>();var calls=0
        try {scope.run(Any()) {calls++;throw IllegalStateException("native-failure")}}
        catch(_:IllegalStateException) {}
        assertEquals(1,calls);assertNull(scope.current())
        scope.run(null) {calls++;assertNull(scope.current())}
        assertEquals(2,calls)
    }
    @Test fun workerThreadCannotBorrowTheUiTargetsFillSuppression() {
        val scope=ScopedDrawOwner<Any>();var leaked=true
        scope.run(Any()) {val t=Thread {leaked=scope.current()!=null};t.start();t.join()}
        assertFalse(leaked)
    }
    @Test fun returnsTheOriginalHostResultIncludingNull() {
        val scope=ScopedDrawOwner<Any>()
        assertEquals("foreground-preserved",scope.run(Any()) {"foreground-preserved"})
        assertNull(scope.run(Any()) {null})
    }
}
