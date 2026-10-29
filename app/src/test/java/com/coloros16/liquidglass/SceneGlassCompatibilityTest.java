package com.coloros16.liquidglass;
import com.coloros16.liquidglass.hook.AppGlassSurfacePolicy;
import com.coloros16.liquidglass.hook.KeyguardGlassPolicy;
import com.coloros16.liquidglass.glass.SharedGlassParams;
import com.coloros16.liquidglass.glass.IosGlassPreset;
import com.coloros16.liquidglass.config.Prefs;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class SceneGlassCompatibilityTest {
    private int role(String name,String id,float w,float h,boolean group,boolean click,boolean controls) {
        return AppGlassSurfacePolicy.INSTANCE.role(name,id,w,h,1080,2400,group,click,controls);
    }
    @Test public void galleryTopAndBottomOuterBarsStayUnframed() {
        assertEquals(0,role("GalleryToolbar","top_action_bar",1080,160,true,false,true));
        assertEquals(0,role("ToolbarBackgroundView","top_action_bar",1080,160,false,false,false));
        assertEquals(0,role("FrameLayout","bottom_toolbar",1080,280,true,false,true));
        assertEquals(0,role("ActionBar","action_bar_group",420,90,true,false,true));
    }
    @Test public void smallToolbarButtonsStillReceiveMaterial() {
        assertEquals(1,role("ToolbarButton","back_button",110,110,true,true,false));
        assertEquals(1,role("ImageButton","share",110,110,false,true,false));
    }
    @Test public void assistantOuterRailAndFullWindowAreExcluded() {
        assertEquals(0,role("FrameLayout/assistant","",1080,1900,true,false,true));
        assertEquals(0,role("Layout/assistant","",1000,180,true,false,true));
    }
    @Test public void sidebarAndAssistantCardsRetainBackdrop() {
        assertEquals(2,role("FrameLayout/sidebar","sidebar_panel",320,1800,true,false,true));
        assertEquals(2,role("Card/assistant","weather_card",500,300,true,false,false));
    }
    @Test public void iosMaterialUsesSameValuesInAppButtons() {
        var p=SharedGlassParams.INSTANCE.resolve(Collections.emptyMap(),IosGlassPreset.ID,null,200,200,100,true);
        assertEquals(8,p.getBlurRadius(),0);assertEquals(36,p.getRefractionAmount(),0);
        assertEquals(.72,p.getHighlight(),.0001);assertEquals(56,p.getRefractionHeight(),.0001);
        assertTrue(p.getSurfacePolish());assertTrue(p.getSourcePreblurred());
    }
    @Test public void classOverrideWinsWithoutReplacingOtherPresetKeys() {
        var p=SharedGlassParams.INSTANCE.resolve(Collections.emptyMap(),IosGlassPreset.ID,Map.of(Prefs.KEY_HIGHLIGHT,.2f),200,200,100,true);
        assertEquals(.2,p.getHighlight(),.0001);assertEquals(36,p.getRefractionAmount(),0);
    }
    @Test public void inheritedButtonBlurAndExplicitButtonBlurStayDistinct() {
        var values=Map.of(Prefs.KEY_BLUR_RADIUS,17f,Prefs.KEY_BUTTON_BLUR_RADIUS,-1f);
        assertEquals(17,SharedGlassParams.INSTANCE.resolve(values,"default",null,200,200,100,true).getBlurRadius(),0);
        values=Map.of(Prefs.KEY_BLUR_RADIUS,17f,Prefs.KEY_BUTTON_BLUR_RADIUS,3f);
        assertEquals(3,SharedGlassParams.INSTANCE.resolve(values,"default",null,200,200,100,true).getBlurRadius(),0);
        assertEquals(17,SharedGlassParams.INSTANCE.resolve(values,"default",null,200,200,100,false).getBlurRadius(),0);
    }
    @Test public void pluginClockCanBeCanvasViewInsteadOfTextView() {
        assertEquals(1,KeyguardGlassPolicy.INSTANCE.role(List.of("vendor.theme.ClockCanvas"),"",false,false,false,1,1,true));
    }
    @Test public void pluginWidgetHintIncludesUnknownCardNamesButNotWholeColumn() {
        assertEquals(2,KeyguardGlassPolicy.INSTANCE.role(List.of("vendor.theme.Card"),"unknown",false,true,true,1,2,false));
        assertEquals(0,KeyguardGlassPolicy.INSTANCE.role(List.of("vendor.theme.Column"),"unknown",false,true,false,.2f,2,false));
        assertEquals(0,KeyguardGlassPolicy.INSTANCE.role(List.of("android.widget.TextView","com.oplus.keyguard.ui.OplusKeyguardStyleClock"),"weather_label",true,false,false,1,2,false));
        assertEquals(0,KeyguardGlassPolicy.INSTANCE.role(List.of("android.widget.TextView","com.oplus.keyguard.ui.OplusKeyguardStyleClock"),"weather_label",true,false,false,1,0,false));
    }
    @Test public void trustedPluginStillCannotOverrideAodOrSecurityScope() {
        for(String name:new String[]{"com.vendor.aod.Clock","com.vendor.security.Pad","com.vendor.bouncer.Clock"})
            assertEquals(0,KeyguardGlassPolicy.INSTANCE.role(List.of(name),"clock",false,false,false,1,1,true));
        assertEquals(0,KeyguardGlassPolicy.INSTANCE.role(List.of("vendor.theme.Clock"),"fingerprint",false,false,false,1,1,true));
    }
}
