package com.dreamglass.cos17;

import com.coloros16.liquidglass.hook.BackgroundContrastMap;
import com.coloros16.liquidglass.hook.LocalTextContrast;
import com.coloros16.liquidglass.hook.QsSurfacePolicy;
import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;

public class LocalTextContrastTest {
    private final LocalTextContrast math = LocalTextContrast.INSTANCE;

    @Test public void adjacentButtonsCanChooseOppositeColorsOnOneFrame() {
        int[] pixels = new int[128 * 64];
        for (int y=0; y<64; y++) for (int x=0; x<128; x++)
            pixels[y*128+x] = x<64 ? 0xff101010 : 0xfff0f0f0;
        BackgroundContrastMap map = BackgroundContrastMap.fromPixels(pixels,128,64);
        assertEquals(0xffffffff,math.readableColor(0xffffffff,map.mean(0,.1f,.5f,.9f)));
        assertEquals(0xff000000,math.readableColor(0xffffffff,map.mean(.5f,.1f,1,.9f)));
    }
    @Test public void localSamplingFollowsVerticalRegionsAndClipsAtScreenEdges() {
        int[] pixels = new int[64*128];
        Arrays.fill(pixels,0,64*64,0xffffffff);
        Arrays.fill(pixels,64*64,pixels.length,0xff000000);
        BackgroundContrastMap map = BackgroundContrastMap.fromPixels(pixels,64,128);
        assertEquals(1,map.mean(-1,-1,1,.5f),1e-9);
        assertEquals(0,map.mean(0,.5f,2,2),1e-9);
        assertNull(map.mean(2,0,3,1));
        assertNull(map.mean(.5f,.1f,.2f,.9f));
    }
    @Test public void partialCellAndFractionalRegionsHaveStableAverages() {
        int[] pixels = new int[8*8]; Arrays.fill(pixels,0xff808080);
        BackgroundContrastMap map = BackgroundContrastMap.fromPixels(pixels,8,8);
        assertEquals(math.luminance(0xff808080), map.mean(.113f,.227f,.783f,.926f),1e-8);
        assertEquals(math.luminance(0xff808080),map.mean(0,0,1,1),1e-8);
    }
    @Test public void invisibleOrInvalidBackgroundNeverInventsAColor() {
        BackgroundContrastMap map = BackgroundContrastMap.fromPixels(new int[16],4,4);
        assertNull(map.mean(0,0,1,1));
        assertNull(map.mean(Float.NaN,0,1,1));
        assertEquals(0x99123456,math.readableColor(0x99123456,Double.NaN));
        assertThrows(IllegalArgumentException.class,()->BackgroundContrastMap.fromPixels(new int[0],0,1));
    }
    @Test public void colorChoiceUsesLinearRgbAndPreservesNativeAlpha() {
        assertEquals(0.0,math.luminance(0xff000000),1e-12);
        assertEquals(1.0,math.luminance(0xffffffff),1e-12);
        assertEquals(0.7152,math.luminance(0xff00ff00),1e-12);
        assertEquals(0xbb000000,math.readableColor(0xbb808080,.5));
        assertEquals(0x88ffffff,math.readableColor(0x88808080,.02));
        // Relative luminance 0.18 is where black has slightly higher contrast than white.
        assertEquals(0xff000000,math.readableColor(-1,.18));
        assertEquals(0xffffffff,math.readableColor(-1,.17));
    }
    @Test public void transparencyScopeDoesNotLeakIntoOtherScenes() {
        QsSurfacePolicy p=QsSurfacePolicy.INSTANCE;
        assertTrue(p.buttonMaterial("OplusQSResizeableTileViewTwoXOne"));
        assertTrue(p.buttonMaterial("com.oplus.systemui.plugins.qs.customize.view.tile.OplusQSIconView"));
        for (String name: new String[]{null,"OplusQSRootView","OplusQsVerticalSeekBar",
                "NotificationBackgroundView","com.android.launcher.Folder","OplusVolumeSeekBar"})
            assertFalse(name,p.buttonMaterial(name));
        assertTrue(p.excludesButtonOpacity("OplusQsVerticalSeekBar"));
        assertTrue(p.excludesButtonOpacity("com.oplus.systemui.volume.OplusVolumeDialogView"));
        assertTrue(p.excludesButtonOpacity("OplusMediaCarouselView"));
        assertFalse(p.excludesButtonOpacity("OplusQSResizeableTileViewOneXOne"));
    }
}
