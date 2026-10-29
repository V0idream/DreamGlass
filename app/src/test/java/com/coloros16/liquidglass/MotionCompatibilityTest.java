package com.coloros16.liquidglass;

import android.view.View;
import com.coloros16.liquidglass.hook.NotificationMotionContract;
import com.coloros16.liquidglass.hook.LauncherTiltCompat;
import com.coloros16.liquidglass.hook.NotificationTiltCompat;
import org.junit.Test;
import static org.junit.Assert.*;

public class MotionCompatibilityTest {
    // Signatures from supplied stock SystemUI 17.99.02; no Android View is instantiated.
    public static class Stock17Swipe {
        public void onDownUpdate(View v) {}
        public void dismiss(View v, float velocity) {}
        public void snapChild(float x, float velocity, View v) {}
        public void onChildSnappedBack(float x, View v) {}
        public void snapOpen(View v, int slot, float velocity) {}
        public void forceResetSwipeState(View v) {}
        public void onSwipeOutAnimStart(View v) {}
        public void onChildDismissed(View v) {}
        public void onChildNotDismissed(View v, boolean cancelled, boolean removed) {}
        public void dismiss(String unrelated) {}
        public void onTouchEvent(Object event) {}
    }
    public static class LegacySwipe {
        public void snapChild(View v, float x, float velocity) {}
        public void dismissChild(View v, float velocity, boolean animate) {}
    }
    @Test public void stock17RoutesFindReorderedViewAndIgnoreUnrelatedMethods() {
        var routes=NotificationMotionContract.INSTANCE.routes(Stock17Swipe.class);
        assertEquals(8,routes.size());
        var snap=routes.stream().filter(r -> r.getMethod().getName().equals("snapChild")).findFirst().get();
        assertEquals(NotificationMotionContract.Event.COMMIT,routes.stream().filter(r -> r.getMethod().getName().equals("onSwipeOutAnimStart")).findFirst().get().getEvent());
        assertEquals(NotificationMotionContract.Event.ABORT,routes.stream().filter(r -> r.getMethod().getName().equals("onChildNotDismissed")).findFirst().get().getEvent());
        assertEquals(2,snap.getIndex());assertEquals(NotificationMotionContract.Event.CANCEL,snap.getEvent());
        assertEquals(NotificationMotionContract.Event.PREPARE,routes.stream().filter(r -> r.getMethod().getName().equals("onDownUpdate")).findFirst().get().getEvent());
        assertEquals(NotificationMotionContract.Event.REQUEST,routes.stream().filter(r -> r.getMethod().getName().equals("dismiss")).findFirst().get().getEvent());
    }
    @Test public void legacyAndSubclassDeclarationsRemainSupported() {
        var routes=NotificationMotionContract.INSTANCE.routes(LegacySwipe.class);
        assertEquals(2,routes.size());assertTrue(routes.stream().allMatch(r -> r.getIndex()==0));
    }
    @Test public void tiltEndpointsAreIdentityAndMidpointHasPerspective() {
        float[] m=new float[16];
        for(float p:new float[]{0,1,-1,2}) {
            assertFalse(LauncherTiltCompat.INSTANCE.projection(m,p,1,1,500,1000,2000,1));
            for(int i=0;i<16;i++) assertEquals(i%5==0?1:0,m[i],0);
        }
        assertTrue(LauncherTiltCompat.INSTANCE.projection(m,.5f,1,1,500,1000,2000,1));
        assertTrue(m[13]>0);assertTrue(m[5]!=1);
        float w=m[13]*1000+m[15];
        assertEquals(500,(m[0]*500+m[1]*1000+m[3])/w,.001);
        assertEquals(1000,(m[4]*500+m[5]*1000+m[7])/w,.001);
    }
    @Test public void invalidAndZeroStrengthDoNotWarpWindows() {
        float[] m=new float[16];
        assertFalse(LauncherTiltCompat.INSTANCE.projection(m,.5f,0,1,500,1000,2000,1));
        assertFalse(LauncherTiltCompat.INSTANCE.projection(m,Float.NaN,1,1,500,1000,2000,1));
        assertFalse(LauncherTiltCompat.INSTANCE.projection(m,.5f,1,1,500,1000,0,1));
        assertFalse(LauncherTiltCompat.INSTANCE.projection(m,.5f,1,1,Float.POSITIVE_INFINITY,1000,2000,1));
    }
    @Test public void projectionHasNoSingularityAcrossSupportedRanges() {
        float[] m=new float[16];
        for(int p=0;p<=100;p++)for(int strength=0;strength<=2;strength++)for(float direction:new float[]{-1,1}) {
            LauncherTiltCompat.INSTANCE.projection(m,p/100f,strength,1.5f,500,1000,2000,direction);
            for(float v:m)assertTrue(Float.isFinite(v));
            assertTrue(m[15]>0);assertTrue(m[13]*2000+m[15]>0);
        }
    }
    @Test public void notificationEnvelopeSettlesAndRespectsAngleSetting() {
        assertEquals(12,NotificationTiltCompat.INSTANCE.angle(0,true,12),0);
        assertEquals(0,NotificationTiltCompat.INSTANCE.angle(1,true,12),0);
        assertEquals(0,NotificationTiltCompat.INSTANCE.angle(0,false,12),.0001);
        assertEquals(0,NotificationTiltCompat.INSTANCE.angle(1,false,12),.0001);
        assertEquals(-12,NotificationTiltCompat.INSTANCE.angle(.5f,false,12),.0001);
        assertEquals(0,NotificationTiltCompat.INSTANCE.angle(.5f,true,0),0);
        assertEquals(0,NotificationTiltCompat.INSTANCE.angle(Float.NaN,true,12),0);
    }
}
