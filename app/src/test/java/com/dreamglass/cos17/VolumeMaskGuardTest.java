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
    @Test public void actualInnerDrawableIsProtectedAlongWithTheWrapper() {
        Object wrapper = new Object(), target = new Object(), unrelated = new Object();
        VolumeMaskGuard.INSTANCE.protectNativeContent(wrapper, target);
        assertTrue(VolumeMaskGuard.INSTANCE.preservesContent(target));
        assertTrue(VolumeMaskGuard.INSTANCE.preserveMask(wrapper));
        assertFalse(VolumeMaskGuard.INSTANCE.preservesContent(wrapper));
        assertFalse(VolumeMaskGuard.INSTANCE.preservesContent(unrelated));
    }
    @Test public void replacementNativeContentIsRegisteredIndependently() {
        Object wrapper = new Object(), first = new Object(), replacement = new Object();
        VolumeMaskGuard.INSTANCE.protectNativeContent(wrapper, first);
        VolumeMaskGuard.INSTANCE.protectNativeContent(wrapper, replacement);
        assertTrue(VolumeMaskGuard.INSTANCE.preservesContent(first));
        assertTrue(VolumeMaskGuard.INSTANCE.preservesContent(replacement));
    }
    @Test public void earlyUnknownOwnershipDoesNotPreventLaterProtection() {
        Object wrapper = new Object(), target = new Object();
        assertFalse(VolumeMaskGuard.INSTANCE.preserveMask(wrapper));
        VolumeMaskGuard.INSTANCE.protectNativeContent(wrapper, target);
        assertTrue(VolumeMaskGuard.INSTANCE.preserveMask(wrapper));
        assertTrue(VolumeMaskGuard.INSTANCE.preservesContent(target));
    }
}
