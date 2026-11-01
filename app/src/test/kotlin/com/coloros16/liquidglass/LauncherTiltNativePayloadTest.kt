package com.coloros16.liquidglass

import com.coloros16.liquidglass.hook.LauncherTiltCompat
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

/** Decode as android::TMat44, the ABI actually consumed by COS17 JNI. */
class LauncherTiltNativePayloadTest {
    private fun point(m:FloatArray,x:Float,y:Float):FloatArray {
        val w=m[3]*x+m[7]*y+m[15]
        assertTrue("Visible native window must remain in front of the projection plane",w>0f)
        return floatArrayOf((m[0]*x+m[4]*y+m[12])/w,(m[1]*x+m[5]*y+m[13])/w)
    }
    @Test fun nativeAbiPreservesPivotAndProducesVisiblePerspective() {
        for(direction in floatArrayOf(-1f,1f)) {
            val m=FloatArray(16)
            assertTrue(LauncherTiltCompat.nativeProjection(m,.5f,1f,1f,720f,1600f,3200f,direction))
            val pivot=point(m,720f,1600f)
            assertEquals(720f,pivot[0],.002f);assertEquals(1600f,pivot[1],.002f)
            val tl=point(m,0f,0f);val tr=point(m,1440f,0f)
            val bl=point(m,0f,3200f);val br=point(m,1440f,3200f)
            assertTrue(abs((tr[0]-tl[0])-(br[0]-bl[0]))>100f)
            assertTrue((tr[0]-tl[0])>0f && (br[0]-bl[0])>0f)
        }
    }
    @Test fun maximumSettingsNeverCrossProjectionPlane() {
        val m=FloatArray(16)
        for(i in 1..99)for(direction in floatArrayOf(-1f,1f)) {
            assertTrue(LauncherTiltCompat.nativeProjection(m,i/100f,2f,1.5f,720f,1600f,3200f,direction))
            for(x in floatArrayOf(0f,720f,1440f))for(y in floatArrayOf(0f,1600f,3200f))
                assertTrue(point(m,x,y).all { it.isFinite() })
        }
    }
    @Test fun endpointsAndInvalidInputKeepNativeIdentity() {
        val identity=FloatArray(16).also { it[0]=1f;it[5]=1f;it[10]=1f;it[15]=1f }
        for(p in floatArrayOf(0f,1f,Float.NaN)) {
            val m=FloatArray(16)
            assertFalse(LauncherTiltCompat.nativeProjection(m,p,1f,1f,720f,1600f,3200f,1f))
            assertArrayEquals(identity,m,0f)
        }
    }
    @Test fun payloadMatchesMathematicalProjectionUnderNativeDecoding() {
        val math=FloatArray(16);val native=FloatArray(16)
        assertTrue(LauncherTiltCompat.projection(math,.37f,1.3f,.8f,500f,900f,1800f,1f))
        assertTrue(LauncherTiltCompat.nativeProjection(native,.37f,1.3f,.8f,500f,900f,1800f,1f))
        for(x in floatArrayOf(0f,123f,1000f))for(y in floatArrayOf(0f,456f,1800f)) {
            val w=math[12]*x+math[13]*y+math[15];val actual=point(native,x,y)
            assertEquals((math[0]*x+math[1]*y+math[3])/w,actual[0],.001f)
            assertEquals((math[4]*x+math[5]*y+math[7])/w,actual[1],.001f)
        }
    }
}
