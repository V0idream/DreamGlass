package com.dreamglass.cos17;

import com.coloros16.liquidglass.hook.QsSurfacePolicy;
import org.junit.Test;
import static org.junit.Assert.*;

public class QsSurfacePolicyTest {
    private final QsSurfacePolicy policy = QsSurfacePolicy.INSTANCE;
    private static final String TILE = "com.oplus.systemui.plugins.qs.customize.view.tile.";
    private static class NativeSliderBase {
        private float mCurProgressRadius = 39f;
        private float mBackgroundRoundCornerWeight = 1.1f;
    }
    private static class NativeSlider extends NativeSliderBase { }

    @Test public void onlyWideTileOwnsHiddenIconBackground() {
        assertTrue(policy.isWideTile(TILE + "OplusQSResizeableTileViewTwoXOne"));
        assertFalse(policy.isWideTile(TILE + "OplusQSResizeableTileViewOneXOne"));
        assertFalse(policy.isWideTile(TILE + "OplusQSIconView"));
        assertFalse(policy.isWideTile("other.OplusQSResizeableTileViewTwoXOne"));
    }
    @Test public void controlsAndNotificationCardsUsePolishedFinish() {
        assertTrue(policy.polishMaterial(TILE + "OplusQSIconView"));
        assertTrue(policy.polishMaterial("com.oplus.systemui.qs.base.seek.OplusQsVerticalSeekBar"));
        assertTrue(policy.polishMaterial("com.android.systemui.statusbar.notification.row.NotificationBackgroundView"));
        assertTrue(policy.polishMaterial("OplusQSResizeableTileViewTwoXOne"));
        assertTrue(policy.polishMaterial("OplusQsVerticalSeekBar"));
        assertTrue(policy.polishMaterial("NotificationBackgroundView"));
    }
    @Test public void otherScenesRetainTheirMaterial() {
        for (String name : new String[]{null, "com.android.launcher.Folder", "com.android.systemui.scrim.ScrimView",
                "com.oplus.systemui.volume.OplusVolumeSeekBar", "com.android.keyguard.Clock"}) {
            assertFalse(policy.polishMaterial(name));
        }
    }
    @Test public void dynamicGeometryIsReadFromNativeSuperclass() throws Exception {
        NativeSlider view = new NativeSlider();
        assertEquals(39f, policy.field(NativeSlider.class, "mCurProgressRadius").getFloat(view), 0f);
        assertEquals(1.1f, policy.field(NativeSlider.class, "mBackgroundRoundCornerWeight").getFloat(view), 0f);
    }
    @Test public void missingGeometryFailsExplicitlyInsteadOfInventingRadius() {
        assertThrows(NoSuchFieldException.class, () -> policy.field(NativeSlider.class, "unknownRadius"));
    }
}
