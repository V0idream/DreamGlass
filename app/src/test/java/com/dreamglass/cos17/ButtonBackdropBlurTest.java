package com.dreamglass.cos17;

import com.coloros16.liquidglass.hook.ButtonBackdropBlur;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public class ButtonBackdropBlurTest {
    private final ButtonBackdropBlur blur = ButtonBackdropBlur.INSTANCE;

    @Test public void strengthUsesOriginalNativeRadiusAndDoesNotCompound() {
        assertEquals(64f, blur.radius(800, 8), 0f);
        assertEquals(800f, blur.radius(800, 100), 0f);
        assertEquals(1600f, blur.radius(800, 300), 0f);
        assertEquals(0f, blur.radius(800, -1), 0f);
        assertEquals(0f, blur.radius(-1, 100), 0f);
        assertEquals(64f, blur.radius(800, 8), 0f);
    }
    @Test public void zeroBlurPreservesEveryPixelAndDoesNotMutateInput() {
        int[] input = {0x00ff0000, 0x80402010, 0xff123456};
        int[] copy = input.clone();
        int[] output = blur.apply(input, 3, 1, 0);
        assertArrayEquals(copy, output);
        assertArrayEquals(copy, input);
        assertNotSame(input, output);
    }
    @Test public void flatBackdropAndClampedEdgesStayFlatForLargeRadii() {
        for (int width : new int[]{1, 2, 19}) {
            int[] pixels = new int[width * 7]; Arrays.fill(pixels, 0xff2468ac);
            assertArrayEquals(pixels, blur.apply(pixels, width, 7, 100));
        }
    }
    @Test public void blurSpreadsFineDetailWithoutFadingOpaqueCoverage() {
        int[] pixels = new int[65 * 65]; Arrays.fill(pixels, 0xff000000);
        pixels[32 * 65 + 32] = 0xffffffff;
        int[] output = blur.apply(pixels, 65, 65, 2);
        assertTrue((output[32 * 65 + 32] & 255) < 255);
        assertTrue((output[32 * 65 + 33] & 255) > 0);
        for (int p : output) assertEquals(255, p >>> 24);
        assertEquals(0xffffffff, pixels[32 * 65 + 32]);
    }
    @Test public void strongerBlurSuppressesHighFrequencyBackdropVariation() {
        int width = 97, height = 31;
        int[] pixels = new int[width * height];
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++)
            pixels[y * width + x] = (x / 6 % 2 == 0) ? 0xff000000 : 0xffffffff;
        int[] light = blur.apply(pixels, width, height, 1);
        int[] strong = blur.apply(pixels, width, height, 6);
        long lightEnergy = 0, strongEnergy = 0;
        for (int y = 0; y < height; y++) for (int x = 20; x < width - 20; x++) {
            int i = y * width + x;
            lightEnergy += Math.abs((light[i] & 255) - (light[i - 1] & 255));
            strongEnergy += Math.abs((strong[i] & 255) - (strong[i - 1] & 255));
        }
        assertTrue(strongEnergy < lightEnergy);
    }
    @Test public void transparentHiddenColorCannotBleedIntoVisibleBackdrop() {
        int[] pixels = new int[31]; Arrays.fill(pixels, 0x00ff0000);
        pixels[15] = 0xff0000ff;
        int[] output = blur.apply(pixels, 31, 1, 2);
        boolean visible = false;
        for (int p : output) {
            assertEquals(0, p >>> 16 & 255);
            assertEquals(0, p >>> 8 & 255);
            if ((p >>> 24) > 0) { visible = true; assertEquals(255, p & 255); }
        }
        assertTrue(visible);
    }
}
