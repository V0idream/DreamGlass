package com.coloros16.liquidglass

import com.coloros16.liquidglass.hook.CalculatorForegroundDraw
import org.junit.Assert.*
import org.junit.Test

class CalculatorForegroundDrawTest {
    @Test fun mountedParentRemovesOnlyNativeFillAndCoatWhileTextCallbackStillRuns() {
        val adapter=CalculatorForegroundDraw();val owner=Any();var fill=true;var foreground=0
        val value=adapter.draw(owner,true,{fill},{fill=it}) {
            assertFalse(fill);assertTrue(adapter.suppressCoat(owner));foreground++;"native-result"
        }
        assertEquals("native-result",value);assertEquals(1,foreground);assertTrue(fill)
        assertFalse(adapter.suppressCoat(owner))
    }
    @Test fun inactiveOrSoftwareDrawDoesNotEvenReadOrWriteNativeFillState() {
        val adapter=CalculatorForegroundDraw();val owner=Any();var calls=0
        adapter.draw(owner,false,{error("Native fill must remain untouched")},{error("Unexpected fill write")}) {
            calls++;assertFalse(adapter.suppressCoat(owner))
        }
        assertEquals(1,calls)
    }
    @Test fun hostFailureRestoresFillWithoutReplayingForeground() {
        val adapter=CalculatorForegroundDraw();val owner=Any();var fill=true;var calls=0
        try {adapter.draw(owner,true,{fill},{fill=it}) {calls++;throw IllegalStateException("host draw failed")}}
        catch(_:IllegalStateException) {}
        assertTrue(fill);assertEquals(1,calls);assertFalse(adapter.suppressCoat(owner))
    }
    @Test fun preexistingFalseFillStateIsNotChangedToTrueAfterDrawing() {
        val adapter=CalculatorForegroundDraw();var fill=false
        adapter.draw(Any(),true,{fill},{fill=it}) {assertFalse(fill)}
        assertFalse(fill)
    }
    @Test fun nestedUnadaptedButtonDoesNotBorrowParentSuppression() {
        val adapter=CalculatorForegroundDraw();val outer=Any();val inner=Any();var fill=true
        adapter.draw(outer,true,{fill},{fill=it}) {
            adapter.draw(inner,false,{true},{}) {
                assertFalse(adapter.suppressCoat(outer));assertFalse(adapter.suppressCoat(inner))
            }
            assertTrue(adapter.suppressCoat(outer));assertFalse(adapter.suppressCoat(inner))
        }
        assertTrue(fill)
    }
}
