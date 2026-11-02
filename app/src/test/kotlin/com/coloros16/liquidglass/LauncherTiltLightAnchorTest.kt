package com.coloros16.liquidglass

import com.coloros16.liquidglass.hook.LauncherTiltAnchorRegistry
import com.coloros16.liquidglass.hook.LauncherTiltCapturedAnchor
import com.coloros16.liquidglass.hook.LauncherTiltIconBounds
import com.coloros16.liquidglass.hook.LauncherTiltLightAnchorScope
import org.junit.Assert.*
import org.junit.Test

class LauncherTiltLightAnchorTest {
    private val left=LauncherTiltIconBounds(100f,200f,160f,260f)
    private val right=LauncherTiltIconBounds(840f,1740f,900f,1800f)

    @Test fun creationMatchesThisRequestsTargetsByIdentityAndNeverLastClick() {
        class EqualTargets {
            override fun equals(other:Any?)=other is EqualTargets
            override fun hashCode()=0
        }
        val scope=LauncherTiltLightAnchorScope();val request=Any();val targets=EqualTargets()
        assertNull(scope.anchor(targets,1000f,2000f))
        scope.run(request,targets,left) {
            assertSame(request,scope.request())
            assertTrue(scope.anchor(targets,1000f,2000f)!!.fractionX<0)
            assertNull(scope.anchor(EqualTargets(),1000f,2000f))
        }
        assertNull(scope.request());assertNull(scope.anchor(targets,1000f,2000f))
    }

    @Test fun listenerBindingsKeepOppositeIconsSeparateEvenOnSharedTargets() {
        val scope=LauncherTiltLightAnchorScope();val bindings=LauncherTiltAnchorRegistry()
        val targets=Any();val firstListener=Any();val secondListener=Any()
        scope.run(Any(),targets,left) {
            bindings.bind(firstListener,LauncherTiltCapturedAnchor(targets,firstListener,scope.anchor(targets,1000f,2000f)!!))
        }
        scope.run(Any(),targets,right) {
            bindings.bind(secondListener,LauncherTiltCapturedAnchor(targets,secondListener,scope.anchor(targets,1000f,2000f)!!))
        }
        assertTrue(bindings.resolve(firstListener,targets)!!.anchor.fractionX<0)
        assertTrue(bindings.resolve(secondListener,targets)!!.anchor.fractionX>0)
        assertNull(bindings.resolve(Any(),targets));assertNull(bindings.resolve(firstListener,Any()))
    }

    @Test fun nativeCreationCancellationRestoresNestedScopeWithoutReplayingNative() {
        val scope=LauncherTiltLightAnchorScope();val targets=Any();val first=Any();var calls=0
        scope.run(first,targets,left) {
            try {
                scope.run(Any(),targets,right) {
                    calls++;assertTrue(scope.anchor(targets,1000f,2000f)!!.fractionX>0)
                    throw IllegalStateException("native cancelled")
                }
                fail("Expected native cancellation")
            } catch(expected:IllegalStateException) {assertEquals("native cancelled",expected.message)}
            assertSame(first,scope.request());assertTrue(scope.anchor(targets,1000f,2000f)!!.fractionX<0)
        }
        assertEquals(1,calls);assertNull(scope.request());assertNull(scope.anchor(targets,1000f,2000f))
    }

    @Test fun invalidGeometryAndOtherThreadsDoNotAcquireAnIcon() {
        val scope=LauncherTiltLightAnchorScope();val targets=Any()
        scope.run(Any(),targets,left) {
            assertNull(scope.anchor(targets,0f,2000f))
            assertNull(scope.anchor(targets,1000f,Float.NaN))
            var captured=true
            Thread {captured=scope.anchor(targets,1000f,2000f)!=null}.apply {start();join()}
            assertFalse(captured)
        }
        scope.run(Any(),targets,LauncherTiltIconBounds(0f,0f,1000f,2000f)) {
            assertNull(scope.anchor(targets,1000f,2000f))
        }
    }
}
