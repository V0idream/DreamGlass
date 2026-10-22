package com.dreamglass.cos17;

import com.coloros16.liquidglass.hook.NativeDrawScope;
import com.coloros16.liquidglass.hook.NativeScenePolicy;
import com.coloros16.liquidglass.hook.BlurSceneRegistry;
import com.coloros16.liquidglass.hook.VolumePanelGuard;
import org.junit.Test;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

public class NativeVolumeTest {
    private static final class HostSupplier {
        private final String identity;
        HostSupplier(String identity) { this.identity = identity; }
        public String invoke() { return identity; }
    }
    private static final class PlatformWrapper {
        private final Object getHostViewName;
        PlatformWrapper(String identity) { getHostViewName = new HostSupplier(identity); }
    }

    @Test public void platformHostIsReadFromCallbackFieldWithoutGetterMethod() {
        assertTrue(VolumePanelGuard.INSTANCE.isVolumeWrapper(new PlatformWrapper("OplusVolumeSeekBar{a1b2}")));
        assertFalse(VolumePanelGuard.INSTANCE.isVolumeWrapper(new PlatformWrapper("OplusQsVerticalSeekBar{a1b2}")));
    }

    @Test public void nativeIdentityWinsEvenWithoutLayerRegistration() {
        Object volume = new Object();
        BlurSceneRegistry registry = BlurSceneRegistry.INSTANCE;
        registry.preserveNative(volume);
        assertFalse(registry.isGlassable(volume));
        registry.register(volume, 77, "media/quick_settings", null);
        assertFalse(registry.isGlassable(volume));
        Object mixedLayer = new Object();
        registry.register(mixedLayer, 78, "media/VolumeDialog", null);
        assertFalse(registry.isGlassable(mixedLayer));
        assertTrue(registry.isGlassable(new Object()));
    }

    @Test public void volumeWinsOverMediaOrNotificationInLayerIdentity() {
        assertTrue(NativeScenePolicy.INSTANCE.requiresNative("media/VolumeDialog#progress"));
        assertTrue(NativeScenePolicy.INSTANCE.requiresNative("NotificationShade/volume_dialog"));
        assertTrue(NativeScenePolicy.INSTANCE.isVolume("com.oplus.systemui.volume.OplusVolumeSeekBar{abcdef}"));
        assertTrue(NativeScenePolicy.INSTANCE.isVolume("OplusVolumeSideAccessoryButton"));
        assertFalse(NativeScenePolicy.INSTANCE.requiresNative("OplusQsVerticalSeekBar"));
        assertFalse(NativeScenePolicy.INSTANCE.requiresNative("quick_settings/media"));
    }

    @Test public void nestedNativeDrawAndExceptionDoNotLeakIntoNextTile() {
        NativeDrawScope scope = NativeDrawScope.INSTANCE;
        assertFalse(scope.isActive());
        try {
            scope.runNative(() -> {
                assertTrue(scope.isActive());
                scope.runNative(() -> { assertTrue(scope.isActive()); return null; });
                assertTrue(scope.isActive());
                throw new IllegalStateException("host draw failed");
            });
            fail("Expected host exception");
        } catch (IllegalStateException expected) {
            assertEquals("host draw failed", expected.getMessage());
        }
        assertFalse(scope.isActive());
    }

    @Test public void nativeScopeDoesNotAffectAnotherRenderThread() throws Exception {
        NativeDrawScope.INSTANCE.runNative(() -> {
            AtomicBoolean activeInOtherThread = new AtomicBoolean(true);
            Thread other = new Thread(() -> activeInOtherThread.set(NativeDrawScope.INSTANCE.isActive()));
            other.start();
            try { other.join(); } catch (InterruptedException e) { throw new AssertionError(e); }
            assertFalse(activeInOtherThread.get());
            return null;
        });
        assertFalse(NativeDrawScope.INSTANCE.isActive());
    }
}
