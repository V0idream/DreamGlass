package com.dreamglass.cos17;

import com.coloros16.liquidglass.hook.QsSurfacePolicy;
import org.junit.Test;
import static org.junit.Assert.*;

public class ButtonBlurPolicyTest {
    private final QsSurfacePolicy policy = QsSurfacePolicy.INSTANCE;

    @Test public void sliderChangesBlurRadiusAndClampsOnlyItsUpperBound() {
        assertEquals(0f, policy.resolveButtonBlur(0f, 13f, true), 0f);
        assertEquals(32f, policy.resolveButtonBlur(32f, 13f, true), 0f);
        assertEquals(64f, policy.resolveButtonBlur(64f, 13f, true), 0f);
        assertEquals(64f, policy.resolveButtonBlur(1000f, 13f, true), 0f);
    }
    @Test public void absentOrInvalidSettingKeepsExistingMaterialBlur() {
        for (float inherited : new float[]{0f, 13f, 30f}) {
            for (float value : new float[]{-1f, -10f, Float.NaN, Float.POSITIVE_INFINITY})
                assertEquals(inherited, policy.resolveButtonBlur(value, inherited, true), 0f);
        }
    }
    @Test public void nonButtonsKeepTheirOwnBlurIncludingZero() {
        for (float configured : new float[]{0f, 32f, 64f}) {
            assertEquals(0f, policy.resolveButtonBlur(configured, 0f, false), 0f);
            assertEquals(22f, policy.resolveButtonBlur(configured, 22f, false), 0f);
        }
    }
    @Test public void fractionalValuesArePixelRadiiNotAlphaFractions() {
        assertEquals(.5f, policy.resolveButtonBlur(.5f, 13f, true), 0f);
        assertEquals(1f, policy.resolveButtonBlur(1f, 13f, true), 0f);
        assertEquals(9.5f, policy.resolveButtonBlur(9.5f, 13f, true), 0f);
    }
}
