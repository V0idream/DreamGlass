package com.dreamglass.cos17;

import com.coloros16.liquidglass.hook.VolumeMaskGuard;
import com.coloros16.liquidglass.hook.VolumeNativeBlurPolicy;
import org.junit.Test;
import static org.junit.Assert.*;

public class VolumeNativeBlurPolicyTest {
    @Test public void highNativeBlurIsCappedAndFadesAreNotRaised() {
        assertEquals(16, VolumeNativeBlurPolicy.INSTANCE.limitRadius(800));
        assertEquals(16, VolumeNativeBlurPolicy.INSTANCE.limitRadius(32));
        assertEquals(12, VolumeNativeBlurPolicy.INSTANCE.limitRadius(12));
        assertEquals(0, VolumeNativeBlurPolicy.INSTANCE.limitRadius(0));
        assertEquals(-1, VolumeNativeBlurPolicy.INSTANCE.limitRadius(-1));
    }

    @Test public void nativeCopyRetainsMaskCaptureAndMixProperties() {
        Wrapper wrapper = new Wrapper();
        VolumeMaskGuard.INSTANCE.protectNativeContent(wrapper, wrapper.drawable);
        assertTrue(VolumeNativeBlurPolicy.INSTANCE.reduceNativeRadius(wrapper));
        assertEquals(16, wrapper.drawable.current.radius);
        assertEquals(0x55123456, wrapper.drawable.current.mask);
        assertEquals("expanded-volume-region", wrapper.drawable.current.capture);
        assertEquals(17, wrapper.drawable.current.mix);
        assertEquals(800, wrapper.drawable.original.radius);
        assertEquals(1, wrapper.drawable.commits);
        assertTrue(VolumeMaskGuard.INSTANCE.preserveMask(wrapper));
        assertTrue(VolumeMaskGuard.INSTANCE.preservesContent(wrapper.drawable));
    }

    @Test public void repeatedChecksDoNotCommitOrReduceAgain() {
        Wrapper wrapper = new Wrapper();
        VolumeMaskGuard.INSTANCE.protectNativeContent(wrapper, wrapper.drawable);
        assertTrue(VolumeNativeBlurPolicy.INSTANCE.reduceNativeRadius(wrapper));
        assertFalse(VolumeNativeBlurPolicy.INSTANCE.reduceNativeRadius(wrapper));
        assertEquals(1, wrapper.drawable.commits);
    }

    @Test public void ordinarySystemUiWrapperIsNeverChanged() {
        Wrapper unrelated = new Wrapper();
        assertFalse(VolumeNativeBlurPolicy.INSTANCE.reduceNativeRadius(unrelated));
        assertEquals(800, unrelated.drawable.current.radius);
        assertEquals(0, unrelated.drawable.commits);
    }

    @Test public void unsupportedNativeAbiPreservesNativeState() {
        Object incompatible = new Object();
        VolumeMaskGuard.INSTANCE.protectNativeContent(incompatible, new Object());
        assertFalse(VolumeNativeBlurPolicy.INSTANCE.reduceNativeRadius(incompatible));
    }

    @Test public void zeroRadiusDoesNotWakeNativeBlur() {
        Wrapper wrapper = new Wrapper();
        wrapper.drawable.current.radius = 0;
        VolumeMaskGuard.INSTANCE.protectNativeContent(wrapper, wrapper.drawable);
        assertFalse(VolumeNativeBlurPolicy.INSTANCE.reduceNativeRadius(wrapper));
        assertEquals(0, wrapper.drawable.commits);
    }

    public static class Wrapper {
        final NativeDrawable drawable = new NativeDrawable();
        public NativeDrawable getBlurDrawable() { return drawable; }
    }
    public static class NativeDrawable {
        final NativeParams original = new NativeParams();
        NativeParams current = original;
        int commits;
        public NativeParams getBlurParams() { return new NativeParams(current); }
        public void setBlurParamsSync(NativeParams value) { current = value; commits++; }
    }
    public static class NativeParams {
        int radius = 800, mask = 0x55123456, mix = 17;
        String capture = "expanded-volume-region";
        NativeParams() {}
        NativeParams(NativeParams source) {
            radius = source.radius; mask = source.mask; mix = source.mix; capture = source.capture;
        }
        public int getBlurRadius() { return radius; }
        public void setBlurRadius(int value) { radius = value; }
    }
}
