package com.coloros16.liquidglass

import com.coloros16.liquidglass.hook.LauncherTiltAffine
import com.coloros16.liquidglass.hook.LauncherTiltAnchorScope
import com.coloros16.liquidglass.hook.LauncherTiltAnchorRegistry
import com.coloros16.liquidglass.hook.LauncherTiltIconAnchor
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class LauncherTiltIconAnchorTest {
    private val identity=floatArrayOf(1f,0f,0f,0f,1f,0f,0f,0f,1f)
    private fun anchor(x:Float,y:Float)=LauncherTiltIconAnchor.fromRects(
        x-30,y-30,x+30,y+30,0f,0f,1000f,2000f)!!
    private fun point(m:FloatArray,x:Float,y:Float)=floatArrayOf(m[0]*x+m[1]*y+m[2],m[3]*x+m[4]*y+m[5])
    private fun det(m:FloatArray)=m[0]*m[4]-m[1]*m[3]
    private fun output(a:LauncherTiltIconAnchor?,m:FloatArray=identity,p:Float=.5f,depth:Float=1f)=FloatArray(9).also {
        LauncherTiltAffine.composeTowardsIcon(m,it,p,1f,depth,500f,1000f,a)
    }

    @Test fun allFourIconQuadrantsProduceDistinctSignedTransforms() {
        val corners=listOf(anchor(100f,200f),anchor(900f,200f),anchor(100f,1800f),anchor(900f,1800f))
        val matrices=corners.map {output(it)}
        for(i in matrices.indices)for(j in 0 until i)assertFalse(matrices[i].contentEquals(matrices[j]))
        for(m in matrices)assertArrayEquals(floatArrayOf(500f,1000f),point(m,500f,1000f),.001f)
        // Opposite icon sides must not collapse to the same unsigned axis.
        assertTrue(abs(matrices[0][1]-matrices[3][1])>.02f)
    }

    @Test fun centreIconDeadZoneAndMissingAnchorPreserveNativeExactly() {
        for(a in listOf(null,anchor(500f,1000f),anchor(510f,1010f))) {
            val out=FloatArray(9)
            assertFalse(LauncherTiltAffine.composeTowardsIcon(identity,out,.5f,2f,1.5f,500f,1000f,a))
            assertArrayEquals(identity,out,0f)
        }
    }

    @Test fun endpointsAndZeroStrengthDoNotChangeNativeScaleOrTranslation() {
        val native=floatArrayOf(.21f,.03f,713f,-.02f,.35f,151f,0f,0f,1f)
        for(p in floatArrayOf(-1f,0f,1f,2f))assertArrayEquals(native,output(anchor(100f,1800f),native,p),0f)
        val out=FloatArray(9)
        assertFalse(LauncherTiltAffine.composeTowardsIcon(native,out,.5f,0f,1f,500f,1000f,anchor(100f,1800f)))
        assertArrayEquals(native,out,0f)
    }

    @Test fun nativeCentreTrajectoryIsPreservedForOpeningAndClosingAtEveryFrame() {
        for(a in listOf(anchor(100f,200f),anchor(900f,1800f)))for(i in 0..100) {
            val p=i/100f
            val native=floatArrayOf(.1f+.9f*p,.02f,700f*(1-p),-.03f,.2f+.8f*p,1400f*(1-p),0f,0f,1f)
            val out=FloatArray(9)
            LauncherTiltAffine.composeTowardsIcon(native,out,p,2f,1.5f,500f,1000f,a)
            assertArrayEquals(point(native,500f,1000f),point(out,500f,1000f),.001f)
        }
    }

    @Test fun nativeScaleRemainsAMultiplierAndSharedMatrixNeverAccumulatesTilt() {
        val a=floatArrayOf(.2f,0f,310f,0f,.2f,570f,0f,0f,1f)
        val b=floatArrayOf(.8f,0f,90f,0f,.8f,70f,0f,0f,1f)
        val saved=a.copyOf();val icon=anchor(850f,1700f)
        val x=output(icon,a);val y=output(icon,b)
        for(i in intArrayOf(0,1,3,4))assertEquals(x[i]*4,y[i],.000001f)
        assertArrayEquals(saved,a,0f);assertArrayEquals(x,output(icon,a),0f)
    }

    @Test fun screenDirectionIsConvertedThroughNativeRotation() {
        val rotation=floatArrayOf(0f,-1f,2000f,1f,0f,0f,0f,0f,1f)
        val out=output(anchor(900f,1000f),rotation,depth=0f)
        // Right-of-screen endpoint compresses screen X (local Y after native
        // 90-degree rotation), not the now-vertical local X axis.
        assertEquals(1f,out[3],.000001f)
        assertTrue(abs(out[1])<1f)
        assertArrayEquals(point(rotation,500f,1000f),point(out,500f,1000f),.001f)
    }

    @Test fun maximumSettingsRemainFiniteAndNeverFlipOrCollapseTheWindow() {
        for(x in listOf(100f,500f,900f))for(y in listOf(200f,1000f,1800f))for(i in 0..100) {
            val p=i/100f;val native=floatArrayOf(.15f+.85f*p,.02f,200f,-.01f,.2f+.8f*p,700f,0f,0f,1f)
            val out=FloatArray(9)
            LauncherTiltAffine.composeTowardsIcon(native,out,p,2f,1.5f,500f,1000f,anchor(x,y))
            assertTrue(out.all {it.isFinite()});assertTrue(det(out)/det(native)>.64f)
        }
    }

    @Test fun invalidAndProjectiveInputPassThroughWithoutFlattening() {
        val a=anchor(100f,200f)
        for(m in listOf(identity.copyOf().also {it[6]=.01f},FloatArray(9),identity.copyOf().also {it[0]=Float.NaN})) {
            val out=FloatArray(9)
            assertFalse(LauncherTiltAffine.composeTowardsIcon(m,out,.5f,1f,1f,500f,1000f,a))
            assertArrayEquals(m,out,0f)
        }
    }

    @Test fun endpointNormalizationHonoursNonzeroWindowOrigins() {
        val a=LauncherTiltIconAnchor.fromRects(140f,260f,200f,320f,100f,200f,1100f,2200f)!!
        assertEquals(-430f,a.deltaX,0f);assertEquals(-910f,a.deltaY,0f)
        assertEquals(-.86f,a.fractionX,.00001f);assertEquals(-.91f,a.fractionY,.00001f)
    }

    @Test fun fullWindowForeignAndMalformedEndpointsAreNeverTreatedAsIcons() {
        for(rect in listOf(floatArrayOf(0f,0f,1000f,2000f),floatArrayOf(0f,0f,0f,30f),
            floatArrayOf(5000f,0f,5060f,60f),floatArrayOf(Float.NaN,0f,60f,60f)))
            assertNull(LauncherTiltIconAnchor.fromRects(rect[0],rect[1],rect[2],rect[3],0f,0f,1000f,2000f))
    }

    @Test fun scopesMatchHelperAndExecutingAnimationAndReadLiveEndpoints() {
        val scope=LauncherTiltAnchorScope();val helper=Any();val first=Any();val second=Any()
        var current=anchor(100f,200f)
        scope.run(helper,first,{current}) {
            assertSame(first,scope.owner());assertEquals(current,scope.anchor(helper));assertNull(scope.anchor(Any()))
            current=anchor(900f,1800f);assertEquals(current,scope.anchor(helper))
            scope.run(helper,second,{null}) {assertSame(second,scope.owner());assertNull(scope.anchor(helper))}
            assertSame(first,scope.owner());assertEquals(current,scope.anchor(helper))
            var other:LauncherTiltIconAnchor?=current
            Thread {other=scope.anchor(helper)}.apply {start();join()}
            assertNull(other)
        }
        assertNull(scope.anchor(helper));assertNull(scope.owner())
        scope.run(helper,second,{null}) {assertNull(scope.anchor(helper))}
    }

    @Test fun cancellationOrNativeExceptionCannotReplayOrLeakPreviousIcon() {
        val scope=LauncherTiltAnchorScope();val helper=Any();var calls=0
        val expected=IllegalStateException("native cancelled")
        try {
            scope.run(helper,Any(),{anchor(100f,200f)}) {calls++;throw expected}
            fail("Expected host cancellation")
        } catch(actual:IllegalStateException) {assertSame(expected,actual)}
        assertEquals(1,calls);assertNull(scope.anchor(helper));assertNull(scope.owner())
        // A later native frame on the reused helper has no stale fallback angle.
        assertArrayEquals(identity,output(scope.anchor(helper)),0f)
    }

    @Test fun delayedNativeFramesKeepTheirOwnAnimationDespiteInterleavedScheduling() {
        val scope=LauncherTiltAnchorScope();val registry=LauncherTiltAnchorRegistry()
        val helper=Any();val first=Any();val second=Any();val callbackA=Any();val callbackB=Any()
        val topLeft=anchor(100f,200f);val bottomRight=anchor(900f,1800f)
        scope.run(helper,first,{topLeft}) {registry.bind(callbackA,scope.capture())}
        scope.run(helper,second,{bottomRight}) {registry.bind(callbackB,scope.capture())}
        assertNull(scope.anchor(helper))
        val a=registry.resolve(callbackA,helper)!!;val b=registry.resolve(callbackB,helper)!!
        assertSame(first,a.animation);assertEquals(topLeft,a.anchor)
        assertSame(second,b.animation);assertEquals(bottomRight,b.anchor)
        scope.run(a.helper,a.animation,{a.anchor}) {assertEquals(topLeft,scope.anchor(helper))}
        assertNull(scope.anchor(helper));assertNull(registry.resolve(callbackA,Any()))
    }

    @Test fun delayedRegistryUsesIdentityAndCanClearAReusedCallbackOnMissingAnchor() {
        class EqualCallback {
            override fun equals(other:Any?)=other is EqualCallback
            override fun hashCode()=0
        }
        val scope=LauncherTiltAnchorScope();val registry=LauncherTiltAnchorRegistry()
        val helper=Any();val animation=Any();val a=EqualCallback();val b=EqualCallback()
        scope.run(helper,animation,{anchor(100f,200f)}) {registry.bind(a,scope.capture())}
        assertNotNull(registry.resolve(a,helper));assertNull(registry.resolve(b,helper))
        registry.bind(a,null)
        assertNull(registry.resolve(a,helper))
    }
}
