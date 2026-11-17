package com.coloros16.liquidglass

import com.coloros16.liquidglass.hook.IconGlassMotionPolicy
import org.junit.Assert.*
import org.junit.Test

class IconGlassMotionPolicyTest {
    @Test fun parentPageScrollChangesSamplingEvenWhenChildLayoutIsUnchanged() {
        assertTrue(IconGlassMotionPolicy.changed(100f,200f,164f,264f,82f,200f,146f,264f))
    }
    @Test fun ancestorZoomChangesSamplingWithoutChildTranslation() {
        assertTrue(IconGlassMotionPolicy.changed(100f,200f,164f,264f,98f,198f,166f,266f))
    }
    @Test fun idleAndSubpixelNoiseDoNotRequestAnotherFrame() {
        assertFalse(IconGlassMotionPolicy.changed(100f,200f,164f,264f,100f,200f,164f,264f))
        assertFalse(IconGlassMotionPolicy.changed(100f,200f,164f,264f,100.1f,200f,164.1f,264f))
    }
    @Test fun firstValidFrameIsDirtyButHiddenAndInvalidBoundsAreNot() {
        assertTrue(IconGlassMotionPolicy.changed(Float.NaN,0f,0f,0f,100f,200f,164f,264f))
        assertFalse(IconGlassMotionPolicy.changed(0f,0f,1f,1f,Float.NaN,0f,1f,1f))
        assertFalse(IconGlassMotionPolicy.changed(0f,0f,1f,1f,0f,0f,0f,0f))
    }
}
