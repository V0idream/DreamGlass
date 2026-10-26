package com.dreamglass.cos17;

import com.coloros16.liquidglass.glass.IosGlassPreset;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class IosGlassPresetTest {
    private final IosGlassPreset preset=IosGlassPreset.INSTANCE;
    @Test public void builtinIsOptInAndUnknownIdsKeepOriginalParameters() {
        assertNull(preset.resolve(null,true,null));
        assertNull(preset.resolve("default",true,null));
        assertNull(preset.resolve("unknown",true,null));
        assertEquals("IOS风",IosGlassPreset.NAME);
    }
    @Test public void launcherAndOtherUnpolishedSurfacesDoNotReceiveTheOverlay() {
        Map<String,Float> custom=Collections.singletonMap("depth",4f);
        assertSame(custom,preset.resolve(IosGlassPreset.ID,false,custom));
        assertNull(preset.resolve(IosGlassPreset.ID,false,null));
    }
    @Test public void returningToDefaultRestoresTheSameCustomMapWithoutMutation() {
        Map<String,Float> custom=new HashMap<>();custom.put("depth",4f);
        Map<String,Float> before=new HashMap<>(custom);
        preset.resolve(IosGlassPreset.ID,true,custom);
        assertEquals(before,custom);assertSame(custom,preset.resolve("default",true,custom));
    }
    @Test public void explicitClassParametersOverrideBuiltinValuesButInheritOtherKeys() {
        Map<String,Float> custom=Collections.singletonMap("blur_radius",0f);
        Map<String,Float> effective=preset.resolve(IosGlassPreset.ID,true,custom);
        assertEquals(0,effective.get("blur_radius"),0);
        assertEquals(36,effective.get("refraction_amount"),0);
        assertEquals(8,preset.getValues().get("blur_radius"),0);
    }
    @Test public void builtinUsesFiniteRestrainedOpticsAndNoArtificialGlowOrDispersion() {
        for(float v:preset.getValues().values())assertTrue(Float.isFinite(v));
        assertTrue(preset.getValues().get("vibrancy")>=1f && preset.getValues().get("vibrancy")<1.3f);
        assertEquals(0,preset.getValues().get("inner_glow"),0);
        assertEquals(0,preset.getValues().get("dispersion"),0);
        assertTrue(preset.getValues().get("refraction_height_ratio")<.35f);
    }
}
