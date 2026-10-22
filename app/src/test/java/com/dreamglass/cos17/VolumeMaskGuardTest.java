package com.dreamglass.cos17;

import com.coloros16.liquidglass.hook.VolumeMaskGuard;
import org.junit.Test;
import static org.junit.Assert.*;

public class VolumeMaskGuardTest {
    @Test public void independentVolumeClassesAreRecognized() {
        assertTrue(VolumeMaskGuard.INSTANCE.isVolumeClass("com.oplus.systemui.volume.OplusVolumeSeekBar"));
        assertTrue(VolumeMaskGuard.INSTANCE.isVolumeClass("com.oplus.systemui.volume.view.OplusVolumeDialogView"));
        assertTrue(VolumeMaskGuard.INSTANCE.isVolumeClass("com.android.systemui.volume.VolumeDialogImpl"));
    }
    @Test public void quickSettingsVolumeAndSharedLabelsDoNotPreserveMask() {
        assertFalse(VolumeMaskGuard.INSTANCE.isVolumeClass("com.oplus.systemui.plugins.qs.widget.OplusQsVerticalSeekBar"));
        assertFalse(VolumeMaskGuard.INSTANCE.isVolumeClass("ImageView#qs_volume_seekbar"));
        assertFalse(VolumeMaskGuard.INSTANCE.isVolumeClass("NotificationShade/VolumeDialog"));
    }
}
