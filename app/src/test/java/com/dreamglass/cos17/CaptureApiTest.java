package com.dreamglass.cos17;

import com.coloros16.liquidglass.hook.CaptureApi;
import org.junit.Before;
import org.junit.Test;
import java.lang.reflect.Field;
import static org.junit.Assert.*;

public class CaptureApiTest {
    @Before public void resetFamily() throws Exception {
        Field cache = CaptureApi.class.getDeclaredField("resolved");
        cache.setAccessible(true); cache.set(null, null);
    }
    @Test public void api37UsesOneInternalFamilyForRootAndArguments() throws Exception {
        ClassLoader loader = getClass().getClassLoader();
        assertEquals("android.window.ScreenCaptureInternal", CaptureApi.INSTANCE.load("android.window.ScreenCapture", loader).getName());
        assertEquals("android.window.ScreenCaptureInternal$CaptureArgs$Builder", CaptureApi.INSTANCE.load("android.window.ScreenCapture$CaptureArgs$Builder", loader).getName());
        assertEquals("android.window.ScreenCaptureInternal$DisplayCaptureArgs$Builder", CaptureApi.INSTANCE.load("android.window.ScreenCapture$DisplayCaptureArgs$Builder", loader).getName());
    }
    @Test public void olderDeviceFallsBackToTheWholeLegacyFamily() throws Exception {
        ClassLoader olderDevice = new ClassLoader(getClass().getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("android.window.ScreenCaptureInternal")) throw new ClassNotFoundException(name);
                return super.loadClass(name, resolve);
            }
        };
        assertEquals("android.window.ScreenCapture", CaptureApi.INSTANCE.load("android.window.ScreenCapture", olderDevice).getName());
        assertEquals("android.window.ScreenCapture$CaptureArgs$Builder", CaptureApi.INSTANCE.load("android.window.ScreenCapture$CaptureArgs$Builder", olderDevice).getName());
    }
}
