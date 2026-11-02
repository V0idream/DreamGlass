package com.coloros16.liquidglass

import com.coloros16.liquidglass.hook.LauncherTiltAffine
import com.coloros16.liquidglass.hook.LauncherTiltAffineScope
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class LauncherTiltAffineTest {
    private fun native(p:Float)=floatArrayOf(.1f+.9f*p,.03f,200f*(1-p),-.02f,.2f+.8f*p,700f*(1-p),0f,0f,1f)
    private fun point(m:FloatArray,x:Float,y:Float)=floatArrayOf(m[0]*x+m[1]*y+m[2],m[3]*x+m[4]*y+m[5])
    private fun det(m:FloatArray)=m[0]*m[4]-m[1]*m[3]
    private fun pose(p:Float)=LauncherTiltAffineScope.Pose(p,1f,1f,600f,1300f,1f)
    private fun composed(m:FloatArray,p:Float,direction:Float=1f)=FloatArray(9).also {
        LauncherTiltAffine.compose(m,it,p,1f,1f,600f,1300f,direction)
    }
    @Test fun endpointsAreExactNativeMatricesIncludingTheirScaleAndTranslation() {
        for(p in floatArrayOf(-1f,0f,1f,2f)) {
            val original=native(.37f);val out=FloatArray(9)
            assertFalse(LauncherTiltAffine.compose(original,out,p,1f,1f,600f,1300f,1f))
            assertArrayEquals(original,out,0f)
        }
    }
    @Test fun centreFollowsNativeTrajectoryAtEveryFrameAndAspectRatio() {
        for((cx,cy) in listOf(600f to 1300f,1300f to 600f,500f to 500f,162f to 1100f))
            for(i in 0..100) {
                val p=i/100f;val m=native(p);val out=FloatArray(9)
                LauncherTiltAffine.compose(m,out,p,2f,1.5f,cx,cy,1f)
                assertArrayEquals(point(m,cx,cy),point(out,cx,cy),.001f)
            }
    }
    @Test fun nativeScaleRemainsAFactorInsteadOfBeingReplacedByIdentity() {
        val a=floatArrayOf(.2f,0f,320f,0f,.2f,840f,0f,0f,1f)
        val b=floatArrayOf(.8f,0f,100f,0f,.8f,40f,0f,0f,1f)
        val x=composed(a,.5f);val y=composed(b,.5f)
        for(i in intArrayOf(0,1,3,4))assertEquals(x[i]*4,y[i],.000001f)
        assertEquals(det(x)*16,det(y),.000001f)
    }
    @Test fun sharedOemMatrixIsNeverMutatedAndFramesDoNotAccumulateTilt() {
        val m=native(.6f);val saved=m.copyOf();val a=composed(m,.5f);val b=composed(m,.5f)
        assertArrayEquals(saved,m,0f);assertArrayEquals(a,b,0f)
        assertTrue(abs(m[1]-a[1])>.01f)
    }
    @Test fun zeroStrengthDirectionAndNonFiniteConfigurationPassThrough() {
        val m=native(.5f)
        for(args in listOf(floatArrayOf(.5f,0f,1f,1f),floatArrayOf(.5f,1f,1f,0f),
            floatArrayOf(Float.NaN,1f,1f,1f),floatArrayOf(.5f,Float.NaN,1f,1f),
            floatArrayOf(.5f,1f,Float.POSITIVE_INFINITY,1f))) {
            val out=FloatArray(9)
            assertFalse(LauncherTiltAffine.compose(m,out,args[0],args[1],args[2],600f,1300f,args[3]))
            assertArrayEquals(m,out,0f)
        }
    }
    @Test fun unsupportedProjectiveInputPassesThroughWithoutFlattening() {
        val m=native(.5f).also {it[6]=.002f};val out=FloatArray(9)
        assertFalse(LauncherTiltAffine.compose(m,out,.5f,1f,1f,600f,1300f,1f))
        assertArrayEquals(m,out,0f)
    }
    @Test fun maximumSettingsKeepOrientationAndCannotCollapseAWindow() {
        for(i in 0..100)for(direction in floatArrayOf(-1f,1f)) {
            val m=native(i/100f);val out=FloatArray(9)
            LauncherTiltAffine.compose(m,out,i/100f,2f,1.5f,600f,1300f,direction)
            assertTrue(out.all {it.isFinite()});assertTrue(det(out)/det(m)>.64f)
            assertEquals(0f,out[6],0f);assertEquals(0f,out[7],0f);assertEquals(1f,out[8],0f)
        }
    }
    @Test fun closeBanksInOppositeDirectionWithoutChangingItsCentre() {
        val m=native(.4f);val a=composed(m,.5f,1f);val b=composed(m,.5f,-1f)
        assertTrue(abs(a[1]-b[1])>.02f)
        assertArrayEquals(point(a,600f,1300f),point(b,600f,1300f),.001f)
    }
    @Test fun scopeIsPerTargetAndOwnerRatherThanTransactionWide() {
        val scope=LauncherTiltAffineScope();val owner=Any();val target=Any()
        assertNull(scope.pose(owner,target))
        val result=scope.run(owner,target,{pose(.3f)}) {
            assertEquals(.3f,scope.pose(owner,target)!!.progress,0f)
            assertNull(scope.pose(Any(),target));assertNull(scope.pose(owner,Any()));"native-return"
        }
        assertEquals("native-return",result);assertNull(scope.pose(owner,target))
    }
    @Test fun nestedCallbackAndHostExceptionRestoreOuterScopeWithoutReplayingHost() {
        val scope=LauncherTiltAffineScope();val owner=Any();val target=Any();val child=Any();var calls=0
        scope.run(owner,target,{pose(.3f)}) {
            try {scope.run(owner,child,{pose(.7f)}) {
                calls++;assertNull(scope.pose(owner,target));throw IllegalStateException("native-failure")
            }} catch(_:IllegalStateException) {}
            assertEquals(.3f,scope.pose(owner,target)!!.progress,0f)
        }
        assertEquals(1,calls);assertNull(scope.pose(owner,target));assertNull(scope.pose(owner,child))
    }
    @Test fun nativeProgressIsReadAtTheWriteAndHasNoFixedTimeout() {
        val scope=LauncherTiltAffineScope();val owner=Any();val target=Any();var p=.2f
        scope.run(owner,target,{pose(p)}) {
            assertEquals(.2f,scope.pose(owner,target)!!.progress,0f)
            p=.8f;assertEquals(.8f,scope.pose(owner,target)!!.progress,0f)
        }
        assertNull(scope.pose(owner,target))
    }
    @Test fun scopeNeverLeaksAcrossAnimationThreads() {
        val scope=LauncherTiltAffineScope();val owner=Any();val target=Any();var leaked=false
        scope.run(owner,target,{pose(.5f)}) {
            val t=Thread {leaked=scope.pose(owner,target)!=null};t.start();t.join()
        }
        assertFalse(leaked)
    }
    @Test fun nativeCancelFollowedByNewNativeWriteNeedsNoIndependentReset() {
        val scope=LauncherTiltAffineScope();val owner=Any();val target=Any()
        scope.run(owner,target,{pose(.5f)}) {assertNotNull(scope.pose(owner,target))}
        val next=native(.77f);val output=next.copyOf()
        assertNull(scope.pose(owner,target));assertArrayEquals(next,output,0f)
    }
    @Test(expected=IllegalArgumentException::class) fun aliasingCannotOverwriteSharedNativeInput() {
        val m=native(.4f);LauncherTiltAffine.compose(m,m,.5f,1f,1f,600f,1300f,1f)
    }
}
