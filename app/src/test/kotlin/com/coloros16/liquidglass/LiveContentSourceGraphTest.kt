package com.coloros16.liquidglass

import com.coloros16.liquidglass.hook.LiveContentSourceGraph
import org.junit.Assert.*
import org.junit.Test

class LiveContentSourceGraphTest {
    private class Owner(var parent:Owner?=null)
    private fun safe(a:Owner,b:Owner)=LiveContentSourceGraph.independent(a,b) {it.parent}
    @Test fun realContentSiblingAndSeparateDialogWindowAreIndependent() {
        val root=Owner();val pager=Owner(root);val bar=Owner(root);val button=Owner(bar)
        assertTrue(safe(pager,button));assertTrue(safe(root,Owner(Owner())))
    }
    @Test fun activityRootContainingAnInlineBarCannotBeItsSource() {
        val root=Owner();val bar=Owner(Owner(root))
        assertFalse(safe(root,bar));assertFalse(safe(bar,root));assertFalse(safe(bar,bar))
    }
    @Test fun malformedOrUnboundedPathsFailClosedRatherThanCreatingNativeCycles() {
        val a=Owner();val b=Owner(a);a.parent=b
        assertFalse(safe(a,Owner()))
        var long=Owner();repeat(65) {long=Owner(long)}
        assertFalse(safe(long,Owner()))
    }
}
